package dev.vvideo.feature.library

import dev.vvideo.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryKeysTest {
    private val dir = Directory(5, "lib", 1, "folder", 2)
    private val item =
        Media(
            "m1", "v", "lib", 5, "a.mp4", MediaKind.Video, 1920, 1080, 0, 1000, 10, 0, 0,
            MediaStatus.Ready, "", CaptureSource.Unknown
        )

    private fun page(found: Boolean, items: List<Media>) =
        MediaPage(dir, items, "", "", "", found, true)

    @Test
    fun missingAnchorWithNearbyMediaIsReported() {
        assertTrue(anchorMoved("gone", page(false, listOf(item))))
    }

    @Test
    fun foundAnchorOrNoSavedPositionIsNotReported() {
        assertFalse(anchorMoved("m1", page(true, listOf(item))))
        assertFalse(anchorMoved("", page(false, listOf(item))))
    }

    // An empty folder keeps its stale bookmark; every visit used to repeat the notice.
    @Test
    fun emptyFolderIsNotReported() {
        assertFalse(anchorMoved("gone", page(false, emptyList())))
    }

    @Test
    fun restoreFindsTheSavedRowOrTheAnchorAlbum() {
        val state =
            LibraryUiState(subfolders = Subfolders(listOf(dir.copy(id = 7), dir.copy(id = 8))))
        assertEquals(1, restoreIndex(libraryKeys(state), state.albums, "folder:8", ""))
        val albums = listOf(Album(listOf(item)))
        val keys = libraryKeys(LibraryUiState(albums = albums))
        assertEquals(0, restoreIndex(keys, albums, "album:gone", "m1"))
    }

    // Subfolders are listed above the albums and can be opened with one tap; going
    // back finds the subfolder row that was opened.
    @Test
    fun subfoldersComeBeforeAlbums() {
        val albums = listOf(Album(listOf(item)))
        val state = LibraryUiState(
            albums = albums,
            subfolders = Subfolders(listOf(dir.copy(id = 7), dir.copy(id = 8)))
        )
        val keys = libraryKeys(state)
        assertEquals(listOf("folder:7", "folder:8", "album:m1"), keys)
        assertEquals(1, restoreIndex(keys, albums, "folder:8", "m1"))
        assertTrue(isFolderKey("folder:8") && isPositionKey("folder:8") && isPositionKey("album:m1"))
        assertFalse(isPositionKey("date:m1"))
    }

    // Albums loaded from further in do not start the folder, so its subfolders wait
    // until paging back reaches the start; a saved subfolder row then falls back to the anchor.
    @Test
    fun subfoldersWaitForTheStartOfTheAlbums() {
        val albums = listOf(Album(listOf(item)))
        val state = LibraryUiState(
            albums = albums,
            hasPrevious = true,
            subfolders = Subfolders(listOf(dir.copy(id = 7)))
        )
        assertEquals(listOf("album:m1"), libraryKeys(state))
        assertEquals(0, restoreIndex(libraryKeys(state), albums, "folder:7", "m1"))
        val onlyFolders = LibraryUiState(hasPrevious = true, subfolders = state.subfolders)
        assertEquals(listOf("folder:7"), libraryKeys(onlyFolders))
    }

    // A row that is no longer shown gives no index, so its offset is not applied at the top.
    @Test
    fun missingRowGivesNoIndex() {
        val state = LibraryUiState(subfolders = Subfolders(listOf(dir.copy(id = 7))))
        assertNull(restoreIndex(libraryKeys(state), state.albums, "folder:9", "m1"))
    }
}
