package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.runtime.GuestInit
import com.linuxdroid.native_bridge.NativeBridge
import java.io.File
import java.nio.file.Files

/**
 * Prepares the runtime environment infrastructure within the Linux root filesystem.
 *
 * Installs persistent guest init (/sbin/linuxdroid-init), initializes guest hooks
 * (/etc/linuxdroid/init.d), prepares user home directories (/home/user), and ensures
 * runtime mount points (/tmp, /run, /dev/shm) exist with correct permissions.
 */
class RuntimeEnvironmentSetup(
    private val log: LinuxDroidLogger = LinuxDroidLogger(LogSubsystem.BOOTSTRAP),
) {

    /**
     * Sets up the runtime environment infrastructure in [rootfsDir].
     */
    fun setup(rootfsDir: File) {
        log.info("[RUNTIME_SETUP] Preparing runtime environment infrastructure in ${rootfsDir.path}")

        // 1. Persistent Guest Init (/sbin/linuxdroid-init)
        val sbinDir = File(rootfsDir, "sbin").apply { mkdirs() }
        val initFile = File(sbinDir, "linuxdroid-init")
        val usrSbinInit = File(rootfsDir, "usr/sbin/linuxdroid-init")

        val hadExistingInit = initFile.exists() || Files.isSymbolicLink(initFile.toPath()) ||
                usrSbinInit.exists() || Files.isSymbolicLink(usrSbinInit.toPath())
        if (hadExistingInit) {
            log.info("[RUNTIME_SETUP] Existing /sbin/linuxdroid-init found in rootfs; removing old init before installing app's linuxdroid-init")
            try {
                initFile.setWritable(true)
                Files.deleteIfExists(initFile.toPath())
            } catch (_: Exception) {
                initFile.delete()
            }
            try {
                usrSbinInit.setWritable(true)
                Files.deleteIfExists(usrSbinInit.toPath())
            } catch (_: Exception) {
                usrSbinInit.delete()
            }
        }

        initFile.writeText(GuestInit.SCRIPT_CONTENT)
        initFile.setReadable(true, false)
        initFile.setExecutable(true, false)
        try {
            val perms = setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
            )
            Files.setPosixFilePermissions(initFile.toPath(), perms)
        } catch (_: Exception) {}
        NativeBridge.setExecutable(initFile.absolutePath)

        // Also ensure /usr/sbin/linuxdroid-init exists if /sbin is not merged with /usr/sbin
        val usrSbinDir = File(rootfsDir, "usr/sbin").apply { mkdirs() }
        if (!usrSbinInit.exists() && usrSbinInit.canonicalPath != initFile.canonicalPath) {
            try {
                usrSbinInit.writeText(GuestInit.SCRIPT_CONTENT)
                usrSbinInit.setReadable(true, false)
                usrSbinInit.setExecutable(true, false)
                NativeBridge.setExecutable(usrSbinInit.absolutePath)
            } catch (e: Exception) {
                log.warn("[RUNTIME_SETUP] Failed to write usr/sbin/linuxdroid-init: ${e.message}")
            }
        }

        // 2. Guest Init Hooks Directory (/etc/linuxdroid/init.d)
        File(rootfsDir, "etc/linuxdroid/init.d").mkdirs()

        // 3. User Directories
        File(rootfsDir, "home/user").mkdirs()
        File(rootfsDir, "home/user/Android").mkdirs()

        // 4. Runtime directories
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)

        File(rootfsDir, "run").mkdirs()
        File(rootfsDir, "run/lddm").mkdirs()
        File(rootfsDir, "dev/shm").mkdirs()
        File(rootfsDir, "var/tmp").mkdirs()
    }
}

