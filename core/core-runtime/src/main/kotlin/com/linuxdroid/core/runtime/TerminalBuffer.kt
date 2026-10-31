package com.linuxdroid.core.runtime

import com.linuxdroid.core.runtime.terminal.TerminalChar
import com.linuxdroid.core.runtime.terminal.TerminalGrid
import com.linuxdroid.core.runtime.terminal.TerminalInputController
import com.linuxdroid.core.runtime.terminal.TerminalParser
import com.linuxdroid.core.runtime.terminal.TerminalPosition
import com.linuxdroid.core.runtime.terminal.TerminalScrollback
import com.linuxdroid.core.runtime.terminal.TerminalSelection
import com.linuxdroid.core.runtime.terminal.TerminalSelectionExtractor
import com.linuxdroid.core.runtime.terminal.TerminalTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// Public typealiases preserving exact backward compatibility for existing callers
typealias StyledSpan = com.linuxdroid.core.runtime.terminal.StyledSpan
typealias TerminalLineData = com.linuxdroid.core.runtime.terminal.TerminalLineData

/**
 * Production-ready, thread-safe ANSI VT100 / xterm terminal screen buffer.
 *
 * Implements an original, first-party terminal engine adhering strictly to macOS Terminal
 * interaction and usability conventions, adapted for Android touchscreens.
 *
 * Architecture:
 * - [TerminalParser]: Stateful streaming ECMA-48 / VT parser handling partial sequences across chunk boundaries.
 * - [TerminalGrid]: 2D character matrix managing primary and alternate screens, margins, and editing.
 * - [TerminalScrollback]: Ring-buffer scrollback storage with stable viewport navigation during streaming.
 * - [TerminalInputController]: VT keycode translator and Bracketed Paste manager.
 * - [TerminalTheme]: macOS Terminal presets (Pro, Basic, Homebrew) with contrast adaptation.
 * - [TerminalSelectionExtractor]: Touch selection extraction and soft-wrap line reassembly.
 */
class TerminalBuffer(
    val maxScrollbackLines: Int = 2000,
    initialTheme: TerminalTheme = TerminalTheme.MACOS_PRO,
    var onOutputBytes: ((ByteArray) -> Unit)? = null,
) {
    var theme: TerminalTheme = initialTheme
        set(value) {
            field = value
            parser.theme = value
            publishLines()
        }
    private val _lines = MutableStateFlow<List<TerminalLineData>>(listOf(TerminalLineData.simple("")))
    val lines: StateFlow<List<TerminalLineData>> = _lines.asStateFlow()

    private val _activeCursorRow = MutableStateFlow(0)
    val activeCursorRow: StateFlow<Int> = _activeCursorRow.asStateFlow()

    private val _activeCursorCol = MutableStateFlow(0)
    val activeCursorCol: StateFlow<Int> = _activeCursorCol.asStateFlow()

    private val _isCursorVisible = MutableStateFlow(true)
    val isCursorVisible: StateFlow<Boolean> = _isCursorVisible.asStateFlow()

    private val _terminalTitle = MutableStateFlow("")
    val terminalTitle: StateFlow<String> = _terminalTitle.asStateFlow()

    // Modular Subsystems
    val grid = TerminalGrid(initialRows = 24, initialCols = 80)
    val scrollback = TerminalScrollback(maxLines = maxScrollbackLines)

    val inputController = TerminalInputController { bytes ->
        onOutputBytes?.invoke(bytes)
    }

    private val parser = TerminalParser(
        grid = grid,
        scrollback = scrollback,
        theme = theme,
        onTitleChanged = { title ->
            _terminalTitle.value = title
        },
        onDeviceStatusReport = { report ->
            onOutputBytes?.invoke(report.toByteArray(StandardCharsets.UTF_8))
        },
        onModeChanged = { mode, enabled ->
            when (mode) {
                1 -> inputController.isApplicationCursorKeys = enabled
                2004 -> inputController.isBracketedPasteEnabled = enabled
                25 -> _isCursorVisible.value = enabled
            }
        }
    )

    val rows: Int
        get() = grid.rows

    val cols: Int
        get() = grid.cols

    val isAlternateScreen: Boolean
        get() = grid.isAlternateScreen

    // Stateful UTF-8 streaming decoder
    private val utf8Decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes: ByteArray? = null

    init {
        publishLines()
    }

    /**
     * Resizes the terminal 2D grid matrix.
     */
    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        val r = maxOf(1, newRows)
        val c = maxOf(1, newCols)
        grid.resize(r, c)
        publishLines()
    }

    /**
     * Resets screen, scrollback history, and parser state.
     */
    @Synchronized
    fun clear() {
        scrollback.clear()
        grid.clear()
        parser.reset()
        _activeCursorRow.value = 0
        _activeCursorCol.value = 0
        _terminalTitle.value = ""
        publishLines()
    }

    /**
     * Decodes incoming byte chunk preserving incomplete multi-byte UTF-8 sequences.
     */
    private fun decodeStreamBytes(data: ByteArray, length: Int): String {
        val totalBytes: ByteArray = if (pendingBytes != null && pendingBytes!!.isNotEmpty()) {
            val combined = ByteArray(pendingBytes!!.size + length)
            System.arraycopy(pendingBytes!!, 0, combined, 0, pendingBytes!!.size)
            System.arraycopy(data, 0, combined, pendingBytes!!.size, length)
            pendingBytes = null
            combined
        } else {
            if (length == data.size) data else data.copyOf(length)
        }

        val inBuf = ByteBuffer.wrap(totalBytes)
        val outBuf = CharBuffer.allocate(totalBytes.size + 8)

        utf8Decoder.decode(inBuf, outBuf, false)

        if (inBuf.hasRemaining()) {
            val rem = inBuf.remaining()
            if (rem in 1..4) {
                val leftover = ByteArray(rem)
                inBuf.get(leftover)
                pendingBytes = leftover
            } else {
                utf8Decoder.reset()
                pendingBytes = null
            }
        } else {
            pendingBytes = null
        }

        outBuf.flip()
        return outBuf.toString()
    }

    /**
     * Appends raw output data from the PTY stream and processes ANSI codes.
     */
    @Synchronized
    fun append(data: ByteArray, length: Int) {
        val text = decodeStreamBytes(data, length)
        if (text.isNotEmpty()) {
            parser.parse(text)
            publishLines()
        }
    }

    /**
     * Publishes current scrollback and active screen grid into reactive StateFlows.
     */
    private fun publishLines() {
        val result = ArrayList<TerminalLineData>()

        // 1. Scrollback lines (only in primary screen mode)
        if (!grid.isAlternateScreen) {
            for (row in scrollback.lines) {
                result.add(convertRowToLineData(row))
            }
        }

        // 2. Active screen grid
        val activeGrid = grid.activeScreen
        val maxRow = if (grid.isAlternateScreen) {
            grid.rows - 1
        } else {
            maxOf(grid.cursorRow, findLastNonEmptyRow(activeGrid))
        }

        for (r in 0..maxRow) {
            val row = if (r < activeGrid.size) activeGrid[r] else emptyList()
            result.add(convertRowToLineData(row))
        }

        if (result.isEmpty()) {
            result.add(TerminalLineData(emptyList()))
        }

        _lines.value = result
        _activeCursorRow.value = grid.cursorRow
        _activeCursorCol.value = grid.cursorCol
        _isCursorVisible.value = parser.isCursorVisible
    }

    private fun findLastNonEmptyRow(grid: ArrayList<ArrayList<TerminalChar>>): Int {
        for (r in grid.indices.reversed()) {
            if (grid[r].any { it.char != ' ' }) {
                return r
            }
        }
        return 0
    }

    private fun convertRowToLineData(lineChars: List<TerminalChar>): TerminalLineData {
        if (lineChars.isEmpty()) {
            return TerminalLineData(emptyList())
        }

        val spans = ArrayList<StyledSpan>()
        var currentSpanText = StringBuilder()
        var spanColor = lineChars[0].effectiveFg
        var spanBgColor = lineChars[0].effectiveBg
        var spanBold = lineChars[0].isBold
        var spanUnderline = lineChars[0].isUnderline
        var spanInverse = lineChars[0].isInverse
        var spanDim = lineChars[0].isDim
        var spanItalic = lineChars[0].isItalic
        var spanStrikethrough = lineChars[0].isStrikethrough

        for (tc in lineChars) {
            val fg = tc.effectiveFg
            val bg = tc.effectiveBg
            if (fg != spanColor || bg != spanBgColor ||
                tc.isBold != spanBold || tc.isUnderline != spanUnderline ||
                tc.isInverse != spanInverse || tc.isDim != spanDim ||
                tc.isItalic != spanItalic || tc.isStrikethrough != spanStrikethrough
            ) {
                if (currentSpanText.isNotEmpty()) {
                    spans.add(
                        StyledSpan(
                            text = currentSpanText.toString(),
                            color = spanColor,
                            isBold = spanBold,
                            isUnderline = spanUnderline,
                            isInverse = spanInverse,
                            backgroundColor = spanBgColor,
                            isDim = spanDim,
                            isItalic = spanItalic,
                            isStrikethrough = spanStrikethrough,
                        )
                    )
                    currentSpanText = StringBuilder()
                }
                spanColor = fg
                spanBgColor = bg
                spanBold = tc.isBold
                spanUnderline = tc.isUnderline
                spanInverse = tc.isInverse
                spanDim = tc.isDim
                spanItalic = tc.isItalic
                spanStrikethrough = tc.isStrikethrough
            }
            currentSpanText.append(tc.char)
        }

        if (currentSpanText.isNotEmpty()) {
            spans.add(
                StyledSpan(
                    text = currentSpanText.toString(),
                    color = spanColor,
                    isBold = spanBold,
                    isUnderline = spanUnderline,
                    isInverse = spanInverse,
                    backgroundColor = spanBgColor,
                    isDim = spanDim,
                    isItalic = spanItalic,
                    isStrikethrough = spanStrikethrough,
                )
            )
        }

        return TerminalLineData(spans)
    }

    /**
     * Extracts the complete scrollback history and active screen as plain text.
     */
    @Synchronized
    fun getPlainText(): String {
        val plainLines = ArrayList<String>()
        if (!grid.isAlternateScreen) {
            for (row in scrollback.lines) {
                plainLines.add(row.map { it.char }.joinToString("").trimEnd())
            }
        }
        val activeGrid = grid.activeScreen
        val maxRow = if (grid.isAlternateScreen) grid.rows - 1 else maxOf(grid.cursorRow, findLastNonEmptyRow(activeGrid))
        for (r in 0..maxRow) {
            val row = if (r < activeGrid.size) activeGrid[r] else emptyList()
            plainLines.add(row.map { it.char }.joinToString("").trimEnd())
        }
        return plainLines.joinToString("\n")
    }

    /**
     * Extracts the most recent [count] lines of terminal text.
     */
    @Synchronized
    fun getRecentLines(count: Int = 100): List<String> {
        val allLines = getPlainText().split("\n")
        return allLines.takeLast(count)
    }

    /**
     * Extracts selected text adhering to [selection] boundaries and line wrapping.
     */
    @Synchronized
    fun extractSelection(selection: TerminalSelection): String {
        return TerminalSelectionExtractor.extractText(_lines.value, selection)
    }
}
