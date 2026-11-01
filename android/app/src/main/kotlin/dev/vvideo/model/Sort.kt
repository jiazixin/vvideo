package dev.vvideo.model

/** The time or name a folder is ordered by; [wire] is the server's value. */
internal enum class SortField(val wire: String) {
    Capture("capture"),
    Modified("mtime"),
    Name("name"),
    ;

    companion object {
        fun fromWire(value: String): SortField = entries.firstOrNull { it.wire == value } ?: Capture
    }
}

internal enum class SortDirection(val wire: String) {
    Ascending("asc"),
    Descending("desc"),
    ;

    companion object {
        fun fromWire(value: String): SortDirection = entries.firstOrNull { it.wire == value } ?: Ascending
    }
}

internal data class Sort(
    val field: SortField = SortField.Capture,
    val direction: SortDirection = SortDirection.Ascending,
)
