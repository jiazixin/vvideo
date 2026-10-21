package dev.vvideo.feature.player

import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.data.library.remote.MediaResource
import dev.vvideo.data.library.remote.mediaUrl
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media

/** What the media engine needs to play and cache this version of a video. */
fun Media.playback(connection: Connection) = PlaybackItem(
    server = connection.id,
    library = libraryId,
    id = id,
    version = version,
    url = connection.mediaUrl(this, MediaResource.Bytes),
    indexUrl = connection.mediaUrl(this, MediaResource.Index),
    token = connection.token,
    size = size,
    durationMs = durationMs
)
