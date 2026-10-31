package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Status of the official Antigravity CLI installation and execution.
 */
enum class AntigravityStatus {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    CHECKING_FOR_UPDATES,
    UPDATING,
    LAUNCHING,
    RUNNING,
    FAILED;

    val isBusy: Boolean
        get() = this in setOf(INSTALLING, CHECKING_FOR_UPDATES, UPDATING, LAUNCHING)
}

/**
 * Explicit 14-state machine for Antigravity background launch, OAuth automation,
 * Remote Control detection, and WebView presentation.
 */
enum class AntigravityAuthState {
    IDLE,
    STARTING,
    DETECTING_LOGIN,
    SELECTING_GOOGLE_OAUTH,
    WAITING_FOR_BROWSER_AUTH,
    WAITING_FOR_AUTH_CODE,
    SUBMITTING_AUTH_CODE,
    AUTHENTICATING,
    WAITING_FOR_REMOTE_URL,
    CONNECTING_WEBVIEW,
    CONNECTED,
    MINIMIZED,
    ERROR,
    CANCELLED,
    STOPPING;

    val isInAuthFlow: Boolean
        get() = this in setOf(
            STARTING,
            DETECTING_LOGIN,
            SELECTING_GOOGLE_OAUTH,
            WAITING_FOR_BROWSER_AUTH,
            WAITING_FOR_AUTH_CODE,
            SUBMITTING_AUTH_CODE,
            AUTHENTICATING,
            WAITING_FOR_REMOTE_URL
        )

    val isConnecting: Boolean
        get() = this in setOf(
            STARTING,
            DETECTING_LOGIN,
            SELECTING_GOOGLE_OAUTH,
            WAITING_FOR_BROWSER_AUTH,
            WAITING_FOR_AUTH_CODE,
            SUBMITTING_AUTH_CODE,
            AUTHENTICATING,
            WAITING_FOR_REMOTE_URL,
            CONNECTING_WEBVIEW
        )

    val isReady: Boolean
        get() = this == CONNECTED || this == MINIMIZED
}

/**
 * Comprehensive state representation of the Antigravity CLI integration.
 */
@Serializable
data class AntigravityState(
    val status: AntigravityStatus = AntigravityStatus.NOT_INSTALLED,
    val authState: AntigravityAuthState = AntigravityAuthState.IDLE,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val progress: Float = 0f,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastCheckedTimestamp: Long = 0L,
    val authUrl: String? = null,
    val remoteControlUrl: String? = null,
    val isRemoteControlReady: Boolean = false,
    val hasSavedSession: Boolean = false,
) {
    val canInstall: Boolean
        get() = status == AntigravityStatus.NOT_INSTALLED || status == AntigravityStatus.FAILED

    val canLaunch: Boolean
        get() = (status == AntigravityStatus.INSTALLED || status == AntigravityStatus.FAILED) &&
                (authState == AntigravityAuthState.IDLE || authState == AntigravityAuthState.ERROR || authState == AntigravityAuthState.CANCELLED)

    val isRunning: Boolean
        get() = status == AntigravityStatus.RUNNING || authState.isConnecting || authState.isReady
}

/**
 * Official manifest payload schema for Antigravity CLI releases.
 */
@Serializable
data class AntigravityManifest(
    val version: String,
    val url: String,
    val sha512: String,
)
