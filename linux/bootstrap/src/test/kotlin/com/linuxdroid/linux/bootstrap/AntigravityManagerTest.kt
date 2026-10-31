package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AntigravityManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val mockContext = mockk<Context>(relaxed = true)

    @Test
    fun `launch command strictly matches official remote control specification`() {
        assertThat(AntigravityManager.LAUNCH_COMMAND)
            .isEqualTo("agy --remote-control --dangerously-skip-permissions")
    }

    @Test
    fun `version parsing extracts clean semver strings accurately`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        assertThat(manager.parseVersionString("antigravity 1.10.4")).isEqualTo("1.10.4")
        assertThat(manager.parseVersionString("agy 1.10.4")).isEqualTo("1.10.4")
        assertThat(manager.parseVersionString("agy version 2.0.1-preview.1")).isEqualTo("2.0.1-preview.1")
        assertThat(manager.parseVersionString("1.11.0")).isEqualTo("1.11.0")
        assertThat(manager.parseVersionString("Google Antigravity CLI v1.12.5 (aarch64)")).isEqualTo("1.12.5")
    }

    @Test
    fun `semver comparison detects newer releases correctly`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        // Newer
        assertThat(manager.isVersionNewer("1.10.4", "1.10.3")).isTrue()
        assertThat(manager.isVersionNewer("1.11.0", "1.10.99")).isTrue()
        assertThat(manager.isVersionNewer("2.0.0", "1.99.99")).isTrue()
        assertThat(manager.isVersionNewer("1.10.4", null)).isTrue()
        assertThat(manager.isVersionNewer("1.10.4", "")).isTrue()

        // Older or identical
        assertThat(manager.isVersionNewer("1.10.4", "1.10.4")).isFalse()
        assertThat(manager.isVersionNewer("1.10.3", "1.10.4")).isFalse()
        assertThat(manager.isVersionNewer("1.9.9", "1.10.0")).isFalse()
    }

    @Test
    fun `sha512 checksum calculation is cryptographically accurate`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        val sampleFile = tempFolder.newFile("sample_binary.bin")
        sampleFile.writeText("Antigravity Autonomous Agentic Coding CLI Test Payload")

        val calculated = manager.calculateSha512(sampleFile)
        val expectedDigest = MessageDigest.getInstance("SHA-512").digest(sampleFile.readBytes())
        val expectedHex = expectedDigest.joinToString("") { "%02x".format(it) }

        assertThat(calculated).isEqualTo(expectedHex)
        assertThat(calculated.length).isEqualTo(128)
    }

    @Test
    fun `state machine preserves remote control URL and readiness transitions`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        assertThat(manager.state.value.status).isEqualTo(AntigravityStatus.NOT_INSTALLED)
        assertThat(manager.state.value.isRemoteControlReady).isFalse()

        manager.setRemoteControlState(
            isRunning = true,
            remoteControlUrl = "https://antigravity.google/remote-control?session=xyz",
            isReady = true
        )

        val runningState = manager.state.value
        assertThat(runningState.status).isEqualTo(AntigravityStatus.RUNNING)
        assertThat(runningState.isRemoteControlReady).isTrue()
        assertThat(runningState.remoteControlUrl).isEqualTo("https://antigravity.google/remote-control?session=xyz")

        manager.setRemoteControlState(isRunning = false, isReady = false)
        val stoppedState = manager.state.value
        assertThat(stoppedState.isRemoteControlReady).isFalse()
    }

    @Test
    fun `submitAuthCode rejects blank or whitespace code`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        val result1 = manager.submitAuthCode("")
        assertThat(result1).isInstanceOf(AntigravityManager.AuthCodeSubmitResult.Error::class.java)
        assertThat((result1 as AntigravityManager.AuthCodeSubmitResult.Error).message)
            .contains("cannot be empty")

        val result2 = manager.submitAuthCode("   \n\r  ")
        assertThat(result2).isInstanceOf(AntigravityManager.AuthCodeSubmitResult.Error::class.java)
    }

    @Test
    fun `submitAuthCode rejects when CLI session is not active`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        val result = manager.submitAuthCode("4/0AY0e-sample-code")
        assertThat(result).isInstanceOf(AntigravityManager.AuthCodeSubmitResult.Error::class.java)
        assertThat((result as AntigravityManager.AuthCodeSubmitResult.Error).message)
            .contains("no longer running")
    }

    @Test
    fun `cancelAuthentication sets CANCELLED state cleanly`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        manager.updateAuthState(AntigravityAuthState.WAITING_FOR_AUTH_CODE)
        assertThat(manager.state.value.authState).isEqualTo(AntigravityAuthState.WAITING_FOR_AUTH_CODE)

        manager.cancelAuthentication()
        assertThat(manager.state.value.authState).isEqualTo(AntigravityAuthState.CANCELLED)
    }

    @Test
    fun `checkInstallationStatus detects existing persistent binary and metadata without reinstallation`() = kotlinx.coroutines.runBlocking {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)
        val envId = EnvironmentId("test-env")
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Test Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = EnvironmentState.READY,
            rootfsPath = storage.rootfsDir(envId).absolutePath,
            metadataPath = storage.metadataDir(envId).absolutePath,
        )

        val binFile = manager.getAgyBinaryFile(env)
        binFile.parentFile?.mkdirs()
        binFile.writeBytes(ByteArray(150_000) { 0x7F.toByte() })
        manager.saveVersionMetadata(env, "1.10.4")

        val state = manager.checkInstallationStatus(env)
        assertThat(state.status).isEqualTo(AntigravityStatus.INSTALLED)
        assertThat(state.installedVersion).isEqualTo("1.10.4")
        assertThat(state.canLaunch).isTrue()
        assertThat(state.canInstall).isFalse()
    }

    @Test
    fun `checkInstallationStatus marks installed when binary exists even if metadata was absent`() = kotlinx.coroutines.runBlocking {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)
        val envId = EnvironmentId("test-env-no-meta")
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Test Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = EnvironmentState.READY,
            rootfsPath = storage.rootfsDir(envId).absolutePath,
            metadataPath = storage.metadataDir(envId).absolutePath,
        )

        val binFile = manager.getAgyBinaryFile(env)
        binFile.parentFile?.mkdirs()
        binFile.writeBytes(ByteArray(150_000) { 0x7F.toByte() })

        val state = manager.checkInstallationStatus(env)
        assertThat(state.status).isEqualTo(AntigravityStatus.INSTALLED)
        assertThat(state.installedVersion).isNotNull()
        assertThat(state.canLaunch).isTrue()
        assertThat(state.canInstall).isFalse()
    }

    @Test
    fun `handleOAuthCallback rejects invalid host for HTTPS callbacks`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        val uri = mockk<android.net.Uri>()
        io.mockk.every { uri.scheme } returns "https"
        io.mockk.every { uri.host } returns "attacker.com"

        val result = manager.handleOAuthCallback(uri)
        assertThat(result).isInstanceOf(AntigravityManager.AuthCodeSubmitResult.Error::class.java)
        assertThat((result as AntigravityManager.AuthCodeSubmitResult.Error).message)
            .contains("Unauthorized HTTPS callback host")
    }

    @Test
    fun `handleOAuthCallback rejects callbacks with error parameter`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        val uri = mockk<android.net.Uri>()
        io.mockk.every { uri.scheme } returns "linuxdroid"
        io.mockk.every { uri.getQueryParameter("error") } returns "access_denied"
        io.mockk.every { uri.getQueryParameter("error_description") } returns "User denied access"

        val result = manager.handleOAuthCallback(uri)
        assertThat(result).isInstanceOf(AntigravityManager.AuthCodeSubmitResult.Error::class.java)
        assertThat((result as AntigravityManager.AuthCodeSubmitResult.Error).message)
            .contains("User denied access")
    }

    @Test
    fun `submitAuthCode ignores duplicate submissions when already connected or in flight`() {
        val storage = EnvironmentStorage(tempFolder.root)
        val manager = AntigravityManager(mockContext, storage)

        // Case 1: Already connected
        manager.updateAuthState(AntigravityAuthState.CONNECTED)
        val result1 = manager.submitAuthCode("4/0AY0e-sample", AntigravityManager.AuthCodeSource.CALLBACK)
        assertThat(result1).isEqualTo(AntigravityManager.AuthCodeSubmitResult.IgnoredDuplicate)

        // Case 2: In flight
        manager.updateAuthState(AntigravityAuthState.SUBMITTING_AUTH_CODE)
        val result2 = manager.submitAuthCode("4/0AY0e-sample", AntigravityManager.AuthCodeSource.MANUAL)
        assertThat(result2).isEqualTo(AntigravityManager.AuthCodeSubmitResult.IgnoredDuplicate)
    }
}
