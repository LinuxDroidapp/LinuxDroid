package com.linuxdroid.core.runtime.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalSelectionTest {

    @Test
    fun `word boundary detection identifies words delimited by symbols`() {
        val line = "git commit -m 'initial commit'"

        // Inside 'commit' (index 4..10)
        val (start1, end1) = TerminalSelectionExtractor.findWordBoundary(line, 6)
        assertThat(line.substring(start1, end1)).isEqualTo("commit")

        // Inside 'git'
        val (start2, end2) = TerminalSelectionExtractor.findWordBoundary(line, 1)
        assertThat(line.substring(start2, end2)).isEqualTo("git")
    }

    @Test
    fun `linear text selection extracts spans across multiple lines`() {
        val lines = listOf(
            TerminalLineData.simple("First line of terminal"),
            TerminalLineData.simple("Second line of terminal"),
            TerminalLineData.simple("Third line of terminal"),
        )

        val selection = TerminalSelection(
            start = TerminalPosition(row = 0, col = 6),
            end = TerminalPosition(row = 1, col = 11)
        )

        val text = TerminalSelectionExtractor.extractText(lines, selection)
        assertThat(text).isEqualTo("line of terminal\nSecond line")
    }

    @Test
    fun `soft-wrapped lines reassemble without artificial newlines`() {
        val lines = listOf(
            TerminalLineData(listOf(StyledSpan("https://github.com/LinuxDroidapp/LinuxDroid/blob/main/README")), isWrapped = true),
            TerminalLineData(listOf(StyledSpan(".md - documentation")), isWrapped = false)
        )

        val selection = TerminalSelection(
            start = TerminalPosition(0, 0),
            end = TerminalPosition(1, 3)
        )

        val text = TerminalSelectionExtractor.extractText(lines, selection)
        // Since line 0 is marked isWrapped = true, it should concatenate without \n
        assertThat(text).isEqualTo("https://github.com/LinuxDroidapp/LinuxDroid/blob/main/README.md")
    }

    @Test
    fun `block selection extracts rectangular column region`() {
        val lines = listOf(
            TerminalLineData.simple("ABCDEF"),
            TerminalLineData.simple("123456"),
            TerminalLineData.simple("UVWXYZ"),
        )

        val selection = TerminalSelection(
            start = TerminalPosition(row = 0, col = 2),
            end = TerminalPosition(row = 2, col = 4),
            isBlockSelection = true
        )

        val text = TerminalSelectionExtractor.extractText(lines, selection)
        assertThat(text).isEqualTo("CDE\n345\nWXY")
    }
}
