package com.linuxdroid.core.runtime.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class TerminalParserTest {

    private lateinit var grid: TerminalGrid
    private lateinit var scrollback: TerminalScrollback
    private var lastReportedTitle: String? = null
    private var lastDeviceStatusReport: String? = null
    private val modesChanged = mutableMapOf<Int, Boolean>()
    private lateinit var parser: TerminalParser

    @Before
    fun setUp() {
        grid = TerminalGrid(initialRows = 24, initialCols = 80)
        scrollback = TerminalScrollback(maxLines = 100)
        lastReportedTitle = null
        lastDeviceStatusReport = null
        modesChanged.clear()
        parser = TerminalParser(
            grid = grid,
            scrollback = scrollback,
            theme = TerminalTheme.MACOS_PRO,
            onTitleChanged = { lastReportedTitle = it },
            onDeviceStatusReport = { lastDeviceStatusReport = it },
            onModeChanged = { mode, enabled -> modesChanged[mode] = enabled }
        )
    }

    @Test
    fun `partial escape sequence split across chunks is correctly reassembled`() {
        // Chunk 1 has incomplete sequence "\u001B[31"
        parser.parse("Hello \u001B[31")
        // No crash, and attrs not yet updated
        assertThat(parser.currentAttrs.fgColor).isEqualTo(TerminalTheme.MACOS_PRO.foreground)

        // Chunk 2 completes the sequence "mRed World\u001B[0m"
        parser.parse("mRed World\u001B[0m")

        val activeLine = grid.activeScreen[0]
        val text = activeLine.map { it.char }.joinToString("")
        assertThat(text).isEqualTo("Hello Red World")

        // Red text should have Red ANSI color (index 1: 0xFFE57373)
        val redChar = activeLine[6]
        assertThat(redChar.char).isEqualTo('R')
        assertThat(redChar.fgColor).isEqualTo(0xFFE57373L)
    }

    @Test
    fun `ANSI 256 color and 24-bit TrueColor parsing`() {
        // 256 color: \u001B[38;5;46m (Bright Green), TrueColor: \u001B[48;2;40;50;60m (Background)
        parser.parse("\u001B[38;5;46m\u001B[48;2;40;50;60mTest\u001B[0m")

        val char = grid.activeScreen[0][0]
        assertThat(char.char).isEqualTo('T')
        // TrueColor background: 0xFF000000 or (40 shl 16) or (50 shl 8) or 60
        val expectedBg = 0xFF000000L or (40L shl 16) or (50L shl 8) or 60L
        assertThat(char.bgColor).isEqualTo(expectedBg)
    }

    @Test
    fun `cursor addressing CUP and relative movements CUU CUD CUF CUB`() {
        // CUP: move to row 10, col 20 (1-indexed: \u001B[10;20H)
        parser.parse("\u001B[10;20H")
        assertThat(grid.cursorRow).isEqualTo(9)
        assertThat(grid.cursorCol).isEqualTo(19)

        // CUU: Up 3 -> row 6
        parser.parse("\u001B[3A")
        assertThat(grid.cursorRow).isEqualTo(6)

        // CUD: Down 2 -> row 8
        parser.parse("\u001B[2B")
        assertThat(grid.cursorRow).isEqualTo(8)

        // CUF: Forward 5 -> col 24
        parser.parse("\u001B[5C")
        assertThat(grid.cursorCol).isEqualTo(24)

        // CUB: Backward 4 -> col 20
        parser.parse("\u001B[4D")
        assertThat(grid.cursorCol).isEqualTo(20)
    }

    @Test
    fun `alternate screen buffer enter and exit notifies mode listener`() {
        // Enter alternate screen: \u001B[?1049h
        parser.parse("\u001B[?1049h")
        assertThat(grid.isAlternateScreen).isTrue()
        assertThat(modesChanged[1049]).isTrue()

        // Exit alternate screen: \u001B[?1049l
        parser.parse("\u001B[?1049l")
        assertThat(grid.isAlternateScreen).isFalse()
        assertThat(modesChanged[1049]).isFalse()
    }

    @Test
    fun `OSC 0 and OSC 2 window title sequence updates title`() {
        parser.parse("\u001B]0;user@linuxdroid:~/workspace\u0007")
        assertThat(lastReportedTitle).isEqualTo("user@linuxdroid:~/workspace")

        parser.parse("\u001B]2;vim file.kt\u001B\\")
        assertThat(lastReportedTitle).isEqualTo("vim file.kt")
    }

    @Test
    fun `device status report DSR 6 returns 1-indexed cursor position`() {
        // Place cursor at row 4, col 12 (0-indexed)
        grid.cursorRow = 4
        grid.cursorCol = 12

        parser.parse("\u001B[6n")
        // Row 5, Col 13
        assertThat(lastDeviceStatusReport).isEqualTo("\u001B[5;13R")
    }
}
