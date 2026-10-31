package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.AntigravityAuthState
import org.junit.Before
import org.junit.Test

class AntigravityTerminalAutomationTest {

    private lateinit var automation: AntigravityTerminalAutomation

    @Before
    fun setUp() {
        automation = AntigravityTerminalAutomation()
        automation.reset()
    }

    @Test
    fun `stripAnsi strips color and cursor control escape codes without mangling text`() {
        val coloredOutput = "\u001B[1;32mWelcome to the Antigravity CLI.\u001B[0m\r\n\u001B[33mYou are currently not signed in.\u001B[0m"
        val clean = automation.stripAnsi(coloredOutput)
        assertThat(clean).isEqualTo("Welcome to the Antigravity CLI.\nYou are currently not signed in.")
    }

    @Test
    fun `isGoogleOAuthMenuPresent detects official unauthenticated menu accurately`() {
        val sampleMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            
            Select login method:
            
            «1. Google OAuth»
            
            2. Use a Google Cloud project
            
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        assertThat(automation.isGoogleOAuthMenuPresent(sampleMenu)).isTrue()
    }

    @Test
    fun `isGoogleOAuthMenuPresent rejects arbitrary shell output or commands`() {
        val shellOutput = """
            root@localhost:~# ls -la
            drwxr-xr-x 1 root root 4096 Oct 24 10:00 .
            -rw-r--r-- 1 root root  120 Oct 24 10:00 1.txt
            -rw-r--r-- 1 root root  240 Oct 24 10:00 2.txt
        """.trimIndent()

        assertThat(automation.isGoogleOAuthMenuPresent(shellOutput)).isFalse()
    }

    @Test
    fun `Google OAuth already highlighted sends exactly one Enter key`() {
        val sampleMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            
            Select login method:
            
            «1. Google OAuth»
            
            2. Use a Google Cloud project
            
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        val actions = automation.processChunk(sampleMenu)
        val sendInputs = actions.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(sendInputs).hasSize(1)
        assertThat(sendInputs[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_ENTER)
        assertThat(sendInputs[0].text).isNotEqualTo("1\n")
        assertThat(sendInputs[0].text).isNotEqualTo("1")
        assertThat(automation.menuInteractionState).isEqualTo(MenuInteractionState.SELECTION_SUBMITTED)

        // Check diagnostics recorded
        val diags = automation.diagnostics
        assertThat(diags).isNotEmpty()
        val submitDiag = diags.find { it.keyAction == "ENTER" }
        assertThat(submitDiag).isNotNull()
        assertThat(submitDiag?.highlightedOption).isEqualTo(LoginMenuOption.GOOGLE_OAUTH)
        assertThat(submitDiag?.ptyInputBytesHex).isEqualTo("0D")
    }

    @Test
    fun `Google Cloud project highlighted sends Up Arrow then verified selection sends Enter`() {
        val gcpMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            
            Select login method:
            
            1. Google OAuth
            
            «2. Use a Google Cloud project»
            
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        // Chunk 1: GCP is highlighted
        val actions1 = automation.processChunk(gcpMenu)
        val inputs1 = actions1.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(inputs1).hasSize(1)
        assertThat(inputs1[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_UP_ARROW)
        assertThat(inputs1[0].text).isNotEqualTo("1\n")
        assertThat(automation.menuInteractionState).isEqualTo(MenuInteractionState.NAVIGATING_UP)

        // Diagnostics check for Up Arrow
        val navDiag = automation.diagnostics.find { it.keyAction == "UP_ARROW" }
        assertThat(navDiag).isNotNull()
        assertThat(navDiag?.highlightedOption).isEqualTo(LoginMenuOption.GOOGLE_CLOUD_PROJECT)
        assertThat(navDiag?.ptyInputBytesHex).isEqualTo("1B5B41")

        // Chunk 2: CLI redraws with Google OAuth now highlighted
        val oauthMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            
            Select login method:
            
            «1. Google OAuth»
            
            2. Use a Google Cloud project
            
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        val actions2 = automation.processChunk(oauthMenu)
        val inputs2 = actions2.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(inputs2).hasSize(1)
        assertThat(inputs2[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_ENTER)
        assertThat(automation.menuInteractionState).isEqualTo(MenuInteractionState.SELECTION_SUBMITTED)
    }

    @Test
    fun `repeated terminal output does not duplicate submission`() {
        val sampleMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            
            Select login method:
            
            «1. Google OAuth»
            
            2. Use a Google Cloud project
            
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        // First appearance: Enter is sent
        val actions1 = automation.processChunk(sampleMenu)
        val inputs1 = actions1.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(inputs1).hasSize(1)
        assertThat(inputs1[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_ENTER)

        // Redrawn / repeated terminal output: no input must be sent
        val actions2 = automation.processChunk(sampleMenu)
        assertThat(actions2.filterIsInstance<AntigravityAutomationAction.SendInput>()).isEmpty()

        val actions3 = automation.processChunk(sampleMenu)
        assertThat(actions3.filterIsInstance<AntigravityAutomationAction.SendInput>()).isEmpty()
    }

    @Test
    fun `partial or ANSI-formatted menu output is detected reliably`() {
        // Test partial chunking across buffer
        val chunk1 = "Welcome to the Antigravity CLI. You are currently not signed in.\n\nSelect login method:\n"
        val actions1 = automation.processChunk(chunk1)
        assertThat(actions1.filterIsInstance<AntigravityAutomationAction.SendInput>()).isEmpty()

        val chunk2 = "\n«1. Google OAuth»\n\n2. Use a Google Cloud project\n\n↑/↓ Navigate · enter Select\n"
        val actions2 = automation.processChunk(chunk2)
        val inputs2 = actions2.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(inputs2).hasSize(1)
        assertThat(inputs2[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_ENTER)

        // Test ANSI formatting with reverse video and color codes
        val automationAnsi = AntigravityTerminalAutomation()
        automationAnsi.reset()
        val ansiMenu = "\u001B[1mWelcome to the Antigravity CLI.\u001B[0m You are currently not signed in.\r\n\r\nSelect login method:\r\n\r\n\u001B[7m«1. Google OAuth»\u001B[0m\r\n\r\n2. Use a Google Cloud project\r\n\r\n\u001B[2m↑/↓ Navigate · enter Select\u001B[0m"
        val actionsAnsi = automationAnsi.processChunk(ansiMenu)
        val inputsAnsi = actionsAnsi.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(inputsAnsi).hasSize(1)
        assertThat(inputsAnsi[0].text).isEqualTo(AntigravityTerminalAutomation.KEY_ENTER)
    }

    @Test
    fun `stuck menu without progress reports selection failure instead of repeated Enter`() {
        val sampleMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            Select login method:
            «1. Google OAuth»
            2. Use a Google Cloud project
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        // First chunk submits Enter
        automation.processChunk(sampleMenu)

        // Subsequent chunks repeat the menu without any transition evidence
        var errorAction: AntigravityAutomationAction.StateChanged? = null
        for (i in 1..AntigravityTerminalAutomation.MAX_STUCK_MENU_CHUNKS) {
            val actions = automation.processChunk(sampleMenu)
            // Must NEVER send any SendInput on repeated chunks
            assertThat(actions.filterIsInstance<AntigravityAutomationAction.SendInput>()).isEmpty()
            val stateChange = actions.filterIsInstance<AntigravityAutomationAction.StateChanged>().firstOrNull()
            if (stateChange?.newState == AntigravityAuthState.ERROR) {
                errorAction = stateChange
            }
        }

        assertThat(errorAction).isNotNull()
        assertThat(errorAction?.message).contains("did not advance beyond login menu")
        assertThat(automation.menuInteractionState).isEqualTo(MenuInteractionState.SUBMISSION_FAILED)
    }

    @Test
    fun `successful menu transition extracts and emits Google OAuth URL`() {
        val sampleMenu = """
            Welcome to the Antigravity CLI. You are currently not signed in.
            Select login method:
            «1. Google OAuth»
            2. Use a Google Cloud project
            ↑/↓ Navigate · enter Select
        """.trimIndent()

        // Step 1: Submit Enter
        automation.processChunk(sampleMenu)

        // Step 2: CLI outputs OAuth URL
        val oauthChunk = """
            Please open the following URL in your browser:
            https://accounts.google.com/o/oauth2/auth?client_id=974169037036.apps.googleusercontent.com&scope=email
            Enter authorization code: 
        """.trimIndent()

        val actions = automation.processChunk(oauthChunk)
        val openBrowserActions = actions.filterIsInstance<AntigravityAutomationAction.OpenAuthBrowser>()
        assertThat(openBrowserActions).hasSize(1)
        assertThat(openBrowserActions[0].url).contains("https://accounts.google.com/o/oauth2/auth")
        assertThat(automation.menuInteractionState).isEqualTo(MenuInteractionState.TRANSITIONED)

        // Verify diagnostics
        val diags = automation.diagnostics
        assertThat(diags).isNotEmpty()
        val transitionedDiag = diags.find { it.menuTransitionConfirmed }
        assertThat(transitionedDiag).isNotNull()
    }

    @Test
    fun `extractGoogleAuthUrl extracts unwrapped and line-wrapped authorization URLs`() {
        val wrappedOutput = """
            Please open the following URL in your browser:
            
            https://accounts.google.com/o/oauth2/auth?client_id=974169037036-xyz.apps.googleu
            sercontent.com&redirect_uri=urn%3Aietf%3Awg%3Aoauth%3A2.0%3Aoob&response_type=code
            
            Enter authorization code: 
        """.trimIndent()

        val extracted = automation.extractGoogleAuthUrl(wrappedOutput)
        assertThat(extracted).isNotNull()
        assertThat(extracted).startsWith("https://accounts.google.com/o/oauth2/auth")
        assertThat(extracted).contains("client_id=974169037036-xyz.apps.googleusercontent.com")
        assertThat(extracted).contains("response_type=code")
    }

    @Test
    fun `isValidGoogleAuthUrl rejects untrusted origins or non-https schemes`() {
        assertThat(automation.isValidGoogleAuthUrl("https://accounts.google.com/o/oauth2/auth?code=123")).isTrue()
        assertThat(automation.isValidGoogleAuthUrl("http://accounts.google.com/o/oauth2/auth?code=123")).isFalse()
        assertThat(automation.isValidGoogleAuthUrl("https://evil-accounts.google.com/o/oauth2/auth")).isFalse()
        assertThat(automation.isValidGoogleAuthUrl("https://accounts.google.com.attacker.com/o/oauth2/auth")).isFalse()
    }

    @Test
    fun `extractRemoteControlUrl accurately parses and unwraps official session URLs`() {
        val remoteControlOutput = """
            Remote control on for this session.
            Hostname: localhost-noble-halo
            
            Open https://antigravity.google.com/r/a1b2c3d4e5f60123456789abcdef0123-v2 on another device to take over.
        """.trimIndent()

        val url = automation.extractRemoteControlUrl(remoteControlOutput)
        assertThat(url).isEqualTo("https://antigravity.google.com/r/a1b2c3d4e5f60123456789abcdef0123-v2")
    }

    @Test
    fun `isValidRemoteControlUrl rejects untrusted hostnames and non-https schemes`() {
        assertThat(automation.isValidRemoteControlUrl("https://antigravity.google.com/r/session-123")).isTrue()
        assertThat(automation.isValidRemoteControlUrl("https://antigravity.google/join/session-456")).isTrue()
        assertThat(automation.isValidRemoteControlUrl("http://antigravity.google.com/r/session-123")).isFalse()
        assertThat(automation.isValidRemoteControlUrl("https://unrelated-domain.com/r/session-123")).isFalse()
    }

    @Test
    fun `auth code submission never records sensitive credentials in diagnostics`() {
        automation.submitAuthCode("4/0AY0e-SENSITIVE-SECRET-TOKEN")
        val diags = automation.diagnostics
        for (diag in diags) {
            assertThat(diag.detailMessage).doesNotContain("4/0AY0e-SENSITIVE-SECRET-TOKEN")
            assertThat(diag.ptyInputBytesHex).isNull()
        }
    }

    @Test
    fun `isWaitingForAuthCodePrompt recognizes diverse prompt variations with ANSI and line wraps`() {
        assertThat(automation.isWaitingForAuthCodePrompt("Enter authorization code: ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Enter authorization code:")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("\u001B[1mEnter the authorization code:\u001B[0m ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Paste the authorization code: ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Paste code: ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Enter verification code: ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Authorization code: ")).isTrue()
        assertThat(automation.isWaitingForAuthCodePrompt("Enter code: ")).isTrue()
    }

    @Test
    fun `isWaitingForAuthCodePrompt rejects login menu prompts`() {
        val menuText = """
            Select login method:
            «1. Google OAuth»
            2. Use a Google Cloud project
            ↑/↓ Navigate · enter Select
        """.trimIndent()
        assertThat(automation.isWaitingForAuthCodePrompt(menuText)).isFalse()
    }

    @Test
    fun `auth code submission trims whitespace and sends code with single Enter key`() {
        val actions = automation.submitAuthCode("  4/0AY0e-secret-token \r\n ")
        val sendInputs = actions.filterIsInstance<AntigravityAutomationAction.SendInput>()
        assertThat(sendInputs).hasSize(1)
        assertThat(sendInputs[0].text).isEqualTo("4/0AY0e-secret-token\r")
        assertThat(sendInputs[0].description).isEqualTo("Authorization Code")
        assertThat(automation.isWaitingForAuthCode).isFalse()

        val states = actions.filterIsInstance<AntigravityAutomationAction.StateChanged>()
        assertThat(states.map { it.newState }).containsExactly(
            AntigravityAuthState.SUBMITTING_AUTH_CODE,
            AntigravityAuthState.AUTHENTICATING
        ).inOrder()
    }

    @Test
    fun `auth failure triggers reprompt and recoverable error state`() {
        // Setup waiting for auth code
        automation.processChunk("Please open https://accounts.google.com/o/oauth2/auth?client_id=xyz\n")
        automation.processChunk("Enter authorization code: ")
        assertThat(automation.isWaitingForAuthCode).isTrue()

        // Submit code
        automation.submitAuthCode("wrong-code")
        assertThat(automation.isWaitingForAuthCode).isFalse()

        // CLI reports auth failure and asks for code again
        val retryChunk = """
            Invalid authorization code. Please try again.
            Enter authorization code: 
        """.trimIndent()
        val actions = automation.processChunk(retryChunk)
        val stateChanges = actions.filterIsInstance<AntigravityAutomationAction.StateChanged>()
        assertThat(stateChanges.any { it.newState == AntigravityAuthState.WAITING_FOR_AUTH_CODE }).isTrue()
        assertThat(automation.isWaitingForAuthCode).isTrue()
    }

    @Test
    fun `full state machine transitions from browser auth to connected`() {
        // Step 1: Detect OAuth URL -> WAITING_FOR_BROWSER_AUTH
        val oauthChunk = "Please open https://accounts.google.com/o/oauth2/auth?client_id=123.apps.googleusercontent.com&response_type=code in browser\n"
        val actions1 = automation.processChunk(oauthChunk)
        assertThat(actions1.filterIsInstance<AntigravityAutomationAction.StateChanged>().map { it.newState })
            .contains(AntigravityAuthState.WAITING_FOR_BROWSER_AUTH)

        // Step 2: Code prompt -> WAITING_FOR_AUTH_CODE
        val promptChunk = "Enter authorization code: "
        val actions2 = automation.processChunk(promptChunk)
        assertThat(actions2.filterIsInstance<AntigravityAutomationAction.StateChanged>().map { it.newState })
            .contains(AntigravityAuthState.WAITING_FOR_AUTH_CODE)
        assertThat(automation.isWaitingForAuthCode).isTrue()

        // Step 3 & 4: Submit code -> SUBMITTING_AUTH_CODE -> AUTHENTICATING
        val actions3 = automation.submitAuthCode("4/0AY0e-valid-code")
        assertThat(actions3.filterIsInstance<AntigravityAutomationAction.StateChanged>().map { it.newState })
            .containsExactly(
                AntigravityAuthState.SUBMITTING_AUTH_CODE,
                AntigravityAuthState.AUTHENTICATING
            ).inOrder()
        assertThat(automation.isWaitingForAuthCode).isFalse()

        // Step 5: Success detected -> WAITING_FOR_REMOTE_URL
        val successChunk = "Authentication successful. Logged in as developer@gmail.com\n"
        val actions4 = automation.processChunk(successChunk)
        assertThat(actions4.filterIsInstance<AntigravityAutomationAction.StateChanged>().map { it.newState })
            .contains(AntigravityAuthState.WAITING_FOR_REMOTE_URL)

        // Step 6 & 7: Remote Control URL -> CONNECTING_WEBVIEW -> CONNECTED
        val remoteChunk = "Remote control on for this session. Open https://antigravity.google.com/r/session-abc on another device to take over.\n"
        val actions5 = automation.processChunk(remoteChunk)
        assertThat(actions5.filterIsInstance<AntigravityAutomationAction.StateChanged>().map { it.newState })
            .containsExactly(
                AntigravityAuthState.CONNECTING_WEBVIEW,
                AntigravityAuthState.CONNECTED
            ).inOrder()
        assertThat(actions5.filterIsInstance<AntigravityAutomationAction.NavigateWebView>().first().url)
            .isEqualTo("https://antigravity.google.com/r/session-abc")
    }

    @Test
    fun `user cancellation transitions cleanly to CANCELLED state`() {
        val actions = automation.notifyCancelled()
        val stateChange = actions.filterIsInstance<AntigravityAutomationAction.StateChanged>().first()
        assertThat(stateChange.newState).isEqualTo(AntigravityAuthState.CANCELLED)
        assertThat(automation.isWaitingForAuthCode).isFalse()
    }

    @Test
    fun `isWaitingForAuthCodePrompt recognizes exact CLI redirect prompt`() {
        val prompt = "If you aren't automatically redirected, paste the authorization code below:"
        assertThat(automation.isWaitingForAuthCodePrompt(prompt)).isTrue()

        val promptWithNewline = "Please complete sign-in in browser.\nIf you aren't automatically redirected, paste the authorization code below:\n"
        assertThat(automation.isWaitingForAuthCodePrompt(promptWithNewline)).isTrue()
    }

    @Test
    fun `already authenticated session outputs Remote Control URL directly without login prompt`() {
        val alreadyAuthChunk = """
            Starting Google Antigravity CLI...
            Using existing authentication session for user@google.com.
            Remote control on for this session. Open https://antigravity.google.com/r/existing-sess on another device to take over.
        """.trimIndent()

        val actions = automation.processChunk(alreadyAuthChunk)
        assertThat(actions.filterIsInstance<AntigravityAutomationAction.OpenAuthBrowser>()).isEmpty()
        val nav = actions.filterIsInstance<AntigravityAutomationAction.NavigateWebView>()
        assertThat(nav).hasSize(1)
        assertThat(nav[0].url).isEqualTo("https://antigravity.google.com/r/existing-sess")
    }

    @Test
    fun `extractRemoteControlUrl handles varied path and query parameters`() {
        assertThat(automation.extractRemoteControlUrl("Connected: https://antigravity.google/remote-control?session=abc123xyz"))
            .isEqualTo("https://antigravity.google/remote-control?session=abc123xyz")
        assertThat(automation.extractRemoteControlUrl("Join at https://antigravity.google/join/sess-456"))
            .isEqualTo("https://antigravity.google/join/sess-456")
        assertThat(automation.extractRemoteControlUrl("Session at https://antigravity.google.com/session/sess-789"))
            .isEqualTo("https://antigravity.google.com/session/sess-789")
    }
}
