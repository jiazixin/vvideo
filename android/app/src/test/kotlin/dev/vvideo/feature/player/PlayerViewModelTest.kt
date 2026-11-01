package dev.vvideo.feature.player

import dev.vvideo.data.library.FakeLibraryData
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.CaptureSource
import dev.vvideo.model.Directory
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaPage
import dev.vvideo.model.MediaStatus
import dev.vvideo.model.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun leavingCancelsAnOpeningQueueWithoutShowingAnError() = runTest(dispatcher) {
        var cancelled = false
        val data = FakeLibraryData().apply {
            fetchPage = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val vm = PlayerViewModel(data)
        vm.open(Connection(), 1, Sort(), "first", MediaKind.Video)
        dispatcher.scheduler.runCurrent()
        vm.leave()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.initialId.isEmpty())
    }

    @Test
    fun loadFailureBecomesStateAndLeavingClearsIt() = runTest(dispatcher) {
        val data = FakeLibraryData().apply { fetchPage = { throw IOException("offline") } }
        val vm = PlayerViewModel(data)
        vm.open(Connection(), 1, Sort(), "first", MediaKind.Video)
        advanceUntilIdle()
        assertTrue(vm.state.value.error)
        vm.leave()
        assertFalse(vm.state.value.error)
    }

    @Test
    fun anchoredLastPageEndsImmediatelyAndReopensWhenEvicted() = runTest(dispatcher) {
        val requests = mutableListOf<String>()
        val data = FakeLibraryData().apply {
            fetchPage = { cursor ->
                requests += cursor
                val index = cursor.removePrefix("previous-").toInt()
                page(index, next = if (index == 3) "" else "next-$index")
            }
        }
        val vm = PlayerViewModel(data)
        vm.open(Connection(), 1, Sort(), "3", MediaKind.Video)
        advanceUntilIdle()
        assertTrue(vm.state.value.endReached)
        vm.page(PageEdge.Next)
        vm.page(PageEdge.Previous)
        advanceUntilIdle()
        assertEquals(listOf("3", "previous-2"), requests)
        assertEquals(listOf("2", "3"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.endReached)
        vm.page(PageEdge.Previous)
        advanceUntilIdle()
        assertTrue(vm.state.value.endReached)
        vm.page(PageEdge.Previous)
        advanceUntilIdle()
        assertEquals(listOf("0", "1", "2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.endReached)
        vm.leave()
    }

    @Test
    fun appendingLastPageNeedsNoExtraEmptyRequest() = runTest(dispatcher) {
        val requests = mutableListOf<String>()
        val data = FakeLibraryData().apply {
            fetchPage = { cursor ->
                requests += cursor
                when (cursor) {
                    "0" -> page(0, next = "last")
                    "last" -> page(1, next = "")
                    else -> error("Unexpected page request: $cursor")
                }
            }
        }
        val vm = PlayerViewModel(data)
        vm.open(Connection(), 1, Sort(), "0", MediaKind.Video)
        advanceUntilIdle()
        assertFalse(vm.state.value.endReached)
        vm.page(PageEdge.Next)
        advanceUntilIdle()
        assertTrue(vm.state.value.endReached)
        vm.page(PageEdge.Next)
        advanceUntilIdle()
        assertEquals(listOf("0", "last"), requests)
        assertEquals(listOf("0", "1"), vm.state.value.items.map { it.id })
        vm.leave()
    }

    private fun page(index: Int, next: String) = MediaPage(
        Directory(1, "fixture", 0, "root", 1),
        listOf(
            Media(
                index.toString(), "v", "fixture", 1, "video", MediaKind.Video,
                320, 180, 0, 1000, 1, 0, 0, MediaStatus.Ready, "", CaptureSource.Modified,
            ),
        ),
        next,
        "previous-${index - 1}",
        "",
        true,
        true,
    )
}
