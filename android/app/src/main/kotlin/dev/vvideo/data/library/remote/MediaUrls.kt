package dev.vvideo.data.library.remote

import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media

const val AUTHORIZATION_HEADER = "Authorization"

/** The Authorization header value for this connection's token. */
fun Connection.authorization(): String = "Bearer $token"

/** A versioned resource of one media item on the server. */
enum class MediaResource(val path: String) {
    Thumbnail("thumbnail"),
    Bytes("bytes"),

    /** Byte ranges worth preloading for a video, such as a trailing moov box. */
    Index("index")
}

/** The URL of [resource] for this exact version of [media]. */
fun Connection.mediaUrl(media: Media, resource: MediaResource): String =
    "$address/v1/media/${media.id}/${resource.path}?version=${media.version}"
