package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Cleanly separated lifecycle states for the LinuxDroid rootfs, GUI, and session subsystems.
 *
 * Requirements:
 * - Rootfs State: ROOTFS_NOT_INSTALLED, ROOTFS_INSTALLING, ROOTFS_READY
 * - GUI State: GUI_NOT_INSTALLED, GUI_INSTALLING, GUI_READY
 * - Session State: SESSION_STOPPED, SESSION_RUNNING, SESSION_BACKGROUND
 *
 * A successful rootfs installation results in:
 * - ROOTFS_READY
 * - GUI_NOT_INSTALLED
 * - SESSION_STOPPED
 *
 * The home screen must then be shown. GUI state must not be required for the rootfs
 * installation screen to disappear.
 */
@Serializable
enum class RootfsState(val displayName: String) {
    ROOTFS_NOT_INSTALLED("Not Installed"),
    ROOTFS_INSTALLING("Installing Rootfs"),
    ROOTFS_READY("Rootfs Ready");

    val isReady: Boolean get() = this == ROOTFS_READY
    val isInstalling: Boolean get() = this == ROOTFS_INSTALLING
    val isNotInstalled: Boolean get() = this == ROOTFS_NOT_INSTALLED
}

@Serializable
enum class GuiInstallState(val displayName: String) {
    GUI_NOT_INSTALLED("Not Installed"),
    GUI_INSTALLING("Installing GUI"),
    GUI_READY("GUI Ready");

    val isReady: Boolean get() = this == GUI_READY
    val isInstalling: Boolean get() = this == GUI_INSTALLING
    val isNotInstalled: Boolean get() = this == GUI_NOT_INSTALLED
}

@Serializable
enum class SessionLifecycleState(val displayName: String) {
    SESSION_STOPPED("Stopped"),
    SESSION_RUNNING("Running"),
    SESSION_BACKGROUND("Background");

    val isRunning: Boolean get() = this == SESSION_RUNNING || this == SESSION_BACKGROUND
    val isStopped: Boolean get() = this == SESSION_STOPPED
}

/**
 * Composite snapshot of the decoupled environment states.
 */
@Serializable
data class EnvironmentLifecycleSnapshot(
    val rootfsState: RootfsState = RootfsState.ROOTFS_NOT_INSTALLED,
    val guiState: GuiInstallState = GuiInstallState.GUI_NOT_INSTALLED,
    val sessionState: SessionLifecycleState = SessionLifecycleState.SESSION_STOPPED,
) {
    /** The home dashboard must be shown whenever rootfs is ready, regardless of GUI state. */
    val isHomeDashboardReady: Boolean get() = rootfsState.isReady
}
