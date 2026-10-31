package com.linuxdroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication state with GitHub for Codespaces integration.
 */
enum class CodespaceAuthState {
    SIGNED_OUT,
    DEVICE_CODE_PENDING,
    AUTHENTICATING,
    AUTHENTICATED,
    ERROR;

    val isBusy: Boolean
        get() = this == DEVICE_CODE_PENDING || this == AUTHENTICATING
}

/**
 * RFC 8628 Device Authorization response payload.
 */
@Serializable
data class CodespaceDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int = 900,
    val interval: Int = 5,
)

/**
 * Operational state of a GitHub Codespace.
 */
@Serializable
enum class CodespaceState(val raw: String) {
    AVAILABLE("Available"),
    AWAITING("Awaiting"),
    REBUILDING("Rebuilding"),
    STARTING("Starting"),
    SHUTTING_DOWN("ShuttingDown"),
    STOPPED("Stopped"),
    EXPORTING("Exporting"),
    UPDATING("Updating"),
    FAILED("Failed"),
    UNKNOWN("Unknown");

    val isRunning: Boolean
        get() = this == AVAILABLE

    val isTransitioning: Boolean
        get() = this in setOf(STARTING, AWAITING, REBUILDING, UPDATING, SHUTTING_DOWN)

    companion object {
        fun fromString(value: String?): CodespaceState {
            if (value == null) return UNKNOWN
            return entries.firstOrNull { it.raw.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}

/**
 * Item representing an individual GitHub Codespace instance.
 */
@Serializable
data class CodespaceItem(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val repository: String,
    val branch: String? = null,
    val state: CodespaceState = CodespaceState.UNKNOWN,
    val machineDisplayName: String? = null,
    val webUrl: String = "",
    val lastUsedAt: String? = null,
    val idleTimeoutMinutes: Int? = null,
)

/**
 * Authenticated GitHub User profile info.
 */
@Serializable
data class GitHubUser(
    val login: String,
    val id: Long = 0L,
    val name: String? = null,
    val avatarUrl: String? = null,
)
