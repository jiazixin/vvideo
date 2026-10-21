package dev.vvideo.data.library

import android.util.Base64
import dev.vvideo.model.Media
import dev.vvideo.model.Sort
import dev.vvideo.model.SortField
import org.json.JSONObject

/** The value [item] is ordered by under [field], as the server compares it. */
fun Media.sortKey(field: SortField): String = when (field) {
    SortField.Name -> name
    SortField.Modified -> mtime.toString()
    SortField.Capture -> effectiveTime.toString()
}

/**
 * The fallback cursor saved with a bookmark: [item]'s place in the folder by its
 * sort key, which the server uses when the anchored media itself is gone.
 * Bookmarks are only kept for unfiltered lists, so the kind is always blank.
 */
fun anchorFallback(item: Media, revision: Long, sort: Sort): String = Base64.encodeToString(
    JSONObject()
        .put("directory_id", item.directoryId)
        .put("revision", revision)
        .put("sort", sort.field.wire)
        .put("direction", sort.direction.wire)
        .put("kind", "")
        .put("key", item.sortKey(sort.field))
        .put("id", item.id)
        .toString()
        .toByteArray(),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
)
