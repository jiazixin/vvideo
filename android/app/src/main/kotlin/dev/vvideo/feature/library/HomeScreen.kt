package dev.vvideo.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.data.library.remote.HTTP_UNAUTHORIZED
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.EmptyState
import dev.vvideo.ui.component.LetterAvatar
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.Motion
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move
import kotlinx.coroutines.delay

/** A refresh behind a shown list waits this long before it shows a spinner. */
private const val SPINNER_DELAY_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: LibraryUiState,
    onSettings: () -> Unit,
    onOpen: (Long) -> Unit,
    onRetry: () -> Unit,
    onConnection: () -> Unit = onSettings
) {
    val colors = LocalVVColors.current
    val quick = fade<Float>()
    val slow = fade<Float>(Motion.CHANGE)
    val resize = move<IntSize>()
    val status = when {
        state.busy && state.libraries.isEmpty() -> R.string.status_connecting
        state.error && state.errorStatus == HTTP_UNAUTHORIZED -> R.string.status_bad_token
        state.error && state.errorStatus == 0 -> R.string.status_waiting_network
        else -> R.string.home_title
    }
    // A refresh behind a shown list only gets a spinner once it is slow enough
    // to notice, so a quick reload does not shift the title back and forth.
    var spinner by remember { mutableStateOf(state.busy) }
    LaunchedEffect(state.busy) {
        if (state.busy) {
            if (state.libraries.isNotEmpty()) delay(SPINNER_DELAY_MS)
            spinner = true
        } else spinner = false
    }
    // The title only acts while it reports a problem: retry, or fix the credential.
    val action =
        if (!state.error || state.busy) null else if (state.errorStatus == HTTP_UNAUTHORIZED) onConnection else onRetry
    Scaffold(containerColor = colors.bgSurface, topBar = {
        TopAppBar(
            expandedHeight = 56.dp,
            title = {
                Row(
                    if (action != null) Modifier.clickable(onClick = action) else Modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        spinner,
                        enter = fadeIn(fade()) + expandHorizontally(move()),
                        exit = fadeOut(fade()) + shrinkHorizontally(move())
                    ) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 8.dp).size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    val color by animateColorAsState(
                        if (state.errorStatus == HTTP_UNAUTHORIZED) colors.warn else colors.textPrimary,
                        fade(),
                        label = "home-title-color"
                    )
                    AnimatedContent(
                        status,
                        transitionSpec = {
                            fadeIn(quick) togetherWith fadeOut(quick) using
                                    SizeTransform(clip = false) { _, _ -> resize }
                        },
                        label = "home-title"
                    ) { Text(stringResource(it), color = color) }
                }
            },
            actions = { ActionIcon(R.drawable.ic_settings, R.string.settings, onSettings) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface)
        )
    }) { padding ->
        // The library list fades in once on arrival; its rows have no entrance animation of their own.
        AnimatedContent(
            state.libraries.isEmpty(),
            Modifier.padding(padding),
            transitionSpec = { fadeIn(slow) togetherWith fadeOut(slow) },
            label = "home-content"
        ) { empty ->
            if (empty) {
                if (!state.busy && !state.error) EmptyState(stringResource(R.string.empty_roots)) else Box(
                    Modifier.fillMaxSize()
                )
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(state.libraries, key = { it.id }) { lib ->
                    LibraryRow(
                        lib.id,
                        lib.name,
                        stringResource(if (lib.healthy) R.string.library_online else R.string.library_offline),
                        pinned = true,
                        healthy = lib.healthy,
                        onClick = { onOpen(lib.directoryId) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    id: String,
    name: String,
    subtitle: String? = null,
    time: String? = null,
    pinned: Boolean = false,
    healthy: Boolean = true,
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = LocalVVColors.current
    Column(Modifier.background(if (pinned) colors.pinnedRow else colors.bgSurface)) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = if (compact) 64.dp else 72.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LetterAvatar(id, name, if (compact) 44.dp else 52.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (!healthy) colors.warn else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pinned) Icon(
                        painterResource(R.drawable.ic_pin),
                        null,
                        Modifier.size(16.dp),
                        tint = colors.textTertiary
                    )
                    else if (time != null) Text(
                        time,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    else Icon(
                        painterResource(R.drawable.ic_chevron),
                        null,
                        tint = colors.textTertiary
                    )
                }
                if (subtitle != null) Row(
                    Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pinned) Box(
                        Modifier.size(7.dp)
                            .background(if (healthy) colors.online else colors.warn, CircleShape)
                    )
                    Text(
                        subtitle,
                        color = if (!healthy) colors.warn else colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 76.dp), color = colors.divider)
    }
}
