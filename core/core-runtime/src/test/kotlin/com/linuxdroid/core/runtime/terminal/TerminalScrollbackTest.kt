package com.linuxdroid.core.runtime.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalScrollbackTest {

    private fun makeLine(text: String): ArrayList<TerminalChar> {
        val list = ArrayList<TerminalChar>()
        for (c in text) list.add(TerminalChar(c))
        return list
    }

    @Test
    fun `scrollback ring buffer evicts oldest lines when exceeding capacity`() {
        val scrollback = TerminalScrollback(maxLines = 3)

        scrollback.addLine(makeLine("Line 1"))
        scrollback.addLine(makeLine("Line 2"))
        scrollback.addLine(makeLine("Line 3"))
        assertThat(scrollback.size).isEqualTo(3)

        // Adding 4th line evicts Line 1
        scrollback.addLine(makeLine("Line 4"))
        assertThat(scrollback.size).isEqualTo(3)

        val firstLine = scrollback.getLine(0)?.map { it.char }?.joinToString("")
        assertThat(firstLine).isEqualTo("Line 2")
    }

    @Test
    fun `viewport remains stable when user has scrolled up and live output arrives`() {
        val scrollback = TerminalScrollback(maxLines = 100)

        repeat(10) { i ->
            scrollback.addLine(makeLine("Initial Line $i"))
        }

        // User is at bottom initially
        assertThat(scrollback.isAtBottom).isTrue()
        assertThat(scrollback.scrollOffset).isEqualTo(0)

        // User scrolls up by 5 lines
        scrollback.scrollBy(5)
        assertThat(scrollback.scrollOffset).isEqualTo(5)
        assertThat(scrollback.isAtBottom).isFalse()

        // Incoming live output line arrives
        scrollback.addLine(makeLine("New Live Output 1"))

        // Scroll offset should increase to 6 so the user's viewing position remains stable
        assertThat(scrollback.scrollOffset).isEqualTo(6)

        // User snaps back to bottom
        scrollback.scrollToBottom()
        assertThat(scrollback.isAtBottom).isTrue()
        assertThat(scrollback.scrollOffset).isEqualTo(0)
    }
}
