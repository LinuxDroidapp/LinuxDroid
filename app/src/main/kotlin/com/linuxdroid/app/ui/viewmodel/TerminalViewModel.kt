package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogConfig
import com.linuxdroid.core.logging.LogFileManager
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessState
import com.linuxdroid.core.process.DefaultProcessManager
import com.linuxdroid.core.process.ProcessManager
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.runtime.TerminalBuffer
import com.linuxdroid.core.runtime.TerminalLineData
import com.linuxdroid.app.service.LinuxSessionService
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.linux.bootstrap.AntigravityAutomationAction
import com.linuxdroid.linux.bootstrap.AntigravityTerminalAutomation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val dao: EnvironmentDao,
    private val runtimeBackend: RuntimeBackend,
    private val logExporter: com.linuxdroid.core.diagnostics.RuntimeLogExporter,
    private val processManager: ProcessManager,
    private val sessionManager: com.linuxdroid.core.session.SessionManager,
    private val storage: com.linuxdroid.core.filesystem.EnvironmentStorage,
    private val antigravityManager: com.linuxdroid.linux.bootstrap.AntigravityManager,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.APPLICATION)
    val environmentId: String = checkNotNull(savedStateHandle["environmentId"])
    val initialCmd: String? = savedStateHandle["cmd"]
    val isAntigravitySession: Boolean = initialCmd?.contains("agy") == true || initialCmd?.contains("antigravity") == true

    val antigravityAutomation = if (isAntigravitySession) AntigravityTerminalAutomation().apply { reset() } else null

    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    val antigravityState = antigravityManager.state

    private val _navigateToWebView = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWebView: SharedFlow<String> = _navigateToWebView.asSharedFlow()

    private val _remoteControlUrl = MutableStateFlow<String?>(null)
    val remoteControlUrl: StateFlow<String?> = _remoteControlUrl.asStateFlow()

    private val _isRemoteControlReady = MutableStateFlow(false)
    val isRemoteControlReady: StateFlow<Boolean> = _isRemoteControlReady.asStateFlow()

    private val _detectedUrls = MutableStateFlow<List<String>>(emptyList())
    val detectedUrls: StateFlow<List<String>> = _detectedUrls.asStateFlow()

    val environment: StateFlow<Environment?> = dao.observeById(environmentId)
        .map { entity -> entity?.let { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val terminalBuffer = TerminalBuffer(maxScrollbackLines = 2000)
    val lines: StateFlow<List<TerminalLineData>> = terminalBuffer.lines
    val cursorRow: StateFlow<Int> = terminalBuffer.activeCursorRow
    val cursorCol: StateFlow<Int> = terminalBuffer.activeCursorCol
    val terminalTitle: StateFlow<String> = terminalBuffer.terminalTitle

    val currentTheme = MutableStateFlow(com.linuxdroid.core.runtime.terminal.TerminalTheme.MACOS_PRO)

    fun setTheme(theme: com.linuxdroid.core.runtime.terminal.TerminalTheme) {
        currentTheme.value = theme
        terminalBuffer.theme = theme
    }

    fun cycleTheme() {
        val themes = listOf(
            com.linuxdroid.core.runtime.terminal.TerminalTheme.MACOS_PRO,
            com.linuxdroid.core.runtime.terminal.TerminalTheme.MACOS_BASIC,
            com.linuxdroid.core.runtime.terminal.TerminalTheme.HOMEBREW,
            com.linuxdroid.core.runtime.terminal.TerminalTheme.SOLARIZED_DARK,
        )
        val currentIndex = themes.indexOfFirst { it.name == currentTheme.value.name }
        val nextTheme = themes[if (currentIndex >= 0) (currentIndex + 1) % themes.size else 0]
        setTheme(nextTheme)
    }

    private val _isShellActive = MutableStateFlow(false)
    val isShellActive: StateFlow<Boolean> = _isShellActive.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _shellExitCode = MutableStateFlow<Int?>(null)
    val shellExitCode: StateFlow<Int?> = _shellExitCode.asStateFlow()

    private val _guiInstallCompleted = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val guiInstallCompleted: SharedFlow<Boolean> = _guiInstallCompleted.asSharedFlow()

    private var ptySession: PtySession? = null
    private var readJob: Job? = null
    private val sessionMutex = Mutex()
    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var isCtrlActive = false
    private var isAltActive = false

    private var currentRows = 24
    private var currentCols = 80

    init {
        terminalBuffer.onOutputBytes = { bytes -> sendBytes(bytes) }
        viewModelScope.launch(Dispatchers.IO) {
            for (bytes in inputChannel) {
                val session = ptySession
                if (session?.isAlive() == true) {
                    if (LogConfig.generate_log) {
                        LogFileManager.appendTerminalBytes(com.linuxdroid.core.model.EnvironmentId(environmentId), bytes, 0, bytes.size)
                    }
                    session.write(bytes)
                }
            }
        }

        viewModelScope.launch {
            environment.filterNotNull().first { env ->
                startInteractiveShellSession(env)
                true
            }
        }
    }

    /**
     * Initializes and starts the persistent interactive shell in PTY.
     */
    fun startInteractiveShellSession(env: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                if (ptySession?.isAlive() == true) return@withLock
                _isStarting.value = true
                _shellExitCode.value = null

                try {
                    // Ensure runtime backend and session are initialized and shared via SessionManager
                    val activeSession = sessionManager.getSession(env.id)
                    if (activeSession == null || !activeSession.state.isActive()) {
                        log.info("[RUNTIME] Start requested: env=${env.id} startMode=CLI")
                        terminalBuffer.append("Starting Linux runtime (CLI mode)…\r\n".toByteArray(), "Starting Linux runtime (CLI mode)…\r\n".length)
                        val readyEnv = when (env.state) {
                            EnvironmentState.FAILED -> {
                                dao.updateState(
                                    id = env.id.value,
                                    state = EnvironmentState.RECOVERING.name,
                                    timestamp = System.currentTimeMillis(),
                                    failureMessage = null,
                                )
                                dao.updateState(
                                    id = env.id.value,
                                    state = EnvironmentState.READY.name,
                                    timestamp = System.currentTimeMillis(),
                                    failureMessage = null,
                                )
                                env.withState(EnvironmentState.RECOVERING).withState(EnvironmentState.READY)
                            }
                            EnvironmentState.STARTING -> env.withState(EnvironmentState.READY)
                            else -> env
                        }
                        sessionManager.startSession(readyEnv, com.linuxdroid.core.model.StartMode.CLI)
                        dao.updateState(
                            id = env.id.value,
                            state = EnvironmentState.RUNNING.name,
                            timestamp = System.currentTimeMillis(),
                            failureMessage = null,
                        )
                        LinuxSessionService.start(context, env.name)
                        log.info("[RUNTIME] CLI_READY")
                    } else {
                        log.info("Linux environment already has active session ${activeSession.id} (mode=${activeSession.startMode}) for ${env.id}; sharing session")
                    }

                    // Close existing session if any
                    closeSession()

                    val targetShell = env.configuration.shell.ifBlank { "/bin/bash" }
                    val shellCommand = if (!initialCmd.isNullOrBlank()) {
                        val decodedCmd = runCatching { java.net.URLDecoder.decode(initialCmd, "UTF-8") }.getOrDefault(initialCmd)
                        listOf(targetShell, "-l", "-c", "$decodedCmd && exit 0 || exec $targetShell -l")
                    } else {
                        listOf(targetShell, "-l")
                    }

                    val session = runtimeBackend.startInteractiveShell(
                        environment = env,
                        rows = currentRows,
                        cols = currentCols,
                        command = shellCommand
                    )
                    ptySession = session
                    terminalBuffer.resize(currentRows, currentCols)
                    _isShellActive.value = true
                    _isStarting.value = false

                    if (isAntigravitySession) {
                        antigravityManager.setRemoteControlState(isRunning = true, isReady = false)
                    }

                    val procHandle = ProcessHandle(
                        handleId = session.sessionId,
                        environmentId = env.id,
                        pid = session.pid,
                        command = shellCommand,
                        processRole = "terminal",
                        state = ProcessState.RUNNING,
                        startedAt = System.currentTimeMillis()
                    )
                    (processManager as? DefaultProcessManager)?.registerProcess(procHandle)

                    log.info("Interactive shell session connected for ${env.id} (pid=${session.pid})")

                    // Start continuous IO reader job
                    readJob = launch(Dispatchers.IO) {
                        val buffer = ByteArray(4096)
                        while (isActive) {
                            val bytesRead = session.read(buffer)
                            if (bytesRead > 0) {
                                terminalBuffer.append(buffer, bytesRead)
                                if (LogConfig.generate_log) {
                                    LogFileManager.appendTerminalBytes(env.id, buffer, 0, bytesRead)
                                }
                                val textChunk = String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8)
                                if (isAntigravitySession && antigravityAutomation != null) {
                                    processAntigravityOutput(textChunk)
                                } else {
                                    processOutputForUrls(textChunk)
                                }
                            } else if (bytesRead < 0) {
                                break
                            }
                        }

                        _isShellActive.value = false
                        if (isAntigravitySession) {
                            antigravityManager.setRemoteControlState(isRunning = false, isReady = false)
                            _isRemoteControlReady.value = false
                        }
                        val exitCode = session.getExitCode() ?: 0
                        _shellExitCode.value = exitCode
                        val exitMsg = "\r\n[Process completed (exit=$exitCode)]\r\n"
                        terminalBuffer.append(exitMsg.toByteArray(), exitMsg.length)
                        if (LogConfig.generate_log) {
                            LogFileManager.appendTerminalText(env.id, exitMsg)
                        }
                        log.info("Shell process exited with code $exitCode")

                        (processManager as? DefaultProcessManager)?.updateProcess(
                            procHandle.copy(
                                state = ProcessState.EXITED,
                                exitCode = exitCode,
                                exitedAt = System.currentTimeMillis()
                            )
                        )

                        // Check if this was an in-guest GUI installation session
                        val isGuiInstall = !initialCmd.isNullOrBlank() && initialCmd.contains("install-gui.sh")
                        if (isGuiInstall) {
                            val rootfsDir = storage.rootfsDir(env.id)
                            val marker = java.io.File(rootfsDir, "etc/linuxdroid/GUI_INSTALL_COMPLETE")
                            if (exitCode == 0 && marker.exists()) {
                                val stateFile = storage.guiStateFile(env.id)
                                storage.writeAtomic(stateFile, "INSTALLED\n")
                                log.info("In-guest GUI installation succeeded for ${env.id}, GUI_INSTALL_COMPLETE marker verified")
                                _guiInstallCompleted.tryEmit(true)
                            } else {
                                log.warn("In-guest GUI installation finished with exitCode=$exitCode (marker exists: ${marker.exists()})")
                                _guiInstallCompleted.tryEmit(false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to spawn interactive shell", e)
                    _isStarting.value = false
                    _isShellActive.value = false
                    if (isAntigravitySession) {
                        antigravityManager.setRemoteControlState(isRunning = false, isReady = false)
                    }
                    val errorMsg = "\r\n[Error launching shell: ${e.message}]\r\n"
                    terminalBuffer.append(errorMsg.toByteArray(), errorMsg.length)
                    if (LogConfig.generate_log) {
                        LogFileManager.appendTerminalText(env.id, errorMsg)
                    }
                }
            }
        }
    }

    /**
     * Sends keyboard input string directly to the active shell stdin in strictly ordered sequence.
     */
    fun sendInput(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        inputChannel.trySend(bytes)
    }

    /**
     * Sends raw bytes to the active shell stdin in strictly ordered sequence.
     */
    fun sendBytes(bytes: ByteArray) {
        inputChannel.trySend(bytes)
    }

    /**
     * Pastes raw text into the active shell, normalizing carriage returns without distorting letters or spaces.
     */
    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\r").replace("\n", "\r")
        sendInput(normalized)
    }

    /**
     * Sends predefined shortcut command to the current shell session.
     */
    fun runCommand(cmd: String) {
        sendInput("$cmd\n")
    }

    val inputController: com.linuxdroid.core.runtime.terminal.TerminalInputController
        get() = terminalBuffer.inputController

    // ─── Control key helpers ────────────────────────────────────────────────────────

    fun sendCtrlC() = inputController.sendCtrlC()
    fun sendCtrlD() = inputController.sendCtrlD()
    fun sendCtrlL() = inputController.sendCtrlL()
    fun sendCtrlZ() = inputController.sendCtrlZ()
    fun sendCtrlA() = inputController.sendCtrlA()
    fun sendCtrlE() = inputController.sendCtrlE()
    fun sendCtrlU() = inputController.sendCtrlU()
    fun sendCtrlW() = inputController.sendCtrlW()

    fun sendTab(shift: Boolean = false) = inputController.sendTab(shift)
    fun sendEscape() = inputController.sendEscape()
    fun sendBackspace() = inputController.sendBackspace()
    fun sendEnter() = inputController.sendEnter()

    fun sendArrowUp(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendArrowUp(shift = shift, alt = alt, ctrl = ctrl)

    fun sendArrowDown(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendArrowDown(shift = shift, alt = alt, ctrl = ctrl)

    fun sendArrowRight(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendArrowRight(shift = shift, alt = alt, ctrl = ctrl)

    fun sendArrowLeft(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendArrowLeft(shift = shift, alt = alt, ctrl = ctrl)

    fun sendHome(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendHome(shift = shift, alt = alt, ctrl = ctrl)

    fun sendEnd(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendEnd(shift = shift, alt = alt, ctrl = ctrl)

    fun sendPageUp(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendPageUp(shift = shift, alt = alt, ctrl = ctrl)

    fun sendPageDown(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendPageDown(shift = shift, alt = alt, ctrl = ctrl)

    fun sendInsert(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendInsert(shift = shift, alt = alt, ctrl = ctrl)

    fun sendDelete(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendDelete(shift = shift, alt = alt, ctrl = ctrl)

    fun sendFunctionKey(num: Int, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendFunctionKey(num, shift = shift, alt = alt, ctrl = ctrl)

    fun sendKey(char: Char, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        inputController.sendKey(char, shift = shift, alt = alt, ctrl = ctrl)

    /**
     * Extracts selected text from the terminal buffer adhering to soft-wrap boundaries.
     */
    fun extractSelection(selection: com.linuxdroid.core.runtime.terminal.TerminalSelection): String =
        terminalBuffer.extractSelection(selection)

    private var resizeJob: Job? = null

    /**
     * Resizes the terminal PTY window with settling debounce to prevent flooding during keyboard/window animations.
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        if (rows == currentRows && cols == currentCols) return
        currentRows = rows
        currentCols = cols
        terminalBuffer.resize(rows, cols)

        resizeJob?.cancel()
        resizeJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(180) // Settle window/keyboard animation before sending TIOCSWINSZ
            ptySession?.resize(rows, cols)
        }
    }

    /**
     * Restarts the interactive shell session.
     */
    fun restartShell() {
        val env = environment.value ?: return
        startInteractiveShellSession(env)
    }

    fun clear() {
        terminalBuffer.clear()
        sendCtrlL()
    }

    private fun processAntigravityOutput(chunk: String) {
        val automation = antigravityAutomation ?: return
        val actions = automation.processChunk(chunk)
        for (action in actions) {
            when (action) {
                is AntigravityAutomationAction.SendInput -> {
                    log.info("Antigravity automation sending input: ${action.text.replace("\n", "\\n")}")
                    sendInput(action.text)
                }
                is AntigravityAutomationAction.OpenAuthBrowser -> {
                    log.info("Antigravity automation detected Google OAuth URL: ${action.url}")
                    _authUrl.value = action.url
                    antigravityManager.updateAuthState(
                        AntigravityAuthState.WAITING_FOR_BROWSER_AUTH,
                        authUrl = action.url,
                        statusMessage = "Opening Google Sign-In in Chrome…"
                    )
                    openBrowser(action.url)
                }
                is AntigravityAutomationAction.NavigateWebView -> {
                    log.info("Antigravity automation detected Remote Control URL: ${action.url}")
                    _remoteControlUrl.value = action.url
                    _isRemoteControlReady.value = true
                    antigravityManager.updateAuthState(
                        AntigravityAuthState.CONNECTED,
                        remoteControlUrl = action.url,
                        statusMessage = "Remote Control active: ${action.url}"
                    )
                    _navigateToWebView.tryEmit(action.url)
                }
                is AntigravityAutomationAction.StateChanged -> {
                    log.info("Antigravity automation state changed: ${action.newState} - ${action.message}")
                    antigravityManager.updateAuthState(
                        action.newState,
                        statusMessage = action.message
                    )
                }
            }
        }
        processOutputForUrls(chunk)
    }

    /**
     * Submits authorization code to the waiting CLI session.
     */
    fun submitAntigravityAuthCode(code: String) {
        val result = antigravityManager.submitAuthCode(code)
        if (result is com.linuxdroid.linux.bootstrap.AntigravityManager.AuthCodeSubmitResult.Success ||
            result is com.linuxdroid.linux.bootstrap.AntigravityManager.AuthCodeSubmitResult.IgnoredDuplicate
        ) {
            return
        }
        val automation = antigravityAutomation ?: return
        val actions = automation.submitAuthCode(code)
        for (action in actions) {
            when (action) {
                is AntigravityAutomationAction.SendInput -> {
                    log.info("Submitting auth code to CLI stdin")
                    sendInput(action.text)
                }
                is AntigravityAutomationAction.StateChanged -> {
                    antigravityManager.updateAuthState(action.newState, statusMessage = action.message)
                }
                else -> {}
            }
        }
    }

    /**
     * Opens or re-opens the Google OAuth authorization URL in Chrome or default browser.
     */
    fun openGoogleAuthBrowser() {
        val url = _authUrl.value ?: antigravityAutomation?.authUrl ?: return
        openBrowser(url)
    }

    /**
     * Launches Android Chrome using browser Intent with fallback to default browser.
     */
    fun openBrowser(urlStr: String) {
        try {
            val uri = android.net.Uri.parse(urlStr)
            val pm = context.packageManager
            val chromeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (chromeIntent.resolveActivity(pm) != null) {
                try {
                    context.startActivity(chromeIntent)
                    return
                } catch (e: Exception) {
                    log.warn("Failed to launch Chrome explicitly, falling back", e)
                }
            }
            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            log.error("Failed to open browser intent for $urlStr", e)
        }
    }

    private fun processOutputForUrls(chunk: String) {
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val matches = urlRegex.findAll(chunk).map { it.value.trimEnd('.', ',', ';', ')') }.toList()
        if (matches.isNotEmpty()) {
            val current = _detectedUrls.value.toMutableList()
            var changed = false
            for (m in matches) {
                if (!current.contains(m)) {
                    current.add(0, m)
                    changed = true
                }
                if (m.contains("antigravity.google") || m.contains("remote-control") || (isAntigravitySession && m.startsWith("http"))) {
                    _remoteControlUrl.value = m
                    _isRemoteControlReady.value = true
                    antigravityManager.setRemoteControlState(isRunning = true, remoteControlUrl = m, isReady = true)
                }
            }
            if (changed) {
                _detectedUrls.value = current.take(10)
            }
        }
    }

    private fun closeSession() {
        readJob?.cancel()
        readJob = null
        ptySession?.close()
        ptySession = null
        _isShellActive.value = false
        if (isAntigravitySession) {
            antigravityManager.setRemoteControlState(isRunning = false, isReady = false)
            _isRemoteControlReady.value = false
            antigravityManager.updateAuthState(
                AntigravityAuthState.IDLE,
                statusMessage = "Antigravity CLI exited"
            )
        }
    }

    /**
     * Extracts the full terminal scrollback buffer as plain text.
     */
    fun getTerminalPlainText(): String = terminalBuffer.getPlainText()

    /**
     * Exports and shares runtime failure reports or diagnostic logs for this environment.
     */
    fun exportLogs(
        context: Context,
        exportType: com.linuxdroid.core.model.LogExportType = com.linuxdroid.core.model.LogExportType.TERMINAL_FAILURE_LOG,
        asJson: Boolean = false,
    ) {
        val env = environment.value ?: return
        val textBuffer = terminalBuffer.getPlainText()
        val exitCode = _shellExitCode.value
        val isAlive = ptySession?.isAlive() == true
        viewModelScope.launch(Dispatchers.IO) {
            val shareIntent = logExporter.createShareIntent(
                context = context,
                environment = env,
                exportType = exportType,
                asJson = asJson,
                terminalOutput = textBuffer,
                exitCode = exitCode,
                isPtyActive = isAlive,
            )
            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }
}
