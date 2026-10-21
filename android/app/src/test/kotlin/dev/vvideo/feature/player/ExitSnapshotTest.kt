package dev.vvideo.feature.player

import dev.vvideo.model.CaptureSource
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaStatus
import org.junit.Assert.*
import org.junit.Test

class ExitSnapshotTest {
    private val photo =
        Media(
            "7", "v", "l", 1, "n.jpg", MediaKind.Image, 4, 3, 0, 0, 1, 0, 0,
            MediaStatus.Ready, "", CaptureSource.Modified
        )
    private val shown =
        PlayerUiState(pathLabel = "media › large", initialId = "7", items = listOf(photo))

    @Test
    fun followsLiveStateUntilLeaving() {
        val snapshot = ExitSnapshot(PlayerUiState(initialId = "7"))
        assertEquals(
            PlayerUiState(initialId = "7"),
            snapshot.resolve(PlayerUiState(initialId = "7"), false)
        )
        assertSame(shown, snapshot.resolve(shown, false))
    }

    @Test
    fun leavingKeepsLastShownStateAfterViewModelClears() {
        val snapshot = ExitSnapshot(shown)
        snapshot.resolve(shown, false)
        assertSame(shown, snapshot.resolve(PlayerUiState(), true))
        assertSame(shown, snapshot.resolve(PlayerUiState(initialId = "9"), true))
    }

    @Test
    fun leavingBeforeClearStillFreezes() {
        val snapshot = ExitSnapshot(shown)
        assertSame(shown, snapshot.resolve(shown, true))
        assertSame(shown, snapshot.resolve(PlayerUiState(), true))
    }

    @Test
    fun interruptedExitFollowsNewStateWithoutOldMedia() {
        val snapshot = ExitSnapshot(shown)
        snapshot.resolve(PlayerUiState(), true)
        val reopened = PlayerUiState(initialId = "9")
        assertSame(reopened, snapshot.resolve(reopened, false))
        assertTrue(snapshot.resolve(reopened, false).items.isEmpty())
    }
}
