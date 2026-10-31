package com.linuxdroid.core.runtime.terminal

/**
 * Arrow direction enum for VT cursor movement.
 */
enum class ArrowDirection {
    UP,
    DOWN,
    RIGHT,
    LEFT,
}

/**
 * Controller responsible for translating user interactions, physical and virtual keyboard input,
 * and accessory controls into standard VT/xterm byte sequences forwarded to the PTY.
 *
 * Fully supports xterm modifier parameters:
 * 1 = None, 2 = Shift, 3 = Alt, 4 = Shift+Alt, 5 = Ctrl, 6 = Shift+Ctrl, 7 = Alt+Ctrl, 8 = Shift+Alt+Ctrl.
 */
class TerminalInputController(
    private val sendBytesCallback: (ByteArray) -> Unit,
) {
    /**
     * When true (set by \x1b[?1h), unmodified arrow keys emit SS3 sequences (\x1bOA) instead of CSI (\x1b[A).
     */
    var isApplicationCursorKeys: Boolean = false

    /**
     * When true (set by \x1b[?2004h), paste operations wrap text with bracketed paste markers.
     */
    var isBracketedPasteEnabled: Boolean = false

    /**
     * Opt-in diagnostic mode for logging input sequences (with sensitive input redacted).
     */
    var isDiagnosticLoggingEnabled: Boolean = false
    var diagnosticLogger: ((String) -> Unit)? = null

    private fun logInput(description: String, rawSeq: String) {
        if (isDiagnosticLoggingEnabled) {
            val printable = rawSeq.replace("\u001B", "\\e").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
            diagnosticLogger?.invoke("[PTY_INPUT] $description -> $printable")
        }
    }

    fun sendText(text: String, description: String = "text") {
        if (text.isEmpty()) return
        logInput(description, text)
        sendBytesCallback(text.toByteArray(Charsets.UTF_8))
    }

    private fun sendBytes(bytes: ByteArray, description: String = "bytes") {
        if (isDiagnosticLoggingEnabled) {
            val hex = bytes.joinToString(" ") { String.format("%02X", it) }
            diagnosticLogger?.invoke("[PTY_INPUT] $description -> 0x[$hex]")
        }
        sendBytesCallback(bytes)
    }

    /**
     * Pastes text into the terminal, respecting bracketed paste mode and carriage returns.
     */
    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\r").replace("\n", "\r")

        if (isBracketedPasteEnabled) {
            val bracketed = "\u001B[200~$normalized\u001B[201~"
            logInput("paste (bracketed, len=${normalized.length})", "<bracketed text>")
            sendBytesCallback(bracketed.toByteArray(Charsets.UTF_8))
        } else {
            logInput("paste (len=${normalized.length})", "<text>")
            sendBytesCallback(normalized.toByteArray(Charsets.UTF_8))
        }
    }

    // ─── Modifier Code Calculation (xterm standard) ──────────────────────────────

    /**
     * Calculates the standard xterm modifier parameter:
     * 1 = None, 2 = Shift, 3 = Alt, 4 = Shift+Alt, 5 = Ctrl, 6 = Shift+Ctrl, 7 = Alt+Ctrl, 8 = All.
     */
    fun calculateModifier(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): Int {
        var mod = 1
        if (shift) mod += 1
        if (alt) mod += 2
        if (ctrl) mod += 4
        return mod
    }

    // ─── Cursor Navigation Keys ──────────────────────────────────────────────────

    /**
     * Sends arrow key sequence with full modifier support (Shift, Ctrl, Alt).
     *
     * Example:
     * - Unmodified Down: \x1b[B (or \x1bOB if DECCKM)
     * - Shift + Down:    \x1b[1;2B (Antigravity shortcut)
     * - Ctrl + Down:     \x1b[1;5B
     * - Alt + Down:      \x1b[1;3B
     * - Ctrl+Shift+Down: \x1b[1;6B
     */
    fun sendArrow(
        direction: ArrowDirection,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ) {
        val mod = calculateModifier(shift = shift, alt = alt, ctrl = ctrl)
        val finalChar = when (direction) {
            ArrowDirection.UP -> 'A'
            ArrowDirection.DOWN -> 'B'
            ArrowDirection.RIGHT -> 'C'
            ArrowDirection.LEFT -> 'D'
        }

        val seq = if (mod == 1) {
            if (isApplicationCursorKeys) "\u001BO$finalChar" else "\u001B[$finalChar"
        } else {
            // In standard xterm / VT, modified cursor keys ALWAYS use CSI format
            "\u001B[1;${mod}$finalChar"
        }

        val modNames = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (shift) add("Shift")
        }.joinToString("+")
        val desc = if (modNames.isEmpty()) direction.name else "$modNames+${direction.name}"

        sendText(seq, desc)
    }

    fun sendArrowUp(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        sendArrow(ArrowDirection.UP, shift, alt, ctrl)

    fun sendArrowDown(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        sendArrow(ArrowDirection.DOWN, shift, alt, ctrl)

    fun sendArrowRight(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        sendArrow(ArrowDirection.RIGHT, shift, alt, ctrl)

    fun sendArrowLeft(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        sendArrow(ArrowDirection.LEFT, shift, alt, ctrl)

    // ─── Extended Navigation ──────────────────────────────────────────────────────

    fun sendHome(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[H" else "\u001B[1;${mod}H"
        sendText(seq, "Home (mod=$mod)")
    }

    fun sendEnd(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[F" else "\u001B[1;${mod}F"
        sendText(seq, "End (mod=$mod)")
    }

    fun sendPageUp(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[5~" else "\u001B[5;${mod}~"
        sendText(seq, "PageUp (mod=$mod)")
    }

    fun sendPageDown(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[6~" else "\u001B[6;${mod}~"
        sendText(seq, "PageDown (mod=$mod)")
    }

    fun sendInsert(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[2~" else "\u001B[2;${mod}~"
        sendText(seq, "Insert (mod=$mod)")
    }

    fun sendDelete(shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = if (mod == 1) "\u001B[3~" else "\u001B[3;${mod}~"
        sendText(seq, "Delete (mod=$mod)")
    }

    // ─── Function Keys F1..F12 ────────────────────────────────────────────────────

    fun sendFunctionKey(fNum: Int, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val mod = calculateModifier(shift, alt, ctrl)
        val seq = when (fNum) {
            1 -> if (mod == 1) "\u001BOP" else "\u001B[1;${mod}P"
            2 -> if (mod == 1) "\u001BOQ" else "\u001B[1;${mod}Q"
            3 -> if (mod == 1) "\u001BOR" else "\u001B[1;${mod}R"
            4 -> if (mod == 1) "\u001BOS" else "\u001B[1;${mod}S"
            5 -> if (mod == 1) "\u001B[15~" else "\u001B[15;${mod}~"
            6 -> if (mod == 1) "\u001B[17~" else "\u001B[17;${mod}~"
            7 -> if (mod == 1) "\u001B[18~" else "\u001B[18;${mod}~"
            8 -> if (mod == 1) "\u001B[19~" else "\u001B[19;${mod}~"
            9 -> if (mod == 1) "\u001B[20~" else "\u001B[20;${mod}~"
            10 -> if (mod == 1) "\u001B[21~" else "\u001B[21;${mod}~"
            11 -> if (mod == 1) "\u001B[23~" else "\u001B[23;${mod}~"
            12 -> if (mod == 1) "\u001B[24~" else "\u001B[24;${mod}~"
            else -> return
        }
        sendText(seq, "F$fNum (mod=$mod)")
    }

    // ─── Standard Terminal Keys ───────────────────────────────────────────────────

    fun sendEnter() = sendBytes(byteArrayOf(0x0D), "Enter")
    fun sendTab(shift: Boolean = false) {
        if (shift) {
            // Shift+Tab sends standard backtab
            sendText("\u001B[Z", "Shift+Tab (Backtab)")
        } else {
            sendBytes(byteArrayOf(0x09), "Tab")
        }
    }
    fun sendBacktab() = sendText("\u001B[Z", "Backtab")
    fun sendBackspace() = sendBytes(byteArrayOf(0x7F), "Backspace")
    fun sendEscape() = sendBytes(byteArrayOf(0x1B), "Escape")

    // ─── Control Key Sequences ───────────────────────────────────────────────────

    fun sendCtrlC() = sendBytes(byteArrayOf(0x03), "Ctrl+C (SIGINT)")
    fun sendCtrlD() = sendBytes(byteArrayOf(0x04), "Ctrl+D (EOF)")
    fun sendCtrlL() = sendBytes(byteArrayOf(0x0C), "Ctrl+L (Clear)")
    fun sendCtrlZ() = sendBytes(byteArrayOf(0x1A), "Ctrl+Z (SIGTSTP)")
    fun sendCtrlA() = sendBytes(byteArrayOf(0x01), "Ctrl+A")
    fun sendCtrlE() = sendBytes(byteArrayOf(0x05), "Ctrl+E")
    fun sendCtrlU() = sendBytes(byteArrayOf(0x15), "Ctrl+U")
    fun sendCtrlW() = sendBytes(byteArrayOf(0x17), "Ctrl+W")
    fun sendCtrlR() = sendBytes(byteArrayOf(0x12), "Ctrl+R")
    fun sendCtrlK() = sendBytes(byteArrayOf(0x0B), "Ctrl+K")

    fun sendCtrlChar(char: Char, alt: Boolean = false) {
        val upper = char.uppercaseChar()
        val code: Byte? = when {
            upper in 'A'..'Z' -> (upper.code - 'A'.code + 1).toByte()
            char == '[' -> 0x1B
            char == '\\' -> 0x1C
            char == ']' -> 0x1D
            char == '^' -> 0x1E
            char == '_' -> 0x1F
            char == '@' || char == ' ' -> 0x00
            else -> null
        }

        if (code != null) {
            if (alt) {
                sendBytes(byteArrayOf(0x1B, code), "Alt+Ctrl+$char")
            } else {
                sendBytes(byteArrayOf(code), "Ctrl+$char")
            }
        }
    }

    /**
     * Dispatches general character key with modifier combination.
     */
    fun sendKey(char: Char, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        if (ctrl) {
            sendCtrlChar(char, alt = alt)
        } else if (alt) {
            val finalChar = if (shift && char.isLowerCase()) char.uppercaseChar() else char
            sendText("\u001B$finalChar", "Alt+$finalChar")
        } else if (shift && char.isLowerCase()) {
            sendText(char.uppercaseChar().toString(), "Shift+$char")
        } else {
            sendText(char.toString(), "Char '$char'")
        }
    }
}
