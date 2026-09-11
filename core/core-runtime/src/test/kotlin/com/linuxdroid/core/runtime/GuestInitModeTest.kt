package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuestInitModeTest {

    @Test
    fun `GuestInit script contains single canonical branching on StartMode`() {
        val script = GuestInit.SCRIPT_CONTENT

        // Exactly one guest init script with common initialization
        assertThat(script).contains("# /sbin/linuxdroid-init")
        assertThat(script).contains("echo \"[INFO] Guest ready\"")

        // Structured startMode resolution
        assertThat(script).contains("START_MODE=\"\${LINUXDROID_START_MODE:-")
        assertThat(script).contains("echo \"[GUEST-INIT] startMode=\$START_MODE\" >&2")

        // GUI branch
        assertThat(script).contains("echo \"[GUEST-INIT] Handing over to LDDM\" >&2")
        assertThat(script).contains("echo \"[LDDM] Starting graphical session\"")
        assertThat(script).contains("/usr/bin/lddm")
        assertThat(script).doesNotContain("Handing over to GUI workload:")

        // CLI branch
        assertThat(script).contains("clear 2>/dev/null || printf '\\033[H\\033[2J' 2>/dev/null || true")
        assertThat(script).contains("ACTIVE_SHELL=")
        assertThat(script).contains("export SHELL=\"\$ACTIVE_SHELL\"")
        assertThat(script).contains("exec \"\$ACTIVE_SHELL\" -l")

        // Deterministic rejection of invalid startMode
        assertThat(script).contains("Invalid startMode: '\$START_MODE'. Only 'GUI' and 'CLI' are allowed.")
    }

    @Test
    fun `CLI mode clears terminal, executes active shell, and bypasses LDDM completely`() {
        val script = GuestInit.SCRIPT_CONTENT
        val cliSection = script.substringAfter("CLI)").substringBefore(";;")

        // Must clear terminal
        assertThat(cliSection).contains("clear 2>/dev/null || printf '\\033[H\\033[2J' 2>/dev/null || true")

        // Must execute active shell as interactive session
        assertThat(cliSection).contains("exec \"\$ACTIVE_SHELL\" -l")

        // Must never invoke LDDM or GUI component in CLI branch
        assertThat(cliSection).doesNotContain("lddm")
        assertThat(cliSection).doesNotContain("LDDM")
        assertThat(cliSection).doesNotContain("WAYLAND")
    }

    @Test
    fun `GUI mode retains LDDM handover flow unchanged`() {
        val script = GuestInit.SCRIPT_CONTENT
        val guiSection = script.substringAfter("GUI)").substringBefore(";;")

        assertThat(guiSection).contains("echo \"[GUEST-INIT] Handing over to LDDM\" >&2")
        assertThat(guiSection).contains("echo \"[LDDM] Starting graphical session\"")
        assertThat(guiSection).contains("/usr/bin/lddm")
    }
}

