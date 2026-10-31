package com.linuxdroid.core.runtime.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class TerminalInputControllerTest {

    private val sentBytes = mutableListOf<Byte>()
    private lateinit var controller: TerminalInputController

    @Before
    fun setUp() {
        sentBytes.clear()
        controller = TerminalInputController { bytes ->
            for (b in bytes) sentBytes.add(b)
        }
    }

    private fun getSentString(): String = String(sentBytes.toByteArray(), Charsets.UTF_8)

    @Test
    fun `unmodified arrows send standard CSI sequences`() {
        controller.isApplicationCursorKeys = false
        controller.sendArrowUp()
        assertThat(getSentString()).isEqualTo("\u001B[A")

        sentBytes.clear()
        controller.sendArrowDown()
        assertThat(getSentString()).isEqualTo("\u001B[B")
    }

    @Test
    fun `unmodified arrows with DECCKM send SS3 sequences`() {
        controller.isApplicationCursorKeys = true
        controller.sendArrowDown()
        assertThat(getSentString()).isEqualTo("\u001BOB")

        sentBytes.clear()
        controller.sendArrowUp()
        assertThat(getSentString()).isEqualTo("\u001BOA")
    }

    @Test
    fun `Shift plus Arrow Down sends exact xterm modified sequence for Antigravity`() {
        // Antigravity shortcut Shift + Arrow Down
        controller.isApplicationCursorKeys = false
        controller.sendArrowDown(shift = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;2B")

        // Must still send CSI 1;2B even if DECCKM is active
        sentBytes.clear()
        controller.isApplicationCursorKeys = true
        controller.sendArrowDown(shift = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;2B")
    }

    @Test
    fun `various modifier combinations on arrows send correct parameters`() {
        // Alt + Arrow Up -> mod 3
        controller.sendArrowUp(alt = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;3A")

        // Ctrl + Arrow Down -> mod 5
        sentBytes.clear()
        controller.sendArrowDown(ctrl = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;5B")

        // Shift + Ctrl + Arrow Right -> mod 6
        sentBytes.clear()
        controller.sendArrowRight(shift = true, ctrl = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;6C")

        // Shift + Alt + Ctrl + Arrow Left -> mod 8
        sentBytes.clear()
        controller.sendArrowLeft(shift = true, alt = true, ctrl = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;8D")
    }

    @Test
    fun `navigation keys with modifiers send correct sequences`() {
        // Shift + Home -> \x1b[1;2H
        controller.sendHome(shift = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;2H")

        // Ctrl + End -> \x1b[1;5F
        sentBytes.clear()
        controller.sendEnd(ctrl = true)
        assertThat(getSentString()).isEqualTo("\u001B[1;5F")

        // Shift + PageDown -> \x1b[6;2~
        sentBytes.clear()
        controller.sendPageDown(shift = true)
        assertThat(getSentString()).isEqualTo("\u001B[6;2~")

        // Ctrl + PageUp -> \x1b[5;5~
        sentBytes.clear()
        controller.sendPageUp(ctrl = true)
        assertThat(getSentString()).isEqualTo("\u001B[5;5~")
    }

    @Test
    fun `Tab and Backtab handling`() {
        // Plain Tab
        controller.sendTab(shift = false)
        assertThat(sentBytes).containsExactly(0x09.toByte())

        // Shift + Tab sends Backtab sequence
        sentBytes.clear()
        controller.sendTab(shift = true)
        assertThat(getSentString()).isEqualTo("\u001B[Z")
    }

    @Test
    fun `bracketed paste wraps text with 200 and 201 sequences`() {
        controller.isBracketedPasteEnabled = true
        controller.pasteText("echo 'hello'\n")
        assertThat(getSentString()).isEqualTo("\u001B[200~echo 'hello'\r\u001B[201~")
    }

    @Test
    fun `diagnostic logging redacts paste content while logging key types`() {
        val logs = mutableListOf<String>()
        controller.isDiagnosticLoggingEnabled = true
        controller.diagnosticLogger = { logs.add(it) }

        controller.sendArrowDown(shift = true)
        assertThat(logs).isNotEmpty()
        assertThat(logs.last()).contains("Shift+DOWN")
        assertThat(logs.last()).contains("\\e[1;2B")

        controller.pasteText("superSecretPassword123")
        assertThat(logs.last()).contains("<text>")
        assertThat(logs.last()).doesNotContain("superSecretPassword123")
    }
}
