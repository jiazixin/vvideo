package dev.vvideo.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.HttpException
import dev.vvideo.R
import dev.vvideo.data.library.remote.MediaResource
import dev.vvideo.data.library.remote.ServiceFailure
import dev.vvideo.data.library.remote.retryAfterMillis
import dev.vvideo.data.library.remote.retryDelayMillis
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.model.MediaStatus
import dev.vvideo.ui.component.imageRequest
import dev.vvideo.ui.theme.LocalDispatchers
import dev.vvideo.ui.theme.LocalVVColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val COVER_CROSSFADE_MS = 120

/** Covers share the API GET policy; invalid images and non-transient statuses never retry. */
internal fun coverRetryDelay(error: Throwable, attempt: Int, retryAfter: String? = null, status: Int? = null): Long? {
    val failure = if (status != null) {
        ServiceFailure(status, retryAfterMillis = retryAfter?.let(::retryAfterMillis))
    } else {
        error as? IOException ?: return null
    }
    return retryDelayMillis(failure, attempt)
}

/**
 * A media cover that retries transient failures on its own, and shows why a cover
 * is missing while it is: being made, failed (tap to retry) or unsupported.
 */
@Composable
internal fun Thumbnail(
    item: Media,
    connection: Connection,
    loader: ImageLoader,
    showLabel: Boolean = false,
    onLoaded: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dispatchers = LocalDispatchers.current
    var attempt by remember(item.id, item.version) { mutableIntStateOf(0) }
    var failed by remember(item.id, item.version) { mutableStateOf(false) }
    var loaded by remember(item.id, item.version) { mutableStateOf(false) }
    var retryDelay by remember(item.id, item.version) { mutableStateOf<Long?>(null) }
    var retryable by remember(item.id, item.version) { mutableStateOf(true) }
    // Requests are tied to visible composition and cancelled by Coil on disposal.
    LaunchedEffect(failed, attempt) {
        val wait = retryDelay
        if (failed && wait != null && item.status != MediaStatus.Failed) {
            delay(wait)
            attempt++
            failed = false
        }
    }
    val request = remember(item.id, item.version, connection.id, connection.token, attempt) {
        item.imageRequest(context, connection, MediaResource.Thumbnail, COVER_CROSSFADE_MS)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Column(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = failed && retryable) {
                    attempt++
                    failed = false
                }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val icon = when {
                    failed && !retryable -> R.drawable.ic_image_off
                    failed -> R.drawable.ic_refresh
                    else -> R.drawable.ic_clock
                }
                Icon(
                    painterResource(icon),
                    if (failed && retryable) stringResource(R.string.cover_retry) else null,
                    tint = LocalVVColors.current.textSecondary,
                )
                if (showLabel) {
                    val label = when {
                        failed && !retryable -> R.string.cover_unsupported
                        failed -> R.string.cover_retry
                        else -> R.string.cover_generating
                    }
                    Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AsyncImage(
            request,
            // Device tests and the frame driver find cells by the kind at the end of this text.
            stringResource(R.string.media_description, item.name, item.kind.wire),
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            onSuccess = {
                loaded = true
                failed = false
                onLoaded(true)
            },
            onError = { error ->
                val response = (error.result.throwable as? HttpException)?.response
                retryable = item.status != MediaStatus.Failed && response?.code !in setOf(401, 403, 404, 415)
                val retryAfter = response?.headers?.get("Retry-After")
                scope.launch {
                    retryDelay = withContext(dispatchers.default) {
                        coverRetryDelay(error.result.throwable, attempt, retryAfter, response?.code)
                    }
                    failed = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
