package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.ArchiveFormat
import com.linuxdroid.core.model.FilesystemError
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Hardened archive extractor for Linux root filesystem archives.
 *
 * Implements pure streaming decompression with path traversal defense,
 * symlink resolution, and executable permission preservation.
 */
class RootfsExtractor(
    private val log: LinuxDroidLogger = LinuxDroidLogger(LogSubsystem.BOOTSTRAP),
) {

    /**
     * Extracts [tarball] into [destDir].
     *
     * @param tarball The source archive file (.tar.xz, .tar.gz, .tar.bz2).
     * @param destDir Target directory to extract files into.
     * @param format Compression format.
     * @param stripComponents Leading path components to strip.
     * @param onProgress Callback receiving fractional progress (0.0 .. 1.0) and status message.
     * @param onLog Callback for diagnostic log lines.
     * @return Total count of extracted entries.
     */
    suspend fun extract(
        tarball: File,
        destDir: File,
        format: ArchiveFormat,
        stripComponents: Int = 0,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val destCanonicalPath = destDir.canonicalPath
        val fileInputStream = BufferedInputStream(FileInputStream(tarball), 64 * 1024)

        val decompressorStream: InputStream = when (format) {
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(fileInputStream)
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(fileInputStream)
            ArchiveFormat.TAR_BZ2 -> fileInputStream
        }

        var entryCount = 0
        val buffer = ByteArray(32 * 1024)
        val deferredHardLinks = mutableListOf<DeferredHardLink>()

        TarArchiveInputStream(decompressorStream).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextEntry
            while (entry != null) {
                entryCount++
                val entryName = stripPathComponents(entry.name, stripComponents)
                if (entryName.isNotBlank()) {
                    val targetFile = File(destDir, entryName)
                    val targetCanonical = targetFile.canonicalPath

                    // Path traversal defense
                    val isContained = targetCanonical == destCanonicalPath ||
                            targetCanonical.startsWith(destCanonicalPath + File.separator)
                    if (!isContained) {
                        throw FilesystemError(
                            path = entryName,
                            message = "Path traversal attack detected in tarball entry: $entryName",
                        )
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        targetFile.parentFile?.mkdirs()
                        try {
                            Files.deleteIfExists(targetFile.toPath())
                            Files.createSymbolicLink(targetFile.toPath(), Paths.get(entry.linkName))
                        } catch (e: Exception) {
                            log.warn("Symlink creation failed for ${targetFile.name} -> ${entry.linkName}: ${e.message}")
                        }
                    } else if (entry.isLink) {
                        // Tar hard link (e.g. usr/lib/cargo/bin/coreutils/env -> usr/bin/coreutils)
                        targetFile.parentFile?.mkdirs()
                        val rawLink = entry.linkName.removePrefix("/").removePrefix("./")
                        val strippedLink = stripPathComponents(rawLink, stripComponents)
                        val sourceFile = File(destDir, strippedLink)
                        try {
                            Files.deleteIfExists(targetFile.toPath())
                            if (sourceFile.exists()) {
                                createHardLinkOrFallback(targetFile, sourceFile)
                                applyPermissions(targetFile, entryName, entry.mode)
                            } else {
                                deferredHardLinks.add(DeferredHardLink(targetFile, sourceFile, entryName, entry.mode))
                            }
                        } catch (e: Exception) {
                            log.warn("Hard link creation failed for ${targetFile.name} -> ${entry.linkName}: ${e.message}")
                        }
                    } else {
                        targetFile.parentFile?.mkdirs()
                        try {
                            Files.deleteIfExists(targetFile.toPath())
                        } catch (_: Exception) {
                            targetFile.delete()
                        }
                        targetFile.outputStream().use { out ->
                            var len: Int
                            while (tarIn.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                            }
                        }

                        applyPermissions(targetFile, entryName, entry.mode)
                    }
                }

                if (entryCount % 350 == 0 || entryName.endsWith("/sh") || entryName.endsWith("/dpkg") || entryName.endsWith("/apt")) {
                    onProgress(0.70f + (entryCount % 10000) * 0.000018f, "Extracting ($entryCount files)…")
                    onLog("extract: $entryName")
                }

                entry = tarIn.nextEntry
            }
        }

        // Process any deferred hard links whose source files were unpacked later in the stream
        for (deferred in deferredHardLinks) {
            try {
                if (deferred.source.exists()) {
                    Files.deleteIfExists(deferred.target.toPath())
                    createHardLinkOrFallback(deferred.target, deferred.source)
                    applyPermissions(deferred.target, deferred.entryName, deferred.mode)
                } else {
                    log.warn("Deferred hardlink source missing: ${deferred.source.path} for target ${deferred.target.path}")
                }
            } catch (e: Exception) {
                log.warn("Deferred hardlink processing failed for ${deferred.target.name}: ${e.message}")
            }
        }

        log.info("[ROOTFS_EXTRACT] Extracted $entryCount entries successfully to ${destDir.path}")
        onLog(">>> [EXTRACT] Completed: $entryCount entries successfully unpacked.")
        entryCount
    }

    private fun createHardLinkOrFallback(target: File, source: File) {
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (e: Exception) {
            try {
                Files.copy(source.toPath(), target.toPath())
            } catch (_: Exception) {
                Files.createSymbolicLink(target.toPath(), source.toPath())
            }
        }
    }

    private fun applyPermissions(targetFile: File, entryName: String, mode: Int) {
        val isExec = (mode and 0b001001001) != 0 ||
                entryName.contains("bin/") ||
                entryName.contains("sbin/") ||
                entryName.contains("lib/") ||
                entryName.contains("libexec/") ||
                entryName.endsWith(".so") ||
                entryName.contains(".so.")
        if (isExec) {
            targetFile.setExecutable(true, false)
            NativeBridge.setExecutable(targetFile.absolutePath)
        }
        targetFile.setReadable(true, false)
    }

    private data class DeferredHardLink(
        val target: File,
        val source: File,
        val entryName: String,
        val mode: Int,
    )

    private fun stripPathComponents(path: String, count: Int): String {
        if (count <= 0) return path
        val parts = path.split("/").filter { it.isNotEmpty() }
        return if (parts.size > count) {
            parts.drop(count).joinToString("/")
        } else {
            ""
        }
    }
}

