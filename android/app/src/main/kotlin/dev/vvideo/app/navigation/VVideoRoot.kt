package dev.vvideo.app.navigation

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import dev.vvideo.feature.library.*
import dev.vvideo.feature.player.*
import dev.vvideo.feature.settings.*
import dev.vvideo.model.Media
import dev.vvideo.model.Sort
import dev.vvideo.ui.component.TimeLabels
import dev.vvideo.ui.theme.LocalAnimations
import dev.vvideo.ui.theme.LocalVV
import kotlinx.coroutines.CancellationException

private class NavigationState : ViewModel() {
    var route by mutableStateOf("library")
}

private class Factory<T : ViewModel>(val make: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

/** Routes that show one media item full screen. */
private val ViewerRoutes = setOf("video", "photo")

@UnstableApi
@OptIn(androidx.activity.ExperimentalActivityApi::class)
@Composable
fun VVideoRoot(application: VVideoApplication, onInteraction: (Boolean) -> Unit) {
    val graph = application.graph
    val ready by application.ready.collectAsStateWithLifecycle()
    val failure by application.failure.collectAsStateWithLifecycle()
    // Null until the stored connection is read, so a configured app never shows
    // the setup form for a frame and a new install never shows anything else.
    val stored by graph.settings.connection.collectAsStateWithLifecycle(initialValue = null)
    val loader by graph.cache.loader.collectAsStateWithLifecycle()
    val navigation: NavigationState = viewModel(factory = Factory { NavigationState() })
    val route = navigation.route
    var selectionBeganMs by remember { mutableLongStateOf(0L) }
    var mediaId by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable { mutableLongStateOf(0L) }
    var field by rememberSaveable { mutableStateOf("capture") }
    var direction by rememberSaveable { mutableStateOf("asc") }
    val timeLabels = rememberTimeLabels()
    val library: LibraryViewModel = viewModel(factory = Factory {
        LibraryViewModel(
            graph.api,
            graph.database.bookmarks(),
            graph.settings,
            graph.scope,
            timeLabels
        )
    })
    val settings: SettingsViewModel =
        viewModel(factory = Factory { SettingsViewModel(graph.settings, graph.cache, graph.api) })
    val player: PlayerViewModel = viewModel(factory = Factory { PlayerViewModel(graph.api) })
    if (!ready || loader == null || stored == null) {
        InitializingScreen(failure, onRetry = { application.initialize() })
        return
    }
    val imageLoader = checkNotNull(loader)
    val connection = checkNotNull(stored)
    // Without a saved address and token the only page is the setup form.
    val screen = if (connection.configured) route else "setup"
    SystemBarAppearance(dark = LocalVV.current.isDark || route in ViewerRoutes)
    val back = {
        player.leave()
        navigation.route = "library"
        onInteraction(false)
    }
    var albumDetails by remember { mutableStateOf<Media?>(null) }
    albumDetails?.let { item ->
        MediaDetailsSheet(
            item,
            library.state.value.path.joinToString(" › ") { it.name },
        ) { albumDetails = null }
    }
    val settingsBack = { navigation.route = "settings" }
    var predictive by remember { mutableFloatStateOf(0f) }
    val animations = LocalAnimations.current
    // Draw order of the two pages in a transition; see routeTransition.
    val layers = remember { mutableMapOf<String, Float>() }
    PredictiveBackHandler(route != "library" && connection.configured) { events ->
        try {
            events.collect { predictive = it.progress }
            if (route == "storage" || route == "connection") {
                navigation.route = "settings"
            } else {
                back()
            }
        } catch (_: CancellationException) {
        } finally {
            predictive = 0f
        }
    }
    LaunchedEffect(
        route,
        connection.id,
        connection.token,
        mediaId
    ) {
        if (route in ViewerRoutes) player.open(
            connection,
            directory,
            Sort(field, direction),
            mediaId,
            route == "photo"
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.graphicsLayer {
                translationX = size.width * predictive
                alpha = 1f - predictive * .15f
            },
            transitionSpec = { routeTransition(animations, layers) },
            label = "route"
        ) { displayedRoute ->
            val leaving = displayedRoute != screen
            when (displayedRoute) {
                "setup" -> ConnectionForm(
                    settings,
                    connection,
                    { navigation.route = "library" })

                "connection" -> ConnectionForm(settings, connection, settingsBack)
                "storage" -> StorageScreen(settings, connection, settingsBack)
                "settings" -> SettingsRoute(
                    settings,
                    connection,
                    { navigation.route = "library" },
                    { navigation.route = "storage" },
                    { navigation.route = "connection" })

                "video" -> VideoRoute(
                    player,
                    connection,
                    imageLoader,
                    graph.cache,
                    back,
                    onInteraction,
                    selectionBeganMs,
                    leaving
                )

                "photo" -> PhotoRoute(
                    player,
                    connection,
                    imageLoader,
                    back,
                    { player.open(connection, directory, Sort(field, direction), mediaId, true) },
                    leaving
                )

                else -> LibraryRoute(
                    library,
                    { c, item -> "${c.address}/v1/media/${item.id}/thumbnail?version=${item.version}" },
                    connection,
                    imageLoader,
                    {
                        library.flush()
                        navigation.route = "settings"
                    },
                    { item ->
                        selectionBeganMs = android.os.SystemClock.elapsedRealtime()
                        library.flush()
                        directory = item.directoryId
                        val sort = library.state.value.sort
                        field = sort.field
                        direction = sort.direction
                        mediaId = item.id
                        navigation.route = if (item.kind == "video") "video" else "photo"
                    },
                    onInteraction,
                    { albumDetails = it },
                    {
                        library.flush()
                        navigation.route = "connection"
                    })
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
            resources.getString(R.string.today),
            resources.getString(R.string.yesterday),
            resources.getString(R.string.date_pattern),
            resources.getString(R.string.date_year_pattern),
            resources.getString(R.string.recent_date_pattern),
            resources.getString(R.string.recent_old_pattern),
            resources.getString(R.string.weekday_pattern)
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
private fun AnimatedContentTransitionScope<String>.routeTransition(
    animations: Boolean,
    layers: MutableMap<String, Float>
): ContentTransform {
    val viewer = targetState in ViewerRoutes || initialState in ViewerRoutes
    // Finishing the setup form moves forward into the library.
    val returning = targetState == "library" && initialState != "setup" ||
            initialState in setOf("storage", "connection") && targetState == "settings"
    val ms = if (!animations) 0 else if (viewer) {
        if (returning) 200 else 180
    } else if (returning) 220 else 260
    val layer = if (initialState == targetState) {
        layers[targetState] ?: 0f
    } else {
        (layers[initialState] ?: 0f) + if (returning && !viewer) -1f else 1f
    }
    layers[targetState] = layer
    val transform = if (viewer && returning) {
        val exit = fadeOut(tween(ms)) + scaleOut(tween(ms), targetScale = .92f)
        fadeIn(tween(ms)) togetherWith exit
    } else if (viewer) {
        val enter = fadeIn(tween(ms)) + scaleIn(tween(ms), initialScale = .92f)
        enter togetherWith fadeOut(tween(ms))
    } else {
        val slide = tween<IntOffset>(ms, easing = FastOutSlowInEasing)
        val enter = slideInHorizontally(slide) { if (returning) -it / 4 else it } +
                fadeIn(tween(ms), initialAlpha = if (returning) .85f else 1f)
        val exit = slideOutHorizontally(slide) { if (returning) it else -it / 4 } +
                fadeOut(tween(ms), targetAlpha = if (returning) 1f else .85f)
        enter togetherWith exit
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
