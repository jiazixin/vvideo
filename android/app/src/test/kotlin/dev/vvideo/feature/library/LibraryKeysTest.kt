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

    // A row that is no longer shown gives no index, so its offset is not applied at the top.
    @Test
    fun missingRowGivesNoIndex() {
        val state = LibraryUiState(subfolders = Subfolders(listOf(dir.copy(id = 7))))
        assertNull(restoreIndex(libraryKeys(state), state.albums, "folder:9", "m1"))
    }
}
