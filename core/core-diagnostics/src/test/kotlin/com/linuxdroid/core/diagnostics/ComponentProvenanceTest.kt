package com.linuxdroid.core.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentProvenanceTest {

    @Test
    fun testAllSevenComponentsPresentAndPinned() {
        val manager = ComponentProvenanceManager()
        val components = manager.getComponents()

        assertThat(components.keys).containsExactly(
            "PRoot",
            "LDDM",
            "LDDE",
            "Wayland",
            "Weston",
            "wayland-protocols",
            "pixman"
        )

        val proot = manager.getComponent("PRoot")
        assertThat(proot.repository).isEqualTo("LinuxDroidapp/proot")
        assertThat(proot.revision).isEqualTo("378aefaac7b62944243fc8d10fc78ba2a5372844")

        val lddm = manager.getComponent("LDDM")
        assertThat(lddm.repository).isEqualTo("LinuxDroidapp/LDDM")
        assertThat(lddm.revision).isEqualTo("aa6c3d38f874244bcd60162889a914637e4ddf46")

        val ldde = manager.getComponent("LDDE")
        assertThat(ldde.repository).isEqualTo("LinuxDroidapp/LDDE")
        assertThat(ldde.revision).isEqualTo("9ee575e963d6d1ff4086fc16fb119daf6ead6db2")

        val wayland = manager.getComponent("Wayland")
        assertThat(wayland.repository).isEqualTo("LinuxDroidapp/wayland")
        assertThat(wayland.revision).isEqualTo("381af21cf84f13be0ca24aed756a9cded3290d49")

        val weston = manager.getComponent("Weston")
        assertThat(weston.repository).isEqualTo("LinuxDroidapp/weston")
        assertThat(weston.revision).isEqualTo("9669073fe8f411ef3e9f40a36d0ec9aa68362fa2")

        val protocols = manager.getComponent("wayland-protocols")
        assertThat(protocols.repository).isEqualTo("LinuxDroidapp/wayland-protocols")
        assertThat(protocols.revision).isEqualTo("afb614d5fcbd02d261a6ae91920aa91cf3915a8a")

        val pixman = manager.getComponent("pixman")
        assertThat(pixman.repository).isEqualTo("LinuxDroidapp/pixman")
        assertThat(pixman.revision).isEqualTo("cc03b56c7b2b2e06199bb9b115af55f5b42b12ba")
    }

    @Test
    fun testFormatComponentsBlockMatchesSpecification() {
        val manager = ComponentProvenanceManager()
        val formatted = manager.formatComponentsBlock()

        val expected = """
=== LINUXDROID COMPONENTS ===
PRoot:            LinuxDroidapp/proot@378aefaac7b62944243fc8d10fc78ba2a5372844
LDDM:             LinuxDroidapp/LDDM@aa6c3d38f874244bcd60162889a914637e4ddf46
LDDE:             LinuxDroidapp/LDDE@9ee575e963d6d1ff4086fc16fb119daf6ead6db2
Wayland:          LinuxDroidapp/wayland@381af21cf84f13be0ca24aed756a9cded3290d49
Weston:           LinuxDroidapp/weston@9669073fe8f411ef3e9f40a36d0ec9aa68362fa2
wayland-protocols: LinuxDroidapp/wayland-protocols@afb614d5fcbd02d261a6ae91920aa91cf3915a8a
pixman:           LinuxDroidapp/pixman@cc03b56c7b2b2e06199bb9b115af55f5b42b12ba
""".trimIndent()

        assertThat(formatted.trim()).isEqualTo(expected.trim())
    }
}
