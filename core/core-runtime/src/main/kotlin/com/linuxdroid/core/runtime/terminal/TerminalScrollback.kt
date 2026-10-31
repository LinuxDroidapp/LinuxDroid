package com.linuxdroid.core.runtime.terminal

/**
 * Ring-buffer backed scrollback history manager with macOS Terminal-style stable viewport navigation.
 */
class TerminalScrollback(
    val maxLines: Int = 2000,
) {
    private val buffer = ArrayList<ArrayList<TerminalChar>>(maxLines)

    /**
     * Viewport scroll offset from bottom (0 = following live output).
     */
    var scrollOffset: Int = 0
        private set

    val isAtBottom: Boolean
        get() = scrollOffset == 0

    val size: Int
        get() = buffer.size

    val lines: List<List<TerminalChar>>
        get() = buffer

    @Synchronized
    fun addLine(line: ArrayList<TerminalChar>) {
        if (buffer.size >= maxLines) {
            buffer.removeAt(0)
            // If user has scrolled up, adjust scroll offset so their viewport stays stable
            if (scrollOffset > 0) {
                scrollOffset = maxOf(0, scrollOffset - 1)
            }
        }
        buffer.add(line)

        // If user was scrolled up, incoming live output increments distance from bottom
        if (scrollOffset > 0) {
            scrollOffset = minOf(buffer.size, scrollOffset + 1)
        }
    }

    @Synchronized
    fun scrollBy(delta: Int) {
        scrollOffset = (scrollOffset + delta).coerceIn(0, buffer.size)
    }

    @Synchronized
    fun scrollToBottom() {
        scrollOffset = 0
    }

    @Synchronized
    fun scrollToTop() {
        scrollOffset = buffer.size
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        scrollOffset = 0
    }

    @Synchronized
    fun getLine(index: Int): List<TerminalChar>? {
        return if (index in buffer.indices) buffer[index] else null
    }
}
