package dev.vvideo.app.navigation

/** The pages of the app. */
internal enum class Route(val isViewer: Boolean = false) {
    Setup,
    Library,
    Settings,
    Storage,
    Connection,
    Video(isViewer = true),
    Photo(isViewer = true),
}

internal data class NavigationUiState(val route: Route = Route.Library)
