package dev.vvideo.feature.settings

import dev.vvideo.data.cache.CacheUsage
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.ThemeMode

/** The outcome of the last settings action, shown once next to the control that caused it. */
internal enum class SettingsMessage {
    InvalidAddress,
    ConnectionFailed,
    AuthenticationFailed,
    CacheFailed,
    SaveFailed,
    CacheCleared,
}

/** What the settings page says about the saved connection. */
internal enum class ConnectionStatus {
    Saved,
    Checking,
    Connected,
    AuthenticationFailed,
    Offline,
    InvalidAddress,
    ConnectionFailed,
}

internal data class SettingsUiState(
    val connection: Connection = Connection(),
    val theme: ThemeMode = ThemeMode.System,
    val version: String = "",
    val libraryCount: Int = 0,
    val releasedBytes: Long = 0,
    val busy: Boolean = false,
    val message: SettingsMessage? = null,
    val usage: CacheUsage? = null,
    /** Counts successful connections, so the form can close on the next one. */
    val connectedSerial: Long = 0,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Saved,
)
