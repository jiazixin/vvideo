package dev.vvideo.data.settings

import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Preferences consumed by settings and library state holders. */
internal interface SettingsData {
    val theme: Flow<ThemeMode>
    val connection: Flow<Connection>
    fun candidate(url: String, token: String): Connection
    suspend fun connect(url: String, token: String)
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setBudget(bytes: Long)
    suspend fun setLastDirectory(directoryId: Long)
}
