package dev.vvideo.feature.library

import dev.vvideo.model.MediaPage

/** Prefix of the lazy-list key of an album row; the rest is its first media ID. */
const val ALBUM_KEY_PREFIX = "album:"

/** The lazy-list key of an album row. */
fun albumKey(album: Album): String = ALBUM_KEY_PREFIX + album.items.first().id

/** The lazy-list keys of a folder page, in display order. */
fun libraryKeys(state: LibraryUiState): List<String> = buildList {
    if (state.albums.isEmpty()) addAll(state.children.map { "folder:${it.id}" })
    else {
        if (state.children.isNotEmpty()) add("subfolder-bar")
        state.albums.forEach { album ->
            val id = album.items.first().id
            if (album.dateLabel != null) add("date:$id")
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
