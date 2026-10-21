package dev.vvideo.feature.library

import dev.vvideo.model.Directory
import dev.vvideo.ui.component.pathLabel

/**
 * The destination page shown while its first load is still running, built only
 * from what the current state already knows. Null means the destination cannot
 * be named yet, so the current page stays until the load finishes.
 */
fun pendingDirectory(state: LibraryUiState, id: Long): LibraryUiState? {
    val ancestor = state.path.indexOfFirst { it.id == id }
    val child = state.subfolders.items.firstOrNull { it.id == id }
    val path = when {
        ancestor >= 0 -> state.path.take(ancestor + 1)
        child != null && state.path.lastOrNull()?.id == child.parentId -> state.path + child
        child != null -> listOf(child)
        else -> state.libraries.firstOrNull { it.directoryId == id }
            ?.let {
                listOf(
                    Directory(
                        id = id,
                        libraryId = it.id,
                        parentId = 0,
                        name = it.name,
                        revision = 0
                    )
                )
            }
    }
    // A recent entry knows its name and label but not its ancestors.
    val recent =
        if (path == null) state.recent.firstOrNull { it.bookmark.directory == id }?.bookmark else null
    val target = path?.last()
        ?: recent?.let {
            Directory(
                id = id,
                libraryId = it.library,
                parentId = 0,
                name = it.name,
                revision = 0
            )
        }
        ?: return null
    return LibraryUiState(
        recent = state.recent,
        libraries = state.libraries,
        directory = target,
        path = path ?: listOf(target),
        pathLabel = recent?.pathLabel ?: pathLabel(path.orEmpty()),
        busy = true,
        restore = ScrollRestore(serial = state.restore.serial),
    )
}

/**
 * The page left behind by a placeholder, shown again without a request. Lists
 * that kept updating meanwhile and the restore counter come from [current];
 * paging requests of the kept page were cancelled when it was left.
 */
fun restoredPage(kept: LibraryUiState, current: LibraryUiState): LibraryUiState =
    kept.copy(
        recent = current.recent,
        libraries = current.libraries,
        pageBusy = false,
        subfolders = kept.subfolders.copy(busy = false),
        restore = kept.restore.copy(serial = current.restore.serial),
        anchorMissing = false
    )
