package dev.vvideo.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.ThemeMode
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.VVBottomSheet
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.ServerGradient
import java.net.URI

/** The saved token is shown as this mask and its last few characters. */
private const val TOKEN_MASK = "••••••"
private const val TOKEN_VISIBLE_CHARS = 4

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    vm: SettingsViewModel,
    connection: Connection,
    onDone: () -> Unit,
    onStorage: () -> Unit = {},
    onConnection: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    LaunchedEffect(connection.id, connection.token) {
        vm.verify(connection)
        vm.usage()
    }
    var themeOpen by remember { mutableStateOf(false) }
    val colors = LocalVVColors.current
    val host = remember(connection.address) {
        runCatching { URI(connection.address).rawAuthority }.getOrNull().orEmpty()
    }
    val context = LocalContext.current
    val version = remember {
        context.packageManager.getPackageInfo(
            context.packageName,
            0
        ).versionName.orEmpty()
    }
    if (themeOpen) ThemeSheet(theme, onDismiss = { themeOpen = false }) {
        vm.setTheme(it)
        themeOpen = false
    }
    Scaffold(
        containerColor = colors.bgPage,
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { ActionIcon(R.drawable.ic_back, R.string.back, onDone) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface)
            )
        }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionHeader(host, state)
            SettingsSection(R.string.connection_section) {
                SettingsRow(R.drawable.ic_globe, R.string.server_address, host, onConnection)
                SettingsRow(
                    R.drawable.ic_key,
                    R.string.access_token,
                    TOKEN_MASK + connection.token.takeLast(TOKEN_VISIBLE_CHARS),
                    onConnection
                )
                SettingsRow(
                    R.drawable.ic_refresh,
                    R.string.verify_connection,
                    onClick = { vm.verify(connection) })
            }
            SettingsSection(R.string.storage_section) {
                SettingsRow(
                    R.drawable.ic_disk,
                    R.string.storage_usage,
                    state.usage?.let {
                        formatBytes(it.total) + " / " + formatBytes(connection.budget)
                    } ?: stringResource(R.string.calculating),
                    onStorage
                )
            }
            SettingsSection(R.string.display_section) {
                SettingsRow(
                    R.drawable.ic_moon,
                    R.string.theme,
                    stringResource(theme.label())
                ) { themeOpen = true }
            }
            Text(
                stringResource(R.string.app_version, version),
                Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
    }
}

@Composable
internal fun SettingsSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(LocalVVColors.current.bgSurface)) {
        Text(
            stringResource(title),
            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            color = LocalVVColors.current.accentText,
            style = MaterialTheme.typography.labelLarge
        )
        content()
    }
}

@Composable
internal fun SettingsRow(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    value: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalVVColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = colors.textSecondary,
            modifier = Modifier.size(24.dp)
        )
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(
            value,
            Modifier.widthIn(max = 180.dp),
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The server's host and whether it is reachable, at the top of Settings. */
@Composable
private fun ConnectionHeader(host: String, state: SettingsUiState) {
    val colors = LocalVVColors.current
    val connected = state.connectionStatus == ConnectionStatus.Connected
    Row(
        Modifier.fillMaxWidth().background(colors.bgSurface).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(64.dp).background(ServerGradient, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_server),
                null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        Column {
            Text(
                host,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (connected) {
                    stringResource(R.string.connection_count, state.libraryCount)
                } else {
                    stringResource(state.connectionStatus.label())
                },
                color = if (connected) colors.online else colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ThemeSheet(current: ThemeMode, onDismiss: () -> Unit, onSelect: (ThemeMode) -> Unit) {
    VVBottomSheet(stringResource(R.string.theme), onDismiss) {
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(mode) }.heightIn(min = 52.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(current == mode, { onSelect(mode) })
                Text(stringResource(mode.label()))
            }
        }
    }
}
