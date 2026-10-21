package dev.vvideo.ui.component

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.vvideo.data.library.remote.AUTHORIZATION_HEADER
import dev.vvideo.data.library.remote.MediaResource
import dev.vvideo.data.library.remote.authorization
import dev.vvideo.data.library.remote.mediaUrl
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media

/** An authorized image request for [resource] of this media; [crossfadeMs] 0 shows it at once. */
fun Media.imageRequest(
    context: Context,
    connection: Connection,
    resource: MediaResource,
    crossfadeMs: Int = 0
): ImageRequest = ImageRequest.Builder(context)
    .data(connection.mediaUrl(this, resource))
    .apply { if (crossfadeMs > 0) crossfade(crossfadeMs) }
    .httpHeaders(
        NetworkHeaders.Builder().set(AUTHORIZATION_HEADER, connection.authorization()).build()
    )
    .build()
