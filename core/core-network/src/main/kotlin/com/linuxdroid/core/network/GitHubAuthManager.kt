package com.linuxdroid.core.network

import android.content.Context
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.CodespaceAuthState
import com.linuxdroid.core.model.CodespaceDeviceCode
import com.linuxdroid.core.model.GitHubUser
import com.linuxdroid.core.storage.SecureCredentialStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Manages GitHub authentication for Codespaces.
 *
 * Implements RFC 8628 OAuth 2.0 Device Authorization Grant,
 * Personal Access Token (PAT) authentication, secure credential storage,
 * and authenticated user profile retrieval.
 */
class GitHubAuthManager(
    private val context: Context,
    private val secureStorage: SecureCredentialStorage = SecureCredentialStorage(context),
) {
    private val log = LinuxDroidLogger(LogSubsystem.NETWORK)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val KEY_GITHUB_TOKEN = "github_access_token"
        const val KEY_GITHUB_USER = "github_user_login"
        const val KEY_CLIENT_ID = "github_custom_client_id"
        // Default Client ID for GitHub CLI device flow
        const val DEFAULT_CLIENT_ID = "01ab8ac9400c4e429b23"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_API_URL = "https://api.github.com/user"
        private const val SCOPES = "codespace,read:user"
    }

    private val _authState = MutableStateFlow<CodespaceAuthState>(
        if (secureStorage.hasToken(KEY_GITHUB_TOKEN)) CodespaceAuthState.AUTHENTICATED
        else CodespaceAuthState.SIGNED_OUT
    )
    val authState: StateFlow<CodespaceAuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<GitHubUser?>(null)
    val currentUser: StateFlow<GitHubUser?> = _currentUser.asStateFlow()

    private val _activeDeviceCode = MutableStateFlow<CodespaceDeviceCode?>(null)
    val activeDeviceCode: StateFlow<CodespaceDeviceCode?> = _activeDeviceCode.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        if (_authState.value == CodespaceAuthState.AUTHENTICATED) {
            val cachedLogin = secureStorage.getToken(KEY_GITHUB_USER)
            if (cachedLogin != null) {
                _currentUser.value = GitHubUser(login = cachedLogin)
            }
        }
    }

    /**
     * Checks if a valid token is stored.
     */
    fun hasValidToken(): Boolean = secureStorage.hasToken(KEY_GITHUB_TOKEN)

    /**
     * Gets the current access token.
     */
    fun getAccessToken(): String? = secureStorage.getToken(KEY_GITHUB_TOKEN)

    /**
     * Sets or gets the custom client ID if provided.
     */
    fun getClientId(): String {
        return secureStorage.getToken(KEY_CLIENT_ID) ?: DEFAULT_CLIENT_ID
    }

    fun setClientId(clientId: String?) {
        if (clientId.isNullOrBlank()) {
            secureStorage.clearToken(KEY_CLIENT_ID)
        } else {
            secureStorage.saveToken(KEY_CLIENT_ID, clientId.trim())
        }
    }

    /**
     * Initiates the RFC 8628 Device Authorization Flow.
     */
    suspend fun startDeviceAuthorization(
        customClientId: String? = null,
    ): Result<CodespaceDeviceCode> = withContext(Dispatchers.IO) {
        val clientId = customClientId?.trim()?.ifBlank { null } ?: getClientId()
        _authState.value = CodespaceAuthState.AUTHENTICATING
        _statusMessage.value = "Requesting device code from GitHub…"

        try {
            val url = URL(DEVICE_CODE_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val body = "client_id=${URLEncoder.encode(clientId, "UTF-8")}&scope=${URLEncoder.encode(SCOPES, "UTF-8")}"
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = json.parseToJsonElement(responseText).jsonObject

                val deviceCode = jsonObj["device_code"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Missing device_code in response: $responseText"))
                val userCode = jsonObj["user_code"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Missing user_code in response: $responseText"))
                val verificationUri = jsonObj["verification_uri"]?.jsonPrimitive?.content
                    ?: "https://github.com/login/device"
                val expiresIn = jsonObj["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 900
                val interval = jsonObj["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

                val code = CodespaceDeviceCode(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    expiresIn = expiresIn,
                    interval = interval,
                )
                _activeDeviceCode.value = code
                _authState.value = CodespaceAuthState.DEVICE_CODE_PENDING
                _statusMessage.value = "Enter code $userCode at $verificationUri"
                log.info("Device authorization initiated. User code: $userCode")
                Result.success(code)
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                _authState.value = CodespaceAuthState.ERROR
                _statusMessage.value = "Failed to start device flow: $errorText"
                Result.failure(Exception("HTTP $responseCode: $errorText"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("Failed to initiate device flow", e)
            _authState.value = CodespaceAuthState.ERROR
            _statusMessage.value = "Network error: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Polls GitHub token endpoint until user approves or device code expires.
     */
    suspend fun pollForToken(
        deviceCode: CodespaceDeviceCode,
        customClientId: String? = null,
        onStatusUpdate: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        val clientId = customClientId?.trim()?.ifBlank { null } ?: getClientId()
        var intervalSeconds = maxOf(5, deviceCode.interval)
        val startTime = System.currentTimeMillis()
        val expiryMs = deviceCode.expiresIn * 1000L

        while (System.currentTimeMillis() - startTime < expiryMs) {
            delay(intervalSeconds * 1000L)

            try {
                val url = URL(ACCESS_TOKEN_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                val body = "client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                        "&device_code=${URLEncoder.encode(deviceCode.deviceCode, "UTF-8")}" +
                        "&grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")}"
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = json.parseToJsonElement(responseText).jsonObject

                    val error = jsonObj["error"]?.jsonPrimitive?.content
                    if (error != null) {
                        when (error) {
                            "authorization_pending" -> {
                                onStatusUpdate("Waiting for authorization in browser…")
                                continue
                            }
                            "slow_down" -> {
                                intervalSeconds += 5
                                onStatusUpdate("Slow down received, polling every $intervalSeconds seconds…")
                                continue
                            }
                            "expired_token" -> {
                                _authState.value = CodespaceAuthState.ERROR
                                _statusMessage.value = "Device authorization expired. Please try again."
                                _activeDeviceCode.value = null
                                return@withContext Result.failure(IllegalStateException("Device code expired"))
                            }
                            "access_denied" -> {
                                _authState.value = CodespaceAuthState.ERROR
                                _statusMessage.value = "Authorization was denied by user."
                                _activeDeviceCode.value = null
                                return@withContext Result.failure(IllegalStateException("Authorization denied"))
                            }
                            else -> {
                                val errorDesc = jsonObj["error_description"]?.jsonPrimitive?.content ?: error
                                _authState.value = CodespaceAuthState.ERROR
                                _statusMessage.value = "OAuth error: $errorDesc"
                                _activeDeviceCode.value = null
                                return@withContext Result.failure(IllegalStateException(errorDesc))
                            }
                        }
                    }

                    val token = jsonObj["access_token"]?.jsonPrimitive?.content
                    if (!token.isNullOrBlank()) {
                        saveAccessToken(token)
                        _activeDeviceCode.value = null
                        _authState.value = CodespaceAuthState.AUTHENTICATED
                        _statusMessage.value = "Authenticated successfully!"
                        fetchCurrentUser()
                        return@withContext Result.success(token)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Polling error: ${e.message}")
            }
        }

        _authState.value = CodespaceAuthState.ERROR
        _statusMessage.value = "Device authorization timed out."
        _activeDeviceCode.value = null
        Result.failure(IllegalStateException("Authorization timed out"))
    }

    /**
     * Authenticates using a Personal Access Token (PAT).
     */
    suspend fun authenticateWithToken(token: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Access token cannot be empty"))
        }

        _authState.value = CodespaceAuthState.AUTHENTICATING
        _statusMessage.value = "Verifying token with GitHub…"

        try {
            val userResult = verifyAndFetchUser(trimmedToken)
            if (userResult.isSuccess) {
                saveAccessToken(trimmedToken)
                val user = userResult.getOrThrow()
                secureStorage.saveToken(KEY_GITHUB_USER, user.login)
                _currentUser.value = user
                _authState.value = CodespaceAuthState.AUTHENTICATED
                _statusMessage.value = "Signed in as @${user.login}"
                log.info("Successfully authenticated with PAT for user @${user.login}")
                Result.success(user)
            } else {
                _authState.value = CodespaceAuthState.SIGNED_OUT
                _statusMessage.value = "Invalid GitHub token: ${userResult.exceptionOrNull()?.message}"
                Result.failure(userResult.exceptionOrNull() ?: IllegalStateException("Token verification failed"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("Token authentication failed", e)
            _authState.value = CodespaceAuthState.ERROR
            _statusMessage.value = "Network or authentication error: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Verifies a token against GitHub's `/user` endpoint.
     */
    suspend fun verifyAndFetchUser(token: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        try {
            val url = URL(USER_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "LinuxDroid-App")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = json.parseToJsonElement(responseText).jsonObject

                val login = jsonObj["login"]?.jsonPrimitive?.content ?: "unknown"
                val id = jsonObj["id"]?.jsonPrimitive?.longOrNull ?: 0L
                val name = jsonObj["name"]?.jsonPrimitive?.content
                val avatarUrl = jsonObj["avatar_url"]?.jsonPrimitive?.content

                Result.success(GitHubUser(login = login, id = id, name = name, avatarUrl = avatarUrl))
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Result.failure(Exception("HTTP $responseCode: $errText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refreshes the currently authenticated user profile.
     */
    suspend fun fetchCurrentUser(): Result<GitHubUser> {
        val token = getAccessToken() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val result = verifyAndFetchUser(token)
        if (result.isSuccess) {
            val user = result.getOrThrow()
            _currentUser.value = user
            secureStorage.saveToken(KEY_GITHUB_USER, user.login)
        }
        return result
    }

    /**
     * Saves the access token into secure storage.
     */
    fun saveAccessToken(token: String) {
        secureStorage.saveToken(KEY_GITHUB_TOKEN, token)
        _authState.value = CodespaceAuthState.AUTHENTICATED
    }

    /**
     * Signs out and removes all saved GitHub credentials.
     */
    fun signOut() {
        secureStorage.clearToken(KEY_GITHUB_TOKEN)
        secureStorage.clearToken(KEY_GITHUB_USER)
        _currentUser.value = null
        _activeDeviceCode.value = null
        _authState.value = CodespaceAuthState.SIGNED_OUT
        _statusMessage.value = "Signed out."
        log.info("GitHub credentials cleared. User signed out.")
    }
}
