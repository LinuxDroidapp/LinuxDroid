package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.app.service.LinuxSessionService
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.core.model.AntigravityState
import com.linuxdroid.core.model.AntigravityStatus
import com.linuxdroid.core.model.Environment
import com.linuxdroid.linux.bootstrap.AntigravityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AntigravityViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val antigravityManager: AntigravityManager,
    private val dao: EnvironmentDao,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)

    val state: StateFlow<AntigravityState> = antigravityManager.state
    val navigateToWebView: SharedFlow<String> = antigravityManager.navigateToWebView

    val activeEnvironment: StateFlow<Environment?> = dao.observeAll()
        .map { list -> list.firstOrNull()?.let { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Collect browser open requests from background automation
        viewModelScope.launch {
            antigravityManager.openAuthBrowser.collect { url ->
                log.info("Triggering external browser for detected OAuth URL: $url")
                openBrowser(url)
            }
        }

        viewModelScope.launch {
            activeEnvironment.filterNotNull().collect { env ->
                // Check installation integrity on initial load
                if (state.value.status == AntigravityStatus.NOT_INSTALLED || state.value.installedVersion == null) {
                    antigravityManager.checkInstallationStatus(env)
                    // Check for updates if installed
                    if (state.value.status == AntigravityStatus.INSTALLED) {
                        antigravityManager.checkForUpdates(env, autoUpdate = false)
                    }
                }
            }
        }
    }

    /**
     * Starts the Antigravity background process and Android foreground service.
     */
    fun startAntigravity(environment: Environment) {
        LinuxSessionService.start(context, "Google Antigravity", environment.id.value)
        viewModelScope.launch {
            antigravityManager.startAntigravitySession(environment)
        }
    }

    /**
     * Submits authorization code to the active Antigravity CLI process.
     */
    fun submitAuthCode(code: String): AntigravityManager.AuthCodeSubmitResult {
        return antigravityManager.submitAuthCode(code)
    }

    /**
     * Cancels authentication flow cleanly without leaving orphaned processes.
     */
    fun cancelAuthentication(environmentId: String? = null) {
        antigravityManager.cancelAuthentication()
        val envId = environmentId ?: activeEnvironment.value?.id?.value
        LinuxSessionService.stop(context, envId)
    }

    val isWaitingForAuthCode: Boolean
        get() = antigravityManager.isWaitingForAuthCode

    /**
     * Stops the background Antigravity CLI session and foreground service.
     */
    fun stopAntigravity(environmentId: String? = null) {
        antigravityManager.stopAntigravity()
        val envId = environmentId ?: activeEnvironment.value?.id?.value
        LinuxSessionService.stop(context, envId)
    }

    /**
     * Minimizes or restores WebView presentation mode.
     */
    fun setMinimized(minimized: Boolean) {
        antigravityManager.setMinimized(minimized)
    }

    /**
     * Re-opens the Google OAuth authorization URL in Chrome or default browser.
     */
    fun openGoogleAuthBrowser() {
        val url = state.value.authUrl ?: return
        openBrowser(url)
    }

    /**
     * Opt-in diagnostics history for login menu detection, navigation, and transition.
     * Guaranteed never to contain authorization codes, tokens, or credentials.
     */
    val menuDiagnostics: List<com.linuxdroid.linux.bootstrap.AntigravityMenuDiagnostics>
        get() = antigravityManager.menuDiagnostics

    /**
     * Launches the OAuth URL in the Android default browser with fallback to Chrome.
     */
    fun openBrowser(urlStr: String) {
        try {
            val uri = Uri.parse(urlStr)
            val defaultBrowserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(defaultBrowserIntent)
        } catch (e: Exception) {
            log.warn("Failed to open default browser for $urlStr, attempting Chrome fallback", e)
            try {
                val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                    setPackage("com.android.chrome")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chromeIntent)
            } catch (e2: Exception) {
                log.error("Failed to launch any browser for $urlStr", e2)
            }
        }
    }

    fun installAntigravity(environment: Environment) {
        viewModelScope.launch {
            antigravityManager.install(environment) { progress, message ->
                log.info("Install progress: $progress - $message")
            }
        }
    }

    fun checkForUpdates(environment: Environment, autoUpdate: Boolean = false) {
        viewModelScope.launch {
            antigravityManager.checkForUpdates(environment, autoUpdate)
        }
    }

    fun refreshStatus(environment: Environment) {
        viewModelScope.launch {
            antigravityManager.checkInstallationStatus(environment)
        }
    }

    fun getLaunchCommand(): String {
        return AntigravityManager.LAUNCH_COMMAND
    }
}
