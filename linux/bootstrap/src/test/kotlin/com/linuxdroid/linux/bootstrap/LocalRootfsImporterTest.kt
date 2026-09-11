package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class LocalRootfsImporterTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private lateinit var importer: LocalRootfsImporter
    private val envId = EnvironmentId("test-local-env")
    private lateinit var env: Environment

    @Before
    fun setup() {
        val rootDir = tempFolder.newFolder("linuxdroid_storage")
        storage = EnvironmentStorage(rootDir)
        runBlocking {
            storage.initializeEnvironmentDirs(envId)
        }

        env = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Ubuntu Base Test",
                distribution = Distribution.UBUNTU,
                architecture = Architecture.ARM64,
            ),
            configuration = EnvironmentConfiguration(linuxUser = "testuser"),
            state = EnvironmentState.READY,
            rootfsPath = storage.rootfsDir(envId).absolutePath,
            metadataPath = storage.metadataDir(envId).absolutePath,
        )

        importer = LocalRootfsImporter(
            context = null,
            storage = storage,
            runtimeBackend = null, // uses simulation fallback
        )
    }

    private fun createValidTarGz(
        outputFile: File,
        nestedPrefix: String = "",
        includeArm64Linker: Boolean = true,
    ) {
        FileOutputStream(outputFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tarOut ->
                    val p = if (nestedPrefix.isNotBlank()) "$nestedPrefix/" else ""

                    // 1. bin/sh
                    val binSh = "#!/bin/sh\necho ok\n".toByteArray()
                    val eBinSh = TarArchiveEntry("${p}bin/sh").apply {
                        size = binSh.size.toLong()
                        mode = 0b111101101
                    }
                    tarOut.putArchiveEntry(eBinSh)
                    tarOut.write(binSh)
                    tarOut.closeArchiveEntry()

                    // 2. etc/os-release
                    val osRel = """
                        NAME="Ubuntu"
                        VERSION="26.04 LTS (Resolute Ringtail)"
                        ID=ubuntu
                        ID_LIKE=debian
                        PRETTY_NAME="Ubuntu 26.04 LTS"
                        VERSION_ID="26.04"
                        VERSION_CODENAME=resolute
                        UBUNTU_CODENAME=resolute
                    """.trimIndent().toByteArray()
                    val eOsRel = TarArchiveEntry("${p}etc/os-release").apply {
                        size = osRel.size.toLong()
                        mode = 0b110100100
                    }
                    tarOut.putArchiveEntry(eOsRel)
                    tarOut.write(osRel)
                    tarOut.closeArchiveEntry()

                    // 3. usr/lib/os-release
                    val eUsrOsRel = TarArchiveEntry("${p}usr/lib/os-release").apply {
                        size = osRel.size.toLong()
                        mode = 0b110100100
                    }
                    tarOut.putArchiveEntry(eUsrOsRel)
                    tarOut.write(osRel)
                    tarOut.closeArchiveEntry()

                    // 4. var/log dummy file
                    val varLog = "log".toByteArray()
                    val eVarLog = TarArchiveEntry("${p}var/log/syslog").apply {
                        size = varLog.size.toLong()
                        mode = 0b110100100
                    }
                    tarOut.putArchiveEntry(eVarLog)
                    tarOut.write(varLog)
                    tarOut.closeArchiveEntry()

                    // 5. lib/ld-linux-aarch64.so.1 (dummy ELF header)
                    if (includeArm64Linker) {
                        // ELF magic + 64-bit + little endian + AArch64 machine (0xB7)
                        val elfHeader = ByteArray(64)
                        elfHeader[0] = 0x7F
                        elfHeader[1] = 'E'.code.toByte()
                        elfHeader[2] = 'L'.code.toByte()
                        elfHeader[3] = 'F'.code.toByte()
                        elfHeader[4] = 2 // 64-bit
                        elfHeader[5] = 1 // Little endian
                        elfHeader[18] = 0xB7.toByte() // EM_AARCH64 (183)
                        elfHeader[19] = 0x00

                        val eLinker = TarArchiveEntry("${p}lib/ld-linux-aarch64.so.1").apply {
                            size = elfHeader.size.toLong()
                            mode = 0b111101101
                        }
                        tarOut.putArchiveEntry(eLinker)
                        tarOut.write(elfHeader)
                        tarOut.closeArchiveEntry()
                    }
                }
            }
        }
    }

    @Test
    fun `validateArchive succeeds for valid tar gz archive`() {
        val archive = tempFolder.newFile("ubuntu-base-26.04-arm64.tar.gz")
        createValidTarGz(archive)

        // Should not throw
        importer.validateArchive(archive)
    }

    @Test
    fun `validateArchive fails when file does not exist`() {
        val nonExistent = File(tempFolder.root, "does-not-exist.tar.gz")
        assertThrows(FilesystemError::class.java) {
            importer.validateArchive(nonExistent)
        }
    }

    @Test
    fun `validateArchive fails for unsupported extension`() {
        val zipFile = tempFolder.newFile("rootfs.zip")
        zipFile.writeText("not a tar gz")

        assertThrows(RuntimeError::class.java) {
            importer.validateArchive(zipFile)
        }
    }

    @Test
    fun `validateArchive fails when gzip magic header is missing`() {
        val fakeArchive = tempFolder.newFile("corrupted.tar.gz")
        fakeArchive.writeBytes(ByteArray(1024) { 0x42 }) // Not 0x1F, 0x8B

        val error = assertThrows(RuntimeError::class.java) {
            importer.validateArchive(fakeArchive)
        }
        assertThat(error.message).contains("does not have valid GZIP magic header")
    }

    @Test
    fun `importRootfs flattens nested layout and injects in-guest setup payload`() = runBlocking {
        val archive = tempFolder.newFile("ubuntu-base-nested.tar.gz")
        createValidTarGz(archive, nestedPrefix = "ubuntu-base-26.04-base-arm64")

        val result = importer.importRootfs(
            archiveFile = archive,
            environment = env,
            installConfig = InstallConfig(
                distro = Distribution.UBUNTU,
                release = "resolute",
                username = "testuser",
                password = "secretpassword",
                architecture = Architecture.ARM64,
            )
        )

        assertThat(result.success).isTrue()
        assertThat(result.distribution).isEqualTo("ubuntu")

        val rootfsDir = storage.rootfsDir(envId)
        // Verified unwrapped to root level
        assertThat(File(rootfsDir, "bin/sh").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/os-release").exists()).isTrue()

        // Verified setup payload injected
        val setupScript = File(rootfsDir, "root/linuxdroid/setup-rootfs.sh")
        assertThat(setupScript.exists()).isTrue()
        assertThat(setupScript.canRead()).isTrue()

        // Verified persistent guest init injected
        val guestInit = File(rootfsDir, "sbin/linuxdroid-init")
        assertThat(guestInit.exists()).isTrue()

        // Check setup state is SETUP_REQUIRED before setup runs
        val stateBefore = importer.checkSetupState(env)
        assertThat(stateBefore).isEqualTo(LocalRootfsState.SETUP_REQUIRED)
    }

    @Test
    fun `executeInGuestSetup transitions rootfs to READY`() = runBlocking {
        val archive = tempFolder.newFile("ubuntu-base.tar.gz")
        createValidTarGz(archive)

        importer.importRootfs(archiveFile = archive, environment = env)

        // Run in-guest setup (simulation mode in unit tests)
        val setupResult = importer.executeInGuestSetup(env)

        assertThat(setupResult.success).isTrue()
        assertThat(setupResult.state).isEqualTo(LocalRootfsState.READY)

        val rootfsDir = storage.rootfsDir(envId)
        val readyMarker = File(rootfsDir, "etc/linuxdroid/rootfs-ready")
        assertThat(readyMarker.exists()).isTrue()

        // Persistent check confirms READY
        val persistentState = importer.checkSetupState(env)
        assertThat(persistentState).isEqualTo(LocalRootfsState.READY)
    }

    @Test
    fun `checkSetupState returns NONE when rootfs not present`() {
        val nonExistentEnv = env.copy(metadata = env.metadata.copy(id = EnvironmentId("ghost-env")))
        val state = importer.checkSetupState(nonExistentEnv)
        assertThat(state).isEqualTo(LocalRootfsState.NONE)
    }
}
