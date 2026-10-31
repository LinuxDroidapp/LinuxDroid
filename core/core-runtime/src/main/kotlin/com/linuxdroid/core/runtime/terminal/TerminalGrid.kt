package com.linuxdroid.core.runtime.terminal

/**
 * 2D character cell matrix managing primary and alternate terminal screens.
 */
class TerminalGrid(
    initialRows: Int = 24,
    initialCols: Int = 80,
) {
    var rows: Int = maxOf(1, initialRows)
        private set
    var cols: Int = maxOf(1, initialCols)
        private set

    // Active cursor coordinates (0-indexed)
    var cursorRow: Int = 0
        set(value) { field = value.coerceIn(0, rows - 1) }
    var cursorCol: Int = 0
        set(value) { field = value.coerceIn(0, cols - 1) }

    // Saved cursor position and attributes (DECSC / DECRC)
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedCharAttrs = TerminalChar()

    // Scrolling margins (0-indexed)
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    // Primary screen active lines
    val primaryScreen = ArrayList<ArrayList<TerminalChar>>()

    // Alternate screen active lines (used by full-screen curses/TUI apps)
    val alternateScreen = ArrayList<ArrayList<TerminalChar>>()

    var isAlternateScreen: Boolean = false
        private set

    init {
        initGrids()
    }

    private fun initGrids() {
        primaryScreen.clear()
        alternateScreen.clear()
        for (r in 0 until rows) {
            primaryScreen.add(ArrayList())
            alternateScreen.add(ArrayList())
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    val activeScreen: ArrayList<ArrayList<TerminalChar>>
        get() = if (isAlternateScreen) alternateScreen else primaryScreen

    fun resize(newRows: Int, newCols: Int) {
        val r = maxOf(1, newRows)
        val c = maxOf(1, newCols)
        rows = r
        cols = c
        scrollTop = 0
        scrollBottom = r - 1

        while (primaryScreen.size < r) primaryScreen.add(ArrayList())
        while (alternateScreen.size < r) alternateScreen.add(ArrayList())

        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
    }

    fun clear() {
        initGrids()
        cursorRow = 0
        cursorCol = 0
        isAlternateScreen = false
    }

    fun enterAlternateScreen() {
        saveCursor()
        isAlternateScreen = true
        for (r in alternateScreen) r.clear()
        cursorRow = 0
        cursorCol = 0
    }

    fun exitAlternateScreen() {
        isAlternateScreen = false
        restoreCursor()
    }

    fun setScrollMargins(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)
        scrollTop = t
        scrollBottom = b
        cursorRow = 0
        cursorCol = 0
    }

    fun saveCursor(currentAttrs: TerminalChar = TerminalChar()) {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedCharAttrs = currentAttrs.copy()
    }

    fun restoreCursor(): TerminalChar {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        return savedCharAttrs.copy()
    }

    fun insertChar(c: Char, attrs: TerminalChar) {
        val grid = activeScreen
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]

        val cell = attrs.copy().apply { char = c }

        if (cursorCol < line.size) {
            line[cursorCol] = cell
        } else {
            while (line.size < cursorCol) {
                line.add(TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L))
            }
            line.add(cell)
        }

        cursorCol++
        if (cursorCol >= cols) {
            cursorCol = cols - 1
        }
    }

    fun lineFeed(onScrollback: (ArrayList<TerminalChar>) -> Unit) {
        if (cursorRow >= scrollBottom) {
            scrollUp(onScrollback)
            cursorRow = scrollBottom
        } else {
            cursorRow++
            if (cursorRow >= rows) {
                cursorRow = rows - 1
            }
        }
    }

    fun reverseIndex() {
        if (cursorRow <= scrollTop) {
            scrollDown()
            cursorRow = scrollTop
        } else {
            cursorRow--
        }
    }

    private fun scrollUp(onScrollback: (ArrayList<TerminalChar>) -> Unit) {
        val grid = activeScreen
        if (scrollTop == 0 && !isAlternateScreen) {
            if (grid.isNotEmpty()) {
                val topRow = grid.removeAt(0)
                onScrollback(topRow)
            }
        } else if (scrollTop < grid.size) {
            grid.removeAt(scrollTop)
        }

        val targetInsert = minOf(scrollBottom, grid.size)
        grid.add(targetInsert, ArrayList())
    }

    private fun scrollDown() {
        val grid = activeScreen
        if (scrollBottom < grid.size) {
            grid.removeAt(scrollBottom)
        } else if (grid.isNotEmpty()) {
            grid.removeAt(grid.size - 1)
        }
        val targetInsert = minOf(scrollTop, grid.size)
        grid.add(targetInsert, ArrayList())
    }

    fun eraseInDisplay(mode: Int, onClearScrollback: () -> Unit) {
        val grid = activeScreen
        when (mode) {
            0 -> { // From cursor to end of screen
                if (cursorRow < grid.size) {
                    val cur = grid[cursorRow]
                    while (cur.size > cursorCol) cur.removeAt(cur.size - 1)
                    for (r in cursorRow + 1 until grid.size) {
                        grid[r].clear()
                    }
                }
            }
            1 -> { // From beginning to cursor
                for (r in 0 until minOf(cursorRow, grid.size)) {
                    grid[r].clear()
                }
                if (cursorRow < grid.size) {
                    val cur = grid[cursorRow]
                    for (c in 0 until minOf(cursorCol + 1, cur.size)) {
                        cur[c] = TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L)
                    }
                }
            }
            2 -> { // Entire screen
                for (r in 0 until grid.size) {
                    grid[r].clear()
                }
                cursorRow = 0
                cursorCol = 0
            }
            3 -> { // Entire screen and scrollback history
                onClearScrollback()
                for (r in 0 until grid.size) {
                    grid[r].clear()
                }
                cursorRow = 0
                cursorCol = 0
            }
        }
    }

    fun eraseInLine(mode: Int) {
        val grid = activeScreen
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]
        when (mode) {
            0 -> { // From cursor to end of line
                while (line.size > cursorCol) {
                    line.removeAt(line.size - 1)
                }
            }
            1 -> { // From start of line to cursor
                for (idx in 0 until minOf(cursorCol, line.size)) {
                    line[idx] = TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L)
                }
            }
            2 -> { // Entire line
                line.clear()
                cursorCol = 0
            }
        }
    }

    fun insertLines(count: Int) {
        val grid = activeScreen
        if (cursorRow in scrollTop..scrollBottom) {
            repeat(count) {
                if (scrollBottom < grid.size) {
                    grid.removeAt(scrollBottom)
                }
                grid.add(cursorRow, ArrayList())
            }
        }
    }

    fun deleteLines(count: Int) {
        val grid = activeScreen
        if (cursorRow in scrollTop..scrollBottom) {
            repeat(count) {
                if (cursorRow < grid.size) {
                    grid.removeAt(cursorRow)
                }
                grid.add(minOf(scrollBottom, grid.size), ArrayList())
            }
        }
    }

    fun deleteChars(count: Int) {
        val grid = activeScreen
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]
        repeat(count) {
            if (cursorCol < line.size) {
                line.removeAt(cursorCol)
            }
        }
    }

    fun insertChars(count: Int) {
        val grid = activeScreen
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]
        repeat(count) {
            if (cursorCol <= line.size) {
                line.add(cursorCol, TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L))
                if (line.size > cols) line.removeAt(line.size - 1)
            }
        }
    }

    fun eraseChars(count: Int) {
        val grid = activeScreen
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]
        for (i in 0 until count) {
            val idx = cursorCol + i
            if (idx < line.size) {
                line[idx] = TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L)
            }
        }
    }

    private fun ensureRowCapacity(grid: ArrayList<ArrayList<TerminalChar>>, targetRow: Int) {
        while (grid.size <= targetRow) {
            grid.add(ArrayList())
        }
    }
}
