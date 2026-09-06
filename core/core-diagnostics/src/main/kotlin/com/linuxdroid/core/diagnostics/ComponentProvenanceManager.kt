package com.linuxdroid.core.diagnostics

import android.content.Context
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.StackProvenance
import com.linuxdroid.core.model.SubmoduleComponent
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages runtime discovery, auditing, and diagnostic rendering of LinuxDroid core
 * Git submodule components (PRoot, LDDM, LDDE, Wayland, Weston, wayland-protocols, pixman).
 */
class ComponentProvenanceManager(
    private val context: Context? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.DIAGNOSTICS)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val PROOT_COMMIT = "caadcae0e7697ec29f02e231a3a88866561aacd0"
        const val LDDM_COMMIT = "aa6c3d38f874244bcd60162889a914637e4ddf46"
        const val LDDE_COMMIT = "9ee575e963d6d1ff4086fc16fb119daf6ead6db2"
        const val WAYLAND_COMMIT = "381af21cf84f13be0ca24aed756a9cded3290d49"
        const val WESTON_COMMIT = "9669073fe8f411ef3e9f40a36d0ec9aa68362fa2"
        const val WAYLAND_PROTOCOLS_COMMIT = "afb614d5fcbd02d261a6ae91920aa91cf3915a8a"
        const val PIXMAN_COMMIT = "cc03b56c7b2b2e06199bb9b115af55f5b42b12ba"

        val DEFAULT_COMPONENTS: Map<String, SubmoduleComponent> = linkedMapOf(
            "PRoot" to SubmoduleComponent("PRoot", "LinuxDroidapp/proot", "vendor/proot", PROOT_COMMIT),
            "LDDM" to SubmoduleComponent("LDDM", "LinuxDroidapp/LDDM", "vendor/LDDM", LDDM_COMMIT),
            "LDDE" to SubmoduleComponent("LDDE", "LinuxDroidapp/LDDE", "vendor/LDDE", LDDE_COMMIT),
            "Wayland" to SubmoduleComponent("Wayland", "LinuxDroidapp/wayland", "vendor/wayland", WAYLAND_COMMIT),
            "Weston" to SubmoduleComponent("Weston", "LinuxDroidapp/weston", "vendor/weston", WESTON_COMMIT),
            "wayland-protocols" to SubmoduleComponent("wayland-protocols", "LinuxDroidapp/wayland-protocols", "vendor/wayland-protocols", WAYLAND_PROTOCOLS_COMMIT),
            "pixman" to SubmoduleComponent("pixman", "LinuxDroidapp/pixman", "vendor/pixman", PIXMAN_COMMIT),
        )
    }

    private val cachedComponents: Map<String, SubmoduleComponent> by lazy {
        loadComponents()
    }

    fun getComponents(): Map<String, SubmoduleComponent> = cachedComponents

    fun getComponent(name: String): SubmoduleComponent {
        return cachedComponents[name]
            ?: DEFAULT_COMPONENTS[name]
            ?: SubmoduleComponent(name, "LinuxDroidapp/$name", "vendor/$name", "unknown")
    }

    private fun loadComponents(): Map<String, SubmoduleComponent> {
        // 1. Try reading from context assets
        try {
            context?.assets?.open("components_provenance.json")?.use { stream ->
                val text = stream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<StackProvenance>(text)
                if (parsed.components.isNotEmpty()) {
                    return parsed.components.mapValues { (k, v) ->
                        if (v.name.isEmpty()) v.copy(name = k) else v
                    }
                }
            }
        } catch (e: Throwable) {
            log.debug("Assets provenance load skipped: ${e.message}")
        }

        // 2. Try filesystem paths (e.g. during testing or desktop runs)
        for (candidatePath in listOf(
            "app/src/main/assets/components_provenance.json",
            "../app/src/main/assets/components_provenance.json",
            "../../app/src/main/assets/components_provenance.json"
        )) {
            val file = File(candidatePath)
            if (file.exists()) {
                try {
                    val parsed = json.decodeFromString<StackProvenance>(file.readText())
                    if (parsed.components.isNotEmpty()) {
                        return parsed.components.mapValues { (k, v) ->
                            if (v.name.isEmpty()) v.copy(name = k) else v
                        }
                    }
                } catch (e: Throwable) {
                    log.debug("File provenance load error: ${e.message}")
                }
            }
        }

        return DEFAULT_COMPONENTS
    }

    /**
     * Formats the exact LINUXDROID COMPONENTS text block required for diagnostics logs and reports.
     */
    fun formatComponentsBlock(): String {
        val proot = getComponent("PRoot")
        val lddm = getComponent("LDDM")
        val ldde = getComponent("LDDE")
        val wayland = getComponent("Wayland")
        val weston = getComponent("Weston")
        val waylandProtocols = getComponent("wayland-protocols")
        val pixman = getComponent("pixman")

        return buildString {
            appendLine("=== LINUXDROID COMPONENTS ===")
            appendLine("PRoot:            ${proot.repository}@${proot.revision}")
            appendLine("LDDM:             ${lddm.repository}@${lddm.revision}")
            appendLine("LDDE:             ${ldde.repository}@${ldde.revision}")
            appendLine("Wayland:          ${wayland.repository}@${wayland.revision}")
            appendLine("Weston:           ${weston.repository}@${weston.revision}")
            appendLine("wayland-protocols: ${waylandProtocols.repository}@${waylandProtocols.revision}")
            appendLine("pixman:           ${pixman.repository}@${pixman.revision}")
        }
    }
}
