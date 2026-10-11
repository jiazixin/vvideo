package dev.vvideo.feature.library

fun libraryKeys(state: LibraryUiState): List<String> = buildList {
    if(state.albums.isEmpty()) addAll(state.children.map { "folder:${it.id}" })
    else {
        if(state.children.isNotEmpty()) add("subfolder-bar")
        state.albums.forEach { album ->
            val id=album.items.first().id
            if(album.dateLabel!=null)add("date:$id")
            add("album:$id")
        }
    }
}
fun anchorKey(albums: List<Album>, anchor: String): String? = albums.firstOrNull { album -> album.items.any { it.id==anchor } }?.let { "album:${it.items.first().id}" }
fun restoreIndex(keys: List<String>, albums: List<Album>, key: String, anchor: String): Int {
    val exact=keys.indexOf(key)
    if(exact>=0)return exact
    return keys.indexOf(anchorKey(albums,anchor)).coerceAtLeast(0)
}
