package dev.vvideo.feature.library

import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.theme.LocalAnimations
import dev.vvideo.ui.theme.PageMotion
import dev.vvideo.ui.theme.pageSlide
import kotlinx.coroutines.FlowPreview

@OptIn(FlowPreview::class, ExperimentalActivityApi::class)
@Composable
internal fun LibraryRoute(
    vm: LibraryViewModel,
    connection: Connection,
    loader: ImageLoader,
    onSettings: () -> Unit,
    onMedia: (Media) -> Unit,
    onInteraction: (Boolean) -> Unit,
    onDetails: (Media) -> Unit = {},
    onConnection: () -> Unit = onSettings,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val persistenceMessage = stringResource(R.string.bookmark_persistence_failed)
    LaunchedEffect(state.persistenceFailed) {
        if (state.persistenceFailed) {
            snackbar.showSnackbar(persistenceMessage)
            vm.persistenceNoticeShown()
        }
    }
    var predictive by remember { mutableFloatStateOf(0f) }
    val animations = LocalAnimations.current
    PredictiveBackHandler(state.directory != null) { events ->
        try {
            events.collect { predictive = it.progress }
            vm.back()
        } finally {
            predictive = 0f
        }
    }
    val owner = LocalLifecycleOwner.current
    // Draw order of the two pages in a transition: a page entered by going back
    // lies beneath the one it replaces. Only the latest pair is retained.
    val layers = remember { mutableMapOf<Long?, Float>() }
    // Direction of the latest directory change, taken while the page being left is
    // still known; the transition itself is driven by the directory id alone.
    val shown = remember {
        object {
            var id: Long? = null
            var parentId: Long? = null
            var returning = false
        }
    }
    state.directory.let { directory ->
        if (directory?.id != shown.id) {
            shown.returning = directory == null || shown.parentId == directory.id
            shown.id = directory?.id
        }
        shown.parentId = directory?.parentId
    }
    LaunchedEffect(connection.id, connection.token) {
        vm.connect(connection)
        vm.resume()
    }
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flush() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            vm.flush()
            onInteraction(false)
            owner.lifecycle.removeObserver(observer)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // AnimatedContent discards the page being left when its target changes during
        // the transition, so the target is the directory id and never the whole state.
        AnimatedContent(
            state.directory?.id,
            modifier = Modifier.graphicsLayer { translationX = size.width * predictive },
            transitionSpec = {
                val returning = shown.returning
                val durationMs = when {
                    !animations -> 0
                    returning -> PageMotion.RETURN
                    else -> PageMotion.ENTER
                }
                val from = initialState
                val to = targetState
                val layer = if (from == to) {
                    layers[to] ?: 0f
                } else {
                    (layers[from] ?: 0f) + if (returning) -1f else 1f
                }
                if (from != to) {
                    layers.keys.retainAll(setOf(from))
                    layers[to] = layer
                }
                pageSlide(returning, durationMs).apply { targetContentZIndex = layer }
            },
            label = "directory",
        ) { id ->
            // The current page follows live state and the list that restoration,
            // pagination and viewport persistence observe; a page being left keeps
            // the last state and list it showed for the bounded transition. A page
            // returned to before it was disposed takes the current list again.
            val page = remember {
                object {
                    var latest = state
                }
            }
            if (state.directory?.id == id) {
                page.latest = state
            }
            val screen = page.latest
            LibraryScreen(
                state = screen.copy(active = state.directory?.id == id),
                thumbnail = { item, label, loaded -> Thumbnail(item, connection, loader, label, loaded) },
                onViewport = vm::reportViewport,
                onResize = vm::resume,
                onInteraction = onInteraction,
                onPage = vm::page,
                onSubfolderPage = vm::moreSubfolders,
                onSettings = onSettings,
                onBack = vm::back,
                onOpen = vm::openDirectory,
                onSort = vm::sort,
                onRetry = {
                    screen.directory?.let { vm.open(it.id, refresh = true, filter = screen.filter) }
                        ?: vm.connect(connection.copy(lastDirectory = 0), refresh = true)
                },
                onMedia = onMedia,
                onFilter = vm::setFilter,
                onJump = vm::jumpToStart,
                onDetails = onDetails,
                onConnection = onConnection,
                onAnchorNotice = vm::anchorNoticeShown,
                onSearch = vm::search,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun LibraryViewModel.openDirectory(id: Long) = open(id)
