package dev.vvideo.app.navigation

import android.os.SystemClock
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.app.VVideoApplication
import dev.vvideo.data.cache.CacheCoordinator
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.library.LibraryRoute
import dev.vvideo.feature.library.LibraryViewModel
import dev.vvideo.feature.player.MediaDetailsSheet
import dev.vvideo.feature.player.PhotoScreen
import dev.vvideo.feature.player.PlayerRoute
import dev.vvideo.feature.player.PlayerViewModel
import dev.vvideo.feature.player.retainedWhileLeaving
import dev.vvideo.feature.settings.ConnectionForm
import dev.vvideo.feature.settings.SettingsRoute
import dev.vvideo.feature.settings.SettingsViewModel
import dev.vvideo.feature.settings.StorageScreen
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import dev.vvideo.ui.component.TimeLabels
import dev.vvideo.ui.component.pathText
import dev.vvideo.ui.theme.LocalAnimations
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.PageMotion
import dev.vvideo.ui.theme.pageSlide
import kotlinx.coroutines.CancellationException

/** A viewer opens from this scale and closes back to it. */
private const val VIEWER_SCALE = .92f

/** A predictive back gesture dims the page it is leaving by up to this much. */
private const val PREDICTIVE_BACK_DIM = .15f

/** The pages of the app. */
private enum class Route(val isViewer: Boolean = false) {
    Setup,
    Library,
    Settings,
    Storage,
    Connection,
    Video(isViewer = true),
    Photo(isViewer = true)
}

private class NavigationState : ViewModel() {
    var route by mutableStateOf(Route.Library)
}

private class ViewModelFactory<T : ViewModel>(val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

/** A media details sheet opened from an album, with the folder path at the time it opened. */
private class DetailsRequest(val item: Media, val pathLabel: String)

@UnstableApi
@OptIn(ExperimentalActivityApi::class)
@Composable
fun VVideoRoot(application: VVideoApplication, onInteraction: (Boolean) -> Unit) {
    val graph = application.graph
    val ready by application.ready.collectAsStateWithLifecycle()
    val failure by application.failure.collectAsStateWithLifecycle()
    // Null until the stored connection is read, so a configured app never shows
    // the setup form for a frame and a new install never shows anything else.
    val stored by graph.settings.connection.collectAsStateWithLifecycle(initialValue = null)
    val loader by graph.cache.loader.collectAsStateWithLifecycle()
    val navigation: NavigationState = viewModel(factory = ViewModelFactory { NavigationState() })
    val route = navigation.route
    // The media a viewer opens, kept across recreation; the sort is the folder's at the tap.
    var selectionBeganMs by remember { mutableLongStateOf(0L) }
    var mediaId by rememberSaveable { mutableStateOf("") }
    var directoryId by rememberSaveable { mutableLongStateOf(0L) }
    var sortField by rememberSaveable { mutableStateOf(SortField.Capture) }
    var sortDirection by rememberSaveable { mutableStateOf(SortDirection.Ascending) }
    val timeLabels = rememberTimeLabels()
    val library: LibraryViewModel = viewModel(factory = ViewModelFactory {
        LibraryViewModel(graph.library, graph.scope, timeLabels)
    })
    val settings: SettingsViewModel =
        viewModel(factory = ViewModelFactory {
            SettingsViewModel(
                graph.settings,
                graph.cache,
                graph.library
            )
        })
    val player: PlayerViewModel =
        viewModel(factory = ViewModelFactory { PlayerViewModel(graph.library) })
    if (!ready || loader == null || stored == null) {
        InitializingScreen(failure, onRetry = { application.initialize() })
        return
    }
    val imageLoader = checkNotNull(loader)
    val connection = checkNotNull(stored)
    // Without a saved address and token the only page is the setup form.
    val screen = if (connection.configured) route else Route.Setup
    SystemBarAppearance(dark = LocalVVColors.current.isDark || route.isViewer)
    val back = {
        player.leave()
        navigation.route = Route.Library
        onInteraction(false)
    }
    val openViewer = { kind: MediaKind ->
        player.open(connection, directoryId, Sort(sortField, sortDirection), mediaId, kind)
    }
    var details by remember { mutableStateOf<DetailsRequest?>(null) }
    details?.let { request ->
        MediaDetailsSheet(request.item, request.pathLabel) { details = null }
    }
    val settingsBack = { navigation.route = Route.Settings }
    var predictive by remember { mutableFloatStateOf(0f) }
    val animations = LocalAnimations.current
    // Draw order of the two pages in a transition; see routeTransition.
    val layers = remember { mutableMapOf<Route, Float>() }
    PredictiveBackHandler(route != Route.Library && connection.configured) { events ->
        try {
            events.collect { predictive = it.progress }
            if (route == Route.Storage || route == Route.Connection) {
                navigation.route = Route.Settings
            } else {
                back()
            }
        } catch (_: CancellationException) {
        } finally {
            predictive = 0f
        }
    }
    LaunchedEffect(route, connection.id, connection.token, mediaId) {
        if (route.isViewer) openViewer(if (route == Route.Photo) MediaKind.Image else MediaKind.Video)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.graphicsLayer {
                translationX = size.width * predictive
                alpha = 1f - predictive * PREDICTIVE_BACK_DIM
            },
            transitionSpec = { routeTransition(animations, layers) },
            label = "route"
        ) { displayedRoute ->
            val leaving = displayedRoute != screen
            when (displayedRoute) {
                Route.Setup -> ConnectionForm(settings, connection) {
                    navigation.route = Route.Library
                }

                Route.Connection -> ConnectionForm(settings, connection, settingsBack)
                Route.Storage -> StorageScreen(settings, connection, settingsBack)
                Route.Settings -> SettingsRoute(
                    settings,
                    connection,
                    onDone = { navigation.route = Route.Library },
                    onStorage = { navigation.route = Route.Storage },
                    onConnection = { navigation.route = Route.Connection }
                )

                Route.Video -> VideoRoute(
                    player,
                    connection,
                    imageLoader,
                    graph.cache,
                    back,
                    onInteraction,
                    selectionBeganMs,
                    leaving
                )

                Route.Photo -> PhotoRoute(
                    player,
                    connection,
                    imageLoader,
                    back,
                    { openViewer(MediaKind.Image) },
                    leaving
                )

                Route.Library -> LibraryRoute(
                    library,
                    connection,
                    imageLoader,
                    onSettings = {
                        library.flush()
                        navigation.route = Route.Settings
                    },
                    onMedia = { item ->
                        selectionBeganMs = SystemClock.elapsedRealtime()
                        library.flush()
                        directoryId = item.directoryId
                        val sort = library.state.value.sort
                        sortField = sort.field
                        sortDirection = sort.direction
                        mediaId = item.id
                        navigation.route =
                            if (item.kind == MediaKind.Video) Route.Video else Route.Photo
                    },
                    onInteraction = onInteraction,
                    onDetails = {
                        details = DetailsRequest(it, pathText(library.state.value.path))
                    },
                    onConnection = {
                        library.flush()
                        navigation.route = Route.Connection
                    }
                )
            }
        }
    }
}

/** Shown until the app's storage and connection are ready; a failure offers a retry. */
@Composable
private fun InitializingScreen(failure: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failure) {
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.initializing))
            }
        }
    }
}

/** Date labels in the current locale, rebuilt when the configuration changes. */
@Composable
private fun rememberTimeLabels(): TimeLabels {
    val resources = LocalContext.current.resources
    return remember(LocalConfiguration.current) {
        TimeLabels(
            today = resources.getString(R.string.today),
            yesterday = resources.getString(R.string.yesterday),
            datePattern = resources.getString(R.string.date_pattern),
            yearPattern = resources.getString(R.string.date_year_pattern),
            recentPattern = resources.getString(R.string.recent_date_pattern),
            oldPattern = resources.getString(R.string.recent_old_pattern),
            weekdayPattern = resources.getString(R.string.weekday_pattern)
        )
    }
}

/** Light system-bar icons over dark pages; applied after every composition of the caller. */
@Composable
@NonRestartableComposable
private fun SystemBarAppearance(dark: Boolean) {
    val activity = LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
                .apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
        }
    }
}

/**
 * Viewers zoom and fade over the page they open from; other pages slide sideways,
 * and a page returned to comes back from the left.
 *
 * [layers] keeps the draw order of each route relative to the page left: only a page
 * slid back to lies beneath the one leaving it. A viewer and the page behind it both
 * enter on top, so a tap during the viewer's exit reaches the page returned to
 * instead of the leaving player.
 */
private fun AnimatedContentTransitionScope<Route>.routeTransition(
    animations: Boolean,
    layers: MutableMap<Route, Float>
): ContentTransform {
    val viewer = targetState.isViewer || initialState.isViewer
    // Finishing the setup form moves forward into the library.
    val returning = targetState == Route.Library && initialState != Route.Setup ||
            (initialState == Route.Storage || initialState == Route.Connection) && targetState == Route.Settings
    val durationMs = when {
        !animations -> 0
        viewer && returning -> PageMotion.VIEWER_CLOSE
        viewer -> PageMotion.VIEWER_OPEN
        returning -> PageMotion.RETURN
        else -> PageMotion.ENTER
    }
    val layer = if (initialState == targetState) {
        layers[targetState] ?: 0f
    } else {
        (layers[initialState] ?: 0f) + if (returning && !viewer) -1f else 1f
    }
    layers[targetState] = layer
    val transform = if (viewer && returning) {
        val exit =
            fadeOut(tween(durationMs)) + scaleOut(tween(durationMs), targetScale = VIEWER_SCALE)
        fadeIn(tween(durationMs)) togetherWith exit
    } else if (viewer) {
        val enter =
            fadeIn(tween(durationMs)) + scaleIn(tween(durationMs), initialScale = VIEWER_SCALE)
        enter togetherWith fadeOut(tween(durationMs))
    } else {
        pageSlide(returning, durationMs)
    }
    return transform.apply { targetContentZIndex = layer }
}

/**
 * One PlayerRoute per selection: reopening during the exit must not inherit the
 * leaving page's Surface, pager or paused session. The Box holds the page size
 * while the replacement waits for its session, so the transition does not animate
 * through an empty layout.
 */
@UnstableApi
@Composable
private fun VideoRoute(
    player: PlayerViewModel,
    connection: Connection,
    imageLoader: ImageLoader,
    cache: CacheCoordinator,
    onBack: () -> Unit,
    onInteraction: (Boolean) -> Unit,
    selectionBeganMs: Long,
    leaving: Boolean
) {
    Box(Modifier.fillMaxSize()) {
        key(retainedWhileLeaving(selectionBeganMs, leaving)) {
            PlayerRoute(
                player,
                connection,
                imageLoader,
                cache::session,
                cache::release,
                onBack,
                onInteraction,
                selectionBeganMs,
                leaving
            )
        }
    }
}

@Composable
private fun PhotoRoute(
    player: PlayerViewModel,
    connection: Connection,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    leaving: Boolean
) {
    val live by player.state.collectAsStateWithLifecycle()
    PhotoScreen(
        retainedWhileLeaving(live, leaving),
        connection,
        imageLoader,
        onBack,
        onRetry,
        player::selected,
        player::page,
        leaving
    )
}
