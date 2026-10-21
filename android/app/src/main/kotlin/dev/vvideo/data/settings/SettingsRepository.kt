package dev.vvideo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("connection")

// Stored key names; changing one loses what users already saved under it.
private val ADDRESS_KEY = stringPreferencesKey("address")
private val TOKEN_KEY = stringPreferencesKey("token")
private val BUDGET_KEY = longPreferencesKey("budget")
private val THEME_KEY = stringPreferencesKey("theme")
private val LAST_DIRECTORY_KEY = longPreferencesKey("recent")

class SettingsRepository(private val context: Context) {
    val theme: Flow<ThemeMode> = context.settings.data.map { ThemeMode.fromKey(it[THEME_KEY]) }

    suspend fun setTheme(mode: ThemeMode) {
        context.settings.edit { it[THEME_KEY] = mode.key }
    }

    val connection: Flow<Connection> = context.settings.data.map {
        Connection(
            address = it[ADDRESS_KEY].orEmpty(),
            token = it[TOKEN_KEY].orEmpty(),
            budget = it[BUDGET_KEY] ?: DEFAULT_CACHE_BUDGET,
            lastDirectory = it[LAST_DIRECTORY_KEY] ?: 0L
        )
    }

    /** A connection to try before saving it; throws for an invalid address or a blank token. */
    fun candidate(url: String, token: String): Connection {
        val canonical = ConnectionPolicy.canonical(url)
        require(token.isNotBlank())
        return Connection(canonical, token)
    }

    suspend fun connect(url: String, token: String) {
        val canonical = ConnectionPolicy.canonical(url)
        require(token.isNotBlank())
        context.settings.edit {
            if (it[ADDRESS_KEY] != canonical) it.remove(LAST_DIRECTORY_KEY)
            it[ADDRESS_KEY] = canonical
            it[TOKEN_KEY] = token
        }
    }

    suspend fun setBudget(bytes: Long) {
        require(bytes in CACHE_BUDGETS)
        context.settings.edit { it[BUDGET_KEY] = bytes }
    }

    /** The folder a cold start reopens; 0 opens the library list. */
    suspend fun setLastDirectory(directoryId: Long) {
        context.settings.edit { it[LAST_DIRECTORY_KEY] = directoryId }
    }
}
