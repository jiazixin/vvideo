package dev.vvideo.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvideo.data.settings.Connection

@Composable
internal fun StorageRoute(vm: SettingsViewModel, connection: Connection, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.dismissMessage()
        vm.usage()
    }
    StorageScreen(
        state = state.copy(connection = connection),
        onBack = onBack,
        onUsage = vm::usage,
        onBudget = vm::setBudget,
        onClear = { vm.clearCache(connection.budget) },
        onMessageShown = vm::dismissMessage,
    )
}
