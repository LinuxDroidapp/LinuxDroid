package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallationLifecycleStateTest {

    @Test
    fun `rootfs states represent clean lifecycle separation`() {
        assertThat(RootfsState.ROOTFS_NOT_INSTALLED.isNotInstalled).isTrue()
        assertThat(RootfsState.ROOTFS_NOT_INSTALLED.isReady).isFalse()
        assertThat(RootfsState.ROOTFS_INSTALLING.isInstalling).isTrue()
        assertThat(RootfsState.ROOTFS_INSTALLING.isReady).isFalse()
        assertThat(RootfsState.ROOTFS_READY.isReady).isTrue()
        assertThat(RootfsState.ROOTFS_READY.isInstalling).isFalse()
    }

    @Test
    fun `gui install states represent clean layer separation`() {
        assertThat(GuiInstallState.GUI_NOT_INSTALLED.isNotInstalled).isTrue()
        assertThat(GuiInstallState.GUI_NOT_INSTALLED.isReady).isFalse()
        assertThat(GuiInstallState.GUI_INSTALLING.isInstalling).isTrue()
        assertThat(GuiInstallState.GUI_READY.isReady).isTrue()
    }

    @Test
    fun `session lifecycle states represent session execution separation`() {
        assertThat(SessionLifecycleState.SESSION_STOPPED.isStopped).isTrue()
        assertThat(SessionLifecycleState.SESSION_STOPPED.isRunning).isFalse()
        assertThat(SessionLifecycleState.SESSION_RUNNING.isRunning).isTrue()
        assertThat(SessionLifecycleState.SESSION_BACKGROUND.isRunning).isTrue()
    }

    @Test
    fun `successful rootfs installation results in ROOTFS_READY, GUI_NOT_INSTALLED, SESSION_STOPPED`() {
        val snapshot = EnvironmentLifecycleSnapshot(
            rootfsState = RootfsState.ROOTFS_READY,
            guiState = GuiInstallState.GUI_NOT_INSTALLED,
            sessionState = SessionLifecycleState.SESSION_STOPPED,
        )

        assertThat(snapshot.rootfsState).isEqualTo(RootfsState.ROOTFS_READY)
        assertThat(snapshot.guiState).isEqualTo(GuiInstallState.GUI_NOT_INSTALLED)
        assertThat(snapshot.sessionState).isEqualTo(SessionLifecycleState.SESSION_STOPPED)
        // Home dashboard is shown immediately regardless of GUI state
        assertThat(snapshot.isHomeDashboardReady).isTrue()
    }
}
