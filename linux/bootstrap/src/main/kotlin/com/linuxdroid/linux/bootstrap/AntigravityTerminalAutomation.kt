package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.AntigravityAuthState
import java.net.URI

/**
 * Output actions emitted by AntigravityTerminalAutomation.
 */
sealed class AntigravityAutomationAction {
    data class SendInput(
        val text: String,
        val description: String = "Input",
        val rawBytes: ByteArray = text.toByteArray(Charsets.UTF_8)
    ) : AntigravityAutomationAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendInput) return false
            return text == other.text
        }

        override fun hashCode(): Int {
            return text.hashCode()
        }
    }

    data class OpenAuthBrowser(val url: String) : AntigravityAutomationAction()
    data class NavigateWebView(val url: String) : AntigravityAutomationAction()
    data class StateChanged(val newState: AntigravityAuthState, val message: String?) : AntigravityAutomationAction()
}

/**
 * Interactive menu options presented by Antigravity CLI.
 */
enum class LoginMenuOption {
    GOOGLE_OAUTH,
    GOOGLE_CLOUD_PROJECT,
    UNKNOWN
}

/**
 * Internal states for the login menu interaction lifecycle.
 */
enum class MenuInteractionState {
    NOT_SEEN,
    MENU_DETECTED,
    NAVIGATING_UP,
    SELECTION_SUBMITTED,
    TRANSITIONED,
    SUBMISSION_FAILED
}

/**
 * Diagnostic snapshot of login menu detection, navigation, and transition.
 * Never stores or logs authorization codes, tokens, or credentials.
 */
data class AntigravityMenuDiagnostics(
    val timestamp: Long = System.currentTimeMillis(),
    val menuState: String,
    val highlightedOption: LoginMenuOption,
    val keyAction: String? = null,
    val ptyInputBytesHex: String? = null,
    val menuTransitionConfirmed: Boolean = false,
    val detailMessage: String = "",
)

/**
 * Robust terminal stream parser and state machine orchestrator for official Google Antigravity CLI.
 *
 * Responsibilities:
 * - Strips ANSI escape sequences and terminal formatting.
 * - Concatenates lines across terminal width wraps (e.g. 80-column line breaks).
 * - Detects the interactive unauthenticated Google OAuth selection menu with strict option verification.
 * - Detects which option is highlighted and navigates/selects using standard PTY terminal control keys.
 * - Idempotently submits Enter and confirms transition without duplicate or stuck submissions.
 * - Extracts and validates official Google OAuth authorization URLs.
 * - Detects authorization code submission prompts and CLI authentication success.
 * - Extracts and strictly validates official Antigravity Remote Control URLs.
 * - Exposes safe, opt-in diagnostics without logging sensitive authentication data.
 */
class AntigravityTerminalAutomation {

    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)

    companion object {
        // Standard ASCII / VT terminal key encodings
        const val KEY_ENTER = "\r"
        const val KEY_NEWLINE = "\n"
        const val KEY_UP_ARROW = "\u001B[A"

        // Maximum chunks allowed without progress after sending Enter before flagging failure
        const val MAX_STUCK_MENU_CHUNKS = 5

        // ANSI escape sequence pattern matching standard terminal control codes
        private val ANSI_REGEX = Regex("""\u001B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")

        // Google OAuth URL validation pattern
        private val GOOGLE_AUTH_HOST_REGEX = Regex("""^accounts\.google\.com$""", RegexOption.IGNORE_CASE)

        // Antigravity Remote Control host validation pattern
        private val REMOTE_CONTROL_HOST_REGEX = Regex("""^(?:[a-zA-Z0-9-]+\.)?antigravity\.google(?:\.com)?$""", RegexOption.IGNORE_CASE)

        // CLI authorization code input prompt pattern
        // Specifically recognizes:
        // "If you aren't automatically redirected, paste the authorization code below:"
        // as well as variants across CLI versions
        private val AUTH_CODE_PROMPT_REGEX = Regex(
            """(?i)(?:""" +
            """if\s+you\s+aren['’]?t\s+automatically\s+redirected,\s*(?:please\s+)?paste\s+the\s+authorization\s+code\s+below:?|""" +
            """paste\s+the\s+authorization\s+code\s+below:?|""" +
            """enter\s+(?:the\s+)?(?:authorization|auth|verification)\s+code|""" +
            """paste\s+(?:the\s+)?(?:authorization|auth|verification)\s+code|""" +
            """input\s+(?:the\s+)?(?:authorization|auth|verification)\s+code|""" +
            """provide\s+(?:the\s+)?(?:authorization|auth|verification)\s+code|""" +
            """(?:enter|paste|input)\s+code\s*[:\?]|""" +
            """(?:authorization|auth|verification)\s+code\s*[:\?]""" +
            """)"""
        )
    }

    private val scrollbackBuffer = StringBuilder(32 * 1024)
    private var currentState: AntigravityAuthState = AntigravityAuthState.IDLE
    var menuInteractionState: MenuInteractionState = MenuInteractionState.NOT_SEEN
        private set

    var isWaitingForAuthCode: Boolean = false
        private set

    private var chunksSinceSubmission = 0
    private var hasOpenedAuthBrowser = false
    private var hasTriggeredWebView = false

    var authUrl: String? = null
        private set
    var remoteControlUrl: String? = null
        private set

    // Diagnostics buffer
    var diagnosticsEnabled: Boolean = true
    private val _diagnostics = mutableListOf<AntigravityMenuDiagnostics>()
    val diagnostics: List<AntigravityMenuDiagnostics>
        get() = synchronized(_diagnostics) { _diagnostics.toList() }

    /**
     * Resets the automation state for a new CLI session.
     */
    fun reset() {
        scrollbackBuffer.setLength(0)
        currentState = AntigravityAuthState.STARTING
        menuInteractionState = MenuInteractionState.NOT_SEEN
        chunksSinceSubmission = 0
        hasOpenedAuthBrowser = false
        hasTriggeredWebView = false
        isWaitingForAuthCode = false
        authUrl = null
        remoteControlUrl = null
        synchronized(_diagnostics) {
            _diagnostics.clear()
        }
    }

    /**
     * Records a diagnostic event if diagnostics are enabled.
     * Guaranteed never to log or store sensitive authentication data.
     */
    fun recordDiagnostics(
        menuState: String,
        highlightedOption: LoginMenuOption,
        keyAction: String? = null,
        ptyInputBytesHex: String? = null,
        menuTransitionConfirmed: Boolean = false,
        detailMessage: String = "",
    ) {
        val record = AntigravityMenuDiagnostics(
            menuState = menuState,
            highlightedOption = highlightedOption,
            keyAction = keyAction,
            ptyInputBytesHex = ptyInputBytesHex,
            menuTransitionConfirmed = menuTransitionConfirmed,
            detailMessage = detailMessage
        )
        synchronized(_diagnostics) {
            _diagnostics.add(record)
            if (_diagnostics.size > 50) _diagnostics.removeAt(0)
        }
        if (diagnosticsEnabled) {
            log.info("[AntigravityMenuDiagnostics] state=$menuState, highlighted=$highlightedOption, keyAction=$keyAction, inputBytes=$ptyInputBytesHex, transitioned=$menuTransitionConfirmed, detail=$detailMessage")
        }
    }

    /**
     * Strips ANSI escape sequences, control bytes, and carriage returns from raw terminal output.
     */
    fun stripAnsi(raw: String): String {
        return raw.replace(ANSI_REGEX, "").replace("\r", "")
    }

    /**
     * Normalizes line-wrapped terminal text, reconstructing URLs split across 80-column boundaries.
     */
    fun normalizeTerminalText(raw: String): String {
        val clean = stripAnsi(raw)
        val lines = clean.lines()
        val result = StringBuilder()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()
            if (trimmed.contains(Regex("""https?://\S+$""")) && i + 1 < lines.size) {
                val nextTrimmed = lines[i + 1].trimStart()
                if (nextTrimmed.isNotEmpty() && !nextTrimmed.startsWith("http://") && !nextTrimmed.startsWith("https://")) {
                    val nextToken = nextTrimmed.split(Regex("""\s+""")).firstOrNull() ?: ""
                    result.append(trimmed).append(nextToken).append("\n")
                    val remainder = nextTrimmed.removePrefix(nextToken).trimStart()
                    if (remainder.isNotEmpty()) {
                        result.append(remainder).append("\n")
                    }
                    i += 2
                    continue
                }
            }
            result.append(line).append("\n")
            i++
        }
        return result.toString()
    }

    /**
     * Positively identifies whether the unauthenticated interactive CLI menu is displayed.
     * Confirms that both Google OAuth and Google Cloud project options are present.
     */
    fun isGoogleOAuthMenuPresent(text: String): Boolean {
        val clean = stripAnsi(text)

        // Must confirm BOTH Google OAuth and Google Cloud project options are present
        val hasGoogleOAuth = clean.contains("Google OAuth", ignoreCase = true)
        val hasGoogleCloud = clean.contains("Google Cloud project", ignoreCase = true) ||
                clean.contains("Google Cloud", ignoreCase = true) ||
                clean.contains("GCP", ignoreCase = true)

        if (!hasGoogleOAuth || !hasGoogleCloud) {
            return false
        }

        // Must confirm menu context (welcome / not signed in / login method / navigate)
        val hasMenuContext = clean.contains("Welcome to the Antigravity CLI", ignoreCase = true) ||
                clean.contains("not signed in", ignoreCase = true) ||
                clean.contains("Select login method", ignoreCase = true) ||
                clean.contains("login method", ignoreCase = true) ||
                (clean.contains("Navigate", ignoreCase = true) && clean.contains("Select", ignoreCase = true))

        return hasMenuContext
    }

    /**
     * Detects which menu option is currently highlighted using terminal selection markers,
     * guillemets («...»), cursor indicators (> / ❯ / » / * / ●), or ANSI reverse-video styling.
     * Evaluates lines in reverse to inspect the most recently rendered screen state.
     */
    fun detectHighlightedOption(rawText: String): LoginMenuOption {
        // 1. Check raw lines in reverse (most recent screen redraw first) for ANSI reverse video (7m)
        val rawLines = rawText.lines().reversed()
        for (line in rawLines) {
            val hasReverseVideo = line.contains("\u001B[7m") || line.contains(Regex("""\u001B\[[0-9;]*7m"""))
            if (hasReverseVideo) {
                val cleanLine = stripAnsi(line)
                if (cleanLine.contains("Google OAuth", ignoreCase = true)) {
                    return LoginMenuOption.GOOGLE_OAUTH
                }
                if (cleanLine.contains("Google Cloud", ignoreCase = true) || cleanLine.contains("GCP", ignoreCase = true)) {
                    return LoginMenuOption.GOOGLE_CLOUD_PROJECT
                }
            }
        }

        // 2. Check clean lines in reverse for explicit selection markers
        val clean = stripAnsi(rawText)
        val cleanLines = clean.lines().reversed()

        for (line in cleanLines) {
            val trimmed = line.trim()
            val isOauth = trimmed.contains("Google OAuth", ignoreCase = true)
            val isGcp = trimmed.contains("Google Cloud", ignoreCase = true) || trimmed.contains("GCP", ignoreCase = true)

            if (!isOauth && !isGcp) continue

            // Check for guillemets «...» or brackets [...]
            val hasGuillemets = trimmed.contains("«") || trimmed.contains("»")
            val hasBrackets = (trimmed.startsWith("[") && trimmed.contains("]")) ||
                    (trimmed.startsWith("(") && trimmed.contains(")"))

            // Check for cursor markers: >, ❯, », →, *, ●
            val hasCursorMarker = trimmed.startsWith(">") ||
                    trimmed.startsWith("❯") ||
                    trimmed.startsWith("»") ||
                    trimmed.startsWith("→") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("●") ||
                    trimmed.matches(Regex("""^(?:>|❯|»|→|\*|●)\s*(?:[12][\.\)])?.*"""))

            if (hasGuillemets || hasBrackets || hasCursorMarker) {
                if (isOauth) return LoginMenuOption.GOOGLE_OAUTH
                if (isGcp) return LoginMenuOption.GOOGLE_CLOUD_PROJECT
            }
        }

        // 3. Regex checks for latest occurrence of highlighted option pattern
        val oauthRegex = Regex("""(?:«[^»\n]*Google\s*OAuth[^»\n]*»|(?:^|\n)\s*(?:>|❯|»|→|\*|●)\s*(?:1[\.\)])?\s*Google\s*OAuth)""", RegexOption.IGNORE_CASE)
        val gcpRegex = Regex("""(?:«[^»\n]*(?:Google\s*Cloud|GCP)[^»\n]*»|(?:^|\n)\s*(?:>|❯|»|→|\*|●)\s*(?:2[\.\)])?\s*(?:Use a\s*)?(?:Google\s*Cloud|GCP))""", RegexOption.IGNORE_CASE)

        val oauthLast = oauthRegex.findAll(clean).lastOrNull()?.range?.first ?: -1
        val gcpLast = gcpRegex.findAll(clean).lastOrNull()?.range?.first ?: -1

        if (oauthLast != -1 || gcpLast != -1) {
            return if (oauthLast > gcpLast) LoginMenuOption.GOOGLE_OAUTH else LoginMenuOption.GOOGLE_CLOUD_PROJECT
        }

        // 4. Default: If menu is present, option 1 (Google OAuth) is the default initial selection
        return if (isGoogleOAuthMenuPresent(rawText)) {
            LoginMenuOption.GOOGLE_OAUTH
        } else {
            LoginMenuOption.UNKNOWN
        }
    }

    /**
     * Checks if there is evidence that the CLI has advanced beyond the login menu.
     */
    fun hasEvidenceOfTransitionBeyondMenu(text: String): Boolean {
        val clean = stripAnsi(text)
        return extractGoogleAuthUrl(text) != null ||
                isWaitingForAuthCodePrompt(text) ||
                isAuthenticationSuccessPresent(text) ||
                extractRemoteControlUrl(text) != null ||
                clean.contains("Please authenticate", ignoreCase = true) ||
                clean.contains("Please open the following URL", ignoreCase = true) ||
                clean.contains("accounts.google.com", ignoreCase = true)
    }

    /**
     * Extracts and validates the Google OAuth authorization URL from terminal text.
     */
    fun extractGoogleAuthUrl(text: String): String? {
        val normalized = normalizeTerminalText(text)
        val urlRegex = Regex("""https://accounts\.google\.com/o/oauth2/[^\s"'<>]+""")
        val matches = urlRegex.findAll(normalized)

        for (match in matches) {
            val candidate = match.value.trimEnd('.', ',', ';', ')', ']')
            if (isValidGoogleAuthUrl(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Validates that the URL uses HTTPS and belongs to the official accounts.google.com OAuth endpoint.
     */
    fun isValidGoogleAuthUrl(urlStr: String): Boolean {
        return try {
            val uri = URI(urlStr)
            uri.scheme?.equals("https", ignoreCase = true) == true &&
                    uri.host != null && GOOGLE_AUTH_HOST_REGEX.matches(uri.host) &&
                    uri.path?.contains("/oauth2/") == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the CLI is prompting the user to enter or paste the authorization code.
     * Robustly handles ANSI formatting, line wraps, partial chunks, and distinguishes from login menus.
     */
    fun isWaitingForAuthCodePrompt(text: String): Boolean {
        val clean = stripAnsi(text)

        // Scan lines in reverse order (most recent screen redraw first)
        val lines = clean.lines()
        for (line in lines.reversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Exclude login menu lines
            if (trimmed.contains("enter Select", ignoreCase = true) ||
                trimmed.contains("Select login method", ignoreCase = true) ||
                trimmed.contains("Google Cloud project", ignoreCase = true) ||
                trimmed.contains("↑/↓ Navigate", ignoreCase = true)
            ) {
                continue
            }

            if (AUTH_CODE_PROMPT_REGEX.containsMatchIn(trimmed)) {
                return true
            }
        }

        // Also check normalized continuous text
        val normalized = normalizeTerminalText(clean)
        return AUTH_CODE_PROMPT_REGEX.containsMatchIn(normalized)
    }

    /**
     * Checks if CLI reports an authentication error or invalid code.
     */
    fun isAuthenticationFailurePresent(text: String): Boolean {
        val clean = stripAnsi(text)
        return clean.contains("Invalid authorization code", ignoreCase = true) ||
                clean.contains("Invalid verification code", ignoreCase = true) ||
                clean.contains("Authentication failed", ignoreCase = true) ||
                clean.contains("Failed to authenticate", ignoreCase = true) ||
                clean.contains("Invalid grant", ignoreCase = true) ||
                clean.contains("Authorization failed", ignoreCase = true) ||
                clean.contains("Token exchange failed", ignoreCase = true) ||
                clean.contains("OAuth error", ignoreCase = true)
    }

    /**
     * Checks if CLI reports successful sign-in / authentication.
     */
    fun isAuthenticationSuccessPresent(text: String): Boolean {
        val clean = stripAnsi(text)
        return clean.contains("Signed in as", ignoreCase = true) ||
                clean.contains("Authentication successful", ignoreCase = true) ||
                clean.contains("Logged in as", ignoreCase = true) ||
                clean.contains("Authenticated successfully", ignoreCase = true)
    }

    /**
     * Checks if Remote Control is reported as active.
     */
    fun isRemoteControlReadyMessagePresent(text: String): Boolean {
        val clean = stripAnsi(text)
        return clean.contains("Remote control on for this session", ignoreCase = true) ||
                clean.contains("Remote control active", ignoreCase = true) ||
                clean.contains("Remote control ready", ignoreCase = true) ||
                clean.contains("take over", ignoreCase = true)
    }

    /**
     * Extracts and validates the official Antigravity Remote Control URL from terminal output.
     * Supports:
     * - https://antigravity.google.com/r/<session-id>
     * - https://antigravity.google/join/<session-id>
     * - https://antigravity.google/remote-control?session=<session-id>
     */
    fun extractRemoteControlUrl(text: String): String? {
        val normalized = normalizeTerminalText(text)
        val regex = Regex("""https://(?:[a-zA-Z0-9-]+\.)?antigravity\.google(?:\.com)?/(?:r/|join/|remote-control|session/)[^\s"'<>]+""")
        val matches = regex.findAll(normalized)

        for (match in matches) {
            val candidate = match.value.trimEnd('.', ',', ';', ')', ']')
            if (isValidRemoteControlUrl(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Validates that the URL uses HTTPS and points to an official Antigravity Remote Control host.
     */
    fun isValidRemoteControlUrl(urlStr: String): Boolean {
        return try {
            val uri = URI(urlStr)
            uri.scheme?.equals("https", ignoreCase = true) == true &&
                    uri.host != null && REMOTE_CONTROL_HOST_REGEX.matches(uri.host) &&
                    (uri.path?.startsWith("/r") == true ||
                     uri.path?.startsWith("/join") == true ||
                     uri.path?.startsWith("/remote-control") == true ||
                     uri.path?.startsWith("/session") == true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts the state parameter from an OAuth authorization URL if present.
     */
    fun extractOAuthStateParameter(urlStr: String): String? {
        return try {
            val uri = URI(urlStr)
            val query = uri.rawQuery ?: return null
            for (param in query.split("&")) {
                val pair = param.split("=")
                if (pair.size == 2 && pair[0].equals("state", ignoreCase = true)) {
                    return java.net.URLDecoder.decode(pair[1], "UTF-8")
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the redirect_uri parameter from an OAuth authorization URL if present.
     */
    fun extractOAuthRedirectUri(urlStr: String): String? {
        return try {
            val uri = URI(urlStr)
            val query = uri.rawQuery ?: return null
            for (param in query.split("&")) {
                val pair = param.split("=")
                if (pair.size == 2 && pair[0].equals("redirect_uri", ignoreCase = true)) {
                    return java.net.URLDecoder.decode(pair[1], "UTF-8")
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ingests a new chunk of terminal output from PTY and determines if any automation
     * actions or state transitions must occur according to the 14-state machine.
     */
    fun processChunk(chunk: String): List<AntigravityAutomationAction> {
        val actions = mutableListOf<AntigravityAutomationAction>()
        scrollbackBuffer.append(chunk)
        val fullBuffer = scrollbackBuffer.toString()

        if (currentState == AntigravityAuthState.STARTING) {
            currentState = AntigravityAuthState.DETECTING_LOGIN
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Detecting login method…"))
            recordDiagnostics(
                menuState = "DETECTING_LOGIN",
                highlightedOption = LoginMenuOption.UNKNOWN,
                detailMessage = "Started monitoring terminal output for login menu"
            )
        }

        // 1. Check for Remote Control URL (terminal might already be authenticated)
        val extractedRemote = extractRemoteControlUrl(fullBuffer)
        if (extractedRemote != null && !hasTriggeredWebView) {
            remoteControlUrl = extractedRemote
            hasTriggeredWebView = true
            menuInteractionState = MenuInteractionState.TRANSITIONED
            currentState = AntigravityAuthState.CONNECTING_WEBVIEW
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Connecting to Antigravity Remote Control…"))
            actions.add(AntigravityAutomationAction.NavigateWebView(extractedRemote))
            currentState = AntigravityAuthState.CONNECTED
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Remote Control active: $extractedRemote"))
            recordDiagnostics(
                menuState = "CONNECTED",
                highlightedOption = LoginMenuOption.UNKNOWN,
                menuTransitionConfirmed = true,
                detailMessage = "Remote Control URL detected: $extractedRemote"
            )
            return actions
        }

        // 2. Check for Google OAuth Authorization URL (evidence of successful transition beyond menu)
        val extractedAuth = extractGoogleAuthUrl(fullBuffer)
        if (extractedAuth != null) {
            if (menuInteractionState == MenuInteractionState.SELECTION_SUBMITTED ||
                menuInteractionState == MenuInteractionState.NAVIGATING_UP
            ) {
                menuInteractionState = MenuInteractionState.TRANSITIONED
                recordDiagnostics(
                    menuState = "TRANSITIONED",
                    highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                    menuTransitionConfirmed = true,
                    detailMessage = "Menu transitioned successfully, OAuth URL extracted"
                )
            }
            if (!hasOpenedAuthBrowser) {
                authUrl = extractedAuth
                hasOpenedAuthBrowser = true
                currentState = AntigravityAuthState.WAITING_FOR_BROWSER_AUTH
                actions.add(AntigravityAutomationAction.StateChanged(currentState, "Google Sign-in opened in browser"))
                actions.add(AntigravityAutomationAction.OpenAuthBrowser(extractedAuth))
            }
        }

        // 3. Handle interactive login menu detection & selection
        if (isGoogleOAuthMenuPresent(fullBuffer)) {
            val highlighted = detectHighlightedOption(fullBuffer)

            when (menuInteractionState) {
                MenuInteractionState.NOT_SEEN -> {
                    menuInteractionState = MenuInteractionState.MENU_DETECTED
                    currentState = AntigravityAuthState.SELECTING_GOOGLE_OAUTH
                    actions.add(AntigravityAutomationAction.StateChanged(currentState, "Detected login menu. Selecting Google OAuth…"))

                    if (highlighted == LoginMenuOption.GOOGLE_OAUTH) {
                        // Google OAuth is already highlighted: send exactly one Enter key
                        val enterAction = AntigravityAutomationAction.SendInput(KEY_ENTER, "Enter key")
                        actions.add(enterAction)
                        menuInteractionState = MenuInteractionState.SELECTION_SUBMITTED
                        chunksSinceSubmission = 0
                        recordDiagnostics(
                            menuState = "SELECTION_SUBMITTED",
                            highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                            keyAction = "ENTER",
                            ptyInputBytesHex = "0D",
                            menuTransitionConfirmed = false,
                            detailMessage = "Google OAuth highlighted, sent Enter"
                        )
                        return actions
                    } else if (highlighted == LoginMenuOption.GOOGLE_CLOUD_PROJECT) {
                        // Google Cloud project is highlighted: send Up Arrow key
                        val upArrowAction = AntigravityAutomationAction.SendInput(KEY_UP_ARROW, "Up Arrow key")
                        actions.add(upArrowAction)
                        menuInteractionState = MenuInteractionState.NAVIGATING_UP
                        recordDiagnostics(
                            menuState = "NAVIGATING_UP",
                            highlightedOption = LoginMenuOption.GOOGLE_CLOUD_PROJECT,
                            keyAction = "UP_ARROW",
                            ptyInputBytesHex = "1B5B41",
                            menuTransitionConfirmed = false,
                            detailMessage = "Google Cloud project highlighted, sent Up Arrow to navigate to Google OAuth"
                        )
                        return actions
                    }
                }

                MenuInteractionState.NAVIGATING_UP -> {
                    // Waiting for Google OAuth to become highlighted after Up Arrow
                    if (highlighted == LoginMenuOption.GOOGLE_OAUTH) {
                        // Verified that Google OAuth is now highlighted: send Enter
                        val enterAction = AntigravityAutomationAction.SendInput(KEY_ENTER, "Enter key")
                        actions.add(enterAction)
                        menuInteractionState = MenuInteractionState.SELECTION_SUBMITTED
                        chunksSinceSubmission = 0
                        recordDiagnostics(
                            menuState = "SELECTION_SUBMITTED",
                            highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                            keyAction = "ENTER",
                            ptyInputBytesHex = "0D",
                            menuTransitionConfirmed = false,
                            detailMessage = "Google OAuth selection verified after Up Arrow, sent Enter"
                        )
                        return actions
                    }
                }

                MenuInteractionState.SELECTION_SUBMITTED -> {
                    // Enter was already sent! Idempotent: NEVER send Enter again.
                    // Check if original menu remains visible without progress
                    if (!hasEvidenceOfTransitionBeyondMenu(fullBuffer)) {
                        chunksSinceSubmission++
                        if (chunksSinceSubmission >= MAX_STUCK_MENU_CHUNKS) {
                            menuInteractionState = MenuInteractionState.SUBMISSION_FAILED
                            currentState = AntigravityAuthState.ERROR
                            val errorMsg = "Login menu selection failure: CLI did not advance beyond login menu after Enter."
                            actions.add(AntigravityAutomationAction.StateChanged(currentState, errorMsg))
                            recordDiagnostics(
                                menuState = "SUBMISSION_FAILED",
                                highlightedOption = highlighted,
                                keyAction = null,
                                ptyInputBytesHex = null,
                                menuTransitionConfirmed = false,
                                detailMessage = errorMsg
                            )
                            return actions
                        }
                    } else {
                        menuInteractionState = MenuInteractionState.TRANSITIONED
                        recordDiagnostics(
                            menuState = "TRANSITIONED",
                            highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                            menuTransitionConfirmed = true,
                            detailMessage = "Transition beyond menu confirmed"
                        )
                    }
                }

                MenuInteractionState.MENU_DETECTED,
                MenuInteractionState.TRANSITIONED,
                MenuInteractionState.SUBMISSION_FAILED -> {
                    // No further menu input sent
                }
            }
        }

        // 4. Check for Authorization Code prompt
        if (hasOpenedAuthBrowser || authUrl != null || menuInteractionState == MenuInteractionState.TRANSITIONED) {
            if (isWaitingForAuthCodePrompt(fullBuffer)) {
                if (currentState != AntigravityAuthState.SUBMITTING_AUTH_CODE &&
                    currentState != AntigravityAuthState.AUTHENTICATING &&
                    currentState != AntigravityAuthState.WAITING_FOR_REMOTE_URL &&
                    currentState != AntigravityAuthState.CONNECTING_WEBVIEW &&
                    currentState != AntigravityAuthState.CONNECTED
                ) {
                    isWaitingForAuthCode = true
                    if (currentState != AntigravityAuthState.WAITING_FOR_AUTH_CODE) {
                        currentState = AntigravityAuthState.WAITING_FOR_AUTH_CODE
                        actions.add(AntigravityAutomationAction.StateChanged(currentState, "Waiting for authorization code…"))
                        recordDiagnostics(
                            menuState = "WAITING_FOR_AUTH_CODE",
                            highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                            menuTransitionConfirmed = true,
                            detailMessage = "CLI authorization code prompt detected"
                        )
                    }
                } else if (currentState == AntigravityAuthState.AUTHENTICATING && isAuthenticationFailurePresent(fullBuffer)) {
                    // Recoverable failure: CLI rejected code and reprompted
                    isWaitingForAuthCode = true
                    currentState = AntigravityAuthState.WAITING_FOR_AUTH_CODE
                    actions.add(AntigravityAutomationAction.StateChanged(
                        currentState,
                        "Authentication failed. Please verify your authorization code and try again."
                    ))
                    recordDiagnostics(
                        menuState = "AUTH_FAILED_RETRY",
                        highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                        menuTransitionConfirmed = true,
                        detailMessage = "Authentication failed; CLI reprompted for authorization code"
                    )
                }
            }
        }

        // 5. Check for Auth Success
        if (isAuthenticationSuccessPresent(fullBuffer) && currentState.isInAuthFlow) {
            isWaitingForAuthCode = false
            currentState = AntigravityAuthState.WAITING_FOR_REMOTE_URL
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Authenticated. Initializing Remote Control…"))
        }

        return actions
    }

    /**
     * Submits authorization code to the waiting CLI session.
     * Never stores or logs the actual authorization code.
     * Sends the code followed by exactly one standard newline (\n).
     */
    fun submitAuthCode(code: String): List<AntigravityAutomationAction> {
        val actions = mutableListOf<AntigravityAutomationAction>()
        val cleanCode = code.trim().replace("\r", "").replace("\n", "")
        if (cleanCode.isNotEmpty()) {
            isWaitingForAuthCode = false
            currentState = AntigravityAuthState.SUBMITTING_AUTH_CODE
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Verifying authorization…"))
            actions.add(AntigravityAutomationAction.SendInput("$cleanCode$KEY_NEWLINE", "Authorization Code"))
            currentState = AntigravityAuthState.AUTHENTICATING
            actions.add(AntigravityAutomationAction.StateChanged(currentState, "Verifying authorization…"))
            recordDiagnostics(
                menuState = "AUTH_CODE_SUBMITTED",
                highlightedOption = LoginMenuOption.GOOGLE_OAUTH,
                keyAction = "SUBMIT_CODE",
                ptyInputBytesHex = null,
                menuTransitionConfirmed = true,
                detailMessage = "Submitted authorization code (${cleanCode.length} chars)"
            )
        }
        return actions
    }

    /**
     * Explicit user cancellation of authentication.
     */
    fun notifyCancelled(): List<AntigravityAutomationAction> {
        isWaitingForAuthCode = false
        currentState = AntigravityAuthState.CANCELLED
        recordDiagnostics(
            menuState = "CANCELLED",
            highlightedOption = LoginMenuOption.UNKNOWN,
            detailMessage = "User cancelled authentication"
        )
        return listOf(AntigravityAutomationAction.StateChanged(currentState, "Authentication cancelled"))
    }

    /**
     * Sets minimized state for floating mini-window presentation.
     */
    fun notifyMinimized(isMinimized: Boolean): List<AntigravityAutomationAction> {
        currentState = if (isMinimized) AntigravityAuthState.MINIMIZED else AntigravityAuthState.CONNECTED
        return listOf(AntigravityAutomationAction.StateChanged(currentState, if (isMinimized) "Minimized" else "Restored"))
    }

    /**
     * Explicit user cancellation or stopping notification.
     */
    fun notifyStopping(): List<AntigravityAutomationAction> {
        isWaitingForAuthCode = false
        currentState = AntigravityAuthState.STOPPING
        return listOf(AntigravityAutomationAction.StateChanged(currentState, "Stopping Antigravity session…"))
    }

    fun notifyIdle(): List<AntigravityAutomationAction> {
        isWaitingForAuthCode = false
        currentState = AntigravityAuthState.IDLE
        return listOf(AntigravityAutomationAction.StateChanged(currentState, "Antigravity idle"))
    }

    fun notifyError(errorMessage: String): List<AntigravityAutomationAction> {
        isWaitingForAuthCode = false
        currentState = AntigravityAuthState.ERROR
        return listOf(AntigravityAutomationAction.StateChanged(currentState, errorMessage))
    }
}
