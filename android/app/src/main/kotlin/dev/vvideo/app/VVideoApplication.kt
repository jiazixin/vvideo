package dev.vvideo.app

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import dev.vvideo.data.cache.CacheCoordinator
import dev.vvideo.data.library.LibraryRepository
import dev.vvideo.data.library.local.BrowseDatabase
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's long-lived objects, created once per process. */
@UnstableApi
class AppGraph(val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(application)
    val api = LibraryApi()
    val database = Room.databaseBuilder(application, BrowseDatabase::class.java, "browse.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true).build()
    val cache = CacheCoordinator(application)
    val library = LibraryRepository(api, database.bookmarks(), settings)

    suspend fun initialize() {
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
        cache.configure(settings.connection.first().budget)
    }
}

@UnstableApi
class VVideoApplication : Application() {
    val graph by lazy { AppGraph(this) }
    val ready = MutableStateFlow(false)
    val failure = MutableStateFlow(false)
    private var initializing = false
    fun initialize() {
        if (initializing) return
        initializing = true
        failure.value = false
        graph.scope.launch {
            try {
                graph.initialize()
                ready.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure.value = true
            } finally {
                initializing = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initialize()
    }
}
