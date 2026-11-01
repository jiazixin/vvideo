package dev.vvideo.app.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class NavigationViewModel : ViewModel() {
    private val _state = MutableStateFlow(NavigationUiState())
    val state = _state.asStateFlow()

    fun navigate(route: Route) {
        _state.update { it.copy(route = route) }
    }
}
