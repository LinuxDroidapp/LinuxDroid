package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * State of a local rootfs archive import and its in-guest setup lifecycle.
 *
 * Sequence:
 * ```
 * NONE
 *   ↓
 * IMPORTING
 *   ↓
 * ROOTFS_IMPORTED (Setup: Required, CLI ready for shell access)
 *   ↓
 * SETUP_RUNNING (in-guest setup-rootfs.sh executing in PRoot)
 *   ↓
 * READY (both CLI and GUI ready, marker /etc/linuxdroid/rootfs-ready verified)
 *   or
 * SETUP_FAILED (setup exited non-zero, CLI remains ready for troubleshooting, retryable)
 * ```
 */
@Serializable
enum class LocalRootfsState(val displayName: String) {
    /** Standard catalog environment or no local import active. */
    NONE("None"),

    /** Archive extraction and payload injection in progress on Android host. */
    IMPORTING("Importing Archive"),

    /** Archive extracted and validated; in-guest setup script has not run yet. */
    ROOTFS_IMPORTED("Rootfs Imported"),

    /** Rootfs imported and ready for in-guest setup execution. */
    SETUP_REQUIRED("Setup Required"),

    /** Setup script (/root/linuxdroid/setup-rootfs.sh) executing inside PRoot Linux environment. */
    SETUP_RUNNING("Running Setup"),

    /** In-guest setup finished successfully; /etc/linuxdroid/rootfs-ready created. */
    READY("Ready"),

    /** In-guest setup failed; rootfs preserved, CLI available for troubleshooting. */
    SETUP_FAILED("Setup Failed");

    /** True if archive is extracted and rootfs directory is usable. */
    val isImported: Boolean get() = this != NONE && this != IMPORTING

    /** True if in-guest setup succeeded and full CLI & GUI are ready. */
    val isReady: Boolean get() = this == READY

    /** True if in-guest setup needs to run or be retried. */
    val isSetupPending: Boolean get() = this == ROOTFS_IMPORTED || this == SETUP_REQUIRED || this == SETUP_FAILED

    /** True if import or setup operation is actively in progress. */
    val isInProgress: Boolean get() = this == IMPORTING || this == SETUP_RUNNING

    companion object {
        fun fromString(value: String?): LocalRootfsState {
            if (value.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NONE
        }
    }
}

