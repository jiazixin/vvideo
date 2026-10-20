package dev.vvideo.app.navigation

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.app.VVideoApplication
import dev.vvideo.feature.library.*
import dev.vvideo.feature.player.*
import dev.vvideo.feature.settings.*
import dev.vvideo.model.Sort
import dev.vvideo.ui.component.TimeLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class NavigationState : ViewModel() {
    var route by mutableStateOf("library")
}

private class Factory<T : ViewModel>(val make: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

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
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val timeLabels = remember(androidx.compose.ui.platform.LocalConfiguration.current) {
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failure) {
                Button(onClick = { application.initialize() }) { Text(stringResource(R.string.retry)) }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.initializing))
                }
            }
        }
        return
    }
    val imageLoader = checkNotNull(loader)
    val connection = checkNotNull(stored)
    // Without a saved address and token the only page is the setup form.
    val screen = if (connection.configured) route else "setup"
    val activity = androidx.activity.compose.LocalActivity.current
    val dark = dev.vvideo.ui.theme.LocalVV.current.isDark || route == "video" || route == "photo"
    SideEffect {
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
        }
    }
    val back = {
        player.leave()
        navigation.route = "library"
        onInteraction(false)
    }
    var albumDetails by remember { mutableStateOf<dev.vvideo.model.Media?>(null) }
    albumDetails?.let { item ->
        MediaDetailsSheet(
            item,
            library.state.value.path.joinToString(" › ") { it.name },
        ) { albumDetails = null }
    }
    val settingsBack = { navigation.route = "settings" }
    var predictive by remember { mutableFloatStateOf(0f) }
    val animations = dev.vvideo.ui.theme.LocalAnimations.current
    // Draw order of the two pages in a transition, relative to the page left:
    // only a page slid back to lies beneath the one leaving it. A viewer and the
    // page behind it both enter on top, so a tap during the viewer's exit reaches
    // the page returned to instead of the leaving player.
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
        if (route == "video" || route == "photo") player.open(
            connection,
            directory,
            Sort(field, direction),
            mediaId,
            route == "photo"
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(targetState = screen, modifier = Modifier.graphicsLayer {
            translationX = size.width * predictive
            alpha = 1f - predictive * .15f
        }, transitionSpec = {
            val viewer =
                targetState in setOf("video", "photo") || initialState in setOf("video", "photo")
            // Finishing the setup form moves forward into the library.
            val returning =
                targetState == "library" && initialState != "setup" || initialState in setOf(
                    "storage",
                    "connection"
                ) && targetState == "settings"
            val ms = if (!animations) 0 else if (viewer) {
                if (returning) 200 else 180
            } else if (returning) 220 else 260
            val layer = if (initialState == targetState) layers[targetState]
                ?: 0f else (layers[initialState] ?: 0f) + if (returning && !viewer) -1f else 1f
            layers[targetState] = layer
            (if (viewer && returning) fadeIn(tween(ms)) togetherWith (fadeOut(tween(ms)) + scaleOut(
                tween(ms),
                targetScale = .92f
            ))
            else if (viewer) (fadeIn(tween(ms)) + scaleIn(
                tween(ms),
                initialScale = .92f
            )) togetherWith fadeOut(tween(ms))
            else (slideInHorizontally(
                tween(
                    ms,
                    easing = FastOutSlowInEasing
                )
            ) { if (returning) -it / 4 else it } + fadeIn(
                tween(ms),
                initialAlpha = if (returning) .85f else 1f
            )) togetherWith (slideOutHorizontally(
                tween(
                    ms,
                    easing = FastOutSlowInEasing
                )
            ) { if (returning) it else -it / 4 } + fadeOut(
                tween(ms),
                targetAlpha = if (returning) 1f else .85f
            ))
                    ).apply { targetContentZIndex = layer }
        }, label = "route") { displayedRoute ->
            val leaving = displayedRoute != screen
            when {
                displayedRoute == "setup" -> ConnectionForm(
                    settings,
                    connection,
                    { navigation.route = "library" })

                displayedRoute == "connection" -> ConnectionForm(settings, connection, settingsBack)
                displayedRoute == "storage" -> StorageScreen(settings, connection, settingsBack)
                displayedRoute == "settings" -> SettingsRoute(
                    settings,
                    connection,
                    { navigation.route = "library" },
                    { navigation.route = "storage" },
                    { navigation.route = "connection" })
                // One PlayerRoute per selection: reopening during the exit must not
                // inherit the leaving page's Surface, pager or paused session. The Box
                // holds the page size while the replacement waits for its session, so the
                // transition does not animate through an empty layout.
                displayedRoute == "video" -> Box(Modifier.fillMaxSize()) {
                    key(
                        retainedWhileLeaving(
                            selectionBeganMs,
                            leaving
                        )
                    ) {
                        PlayerRoute(
                            player,
                            connection,
                            imageLoader,
                            graph.cache::session,
                            graph.cache::release,
                            back,
                            onInteraction,
                            selectionBeganMs,
                            leaving
                        )
                    }
                }

                displayedRoute == "photo" -> {
                    val live by player.state.collectAsStateWithLifecycle()
                    PhotoScreen(
                        retainedWhileLeaving(live, leaving),
                        connection,
                        imageLoader,
                        back,
                        {
                            player.open(
                                connection,
                                directory,
                                Sort(field, direction),
                                mediaId,
                                true
                            )
                        },
                        player::selected,
                        player::page,
                        leaving
                    )
                }

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
