package dev.vvideo.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.vvideo.R
import dev.vvideo.data.library.remote.MediaResource
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.component.imageRequest

@Composable
internal fun PhotoPage(
    item: Media,
    connection: Connection,
    loader: ImageLoader,
    front: Boolean,
    resting: Boolean,
    zoom: (GraphicsLayerScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val image =
        if (zoom != null) Modifier.fillMaxSize().graphicsLayer(zoom) else Modifier.fillMaxSize()
    // A page being swiped away keeps its original until the pager rests, so it
    // does not fall back to its thumbnail while still on screen.
    var original by remember { mutableStateOf(front) }
    var attempt by remember(item.version) { mutableIntStateOf(0) }
    var failed by remember(item.version, attempt) { mutableStateOf(false) }
    var loaded by remember(item.version, attempt) { mutableStateOf(false) }
    LaunchedEffect(front, resting) {
        if (front) {
            original = true
        } else if (resting) {
            original = false
            loaded = false
        }
    }
    val context = LocalContext.current
    val request = remember(item.id, item.version, connection.token, attempt) {
        item.imageRequest(context, connection, MediaResource.Bytes)
    }
    val thumb = remember(item.id, item.version, connection.token) {
        item.imageRequest(context, connection, MediaResource.Thumbnail)
    }
    Box(modifier.fillMaxSize()) {
        if (!loaded || !original) {
            AsyncImage(
                thumb,
                null,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                modifier = image,
            )
        }
        if (original) {
            AsyncImage(
                request,
                item.name,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { failed = true },
                modifier = image,
            )
        }
        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.photo_failed), color = Color.White)
                Button(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
