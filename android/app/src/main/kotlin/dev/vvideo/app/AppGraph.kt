package dev.vvideo.app

import android.app.Application
import androidx.room.Room
import dev.vvideo.data.cache.CacheCoordinator
import dev.vvideo.data.library.LibraryRepository
import dev.vvideo.data.library.local.BrowseDatabase
import dev.vvideo.data.library.remote.Authorization
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.network.baseHttpClient
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.ui.theme.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val SETTINGS_LOAD_TIMEOUT_MS = 15_000L

/** The app's long-lived objects, created once per process. This is the only place that names `Dispatchers`. */
internal class AppGraph(val application: Application) {
    val dispatchers = AppDispatchers(
        io = Dispatchers.IO,
        default = Dispatchers.Default,
        main = Dispatchers.Main.immediate,
    )
    val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    val settings = SettingsRepository(application, dispatchers.io)

    /** The saved connection, read by the HTTP client to authorize requests for its server. */
    private val saved = settings.connection.stateIn(scope, SharingStarted.Eagerly, null)
    private val http = baseHttpClient(Authorization { saved.value })
    val api = LibraryApi(http, dispatchers.io)
    val database = Room.databaseBuilder(application, BrowseDatabase::class.java, "browse.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true).build()
    val cache = CacheCoordinator(application, http, dispatchers.io, dispatchers.main)
    val library = LibraryRepository(api, database.bookmarks(), settings)

    suspend fun initialize() {
        withContext(dispatchers.io) { database.openHelper.writableDatabase }
        val connection: Connection = withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MS) { saved.filterNotNull().first() }
            ?: throw IOException("settings_read_timeout")
        cache.configure(connection.budget)
    }
}
