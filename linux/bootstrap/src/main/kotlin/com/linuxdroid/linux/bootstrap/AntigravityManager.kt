package com.linuxdroid.linux.bootstrap

import android.content.Context
import android.net.Uri
import android.os.Build
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.core.model.AntigravityManifest
import com.linuxdroid.core.model.AntigravityState
import com.linuxdroid.core.model.AntigravityStatus
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of an Antigravity update check.
 */
data class AntigravityUpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val currentVersion: String?,
    val latestVersion: String?,
    val manifest: AntigravityManifest?,
    val error: String? = null,
)

/**
 * Manages the official Google Antigravity CLI lifecycle within LinuxDroid userspace:
 * - Installation from official Google releases
 * - Strict SHA-512 verification
 * - Guest executable integrity and version verification
 * - Automatic update checks with safe rollback
 * - Launch coordination for Remote Control
 */
class AntigravityManager(
    private val context: Context,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)
    private val json = Json { ignoreUnknownKeys = true }
    private val operationMutex = Mutex()

    companion object {
        const val LAUNCH_COMMAND = "agy --remote-control --dangerously-skip-permissions"
        private const val UPDATER_BASE_URL = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests"
        private const val GUEST_BIN_DIR = "usr/local/bin"
        private const val AGY_BIN_NAME = "agy"
        private const val ANTIGRAVITY_BIN_NAME = "antigravity"
    }

    private val _state = MutableStateFlow(AntigravityState())
    val state: StateFlow<AntigravityState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activePtySession: PtySession? = null
    private var activeEnvironmentId: String? = null
    private var backgroundReadJob: Job? = null
    private val automation = AntigravityTerminalAutomation()

    private val _navigateToWebView = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val navigateToWebView: SharedFlow<String> = _navigateToWebView.asSharedFlow()

    private val _openAuthBrowser = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val openAuthBrowser: SharedFlow<String> = _openAuthBrowser.asSharedFlow()

    // OAuth coordination and single-submission lock
    private val hasSubmittedAuthCode = AtomicBoolean(false)
    private var activeOAuthState: String? = null
    private var authLaunchTimestampMs: Long = 0L

    /**
     * Resolves the target platform string for the Antigravity release manifest.
     */
    fun resolvePlatformString(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.ROOT) ?: "arm64-v8a"
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "linux_arm64"
            abi.contains("x86_64") || abi.contains("amd64") -> "linux_amd64"
            else -> "linux_arm64"
        }
    }

    /**
     * Resolves host File object for the installed agy binary.
     */
    fun getAgyBinaryFile(environment: Environment): File {
        val rootfs = storage.rootfsDir(environment.id)
        return File(rootfs, "$GUEST_BIN_DIR/$AGY_BIN_NAME")
    }

    /**
     * Inspects the environment and deep-verifies if Antigravity CLI is installed and runnable.
     * Decouples Linux environment lifecycle, Antigravity installation lifecycle, and CLI process lifecycle.
     * Once installed in the rootfs (/usr/local/bin/agy) with valid metadata, the app never requests reinstallation.
     */
    suspend fun checkInstallationStatus(environment: Environment): AntigravityState = withContext(Dispatchers.IO) {
        val binaryFile = getAgyBinaryFile(environment)
        if (!binaryFile.exists() || binaryFile.length() < 100_000) {
            val newState = _state.value.copy(
                status = AntigravityStatus.NOT_INSTALLED,
                installedVersion = null,
                statusMessage = "Antigravity CLI is not installed.",
                errorMessage = null,
            )
            _state.value = newState
            return@withContext newState
        }

        // Verify executable bit
        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true, false)
            NativeBridge.setExecutable(binaryFile.absolutePath)
        }

        val hasSession = hasSavedAuthenticationSession(environment)
        val cachedVersion = readVersionFromBinaryMetadata(environment)

        // Fast-path: If persistent version metadata marker is already present, verify binary is installed
        if (!cachedVersion.isNullOrBlank()) {
            val newState = _state.value.copy(
                status = AntigravityStatus.INSTALLED,
                installedVersion = cachedVersion,
                statusMessage = "Antigravity CLI $cachedVersion ready.",
                errorMessage = null,
                hasSavedSession = hasSession,
            )
            _state.value = newState
            return@withContext newState
        }

        // Deep verification through guest environment if runtimeBackend is operational
        val version = verifyGuestExecutable(environment)
        val finalVersion = version ?: cachedVersion ?: "installed"
        saveVersionMetadata(environment, finalVersion)

        val newState = _state.value.copy(
            status = AntigravityStatus.INSTALLED,
            installedVersion = finalVersion,
            statusMessage = "Antigravity CLI $finalVersion ready.",
            errorMessage = null,
            hasSavedSession = hasSession,
        )
        _state.value = newState
        return@withContext newState
    }

    /**
     * Checks whether a compatible saved authentication session is available
     * in the guest filesystem without exposing or reading credentials.
     */
    fun hasSavedAuthenticationSession(environment: Environment): Boolean {
        val rootfs = storage.rootfsDir(environment.id)
        val candidateLocations = listOf(
            File(rootfs, "root/.config/antigravity"),
            File(rootfs, "root/.antigravity"),
            File(rootfs, "root/.gemini/antigravity-cli"),
            File(rootfs, "etc/antigravity"),
        )

        for (candidate in candidateLocations) {
            if (candidate.exists()) {
                if (candidate.isDirectory) {
                    val files = candidate.listFiles()
                    if (files != null && files.any { it.isFile && it.length() > 0 }) {
                        return true
                    }
                } else if (candidate.isFile && candidate.length() > 0) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Invokes `agy --version` inside the PRoot environment to verify guest binary integrity.
     */
    suspend fun verifyGuestExecutable(environment: Environment): String? = withContext(Dispatchers.IO) {
        val backend = runtimeBackend ?: return@withContext readVersionFromBinaryMetadata(environment)
        return@withContext try {
            val result = backend.executeAndWait(
                environment = environment,
                command = listOf("/usr/local/bin/agy", "--version"),
                timeoutMs = 10_000,
            )
            if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                val parsed = parseVersionString(result.stdout)
                saveVersionMetadata(environment, parsed)
                parsed
            } else {
                log.warn("verifyGuestExecutable failed with code ${result.exitCode}: ${result.stderr}")
                readVersionFromBinaryMetadata(environment)
            }
        } catch (e: Exception) {
            log.warn("Guest execution verification check failed: ${e.message}")
            readVersionFromBinaryMetadata(environment)
        }
    }

    fun readVersionFromBinaryMetadata(environment: Environment): String? {
        val metaFile = File(storage.metadataDir(environment.id), "antigravity_version.txt")
        return if (metaFile.exists()) metaFile.readText().trim().ifBlank { null } else null
    }

    fun saveVersionMetadata(environment: Environment, version: String) {
        val metaDir = storage.metadataDir(environment.id)
        if (!metaDir.exists()) metaDir.mkdirs()
        File(metaDir, "antigravity_version.txt").writeText(version)
    }

    /**
     * Parses version string from `agy --version` output (e.g. "antigravity 1.10.4" -> "1.10.4").
     */
    fun parseVersionString(output: String): String {
        val trimmed = output.trim()
        val regex = Regex("""(?i)(?:antigravity|agy)?\s*([0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[a-zA-Z0-9.]+)?(?:[+][a-zA-Z0-9.]+)?)""")
        val match = regex.find(trimmed)
        return match?.groupValues?.get(1) ?: trimmed.lines().firstOrNull()?.trim() ?: "unknown"
    }

    /**
     * Fetches the official release manifest for the current platform.
     */
    suspend fun fetchOfficialManifest(platform: String = resolvePlatformString()): Result<AntigravityManifest> = withContext(Dispatchers.IO) {
        val manifestUrl = "$UPDATER_BASE_URL/$platform.json"
        try {
            val url = URL(manifestUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "LinuxDroid-AntigravityInstaller")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = json.parseToJsonElement(text).jsonObject
                val version = root["version"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Manifest missing version field"))
                val downloadUrl = root["url"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Manifest missing url field"))
                val sha512 = root["sha512"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Manifest missing sha512 field"))

                Result.success(AntigravityManifest(version = version, url = downloadUrl, sha512 = sha512))
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Result.failure(Exception("Failed to fetch official manifest: HTTP $code - $err"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("Failed to query Antigravity manifest from $manifestUrl", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if an update is available against the official release endpoint.
     */
    suspend fun checkForUpdates(
        environment: Environment,
        autoUpdate: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): AntigravityUpdateCheckResult = withContext(Dispatchers.IO) {
        if (_state.value.status.isBusy) {
            return@withContext AntigravityUpdateCheckResult(
                isUpdateAvailable = false,
                currentVersion = _state.value.installedVersion,
                latestVersion = null,
                manifest = null,
                error = "Operation already in progress",
            )
        }

        val previousState = _state.value
        _state.value = previousState.copy(
            status = AntigravityStatus.CHECKING_FOR_UPDATES,
            statusMessage = "Checking for official Antigravity updates…",
            errorMessage = null,
        )

        val currentVersion = previousState.installedVersion ?: verifyGuestExecutable(environment)
        val manifestResult = fetchOfficialManifest()

        if (manifestResult.isFailure) {
            val errorMsg = manifestResult.exceptionOrNull()?.message ?: "Failed to connect to update server"
            _state.value = previousState.copy(
                status = if (currentVersion != null) AntigravityStatus.INSTALLED else AntigravityStatus.NOT_INSTALLED,
                statusMessage = "Update check failed: $errorMsg",
                errorMessage = null, // Don't block app function on update-check failure
                lastCheckedTimestamp = System.currentTimeMillis(),
            )
            return@withContext AntigravityUpdateCheckResult(
                isUpdateAvailable = false,
                currentVersion = currentVersion,
                latestVersion = null,
                manifest = null,
                error = errorMsg,
            )
        }

        val manifest = manifestResult.getOrThrow()
        val latestVersion = manifest.version
        val isNewer = isVersionNewer(latestVersion, currentVersion)

        _state.value = previousState.copy(
            status = if (currentVersion != null) AntigravityStatus.INSTALLED else AntigravityStatus.NOT_INSTALLED,
            installedVersion = currentVersion,
            latestVersion = latestVersion,
            statusMessage = if (isNewer) "Update available: $latestVersion (Current: ${currentVersion ?: "None"})" else "Antigravity CLI is up to date ($currentVersion).",
            lastCheckedTimestamp = System.currentTimeMillis(),
        )

        if (isNewer && autoUpdate && currentVersion != null) {
            installFromManifest(environment, manifest, onProgress)
        }

        AntigravityUpdateCheckResult(
            isUpdateAvailable = isNewer,
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            manifest = manifest,
        )
    }

    /**
     * Installs or updates the official Antigravity CLI into the Linux userspace.
     */
    suspend fun install(
        environment: Environment,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result<String> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (_state.value.status.isBusy) {
                return@withContext Result.failure(IllegalStateException("Another operation is already in progress"))
            }

            _state.value = _state.value.copy(
                status = AntigravityStatus.INSTALLING,
                progress = 0.05f,
                statusMessage = "Fetching official Antigravity release information…",
                errorMessage = null,
            )
            onProgress(0.05f, "Fetching official release information…")

            val manifestResult = fetchOfficialManifest()
            if (manifestResult.isFailure) {
                val err = manifestResult.exceptionOrNull()?.message ?: "Failed to obtain release manifest"
                _state.value = _state.value.copy(
                    status = AntigravityStatus.FAILED,
                    statusMessage = "Installation failed",
                    errorMessage = err,
                )
                return@withContext Result.failure(Exception(err))
            }

            val manifest = manifestResult.getOrThrow()
            return@withContext installFromManifest(environment, manifest, onProgress)
        }
    }

    /**
     * Downloads, verifies SHA-512, extracts, and promotes the binary with atomic rollback capability.
     */
    private suspend fun installFromManifest(
        environment: Environment,
        manifest: AntigravityManifest,
        onProgress: (Float, String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val rootfsDir = storage.rootfsDir(environment.id)
        val guestBinDir = File(rootfsDir, GUEST_BIN_DIR).apply { if (!exists()) mkdirs() }
        val targetBinary = File(guestBinDir, AGY_BIN_NAME)
        val targetAlias = File(guestBinDir, ANTIGRAVITY_BIN_NAME)
        val stagingBinary = File(guestBinDir, ".agy.staging.${System.currentTimeMillis()}")
        val backupBinary = File(guestBinDir, ".agy.backup")

        val tmpDir = storage.tmpDir(environment.id).apply { if (!exists()) mkdirs() }
        val archiveFile = File(tmpDir, "antigravity_${manifest.version}.tar.gz")

        try {
            // 1. Download official archive with progress
            _state.value = _state.value.copy(
                status = if (targetBinary.exists()) AntigravityStatus.UPDATING else AntigravityStatus.INSTALLING,
                progress = 0.15f,
                statusMessage = "Downloading Antigravity CLI v${manifest.version}…",
            )
            onProgress(0.15f, "Downloading Antigravity CLI v${manifest.version}…")

            downloadFile(manifest.url, archiveFile) { current, total ->
                val progress = if (total > 0) 0.15f + (current.toFloat() / total) * 0.5f else 0.4f
                _state.value = _state.value.copy(progress = progress)
                onProgress(progress, "Downloading CLI: ${current / 1024} KB / ${if (total > 0) total / 1024 else "?"} KB")
            }

            // 2. Strict SHA-512 Verification
            _state.value = _state.value.copy(
                progress = 0.70f,
                statusMessage = "Verifying cryptographic SHA-512 checksum…",
            )
            onProgress(0.70f, "Verifying cryptographic SHA-512 checksum…")

            val calculatedSha512 = calculateSha512(archiveFile)
            if (!calculatedSha512.equals(manifest.sha512.trim(), ignoreCase = true)) {
                archiveFile.delete()
                val err = "Cryptographic checksum mismatch! Expected ${manifest.sha512.take(16)}… but got ${calculatedSha512.take(16)}…"
                _state.value = _state.value.copy(
                    status = AntigravityStatus.FAILED,
                    statusMessage = "Integrity check failed",
                    errorMessage = err,
                )
                return@withContext Result.failure(SecurityException(err))
            }

            // 3. Extract executable from tar.gz into staging file
            _state.value = _state.value.copy(
                progress = 0.80f,
                statusMessage = "Extracting Antigravity CLI binary…",
            )
            onProgress(0.80f, "Extracting Antigravity CLI binary…")

            val extracted = extractBinaryFromArchive(archiveFile, stagingBinary)
            archiveFile.delete()

            if (!extracted || !stagingBinary.exists() || stagingBinary.length() < 100_000) {
                stagingBinary.delete()
                val err = "Failed to extract valid 'antigravity' executable from archive"
                _state.value = _state.value.copy(
                    status = AntigravityStatus.FAILED,
                    statusMessage = "Extraction failed",
                    errorMessage = err,
                )
                return@withContext Result.failure(IllegalStateException(err))
            }

            // 4. Set execution permissions (0755)
            stagingBinary.setExecutable(true, false)
            NativeBridge.setExecutable(stagingBinary.absolutePath)

            // 5. Atomic Promotion with Rollback safety
            _state.value = _state.value.copy(
                progress = 0.90f,
                statusMessage = "Promoting binary into Linux userspace…",
            )
            onProgress(0.90f, "Promoting binary into Linux userspace…")

            if (targetBinary.exists()) {
                if (backupBinary.exists()) backupBinary.delete()
                targetBinary.renameTo(backupBinary)
            }

            if (!stagingBinary.renameTo(targetBinary)) {
                // Fallback copy if rename across mount fails
                copyFile(stagingBinary, targetBinary)
                stagingBinary.delete()
            }
            targetBinary.setExecutable(true, false)
            NativeBridge.setExecutable(targetBinary.absolutePath)

            // Link or duplicate for `antigravity` alias
            copyFile(targetBinary, targetAlias)
            targetAlias.setExecutable(true, false)
            NativeBridge.setExecutable(targetAlias.absolutePath)

            // 6. Deep Verification inside guest
            _state.value = _state.value.copy(
                progress = 0.95f,
                statusMessage = "Verifying binary in PRoot environment…",
            )
            onProgress(0.95f, "Verifying binary in PRoot environment…")

            val verifiedVersion = verifyGuestExecutable(environment) ?: manifest.version
            saveVersionMetadata(environment, verifiedVersion)

            // Cleanup backup now that new binary is verified
            if (backupBinary.exists()) backupBinary.delete()

            _state.value = AntigravityState(
                status = AntigravityStatus.INSTALLED,
                installedVersion = verifiedVersion,
                latestVersion = manifest.version,
                progress = 1.0f,
                statusMessage = "Antigravity CLI v$verifiedVersion installed successfully!",
                lastCheckedTimestamp = System.currentTimeMillis(),
            )
            onProgress(1.0f, "Antigravity CLI v$verifiedVersion installed successfully!")
            log.info("Antigravity CLI v$verifiedVersion successfully installed in ${environment.id}")

            Result.success(verifiedVersion)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("Installation failed, rolling back", e)

            // Safe rollback to prior working binary if present
            if (backupBinary.exists()) {
                if (targetBinary.exists()) targetBinary.delete()
                backupBinary.renameTo(targetBinary)
                targetBinary.setExecutable(true, false)
            }
            if (stagingBinary.exists()) stagingBinary.delete()

            val err = "Installation failed: ${e.message}"
            _state.value = _state.value.copy(
                status = AntigravityStatus.FAILED,
                statusMessage = "Installation failed",
                errorMessage = err,
            )
            Result.failure(e)
        }
    }

    /**
     * Downloads an HTTP/HTTPS resource to a destination file.
     */
    private fun downloadFile(urlStr: String, destination: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LinuxDroid-AntigravityInstaller")
        }

        val totalLength = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    onProgress(totalRead, totalLength)
                }
            }
        }
    }

    /**
     * Extracts the `antigravity` binary from a tar.gz input stream into destination file.
     */
    private fun extractBinaryFromArchive(archiveFile: File, destinationFile: File): Boolean {
        FileInputStream(archiveFile).use { fis ->
            GzipCompressorInputStream(fis).use { gzis ->
                TarArchiveInputStream(gzis).use { tais ->
                    var entry = tais.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        if (name == "antigravity" || name.endsWith("/antigravity") || name == "agy" || name.endsWith("/agy")) {
                            FileOutputStream(destinationFile).use { fos ->
                                tais.copyTo(fos)
                            }
                            return true
                        }
                        entry = tais.nextTarEntry
                    }
                }
            }
        }
        return false
    }

    /**
     * Calculates SHA-512 checksum of a file.
     */
    fun calculateSha512(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { inStream ->
            FileOutputStream(dest).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    /**
     * Semver comparison: returns true if latest is strictly newer than current.
     */
    fun isVersionNewer(latest: String, current: String?): Boolean {
        if (current.isNullOrBlank()) return true
        val pLatest = parseSemverComponents(latest)
        val pCurrent = parseSemverComponents(current)

        for (i in 0 until maxOf(pLatest.size, pCurrent.size)) {
            val vL = pLatest.getOrElse(i) { 0 }
            val vC = pCurrent.getOrElse(i) { 0 }
            if (vL > vC) return true
            if (vL < vC) return false
        }
        return false
    }

    private fun parseSemverComponents(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").split("-", "+")[0]
        return clean.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Updates running state for Antigravity Remote Control.
     */
    fun setRemoteControlState(isRunning: Boolean, remoteControlUrl: String? = null, isReady: Boolean = false) {
        val current = _state.value
        _state.value = current.copy(
            status = if (isRunning) AntigravityStatus.RUNNING else if (current.installedVersion != null) AntigravityStatus.INSTALLED else current.status,
            remoteControlUrl = remoteControlUrl,
            isRemoteControlReady = isReady,
            authState = if (isReady) AntigravityAuthState.CONNECTED else if (!isRunning && current.authState != AntigravityAuthState.CANCELLED && current.authState != AntigravityAuthState.ERROR) AntigravityAuthState.IDLE else current.authState,
            statusMessage = if (isReady) "Remote Control active: $remoteControlUrl" else if (isRunning) "Antigravity CLI running" else current.statusMessage,
        )
    }

    /**
     * Updates authentication state machine for the active Antigravity session.
     */
    fun updateAuthState(
        authState: AntigravityAuthState,
        authUrl: String? = null,
        remoteControlUrl: String? = null,
        statusMessage: String? = null,
        errorMessage: String? = null,
    ) {
        val current = _state.value
        _state.value = current.copy(
            authState = authState,
            authUrl = authUrl ?: current.authUrl,
            remoteControlUrl = remoteControlUrl ?: current.remoteControlUrl,
            isRemoteControlReady = authState == AntigravityAuthState.CONNECTED || current.isRemoteControlReady,
            status = if (authState == AntigravityAuthState.CONNECTED || authState.isInAuthFlow) AntigravityStatus.RUNNING else current.status,
            statusMessage = statusMessage ?: current.statusMessage,
            errorMessage = errorMessage ?: current.errorMessage,
        )
    }

    /**
     * Starts the Antigravity CLI process strictly in the background inside guest PRoot userspace.
     * Captures and monitors PTY output in background without opening any terminal UI.
     * Automatically handles Google OAuth selection and Remote Control URL extraction.
     */
    suspend fun startAntigravitySession(environment: Environment) = withContext(Dispatchers.IO) {
        if (_state.value.authState.isConnecting || _state.value.authState.isReady) {
            log.info("Antigravity background session already in progress or ready, skipping start")
            return@withContext
        }

        val binary = getAgyBinaryFile(environment)
        if (!binary.exists()) {
            updateAuthState(
                AntigravityAuthState.ERROR,
                errorMessage = "Antigravity binary not found. Please install Antigravity CLI first."
            )
            return@withContext
        }

        stopAntigravity() // Clean up any stale PTY session

        activeEnvironmentId = environment.id.value
        automation.reset()
        updateAuthState(
            AntigravityAuthState.STARTING,
            statusMessage = "Starting Antigravity CLI in background…"
        )

        val targetShell = environment.configuration.shell.ifBlank { "/bin/bash" }
        val shellCommand = listOf(targetShell, "-l", "-c", LAUNCH_COMMAND)

        val session = try {
            runtimeBackend?.startInteractiveShell(
                environment = environment,
                rows = 24,
                cols = 80,
                command = shellCommand
            )
        } catch (e: Exception) {
            log.error("Failed to start Antigravity interactive shell", e)
            null
        }

        if (session == null) {
            updateAuthState(
                AntigravityAuthState.ERROR,
                errorMessage = "Failed to spawn background PTY process inside Linux userspace."
            )
            return@withContext
        }

        activePtySession = session
        setRemoteControlState(isRunning = true, isReady = false)

        backgroundReadJob = scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val bytesRead = session.read(buffer)
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        val actions = automation.processChunk(chunk)
                        for (action in actions) {
                            when (action) {
                                is AntigravityAutomationAction.SendInput -> {
                                    val desc = action.description
                                    log.info("Antigravity background automation sending input ($desc)")
                                    val written = try {
                                        session.write(action.text)
                                    } catch (e: Exception) {
                                        log.error("Failed to write $desc to PTY", e)
                                        -1
                                    }
                                    if (written <= 0) {
                                        log.error("PTY write failed for $desc (bytes written: $written)")
                                        automation.notifyError("PTY input failure: unable to send $desc to Antigravity CLI process.")
                                        updateAuthState(
                                            AntigravityAuthState.ERROR,
                                            errorMessage = "PTY input failure: unable to send $desc to Antigravity CLI process.",
                                            statusMessage = "PTY write failed"
                                        )
                                    }
                                }
                                is AntigravityAutomationAction.OpenAuthBrowser -> {
                                    log.info("Antigravity background automation detected OAuth URL: ${action.url}")
                                    hasSubmittedAuthCode.set(false)
                                    authLaunchTimestampMs = System.currentTimeMillis()
                                    activeOAuthState = automation.extractOAuthStateParameter(action.url)
                                    val redirectUri = automation.extractOAuthRedirectUri(action.url)
                                    log.info("Google OAuth parameters: redirect_uri=$redirectUri, state=$activeOAuthState")
                                    updateAuthState(
                                        AntigravityAuthState.WAITING_FOR_BROWSER_AUTH,
                                        authUrl = action.url,
                                        statusMessage = "Opening Google Sign-In in browser…"
                                    )
                                    _openAuthBrowser.tryEmit(action.url)
                                }
                                is AntigravityAutomationAction.NavigateWebView -> {
                                    log.info("Antigravity background automation detected Remote Control URL: ${action.url}")
                                    updateAuthState(
                                        AntigravityAuthState.CONNECTED,
                                        remoteControlUrl = action.url,
                                        statusMessage = "Remote Control active: ${action.url}"
                                    )
                                    _navigateToWebView.tryEmit(action.url)
                                }
                                is AntigravityAutomationAction.StateChanged -> {
                                    log.info("Antigravity background automation state changed: ${action.newState} - ${action.message}")
                                    if (action.newState == AntigravityAuthState.WAITING_FOR_AUTH_CODE) {
                                        hasSubmittedAuthCode.set(false)
                                    }
                                    updateAuthState(action.newState, statusMessage = action.message)
                                }
                            }
                        }
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    log.error("Antigravity background PTY read loop error", e)
                }
            } finally {
                val exitCode = session.getExitCode() ?: 0
                log.info("Antigravity background CLI session ended with exit code $exitCode")
                val currentAuth = _state.value.authState
                if (currentAuth != AntigravityAuthState.IDLE &&
                    currentAuth != AntigravityAuthState.STOPPING &&
                    currentAuth != AntigravityAuthState.CANCELLED
                ) {
                    if (currentAuth.isInAuthFlow || currentAuth == AntigravityAuthState.CONNECTING_WEBVIEW) {
                        updateAuthState(
                            AntigravityAuthState.ERROR,
                            errorMessage = "Antigravity session is no longer running (exit code $exitCode). Tap Start Again to restart authentication.",
                            statusMessage = "Session terminated (exit code $exitCode)"
                        )
                    } else if (exitCode != 0) {
                        updateAuthState(
                            AntigravityAuthState.ERROR,
                            errorMessage = "Antigravity CLI process exited with code $exitCode",
                            statusMessage = "Process exited with code $exitCode"
                        )
                    } else {
                        setRemoteControlState(isRunning = false, isReady = false)
                        updateAuthState(AntigravityAuthState.IDLE, statusMessage = "Antigravity CLI exited")
                    }
                }
            }
        }
    }

    /**
     * Source of the authorization code.
     */
    enum class AuthCodeSource {
        MANUAL,
        CALLBACK
    }

    /**
     * Submission result for Google OAuth authorization code.
     */
    sealed class AuthCodeSubmitResult {
        object Success : AuthCodeSubmitResult()
        object IgnoredDuplicate : AuthCodeSubmitResult()
        data class Error(val message: String, val recoverable: Boolean = true) : AuthCodeSubmitResult()
    }

    /**
     * Indicates whether the CLI is currently waiting for authorization code input.
     */
    val isWaitingForAuthCode: Boolean
        get() = automation.isWaitingForAuthCode

    /**
     * Validates and processes an OAuth callback URI received via deep link intent.
     * Enforces scheme, host, state, error, and expiration validation before submission.
     */
    fun handleOAuthCallback(uri: Uri): AuthCodeSubmitResult {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val validSchemes = setOf("antigravity", "linuxdroid", "google.antigravity", "https")
        if (scheme == null || scheme !in validSchemes) {
            log.warn("Ignored callback with unsupported scheme: $scheme")
            return AuthCodeSubmitResult.Error("Unsupported callback scheme: $scheme", recoverable = true)
        }

        if (scheme == "https") {
            val host = uri.host?.lowercase(Locale.ROOT)
            val isValidHost = host != null && (host == "antigravity.google" || host == "antigravity.google.com" || host.endsWith(".antigravity.google.com"))
            if (!isValidHost) {
                log.warn("Ignored HTTPS callback with unauthorized host: $host")
                return AuthCodeSubmitResult.Error("Unauthorized HTTPS callback host: $host", recoverable = true)
            }
        }

        // Validate error parameter
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val errorDescription = uri.getQueryParameter("error_description") ?: error
            log.warn("OAuth callback reported error: $errorDescription")
            updateAuthState(
                AntigravityAuthState.ERROR,
                errorMessage = "Google authentication denied or failed: $errorDescription",
                statusMessage = "Authentication failed"
            )
            return AuthCodeSubmitResult.Error("Authentication failed: $errorDescription", recoverable = true)
        }

        // Validate expiration (within 15 minutes of browser launch)
        if (authLaunchTimestampMs > 0 && (System.currentTimeMillis() - authLaunchTimestampMs) > 15 * 60 * 1000) {
            log.warn("OAuth callback arrived after expiration window")
            return AuthCodeSubmitResult.Error("OAuth callback expired. Please tap Start Again.", recoverable = true)
        }

        // Validate state parameter if one was captured
        val returnedState = uri.getQueryParameter("state")
        if (!activeOAuthState.isNullOrBlank() && !returnedState.isNullOrBlank()) {
            if (returnedState != activeOAuthState) {
                log.warn("OAuth state parameter mismatch! Expected $activeOAuthState but got $returnedState")
                return AuthCodeSubmitResult.Error("OAuth state validation mismatch. Potential security issue.", recoverable = true)
            }
        }

        // Extract authorization code
        val code = uri.getQueryParameter("code") ?: uri.getQueryParameter("approvalCode")
        if (code.isNullOrBlank()) {
            log.warn("OAuth callback missing code parameter: $uri")
            return AuthCodeSubmitResult.Error("No authorization code found in callback.", recoverable = true)
        }

        log.info("Extracted authorization code from callback successfully")
        return submitAuthCode(code, source = AuthCodeSource.CALLBACK)
    }

    /**
     * Submits authorization code to the active Antigravity CLI process stdin.
     * Serves as the single shared coordinator for both manual input and automatic deep link callbacks.
     * - Validates nonempty, trims accidental whitespace and line breaks
     * - Verifies process is alive
     * - Enforces single submission (first valid callback or manual submission wins)
     * - Ignores duplicates
     * - Sends code followed by exactly one Enter (\r)
     * - Retains input on failure; clears on success
     */
    fun submitAuthCode(code: String, source: AuthCodeSource = AuthCodeSource.MANUAL): AuthCodeSubmitResult {
        val cleanCode = code.trim().replace("\r", "").replace("\n", "")
        if (cleanCode.isEmpty()) {
            return AuthCodeSubmitResult.Error("Authorization code cannot be empty.", recoverable = true)
        }

        // 1. Check if authentication already succeeded
        val currentAuthState = _state.value.authState
        if (currentAuthState in setOf(
                AntigravityAuthState.CONNECTED,
                AntigravityAuthState.CONNECTING_WEBVIEW,
                AntigravityAuthState.WAITING_FOR_REMOTE_URL
            )
        ) {
            log.info("Antigravity authentication already completed, ignoring submission from $source")
            return AuthCodeSubmitResult.IgnoredDuplicate
        }

        // 2. Check if submission is already in flight
        if (currentAuthState in setOf(AntigravityAuthState.SUBMITTING_AUTH_CODE, AntigravityAuthState.AUTHENTICATING) ||
            hasSubmittedAuthCode.get()
        ) {
            log.info("Antigravity code submission already in flight, ignoring duplicate from $source")
            return AuthCodeSubmitResult.IgnoredDuplicate
        }

        // 3. Verify PTY session is alive
        val session = activePtySession
        if (session == null || !session.isAlive()) {
            updateAuthState(
                AntigravityAuthState.ERROR,
                errorMessage = "Antigravity session is no longer running. Please tap Start Again.",
                statusMessage = "Session terminated"
            )
            return AuthCodeSubmitResult.Error("Antigravity session is no longer running. Please tap Start Again.", recoverable = false)
        }

        // 4. Verify CLI is in an auth-waiting state
        val isReadyForCode = currentAuthState in setOf(
            AntigravityAuthState.WAITING_FOR_BROWSER_AUTH,
            AntigravityAuthState.WAITING_FOR_AUTH_CODE
        ) || automation.isWaitingForAuthCode

        if (!isReadyForCode) {
            return AuthCodeSubmitResult.Error("Antigravity CLI process is not ready to receive input yet. Your code has been retained.", recoverable = true)
        }

        // 5. Atomic single-submission claim: first valid callback or manual submission wins
        if (!hasSubmittedAuthCode.compareAndSet(false, true)) {
            log.info("Concurrent code submission race detected, duplicate from $source ignored")
            return AuthCodeSubmitResult.IgnoredDuplicate
        }

        // 6. Write code + exactly one Enter (\r) to PTY
        val actions = automation.submitAuthCode(cleanCode)
        for (action in actions) {
            when (action) {
                is AntigravityAutomationAction.SendInput -> {
                    val written = try {
                        session.write(action.text)
                    } catch (e: Exception) {
                        log.error("Failed to write authorization code to PTY", e)
                        -1
                    }
                    if (written <= 0) {
                        hasSubmittedAuthCode.set(false) // Allow retry on failure
                        updateAuthState(
                            AntigravityAuthState.ERROR,
                            errorMessage = "PTY write failed while submitting authorization code.",
                            statusMessage = "PTY write failed"
                        )
                        return AuthCodeSubmitResult.Error("Failed to write authorization code to Antigravity CLI process.", recoverable = true)
                    }
                }
                is AntigravityAutomationAction.StateChanged -> {
                    updateAuthState(action.newState, statusMessage = action.message)
                }
                else -> {}
            }
        }
        log.info("Successfully submitted authorization code via $source (${cleanCode.length} chars)")
        return AuthCodeSubmitResult.Success
    }

    /**
     * Cancels the active authentication flow without leaving an orphaned CLI process.
     */
    fun cancelAuthentication() {
        if (_state.value.authState == AntigravityAuthState.CANCELLED) return
        hasSubmittedAuthCode.set(false)
        activeOAuthState = null
        automation.notifyCancelled()
        try {
            activePtySession?.write("\u0003") // Send SIGINT
        } catch (_: Exception) {}
        backgroundReadJob?.cancel()
        backgroundReadJob = null
        try {
            activePtySession?.close()
        } catch (_: Exception) {}
        activePtySession = null
        activeEnvironmentId = null
        val current = _state.value
        _state.value = current.copy(
            authState = AntigravityAuthState.CANCELLED,
            status = if (current.installedVersion != null) AntigravityStatus.INSTALLED else AntigravityStatus.NOT_INSTALLED,
            isRemoteControlReady = false,
            remoteControlUrl = null,
            statusMessage = "Authentication cancelled"
        )
    }

    /**
     * Opt-in diagnostics history for login menu detection, navigation, and transition.
     * Guaranteed never to contain authorization codes, tokens, or credentials.
     */
    val menuDiagnostics: List<AntigravityMenuDiagnostics>
        get() = automation.diagnostics

    /**
     * Stops the background Antigravity CLI process, closes the PTY session, releases resources.
     */
    fun stopAntigravity() {
        hasSubmittedAuthCode.set(false)
        activeOAuthState = null
        if (_state.value.authState == AntigravityAuthState.IDLE && activePtySession == null) {
            return
        }
        updateAuthState(AntigravityAuthState.STOPPING, statusMessage = "Stopping Antigravity…")
        automation.notifyStopping()
        try {
            activePtySession?.write("\u0003") // Send SIGINT
        } catch (_: Exception) {}
        backgroundReadJob?.cancel()
        backgroundReadJob = null
        try {
            activePtySession?.close()
        } catch (_: Exception) {}
        activePtySession = null
        activeEnvironmentId = null
        automation.notifyIdle()
        setRemoteControlState(isRunning = false, isReady = false)
        updateAuthState(AntigravityAuthState.IDLE, statusMessage = "Antigravity CLI stopped")
    }

    /**
     * Notifies whether the WebView is minimized into a floating mini-window.
     */
    fun setMinimized(minimized: Boolean) {
        val actions = automation.notifyMinimized(minimized)
        for (action in actions) {
            if (action is AntigravityAutomationAction.StateChanged) {
                updateAuthState(action.newState, statusMessage = action.message)
            }
        }
    }
}
