package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.CodespaceAuthState
import com.linuxdroid.core.model.CodespaceDeviceCode
import com.linuxdroid.core.model.CodespaceItem
import com.linuxdroid.core.model.CodespaceState
import com.linuxdroid.core.model.GitHubUser
import com.linuxdroid.core.network.CodespacesManager
import com.linuxdroid.core.network.GitHubAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CodespacesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val authManager: GitHubAuthManager,
    val codespacesManager: CodespacesManager,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.NETWORK)

    val authState: StateFlow<CodespaceAuthState> = authManager.authState
    val currentUser: StateFlow<GitHubUser?> = authManager.currentUser
    val activeDeviceCode: StateFlow<CodespaceDeviceCode?> = authManager.activeDeviceCode
    val authStatusMessage: StateFlow<String?> = authManager.statusMessage

    val codespaces: StateFlow<List<CodespaceItem>> = codespacesManager.codespaces
    val isLoadingCodespaces: StateFlow<Boolean> = codespacesManager.isLoading
    val codespacesError: StateFlow<String?> = codespacesManager.lastError

    private val _actionFeedback = MutableStateFlow<String?>(null)
    val actionFeedback: StateFlow<String?> = _actionFeedback.asStateFlow()

    private var pollingJob: Job? = null

    init {
        if (authManager.hasValidToken()) {
            viewModelScope.launch {
                authManager.fetchCurrentUser()
                refreshCodespaces()
            }
        }
    }

    fun startDeviceCodeFlow(customClientId: String? = null) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val result = authManager.startDeviceAuthorization(customClientId)
            if (result.isSuccess) {
                val deviceCode = result.getOrThrow()
                authManager.pollForToken(deviceCode, customClientId) { status ->
                    _actionFeedback.value = status
                }
                if (authManager.hasValidToken()) {
                    refreshCodespaces()
                }
            }
        }
    }

    fun cancelDeviceFlow() {
        pollingJob?.cancel()
        pollingJob = null
        authManager.signOut()
    }

    fun authenticateWithToken(token: String) {
        viewModelScope.launch {
            val result = authManager.authenticateWithToken(token)
            if (result.isSuccess) {
                _actionFeedback.value = "Connected as @${result.getOrThrow().login}"
                refreshCodespaces()
            } else {
                _actionFeedback.value = "Authentication failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun refreshCodespaces() {
        viewModelScope.launch {
            val result = codespacesManager.fetchCodespaces()
            if (result.isFailure) {
                _actionFeedback.value = "Failed to load codespaces: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun startCodespace(codespace: CodespaceItem) {
        viewModelScope.launch {
            _actionFeedback.value = "Starting ${codespace.displayName ?: codespace.name}…"
            val result = codespacesManager.startCodespace(codespace.name)
            if (result.isSuccess) {
                _actionFeedback.value = "Start signal sent. Refreshing…"
                refreshCodespaces()
            } else {
                _actionFeedback.value = "Failed to start: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun stopCodespace(codespace: CodespaceItem) {
        viewModelScope.launch {
            _actionFeedback.value = "Stopping ${codespace.displayName ?: codespace.name}…"
            val result = codespacesManager.stopCodespace(codespace.name)
            if (result.isSuccess) {
                _actionFeedback.value = "Stop signal sent."
                refreshCodespaces()
            } else {
                _actionFeedback.value = "Failed to stop: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun signOut() {
        pollingJob?.cancel()
        pollingJob = null
        authManager.signOut()
        _actionFeedback.value = "Signed out from GitHub."
    }

    fun clearFeedback() {
        _actionFeedback.value = null
    }

    fun getTerminalConnectionCommand(codespace: CodespaceItem): String {
        return codespacesManager.buildTerminalConnectionCommand(codespace)
    }
}
