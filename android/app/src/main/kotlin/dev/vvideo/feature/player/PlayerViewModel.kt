package dev.vvideo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.LibraryRepository
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.library.PageResult
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.PagingGate
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.ui.component.pathText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val endReached: Boolean = false,
    val pathLabel: String = "",
    val items: List<Media> = emptyList(),
    val error: Boolean = false,
    val initialId: String = ""
)

/** The queue a viewer pages through: one kind of media of one folder, in its sort order. */
class PlayerViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var connection = Connection()
    private var directoryId = 0L
    private var sort = Sort()
    private var kind = MediaKind.Video
    private val window = PageWindow()
    private val gate = PagingGate()
    private var openJob: Job? = null
    private var pageJob: Job? = null
    private var retryJob: Job? = null

    /** Bumped by every cancellation; replies to an older round are dropped. */
    private var generation = 0L
    private var selectedId = ""

    private fun cancelRequests() {
        generation++
        openJob?.cancel()
        pageJob?.cancel()
        retryJob?.cancel()
        gate.reset()
    }

    fun selected(id: String) {
        selectedId = id
    }

    /** Opens the [kind] queue of folder [directoryId] at media [id]. */
    fun open(connection: Connection, directoryId: Long, sort: Sort, id: String, kind: MediaKind) {
        cancelRequests()
        this.connection = connection
        this.directoryId = directoryId
        this.sort = sort
        this.kind = kind
        selectedId = id
        _state.value = PlayerUiState(initialId = id)
        val photo = kind == MediaKind.Image
        openJob = viewModelScope.launch {
            try {
                val path =
                    runCatching { pathText(repository.path(connection, directoryId)) }.getOrDefault(
                        ""
                    )
                ensureActive()
                _state.value = _state.value.copy(pathLabel = path)
                // A photo whose queue cannot be placed (the folder is being rescanned, or
                // the photo left it) still opens on its own, as it did before it had a queue.
                val page = try {
                    repository.pageAround(connection, directoryId, sort, id, kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!photo) throw e
                    null
                }
                if (page == null || photo && page.items.none { it.id == id }) {
                    window.clear()
                    _state.value =
                        _state.value.copy(items = listOf(repository.media(connection, id)))
                    return@launch
                }
                window.reset(page)
                _state.value = _state.value.copy(items = window.items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = true)
            }
        }
    }

    fun page(edge: PageEdge) {
        if (openJob?.isActive == true) return
        val cursor = if (edge == PageEdge.Previous) window.previous else window.next
        if (cursor.isEmpty() || !gate.begin(edge)) return
        val round = generation
        pageJob = viewModelScope.launch {
            var result = PageResult.Failed
            try {
                val page = repository.page(connection, directoryId, sort, cursor, edge, kind)
                ensureActive()
                if (round != generation) return@launch
                result = if (page.items.isEmpty()) PageResult.Empty else PageResult.Items
                // The server hands out a next cursor with every non-empty page; only an
                // empty answer shows that the last loaded item ends the folder.
                if (page.items.isEmpty()) {
                    if (edge == PageEdge.Next) _state.value = _state.value.copy(endReached = true)
                } else {
                    val tail = window.next
                    if (edge == PageEdge.Previous) window.prepend(page) else window.append(page)
                    _state.value = _state.value.let {
                        it.copy(
                            items = window.items,
                            endReached = it.endReached && window.next == tail
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RevisionChanged) {
                if (round == generation) open(connection, directoryId, sort, selectedId, kind)
            } catch (_: Exception) {
                if (round == generation) _state.value = _state.value.copy(error = true)
            } finally {
                if (round == generation) gate.end(result)?.let(::page)
            }
        }
    }

    /** Fetches media [id] again, for a version that changed since it was listed. */
    fun retry(id: String) {
        retryJob?.cancel()
        val round = generation
        retryJob = viewModelScope.launch {
            try {
                val refreshed = repository.media(connection, id)
                ensureActive()
                if (round == generation) _state.value = _state.value.copy(
                    items = _state.value.items.map { if (it.id == id) refreshed else it },
                    error = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (round == generation) _state.value = _state.value.copy(error = true)
            }
        }
    }

    fun leave() {
        cancelRequests()
        window.clear()
        _state.value = PlayerUiState()
    }
}
