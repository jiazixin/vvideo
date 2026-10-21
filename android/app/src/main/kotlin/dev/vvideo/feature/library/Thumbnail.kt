package dev.vvideo.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.model.MediaStatus
import dev.vvideo.ui.component.imageRequest
import dev.vvideo.ui.theme.LocalVVColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Automatic attempts after a cover fails to load; a tap can still ask again. */
private const val COVER_RETRIES = 3

/** The first retry waits this long; each further one waits twice as long. */
private const val COVER_RETRY_BASE_MS = 1000L

private const val COVER_CROSSFADE_MS = 120

private const val HTTP_NOT_FOUND = 404

/** Responses that may well succeed when asked again a little later. */
private val TRANSIENT_STATUSES = setOf(408, 429, 500, 502, 503, 504)

/**
 * Whether a cover that failed with HTTP [status] (null: no response) is worth
 * asking for again. A missing cover of media still being processed may appear.
 */
internal fun coverRetryable(status: Int?, mediaStatus: MediaStatus): Boolean =
    status == null || status in TRANSIENT_STATUSES ||
            (status == HTTP_NOT_FOUND && mediaStatus != MediaStatus.Ready)

/**
 * The wait a Retry-After header asks for at [nowMs]: whole seconds or an HTTP
 * date. A missing or unreadable header gives the base retry delay.
 */
internal fun retryAfterMs(header: String?, nowMs: Long): Long =
    header?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000)
        ?: runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                .toEpochMilli() - nowMs
        }.getOrDefault(COVER_RETRY_BASE_MS).coerceAtLeast(0)

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
    onLoaded: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember(item.id, item.version) { mutableIntStateOf(0) }
    var failed by remember(item.id, item.version) { mutableStateOf(false) }
    var loaded by remember(item.id, item.version) { mutableStateOf(false) }
    var retryDelay by remember(item.id, item.version) { mutableLongStateOf(COVER_RETRY_BASE_MS) }
    var retryable by remember(item.id, item.version) { mutableStateOf(true) }
    // Requests are tied to visible composition and cancelled by Coil on disposal.
    LaunchedEffect(failed, attempt) {
        if (failed && retryable && attempt < COVER_RETRIES && item.status != MediaStatus.Failed) {
            delay(maxOf(COVER_RETRY_BASE_MS shl attempt, retryDelay))
            attempt++
            failed = false
        }
    }
    val request = remember(item.id, item.version, connection.id, connection.token, attempt) {
        item.imageRequest(context, connection, MediaResource.Thumbnail, COVER_CROSSFADE_MS)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!loaded) Column(Modifier.padding(8.dp).clickable(enabled = failed && retryable) {
            attempt++
            failed = false
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = when {
                failed && !retryable -> R.drawable.ic_image_off
                failed -> R.drawable.ic_refresh
                else -> R.drawable.ic_clock
            }
            Icon(painterResource(icon), null, tint = LocalVVColors.current.textSecondary)
            if (showLabel) {
                val label = when {
                    failed && !retryable -> R.string.cover_unsupported
                    failed -> R.string.cover_retry
                    else -> R.string.cover_generating
                }
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
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
                retryable = coverRetryable(response?.code, item.status)
                val retryAfter = response?.headers?.get("Retry-After")
                scope.launch {
                    retryDelay = withContext(Dispatchers.Default) {
                        retryAfterMs(retryAfter, System.currentTimeMillis())
                    }
                    failed = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
