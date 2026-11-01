package dev.vvideo.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvideo.BuildConfig
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.ThemeMode

@Composable
internal fun SettingsRoute(
    vm: SettingsViewModel,
    connection: Connection,
    onDone: () -> Unit,
    onStorage: () -> Unit = {},
    onConnection: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    LaunchedEffect(connection.id, connection.token) {
        vm.verify(connection)
        vm.usage()
    }
    SettingsScreen(
        state = state.copy(connection = connection, theme = theme, version = BuildConfig.VERSION_NAME),
        onDone = onDone,
        onStorage = onStorage,
        onConnection = onConnection,
        onVerify = { vm.verify(connection) },
        onTheme = vm::setTheme,
    )
}
