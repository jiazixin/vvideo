package dev.vvideo.feature.settings

import androidx.annotation.StringRes
import dev.vvideo.R
import dev.vvideo.model.ThemeMode
import java.util.Locale

private const val GB = 1_000_000_000L
private const val MB = 1_000_000L
private const val KB = 1_000L

/** A cache size in decimal units, as the budgets are offered. */
internal fun formatBytes(value: Long): String = when {
    value >= GB -> String.format(Locale.ROOT, "%.2f GB", value / GB.toDouble())
    value >= MB -> String.format(Locale.ROOT, "%.1f MB", value / MB.toDouble())
    else -> String.format(Locale.ROOT, "%.1f KB", value / KB.toDouble())
}

/** Whole decimal gigabytes of a cache budget. */
internal fun wholeGigabytes(bytes: Long): Int = (bytes / GB).toInt()

@StringRes
internal fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.Saved -> R.string.connection_saved
    ConnectionStatus.Checking -> R.string.connection_checking
    ConnectionStatus.Connected -> R.string.connection_ok
    ConnectionStatus.AuthenticationFailed -> R.string.authentication_failed
    ConnectionStatus.Offline -> R.string.offline
    ConnectionStatus.InvalidAddress -> R.string.invalid_address
    ConnectionStatus.ConnectionFailed -> R.string.error_connection
}

@StringRes
internal fun ThemeMode.label(): Int = when (this) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}
