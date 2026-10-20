package dev.vvideo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.PagingGate
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlayerUiState(
    val endReached: Boolean = false,
    val pathLabel: String = "",
    val items: List<Media> = emptyList(),
    val error: Boolean = false,
    val initialId: String = ""
)

class PlayerViewModel(private val api: LibraryApi) : ViewModel() {
    private val mutable = MutableStateFlow(PlayerUiState())
    val state = mutable.asStateFlow()
    private var connection = Connection()
    private var directory = 0L
    private var sort = Sort()
    private var kind = "video"
    private val window = PageWindow()
    private val gate = PagingGate()
    private var request: Job? = null
    private var pageRequest: Job? = null
    private var retryRequest: Job? = null
    private var generation = 0L
    private var selectedId = ""
    private fun cancelRequests() {
        generation++
        request?.cancel()
        pageRequest?.cancel()
        retryRequest?.cancel()
        gate.reset()
    }

    fun selected(id: String) {
        selectedId = id
    }

    fun open(c: Connection, dir: Long, order: Sort, id: String, photo: Boolean = false) {
        cancelRequests()
        connection = c
        directory = dir
        sort = order
        selectedId = id
        kind = if (photo) "image" else "video"
        mutable.value = PlayerUiState(initialId = id)
        request = viewModelScope.launch {
            try {
                val path = runCatching {
                    api.path(c, dir).joinToString(" › ") { it.name }
                }.getOrDefault("")
                ensureActive()
                mutable.value = mutable.value.copy(pathLabel = path)
                // A photo whose queue cannot be placed (the folder is being rescanned, or
                // the photo left it) still opens on its own, as it did before it had a queue.
                val page = try {
                    api.page(c, dir, order, anchor = id, kind = kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!photo) throw e
                    null
                }
                if (page == null || photo && page.items.none { it.id == id }) {
                    window.clear()
                    mutable.value = mutable.value.copy(items = listOf(api.media(c, id)))
                    return@launch
                }
                window.reset(page)
                mutable.value = mutable.value.copy(items = window.items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = true)
            }
        }
    }

    fun page(previous: Boolean) {
        if (request?.isActive == true) return
        val cursor = if (previous) window.previous else window.next
        if (cursor.isEmpty() || !gate.begin(previous)) return
        val round = generation
        pageRequest = viewModelScope.launch {
            var loaded: Boolean? = null
            try {
                val p = api.page(connection, directory, sort, cursor, previous, kind = kind)
                ensureActive()
                if (round != generation) return@launch
                loaded = p.items.isNotEmpty()
                // The server hands out a next cursor with every non-empty page; only an
                // empty answer shows that the last loaded item ends the folder.
                if (p.items.isEmpty()) {
                    if (!previous) mutable.value = mutable.value.copy(endReached = true)
                } else {
                    val tail = window.next
                    if (previous) window.prepend(p) else window.append(p)
                    mutable.value = mutable.value.let {
                        it.copy(
                            items = window.items,
                            endReached = it.endReached && window.next == tail
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RevisionChanged) {
                if (round == generation) open(
                    connection,
                    directory,
                    sort,
                    selectedId,
                    kind == "image"
                )
            } catch (_: Exception) {
                if (round == generation) mutable.value = mutable.value.copy(error = true)
            } finally {
                if (round == generation) gate.end(loaded)?.let(::page)
            }
        }
    }

    fun retry(id: String) {
        retryRequest?.cancel()
        val round = generation
        retryRequest = viewModelScope.launch {
            try {
                val refreshed = api.media(connection, id)
                ensureActive()
                if (round == generation) mutable.value = mutable.value.copy(
                    items = mutable.value.items.map { if (it.id == id) refreshed else it },
                    error = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (round == generation) mutable.value = mutable.value.copy(error = true)
            }
        }
    }

    fun leave() {
        cancelRequests()
        window.clear()
        mutable.value = PlayerUiState()
    }
}
