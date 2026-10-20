package dev.vvideo.feature.library

import dev.vvideo.model.Directory

fun pathLabel(path: List<Directory>): String = path.dropLast(1).takeLast(2).joinToString(" › ") { it.name }

/**
 * The destination page shown while its first load is still running, built only
 * from what the current state already knows. Null means the destination cannot
 * be named yet, so the current page stays until the load finishes.
 */
fun pendingDirectory(state: LibraryUiState, id: Long): LibraryUiState? {
    val ancestor = state.path.indexOfFirst { it.id == id }
    val child = state.children.firstOrNull { it.id == id }
    val path = when {
        ancestor >= 0 -> state.path.take(ancestor + 1)
        child != null -> if (state.path.lastOrNull()?.id == child.parentId) state.path + child else listOf(child)
        else -> state.libraries.firstOrNull { it.directoryId == id }?.let { listOf(Directory(id, it.id, 0, it.name, 0)) }
    }
    // A recent entry knows its name and label but not its ancestors.
    val recent = if (path == null) state.recent.firstOrNull { it.bookmark.directory == id }?.bookmark else null
    val target = path?.last() ?: recent?.let { Directory(id, it.library, 0, it.name, 0) } ?: return null
    return LibraryUiState(
        recent = state.recent, libraries = state.libraries, directory = target, path = path ?: listOf(target),
        pathLabel = recent?.pathLabel ?: pathLabel(path.orEmpty()), busy = true, restoreSerial = state.restoreSerial,
    )
}

/**
 * The page left behind by a placeholder, shown again without a request. Lists
 * that kept updating meanwhile and the restore counter come from [current];
 * paging requests of the kept page were cancelled when it was left.
 */
fun restoredPage(kept: LibraryUiState, current: LibraryUiState): LibraryUiState =
    kept.copy(recent = current.recent, libraries = current.libraries, pageBusy = false, childBusy = false, restoreSerial = current.restoreSerial, anchorMissing = false)
