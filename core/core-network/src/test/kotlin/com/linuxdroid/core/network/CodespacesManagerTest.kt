package com.linuxdroid.core.network

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.CodespaceItem
import com.linuxdroid.core.model.CodespaceState
import org.junit.Test

class CodespacesManagerTest {

    private val manager = CodespacesManager(authManager = null)

    @Test
    fun `parseCodespacesResponse accurately maps JSON array into domain objects`() {
        val sampleJson = """
        {
          "total_count": 2,
          "codespaces": [
            {
              "id": 101,
              "name": "silver-space-cod",
              "display_name": "Antigravity Dev",
              "repository": {
                "name": "LinuxDroid",
                "full_name": "LinuxDroidapp/LinuxDroid"
              },
              "git_status": {
                "ref": "main"
              },
              "state": "Available",
              "machine": {
                "name": "standardLinux32gb",
                "display_name": "4 cores, 16 GB RAM"
              },
              "web_url": "https://silver-space-cod.github.dev",
              "last_used_at": "2026-10-24T06:00:00Z",
              "idle_timeout_minutes": 30
            },
            {
              "id": 102,
              "name": "crispy-chainsaw",
              "display_name": null,
              "repository": {
                "name": "proot",
                "full_name": "LinuxDroidapp/proot"
              },
              "git_status": {
                "ref": "feature-pty"
              },
              "state": "Stopped",
              "machine": {
                "name": "standardLinux16gb",
                "display_name": "2 cores, 8 GB RAM"
              },
              "web_url": "https://crispy-chainsaw.github.dev",
              "last_used_at": "2026-10-23T12:00:00Z",
              "idle_timeout_minutes": 15
            }
          ]
        }
        """.trimIndent()

        val items = manager.parseCodespacesResponse(sampleJson)
        assertThat(items).hasSize(2)

        val first = items[0]
        assertThat(first.id).isEqualTo(101L)
        assertThat(first.name).isEqualTo("silver-space-cod")
        assertThat(first.displayName).isEqualTo("Antigravity Dev")
        assertThat(first.repository).isEqualTo("LinuxDroidapp/LinuxDroid")
        assertThat(first.branch).isEqualTo("main")
        assertThat(first.state).isEqualTo(CodespaceState.AVAILABLE)
        assertThat(first.state.isRunning).isTrue()
        assertThat(first.machineDisplayName).isEqualTo("4 cores, 16 GB RAM")
        assertThat(first.webUrl).isEqualTo("https://silver-space-cod.github.dev")

        val second = items[1]
        assertThat(second.id).isEqualTo(102L)
        assertThat(second.name).isEqualTo("crispy-chainsaw")
        assertThat(second.displayName).isNull()
        assertThat(second.repository).isEqualTo("LinuxDroidapp/proot")
        assertThat(second.state).isEqualTo(CodespaceState.STOPPED)
        assertThat(second.state.isRunning).isFalse()
    }

    @Test
    fun `buildTerminalConnectionCommand generates robust command with token and fallback`() {
        val item = CodespaceItem(
            id = 123L,
            name = "ubiquitous-chainsaw-xyz",
            displayName = "My Codespace",
            repository = "user/repo",
            branch = "main",
            state = CodespaceState.AVAILABLE,
            machineDisplayName = "2 cores",
            webUrl = "https://ubiquitous-chainsaw-xyz.github.dev",
            lastUsedAt = null,
            idleTimeoutMinutes = null,
        )

        val command = manager.buildTerminalConnectionCommand(item)
        assertThat(command).contains("ubiquitous-chainsaw-xyz")
        assertThat(command).contains("gh codespace ssh")
        assertThat(command).contains("https://ubiquitous-chainsaw-xyz.github.dev")
    }
}
