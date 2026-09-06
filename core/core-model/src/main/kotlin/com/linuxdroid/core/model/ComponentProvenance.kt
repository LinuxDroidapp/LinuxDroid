package com.linuxdroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata for a core LinuxDroid native or graphical stack component backed by a Git submodule.
 */
@Serializable
data class SubmoduleComponent(
    val name: String = "",
    val repository: String,
    @SerialName("vendor_path")
    val vendorPath: String,
    val revision: String,
)

/**
 * Top-level provenance registry schema for all core submodules.
 */
@Serializable
data class StackProvenance(
    @SerialName("schema_version")
    val schemaVersion: String = "1.0.0",
    @SerialName("target_abi")
    val targetAbi: String = "arm64-v8a",
    val components: Map<String, SubmoduleComponent> = emptyMap(),
)
