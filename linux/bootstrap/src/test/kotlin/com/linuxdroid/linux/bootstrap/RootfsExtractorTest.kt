package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.ArchiveFormat
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class RootfsExtractorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val extractor = RootfsExtractor()

    @Test
    fun `extract correctly creates files, symlinks, and hardlinks including deferred links`() = runBlocking {
        val archiveFile = tempFolder.newFile("test-rootfs.tar.gz")
        val targetDir = tempFolder.newFolder("target-rootfs")

        // Construct a tar.gz archive with:
        // 1. A hardlink encountered BEFORE its target (deferred hardlink)
        // 2. A directory
        // 3. A regular file (the target of the hardlink)
        // 4. A hardlink encountered AFTER its target
        // 5. A symbolic link
        FileOutputStream(archiveFile).use { fos ->
            GzipCompressorOutputStream(BufferedOutputStream(fos)).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    // 1. Deferred hardlink: usr/lib/cargo/bin/coreutils/env -> usr/bin/coreutils
                    val deferredLinkEntry = TarArchiveEntry("usr/lib/cargo/bin/coreutils/env", TarArchiveEntry.LF_LINK).apply {
                        linkName = "usr/bin/coreutils"
                        mode = 0b111101101 // 0755
                    }
                    tos.putArchiveEntry(deferredLinkEntry)
                    tos.closeArchiveEntry()

                    // 2. Directory: usr/bin
                    val dirEntry = TarArchiveEntry("usr/bin/", TarArchiveEntry.LF_DIR).apply {
                        mode = 0b111101101 // 0755
                    }
                    tos.putArchiveEntry(dirEntry)
                    tos.closeArchiveEntry()

                    // 3. Target regular file: usr/bin/coreutils
                    val content = "ELF_MOCK_COREUTILS_BIN_CONTENT_12345".toByteArray()
                    val targetEntry = TarArchiveEntry("usr/bin/coreutils", TarArchiveEntry.LF_NORMAL).apply {
                        size = content.size.toLong()
                        mode = 0b111101101 // 0755
                    }
                    tos.putArchiveEntry(targetEntry)
                    tos.write(content)
                    tos.closeArchiveEntry()

                    // 4. Immediate hardlink: usr/bin/env_hardlink -> usr/bin/coreutils
                    val hardLinkEntry = TarArchiveEntry("usr/bin/env_hardlink", TarArchiveEntry.LF_LINK).apply {
                        linkName = "usr/bin/coreutils"
                        mode = 0b111101101 // 0755
                    }
                    tos.putArchiveEntry(hardLinkEntry)
                    tos.closeArchiveEntry()

                    // 5. Symbolic link: usr/bin/env -> ../lib/cargo/bin/coreutils/env
                    val symlinkEntry = TarArchiveEntry("usr/bin/env", TarArchiveEntry.LF_SYMLINK).apply {
                        linkName = "../lib/cargo/bin/coreutils/env"
                        mode = 0b111101101 // 0755
                    }
                    tos.putArchiveEntry(symlinkEntry)
                    tos.closeArchiveEntry()
                }
            }
        }

        // Extract
        val extractedCount = extractor.extract(
            tarball = archiveFile,
            destDir = targetDir,
            format = ArchiveFormat.TAR_GZ,
        )

        assertThat(extractedCount).isGreaterThan(0)

        // Verifications
        val coreutilsFile = File(targetDir, "usr/bin/coreutils")
        val deferredLinkFile = File(targetDir, "usr/lib/cargo/bin/coreutils/env")
        val hardlinkFile = File(targetDir, "usr/bin/env_hardlink")
        val symlinkFile = File(targetDir, "usr/bin/env")

        assertThat(coreutilsFile.exists()).isTrue()
        assertThat(coreutilsFile.readText()).isEqualTo("ELF_MOCK_COREUTILS_BIN_CONTENT_12345")

        // Deferred hard link must exist and have identical content
        assertThat(deferredLinkFile.exists()).isTrue()
        assertThat(deferredLinkFile.readText()).isEqualTo("ELF_MOCK_COREUTILS_BIN_CONTENT_12345")
        assertThat(deferredLinkFile.length()).isGreaterThan(0L)

        // Immediate hard link must exist and have identical content
        assertThat(hardlinkFile.exists()).isTrue()
        assertThat(hardlinkFile.readText()).isEqualTo("ELF_MOCK_COREUTILS_BIN_CONTENT_12345")
        assertThat(hardlinkFile.length()).isGreaterThan(0L)

        // Symlink must exist and point to the cargo env path
        assertThat(Files.isSymbolicLink(symlinkFile.toPath())).isTrue()
        assertThat(Files.readSymbolicLink(symlinkFile.toPath()).toString())
            .isEqualTo("../lib/cargo/bin/coreutils/env")

        // Resolving the symlink chain must lead to valid content
        assertThat(symlinkFile.readText()).isEqualTo("ELF_MOCK_COREUTILS_BIN_CONTENT_12345")
    }
}
