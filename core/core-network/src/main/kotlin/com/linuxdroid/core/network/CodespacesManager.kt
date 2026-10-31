package com.linuxdroid.core.network

import android.content.Context
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.CodespaceItem
import com.linuxdroid.core.model.CodespaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages GitHub Codespaces operations: listing, starting, stopping, and connection commands.
 */
class CodespacesManager(
    private val authManager: GitHubAuthManager? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.NETWORK)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val CODESPACES_BASE_URL = "https://api.github.com/user/codespaces"
    }

    private val _codespaces = MutableStateFlow<List<CodespaceItem>>(emptyList())
    val codespaces: StateFlow<List<CodespaceItem>> = _codespaces.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Fetches all accessible codespaces for the currently authenticated user.
     */
    suspend fun fetchCodespaces(): Result<List<CodespaceItem>> = withContext(Dispatchers.IO) {
        val token = authManager?.getAccessToken()
        if (token.isNullOrBlank()) {
            val err = "Not authenticated with GitHub. Please sign in first."
            _lastError.value = err
            return@withContext Result.failure(IllegalStateException(err))
        }

        _isLoading.value = true
        _lastError.value = null

        try {
            val url = URL(CODESPACES_BASE_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "LinuxDroid-App")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val parsedItems = parseCodespacesResponse(responseText)
                _codespaces.value = parsedItems
                _isLoading.value = false
                log.info("Fetched ${parsedItems.size} Codespaces from GitHub")
                Result.success(parsedItems)
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val err = "GitHub API Error ($responseCode): $errText"
                _lastError.value = err
                _isLoading.value = false
                log.error(err)
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.message ?: "Network error while fetching codespaces"
            _lastError.value = msg
            _isLoading.value = false
            log.error("Failed to fetch codespaces", e)
            Result.failure(e)
        }
    }

    /**
     * Requests GitHub to start/resume a stopped or awaiting codespace.
     */
    suspend fun startCodespace(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authManager?.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))

        try {
            val url = URL("$CODESPACES_BASE_URL/$name/start")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "LinuxDroid-App")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                log.info("Successfully requested start for Codespace $name")
                // Optimistically update state
                _codespaces.value = _codespaces.value.map {
                    if (it.name == name) it.copy(state = CodespaceState.STARTING) else it
                }
                Result.success(Unit)
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Result.failure(Exception("Failed to start codespace: HTTP $responseCode - $errText"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Requests GitHub to stop a running codespace.
     */
    suspend fun stopCodespace(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authManager?.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))

        try {
            val url = URL("$CODESPACES_BASE_URL/$name/stop")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "LinuxDroid-App")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                log.info("Successfully requested stop for Codespace $name")
                _codespaces.value = _codespaces.value.map {
                    if (it.name == name) it.copy(state = CodespaceState.SHUTTING_DOWN) else it
                }
                Result.success(Unit)
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Result.failure(Exception("Failed to stop codespace: HTTP $responseCode - $errText"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Generates the shell command to launch inside the LinuxDroid interactive PTY terminal
     * to connect to the given Codespace.
     */
    fun buildTerminalConnectionCommand(codespace: CodespaceItem): String {
        val token = authManager?.getAccessToken() ?: ""
        // Uses gh CLI if installed, or guides the user directly in terminal
        return "export GH_TOKEN=\"$token\" && " +
                "(command -v gh >/dev/null 2>&1 && gh codespace ssh -c \"${codespace.name}\" || " +
                "echo -e \"\\033[1;33mConnecting to Codespace ${codespace.displayName ?: codespace.name}...\\033[0m\\n" +
                "Web IDE: ${codespace.webUrl}\\n\\n" +
                "To use CLI SSH: apt update && apt install -y gh\\ngh auth login\\ngh codespace ssh -c ${codespace.name}\")"
    }

    /**
     * Parses the GitHub JSON response containing codespaces array.
     */
    fun parseCodespacesResponse(jsonString: String): List<CodespaceItem> {
        val parsed = json.parseToJsonElement(jsonString)
        val array = when (parsed) {
            is JsonObject -> parsed["codespaces"]?.jsonArray ?: JsonArray(emptyList())
            is JsonArray -> parsed
            else -> JsonArray(emptyList())
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val name = obj["name"].stringOrNull() ?: return@mapNotNull null
            val displayName = obj["display_name"].stringOrNull()
            val repoObj = obj["repository"]?.jsonObject
            val repository = repoObj?.get("full_name").stringOrNull()
                ?: repoObj?.get("name").stringOrNull()
                ?: "unknown/repository"
            val gitStatusObj = obj["git_status"]?.jsonObject
            val branch = gitStatusObj?.get("ref").stringOrNull()
            val stateRaw = obj["state"].stringOrNull()
            val state = CodespaceState.fromString(stateRaw)

            val machineObj = obj["machine"]?.jsonObject
            val machineDisplayName = machineObj?.get("display_name").stringOrNull()

            val webUrl = obj["web_url"].stringOrNull() ?: "https://github.com/codespaces"
            val lastUsedAt = obj["last_used_at"].stringOrNull()
            val idleTimeout = obj["idle_timeout_minutes"]?.jsonPrimitive?.intOrNull

            CodespaceItem(
                id = id,
                name = name,
                displayName = displayName,
                repository = repository,
                branch = branch,
                state = state,
                machineDisplayName = machineDisplayName,
                webUrl = webUrl,
                lastUsedAt = lastUsedAt,
                idleTimeoutMinutes = idleTimeout,
            )
        }
    }

    private fun JsonElement?.stringOrNull(): String? {
        val prim = this as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        return prim.content.takeIf { it != "null" }
    }
}
