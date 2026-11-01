package dev.vvideo.data.library.remote

import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media

/** A versioned resource of one media item on the server. */
internal enum class MediaResource(val path: String) {
    Thumbnail("thumbnail"),
    Bytes("bytes"),

    /** Byte ranges worth preloading for a video, such as a trailing moov box. */
    Index("index"),
}

/** The URL of [resource] for this exact version of [media]. */
internal fun Connection.mediaUrl(media: Media, resource: MediaResource): String =
    "$address/api/media/${media.id}/${resource.path}?version=${media.version}"
