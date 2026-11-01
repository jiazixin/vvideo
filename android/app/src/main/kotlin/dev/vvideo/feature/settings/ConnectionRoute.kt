package dev.vvideo.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvideo.data.settings.Connection

@Composable
internal fun ConnectionRoute(vm: SettingsViewModel, connection: Connection, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val initial = remember { state.connectedSerial }
    LaunchedEffect(Unit) { vm.dismissMessage() }
    LaunchedEffect(state.connectedSerial) { if (state.connectedSerial > initial) onDone() }
    ConnectionScreen(
        state = state.copy(connection = connection),
        onDone = onDone,
        onDismissMessage = vm::dismissMessage,
        onConnect = vm::connect,
    )
}
