package dev.vvideo.feature.library

import dev.vvideo.model.MediaPage

/** Prefix of the lazy-list key of an album row; the rest is its first media ID. */
const val ALBUM_KEY_PREFIX = "album:"

/** Prefix of the lazy-list key of a subfolder row; the rest is its directory ID. */
private const val FOLDER_KEY_PREFIX = "folder:"

/** Prefix of the lazy-list key of a date row; the rest is the first media ID of its album. */
private const val DATE_KEY_PREFIX = "date:"

/** Lazy-list key of the zero-height row that stands for the subfolder bar. */
const val SUBFOLDER_BAR_KEY = "subfolder-bar"

/** Lazy-list content type of date rows. */
const val DATE_CONTENT_TYPE = "date"

/** The lazy-list key of an album row. */
fun albumKey(album: Album): String = ALBUM_KEY_PREFIX + album.items.first().id

/** The lazy-list key of a subfolder row. */
fun folderKey(directoryId: Long): String = FOLDER_KEY_PREFIX + directoryId

/** The lazy-list key of the date row above an album. */
fun dateKey(album: Album): String = DATE_KEY_PREFIX + album.items.first().id

/** The lazy-list keys of a folder page, in display order. */
fun libraryKeys(state: LibraryUiState): List<String> = buildList {
    if (state.albums.isEmpty()) {
        addAll(state.subfolders.items.map { folderKey(it.id) })
    } else {
        if (state.subfolders.items.isNotEmpty()) add(SUBFOLDER_BAR_KEY)
        state.albums.forEach { album ->
            if (album.dateLabel != null) add(dateKey(album))
            add(albumKey(album))
        }
    }
}

fun anchorKey(albums: List<Album>, anchor: String): String? =
    albums.firstOrNull { album -> album.items.any { it.id == anchor } }
        ?.let(::albumKey)

/** The row a saved position lands on: its own key, else the album holding the anchor; null if neither is shown. */
fun restoreIndex(keys: List<String>, albums: List<Album>, key: String, anchor: String): Int? {
    val exact = keys.indexOf(key)
    if (exact >= 0) return exact
    return keys.indexOf(anchorKey(albums, anchor)).takeIf { it >= 0 }
}

// A saved position that is gone is only worth a notice when the page shows
// something near it; an empty folder has no nearby position.
fun anchorMoved(saved: String, page: MediaPage): Boolean =
    saved.isNotEmpty() && !page.anchorFound && page.items.isNotEmpty()
