package com.linuxdroid.core.runtime.terminal

/**
 * ANSI / ECMA-48 and DEC VT-compatible streaming escape sequence parser.
 *
 * Designed with strict stateful parsing that seamlessly reassembles escape sequences
 * split across incoming PTY read chunk boundaries.
 */
class TerminalParser(
    private val grid: TerminalGrid,
    private val scrollback: TerminalScrollback,
    var theme: TerminalTheme = TerminalTheme.MACOS_PRO,
    private val onTitleChanged: (String) -> Unit = {},
    private val onDeviceStatusReport: (String) -> Unit = {},
    private val onModeChanged: (mode: Int, enabled: Boolean) -> Unit = { _, _ -> },
) {
    // Current character attribute state
    var currentAttrs = TerminalChar(fgColor = theme.foreground)
        private set

    // Saved attribute state
    private var savedAttrs = TerminalChar(fgColor = theme.foreground)

    var isCursorVisible: Boolean = true
        private set

    // Buffer for escape sequences split across chunk boundaries
    private val pendingEscapeBuffer = StringBuilder()

    fun reset() {
        currentAttrs = TerminalChar(fgColor = theme.foreground)
        savedAttrs = TerminalChar(fgColor = theme.foreground)
        isCursorVisible = true
        pendingEscapeBuffer.setLength(0)
    }

    /**
     * Parses incoming decoded stream text.
     */
    fun parse(text: String) {
        val fullText = if (pendingEscapeBuffer.isNotEmpty()) {
            val combined = pendingEscapeBuffer.toString() + text
            pendingEscapeBuffer.setLength(0)
            combined
        } else {
            text
        }

        var i = 0
        val len = fullText.length

        while (i < len) {
            val c = fullText[i]

            when {
                c == '\u001B' -> { // ESC
                    val parsed = parseEscapeSequence(fullText, i)
                    if (parsed == null) {
                        // Sequence was incomplete at end of buffer; stash remainder and wait for next chunk
                        pendingEscapeBuffer.append(fullText.substring(i))
                        return
                    }
                    i = parsed
                }
                c == '\r' -> {
                    grid.cursorCol = 0
                    i++
                }
                c == '\n' -> {
                    grid.lineFeed { row -> scrollback.addLine(row) }
                    grid.cursorCol = 0
                    i++
                }
                c == '\b' || c == '\u007F' -> {
                    if (grid.cursorCol > 0) {
                        grid.cursorCol--
                    }
                    i++
                }
                c == '\t' -> {
                    val nextTab = ((grid.cursorCol / 8) + 1) * 8
                    val spaces = minOf(grid.cols - 1, nextTab) - grid.cursorCol
                    for (s in 0 until maxOf(1, spaces)) {
                        grid.insertChar(' ', currentAttrs)
                    }
                    i++
                }
                c == '\u0007' -> { // Bell
                    i++
                }
                else -> {
                    grid.insertChar(c, currentAttrs)
                    i++
                }
            }
        }
    }

    /**
     * Attempts to parse an escape sequence starting at index [start].
     * Returns the index immediately following the sequence, or null if incomplete.
     */
    private fun parseEscapeSequence(text: String, start: Int): Int? {
        val len = text.length
        if (start + 1 >= len) return null // Only ESC received

        val second = text[start + 1]
        when (second) {
            '[' -> { // CSI Sequence: ESC [ <params> <command>
                var j = start + 2
                while (j < len && (text[j] in '0'..'9' || text[j] == ';' || text[j] == '?' || text[j] == ' ' || text[j] == '>' || text[j] == '<')) {
                    j++
                }
                if (j >= len) return null // Incomplete CSI sequence
                val cmd = text[j]
                val params = text.substring(start + 2, j)
                handleCsiSequence(cmd, params)
                return j + 1
            }
            ']' -> { // OSC Sequence: ESC ] <param>;<string> (BEL or ST)
                var j = start + 2
                while (j < len && text[j] != '\u0007' && !(text[j] == '\u001B' && j + 1 < len && text[j + 1] == '\\')) {
                    j++
                }
                if (j >= len) return null // Incomplete OSC sequence
                val oscPayload = text.substring(start + 2, j)
                handleOscSequence(oscPayload)
                return if (text[j] == '\u0007') j + 1 else j + 2
            }
            '7' -> { // DECSC: Save Cursor
                grid.saveCursor(currentAttrs)
                savedAttrs = currentAttrs.copy()
                return start + 2
            }
            '8' -> { // DECRC: Restore Cursor
                currentAttrs = grid.restoreCursor()
                return start + 2
            }
            'M' -> { // RI: Reverse Index
                grid.reverseIndex()
                return start + 2
            }
            'c' -> { // RIS: Reset to Initial State
                grid.clear()
                scrollback.clear()
                reset()
                return start + 2
            }
            '(', ')' -> { // Character set designations (G0 / G1)
                return if (start + 2 < len) start + 3 else null
            }
            else -> {
                // Unknown single escape char
                return start + 2
            }
        }
    }

    private fun handleCsiSequence(cmd: Char, params: String) {
        when (cmd) {
            'm' -> handleSgr(params)
            'H', 'f' -> { // CUP / HVP: Cursor Position (1-indexed row;col)
                val parts = if (params.isEmpty() || params == ";") listOf(1, 1) else params.split(";").map { it.toIntOrNull() ?: 1 }
                val r = (parts.getOrNull(0) ?: 1) - 1
                val c = (parts.getOrNull(1) ?: 1) - 1
                grid.cursorRow = r
                grid.cursorCol = c
            }
            'A' -> { // CUU: Cursor Up
                val n = params.toIntOrNull() ?: 1
                grid.cursorRow = maxOf(grid.scrollTop, grid.cursorRow - n)
            }
            'B' -> { // CUD: Cursor Down
                val n = params.toIntOrNull() ?: 1
                grid.cursorRow = minOf(grid.scrollBottom, grid.cursorRow + n)
            }
            'C' -> { // CUF: Cursor Forward
                val n = params.toIntOrNull() ?: 1
                grid.cursorCol = minOf(grid.cols - 1, grid.cursorCol + n)
            }
            'D' -> { // CUB: Cursor Backward
                val n = params.toIntOrNull() ?: 1
                grid.cursorCol = maxOf(0, grid.cursorCol - n)
            }
            'E' -> { // CNL: Cursor Next Line
                val n = params.toIntOrNull() ?: 1
                grid.cursorRow = minOf(grid.scrollBottom, grid.cursorRow + n)
                grid.cursorCol = 0
            }
            'F' -> { // CPL: Cursor Previous Line
                val n = params.toIntOrNull() ?: 1
                grid.cursorRow = maxOf(grid.scrollTop, grid.cursorRow - n)
                grid.cursorCol = 0
            }
            'G' -> { // CHA: Cursor Horizontal Absolute (1-indexed)
                val n = params.toIntOrNull() ?: 1
                grid.cursorCol = n - 1
            }
            'd' -> { // VPA: Line Position Absolute (1-indexed)
                val n = params.toIntOrNull() ?: 1
                grid.cursorRow = n - 1
            }
            'J' -> { // ED: Erase in Display
                val mode = params.toIntOrNull() ?: 0
                grid.eraseInDisplay(mode) { scrollback.clear() }
            }
            'K' -> { // EL: Erase in Line
                val mode = params.toIntOrNull() ?: 0
                grid.eraseInLine(mode)
            }
            'L' -> { // IL: Insert Lines
                val n = params.toIntOrNull() ?: 1
                grid.insertLines(n)
            }
            'M' -> { // DL: Delete Lines
                val n = params.toIntOrNull() ?: 1
                grid.deleteLines(n)
            }
            'P' -> { // DCH: Delete Characters
                val n = params.toIntOrNull() ?: 1
                grid.deleteChars(n)
            }
            '@' -> { // ICH: Insert Characters
                val n = params.toIntOrNull() ?: 1
                grid.insertChars(n)
            }
            'X' -> { // ECH: Erase Characters
                val n = params.toIntOrNull() ?: 1
                grid.eraseChars(n)
            }
            'r' -> { // DECSTBM: Set Top and Bottom Margins
                val parts = if (params.isEmpty()) listOf(1, grid.rows) else params.split(";").map { it.toIntOrNull() ?: 1 }
                val top = (parts.getOrNull(0) ?: 1) - 1
                val bot = (parts.getOrNull(1) ?: grid.rows) - 1
                grid.setScrollMargins(top, bot)
            }
            's' -> { // ANSI Save Cursor
                grid.saveCursor(currentAttrs)
                savedAttrs = currentAttrs.copy()
            }
            'u' -> { // ANSI Restore Cursor
                currentAttrs = grid.restoreCursor()
            }
            'h' -> { // Private Mode Set (DECSET)
                handlePrivateMode(params, enabled = true)
            }
            'l' -> { // Private Mode Reset (DECRST)
                handlePrivateMode(params, enabled = false)
            }
            'n' -> { // Device Status Report (DSR)
                if (params == "6") {
                    // Report cursor position: \x1b[<row>;<col>R (1-indexed)
                    val r = grid.cursorRow + 1
                    val c = grid.cursorCol + 1
                    onDeviceStatusReport("\u001B[$r;${c}R")
                }
            }
        }
    }

    private fun handlePrivateMode(params: String, enabled: Boolean) {
        when (params) {
            "?1049", "?47" -> {
                if (enabled) grid.enterAlternateScreen() else grid.exitAlternateScreen()
                onModeChanged(1049, enabled)
            }
            "?25" -> {
                isCursorVisible = enabled
                onModeChanged(25, enabled)
            }
            "?1" -> { // Application Cursor Keys
                onModeChanged(1, enabled)
            }
            "?2004" -> { // Bracketed Paste Mode
                onModeChanged(2004, enabled)
            }
        }
    }

    private fun handleSgr(params: String) {
        val tokens = if (params.isEmpty()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() }
        var idx = 0
        while (idx < tokens.size) {
            val param = tokens[idx]
            when (param) {
                0 -> { // Reset all
                    currentAttrs = TerminalChar(fgColor = theme.foreground, bgColor = 0x00000000L)
                    idx++
                }
                1 -> { currentAttrs.isBold = true; idx++ }
                2 -> { currentAttrs.isDim = true; idx++ }
                3 -> { currentAttrs.isItalic = true; idx++ }
                4 -> { currentAttrs.isUnderline = true; idx++ }
                7 -> { currentAttrs.isInverse = true; idx++ }
                9 -> { currentAttrs.isStrikethrough = true; idx++ }
                22 -> { currentAttrs.isBold = false; currentAttrs.isDim = false; idx++ }
                23 -> { currentAttrs.isItalic = false; idx++ }
                24 -> { currentAttrs.isUnderline = false; idx++ }
                27 -> { currentAttrs.isInverse = false; idx++ }
                29 -> { currentAttrs.isStrikethrough = false; idx++ }
                in 30..37 -> {
                    currentAttrs.fgColor = theme.ansiColors[param - 30]
                    idx++
                }
                38 -> { // Extended Foreground Color
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentAttrs.fgColor = TerminalTheme.parse256Color(tokens[idx + 2], theme)
                        idx += 3
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentAttrs.fgColor = 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
                        idx += 5
                    } else {
                        idx++
                    }
                }
                39 -> { // Default Foreground
                    currentAttrs.fgColor = theme.foreground
                    idx++
                }
                in 40..47 -> {
                    currentAttrs.bgColor = theme.ansiColors[param - 40]
                    idx++
                }
                48 -> { // Extended Background Color
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentAttrs.bgColor = TerminalTheme.parse256Color(tokens[idx + 2], theme)
                        idx += 3
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentAttrs.bgColor = 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
                        idx += 5
                    } else {
                        idx++
                    }
                }
                49 -> { // Default Background
                    currentAttrs.bgColor = 0x00000000L
                    idx++
                }
                in 90..97 -> {
                    currentAttrs.fgColor = theme.brightAnsiColors[param - 90]
                    idx++
                }
                in 100..107 -> {
                    currentAttrs.bgColor = theme.brightAnsiColors[param - 100]
                    idx++
                }
                else -> idx++
            }
        }
    }

    private fun handleOscSequence(payload: String) {
        val parts = payload.split(";", limit = 2)
        if (parts.size == 2) {
            val command = parts[0].toIntOrNull() ?: return
            val value = parts[1]
            if (command == 0 || command == 2) {
                onTitleChanged(value)
            }
        }
    }
}
