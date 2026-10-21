package dev.vvideo.model

/** What a media item is; [wire] is the server's value. */
enum class MediaKind(val wire: String) {
    Video("video"),
    Image("image");

    companion object {
        // The server only reports video or image; anything else opens like a photo, as before.
        fun fromWire(value: String): MediaKind = if (value == Video.wire) Video else Image
    }
}

/** The server's processing state of a media item, as far as the client distinguishes it. */
enum class MediaStatus {
    Ready,

    /** Cover generation gave up for this version; the client stops waiting for it. */
    Failed,

    /** Still being probed or covered, or a state this client does not know. */
    Pending;

    companion object {
        fun fromWire(value: String): MediaStatus = when (value) {
            "ready" -> Ready
            "failed" -> Failed
            else -> Pending
        }
    }
}

/** Where a media item's capture time came from. */
enum class CaptureSource {
    ExifOriginal,
    ContainerCreation,
    Modified,
    Unknown;

    companion object {
        fun fromWire(value: String): CaptureSource = when (value) {
            "exif_datetime_original" -> ExifOriginal
            "container_creation_time" -> ContainerCreation
            "mtime" -> Modified
            else -> Unknown
        }
    }
}
