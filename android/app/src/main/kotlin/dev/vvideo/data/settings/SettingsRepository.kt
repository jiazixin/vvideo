package dev.vvideo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import java.io.IOException

private const val SETTINGS_RETRY_DELAY_MS = 5000L

private val Context.settings by preferencesDataStore("connection")

// Stored key names; changing one loses what users already saved under it.
private val ADDRESS_KEY = stringPreferencesKey("address")

// The token is stored encrypted; see TokenCipher.
private val TOKEN_KEY = stringPreferencesKey("token_sealed")
private val BUDGET_KEY = longPreferencesKey("budget")
private val THEME_KEY = stringPreferencesKey("theme")
private val LAST_DIRECTORY_KEY = longPreferencesKey("recent")

/** Settings in DataStore; Keystore work runs on [io]. */
internal class SettingsRepository(private val context: Context, private val io: CoroutineDispatcher) : SettingsData {
    private val cipher = TokenCipher()

    // Keep collectors alive across a temporary filesystem failure. Retain the last
    // successful value instead of replacing saved credentials with empty defaults.
    private val preferences = context.settings.data.retryWhen { cause, _ ->
        if (cause !is IOException) return@retryWhen false
        delay(SETTINGS_RETRY_DELAY_MS)
        true
    }

    override val theme: Flow<ThemeMode> = preferences.map { ThemeMode.fromKey(it[THEME_KEY]) }

    override suspend fun setTheme(mode: ThemeMode) {
        context.settings.edit { it[THEME_KEY] = mode.key }
    }

    override val connection: Flow<Connection> = preferences.map {
        Connection(
            address = it[ADDRESS_KEY].orEmpty(),
            token = cipher.decrypt(it[TOKEN_KEY].orEmpty()),
            budget = it[BUDGET_KEY] ?: DEFAULT_CACHE_BUDGET,
            lastDirectory = it[LAST_DIRECTORY_KEY] ?: 0L,
        )
    }.retryWhen { cause, _ ->
        // Keystore reads may also fail temporarily after DataStore emitted.
        if (cause !is IOException) return@retryWhen false
        delay(SETTINGS_RETRY_DELAY_MS)
        true
    }.flowOn(io)

    /** A connection to try before saving it; throws for an invalid address or a blank token. */
    override fun candidate(url: String, token: String): Connection {
        val canonical = ConnectionPolicy.canonical(url)
        require(TokenPolicy.valid(token)) { "invalid bearer token" }
        return Connection(canonical, token)
    }

    override suspend fun connect(url: String, token: String) = withContext(io) {
        val canonical = ConnectionPolicy.canonical(url)
        require(TokenPolicy.valid(token)) { "invalid bearer token" }
        context.settings.edit {
            if (it[ADDRESS_KEY] != canonical) it.remove(LAST_DIRECTORY_KEY)
            it[ADDRESS_KEY] = canonical
            it[TOKEN_KEY] = cipher.encrypt(token)
        }
        Unit
    }

    override suspend fun setBudget(bytes: Long) {
        require(bytes in CACHE_BUDGETS)
        context.settings.edit { it[BUDGET_KEY] = bytes }
    }

    /** The folder a cold start reopens; 0 opens the library list. */
    override suspend fun setLastDirectory(directoryId: Long) {
        context.settings.edit { it[LAST_DIRECTORY_KEY] = directoryId }
    }
}
