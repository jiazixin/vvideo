package dev.vvideo.feature.settings

import dev.vvideo.data.cache.CacheSettings
import dev.vvideo.data.cache.CacheUsage
import dev.vvideo.data.library.FakeLibraryData
import dev.vvideo.data.library.remote.ServiceFailure
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsData
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rejectedConnectionDoesNotOverwriteSavedCredentials() = runTest(dispatcher) {
        val settings = FakeSettings()
        val data = FakeLibraryData().apply {
            fetchLibraries =
                { throw ServiceFailure(401, "unauthenticated", "request-1") }
        }
        val vm = SettingsViewModel(settings, FakeCache(), data)
        vm.connect("https://example.invalid", "candidate")
        advanceUntilIdle()
        assertEquals("saved", settings.connection.value.token)
        assertEquals(SettingsMessage.AuthenticationFailed, vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertEquals(0L, vm.state.value.connectedSerial)
    }

    @Test
    fun connectCancelsVerificationOfTheOldConnection() = runTest(dispatcher) {
        val settings = FakeSettings()
        var cancelled = false
        val data = FakeLibraryData().apply {
            fetchLibraries = {
                if (it.token == "saved") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                emptyList()
            }
        }
        val vm = SettingsViewModel(settings, FakeCache(), data)
        vm.verify(settings.connection.value)
        dispatcher.scheduler.runCurrent()
        vm.connect("https://example.invalid", "replacement")
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("replacement", settings.connection.value.token)
        assertEquals(ConnectionStatus.Connected, vm.state.value.connectionStatus)
        assertEquals(1L, vm.state.value.connectedSerial)
    }

    @Test
    fun simultaneousClearActionsAreCoalescedAndMessageIsConsumed() = runTest(dispatcher) {
        val cache = FakeCache()
        val vm = SettingsViewModel(FakeSettings(), cache, FakeLibraryData())
        vm.clearCache(1_000_000_000)
        vm.clearCache(1_000_000_000)
        advanceUntilIdle()
        assertEquals(1, cache.clears)
        assertEquals(120L, vm.state.value.releasedBytes)
        assertEquals(SettingsMessage.CacheCleared, vm.state.value.message)
        vm.dismissMessage()
        assertEquals(null, vm.state.value.message)
    }

    private class FakeCache : CacheSettings {
        var clears = 0
        override suspend fun configure(budget: Long, clear: Boolean) {
            if (clear) clears++
        }
        override suspend fun usage() = CacheUsage(video = if (clears == 0) 120 else 0)
    }

    private class FakeSettings : SettingsData {
        override val theme = MutableStateFlow(ThemeMode.System)
        override val connection = MutableStateFlow(Connection("https://example.invalid", "saved"))
        override fun candidate(url: String, token: String) = Connection(url, token)
        override suspend fun connect(url: String, token: String) {
            connection.value = candidate(url, token)
        }
        override suspend fun setTheme(mode: ThemeMode) {
            theme.value = mode
        }
        override suspend fun setBudget(bytes: Long) = Unit
        override suspend fun setLastDirectory(directoryId: Long) = Unit
    }
}
