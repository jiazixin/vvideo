package dev.vvideo.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.EmptyState
import dev.vvideo.ui.component.mediaDuration
import dev.vvideo.ui.theme.LocalVV

/** The search box under the folder's app bar. */
@Composable
internal fun FolderSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalVV.current
    val focus = LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(c.bgInput).height(40.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            null,
            Modifier.size(20.dp),
            tint = c.textSecondary
        )
        Box(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = c.textSecondary, maxLines = 1)
            }
            BasicTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentFill),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
            )
        }
        if (query.isNotEmpty()) {
            ActionIcon(R.drawable.ic_close, R.string.clear_search, {
                onQuery("")
                focus.clearFocus()
            })
        }
    }
}

/** Matching folders, then matching media, for the folder's current search. */
@Composable
internal fun FolderSearchResults(
    search: SearchState,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    onOpen: (Long) -> Unit,
    onMedia: (Media) -> Unit,
    onRetry: () -> Unit
) {
    val c = LocalVV.current
    val result = search.result
    when {
        result == null && search.error -> EmptyState(
            stringResource(R.string.search_failed),
            action = stringResource(R.string.retry),
            onAction = onRetry
        )

        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(Modifier.padding(32.dp).size(32.dp))
        }

        result.directories.isEmpty() && result.items.isEmpty() -> EmptyState(stringResource(R.string.search_empty))

        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (result.directories.isNotEmpty()) {
                item("folders-title") { SectionTitle(stringResource(R.string.search_folders)) }
                items(result.directories, key = { "folder:${it.id}" }) { dir ->
                    LibraryRow(
                        dir.id.toString(),
                        dir.name,
                        compact = true,
                        onClick = { onOpen(dir.id) })
                }
            }
            if (result.items.isNotEmpty()) {
                item("media-title") { SectionTitle(stringResource(R.string.search_media)) }
                items(result.items, key = { "media:${it.id}" }) { media ->
                    SearchMediaRow(media, loader, connection, thumbnail) { onMedia(media) }
                }
            }
            if (result.truncated) {
                item("truncated") {
                    Text(
                        stringResource(R.string.search_truncated),
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = LocalVV.current.textSecondary
    )
}

@Composable
private fun SearchMediaRow(
    media: Media,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    onClick: () -> Unit
) {
    val c = LocalVV.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.bgRaised)) {
            Thumbnail(media, connection, loader, thumbnail) {}
        }
        Column(Modifier.weight(1f)) {
            Text(media.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (media.kind == "video") mediaDuration(media.durationMs) else stringResource(R.string.tab_photo),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
        }
    }
}
