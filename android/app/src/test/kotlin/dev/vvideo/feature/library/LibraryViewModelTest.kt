package dev.vvideo.feature.library

import dev.vvideo.data.library.FakeLibraryData
import dev.vvideo.data.library.LibraryError
import dev.vvideo.data.library.remote.ServiceFailure
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Bookmark
import dev.vvideo.model.Library
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
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun authenticationFailureKeepsBookmarksAndBecomesDomainState() = runTest(dispatcher) {
        val data = FakeLibraryData().apply { fetchLibraries = { throw ServiceFailure(401, "unauthenticated") } }
        val vm = LibraryViewModel(data, backgroundScope, dispatcher)
        vm.connect(Connection("https://example.invalid", "fixture"))
        advanceUntilIdle()
        assertEquals(LibraryError.Unauthenticated(), vm.state.value.failure)
        assertTrue(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertTrue(data.saved.isEmpty())
    }

    @Test
    fun aNewConnectionCancelsThePreviousRequest() = runTest(dispatcher) {
        var cancelled = false
        val data = FakeLibraryData().apply {
            fetchLibraries = {
                if (it.token == "old") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                listOf(Library("new", "New library", 1, true))
            }
        }
        val vm = LibraryViewModel(data, backgroundScope, dispatcher)
        vm.connect(Connection("https://example.invalid", "old"))
        dispatcher.scheduler.runCurrent()
        vm.connect(Connection("https://example.invalid", "new"))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("new", vm.state.value.libraries.single().id)
        assertFalse(vm.state.value.error)
    }

    @Test
    fun unreadableBookmarkBecomesAnErrorStateAndDoesNotCrash() = runTest(dispatcher) {
        val data = object : FakeLibraryData() {
            override suspend fun bookmark(connectionId: String, directoryId: Long): Bookmark? =
                throw IOException("unreadable fixture database")
        }
        val vm = LibraryViewModel(data, backgroundScope, dispatcher)
        vm.open(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertTrue(data.saved.isEmpty())
    }
}
