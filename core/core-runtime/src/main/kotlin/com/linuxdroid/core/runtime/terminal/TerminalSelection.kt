package com.linuxdroid.core.runtime.terminal

/**
 * 2D coordinate inside the terminal viewport or scrollback history.
 *
 * @param row 0-indexed line number in the flattened line list (scrollback + active screen).
 * @param col 0-indexed character column within the line.
 */
data class TerminalPosition(
    val row: Int,
    val col: Int,
) : Comparable<TerminalPosition> {
    override fun compareTo(other: TerminalPosition): Int {
        val rowDiff = this.row.compareTo(other.row)
        return if (rowDiff != 0) rowDiff else this.col.compareTo(other.col)
    }
}

/**
 * Selection span across terminal characters.
 */
data class TerminalSelection(
    val start: TerminalPosition,
    val end: TerminalPosition,
    val isBlockSelection: Boolean = false,
) {
    val normalizedStart: TerminalPosition
        get() = if (start <= end) start else end

    val normalizedEnd: TerminalPosition
        get() = if (start <= end) end else start

    val isEmpty: Boolean
        get() = start == end

    fun contains(row: Int, col: Int): Boolean {
        if (isEmpty) return false
        val pos = TerminalPosition(row, col)
        val s = normalizedStart
        val e = normalizedEnd

        if (isBlockSelection) {
            val minCol = minOf(s.col, e.col)
            val maxCol = maxOf(s.col, e.col)
            return row in s.row..e.row && col in minCol..maxCol
        }

        return pos in s..e
    }
}

/**
 * Utilities for extracting selected text and finding word boundaries in terminal buffers.
 */
object TerminalSelectionExtractor {

    private val WORD_DELIMITERS = setOf(' ', '\t', '\n', '\r', '"', '\'', '`', '(', ')', '[', ']', '{', '}', '<', '>', ';', ':', ',', '=', '|')

    /**
     * Finds the start and end column of the word at [col] within [lineText].
     */
    fun findWordBoundary(lineText: String, col: Int): Pair<Int, Int> {
        if (lineText.isEmpty()) return 0 to 0
        val clampedCol = col.coerceIn(0, lineText.length - 1)
        val targetChar = lineText[clampedCol]

        if (targetChar in WORD_DELIMITERS) {
            return clampedCol to clampedCol + 1
        }

        var startCol = clampedCol
        while (startCol > 0 && lineText[startCol - 1] !in WORD_DELIMITERS) {
            startCol--
        }

        var endCol = clampedCol
        while (endCol < lineText.length && lineText[endCol] !in WORD_DELIMITERS) {
            endCol++
        }

        return startCol to endCol
    }

    /**
     * Extracts selected text from the given [lines] according to [selection].
     * Reconstructs wrapped lines seamlessly without artificial line breaks.
     */
    fun extractText(lines: List<TerminalLineData>, selection: TerminalSelection): String {
        if (selection.isEmpty || lines.isEmpty()) return ""

        val s = selection.normalizedStart
        val e = selection.normalizedEnd

        val startRow = s.row.coerceIn(0, lines.size - 1)
        val endRow = e.row.coerceIn(0, lines.size - 1)

        val sb = StringBuilder()

        if (selection.isBlockSelection) {
            val minCol = minOf(s.col, e.col)
            val maxCol = maxOf(s.col, e.col)
            for (r in startRow..endRow) {
                val lineText = lines[r].rawText
                if (minCol < lineText.length) {
                    val endC = minOf(maxCol + 1, lineText.length)
                    sb.append(lineText.substring(minCol, endC).trimEnd())
                }
                if (r < endRow) sb.append("\n")
            }
            return sb.toString()
        }

        for (r in startRow..endRow) {
            val line = lines[r]
            val lineText = line.rawText

            val lineStartCol = if (r == startRow) s.col.coerceIn(0, lineText.length) else 0
            val lineEndCol = if (r == endRow) e.col.coerceIn(lineStartCol, lineText.length) else lineText.length

            if (lineStartCol < lineText.length) {
                val segment = lineText.substring(lineStartCol, minOf(lineEndCol, lineText.length))
                sb.append(segment)
            }

            // Append newline only if the line is not soft-wrapped into the next line
            if (r < endRow && !line.isWrapped) {
                sb.append("\n")
            }
        }

        return sb.toString()
    }
}
