package dev.vvideo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dev.vvideo.data.cache.CacheCoordinator
import dev.vvideo.data.cache.CacheUsage
import dev.vvideo.data.library.LibraryRepository
import dev.vvideo.data.library.remote.HTTP_UNAUTHORIZED
import dev.vvideo.data.library.remote.serviceStatus
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The outcome of the last settings action, shown once next to the control that caused it. */
enum class SettingsMessage {
    InvalidAddress,
    ConnectionFailed,
    AuthenticationFailed,
    CacheFailed,
    CacheCleared
}

/** What the settings page says about the saved connection. */
enum class ConnectionStatus {
    Saved,
    Checking,
    Connected,
    AuthenticationFailed,
    Offline,
    InvalidAddress,
    ConnectionFailed
}

data class SettingsUiState(
    val libraryCount: Int = 0,
    val releasedBytes: Long = 0,
    val busy: Boolean = false,
    val message: SettingsMessage? = null,
    val usage: CacheUsage? = null,
    /** Counts successful connections, so the form can close on the next one. */
    val connectedSerial: Long = 0,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Saved
)

@UnstableApi
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val cache: CacheCoordinator,
    private val repository: LibraryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    val theme: Flow<ThemeMode> = settings.theme
    private var verifyJob: Job? = null

    /** Set while a connect attempt fails because the typed address is not a server root. */
    private var invalidAddress = false

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
        invalidAddress = false
    }

    fun usage() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(usage = cache.usage())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(message = SettingsMessage.CacheFailed)
            }
        }
    }

    /** Checks that the saved [connection] still reaches the server. */
    fun verify(connection: Connection) {
        if (!connection.configured) return
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Checking)
            try {
                val libraries = repository.libraries(connection)
                _state.value = _state.value.copy(
                    libraryCount = libraries.size,
                    connectionStatus = ConnectionStatus.Connected
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = if (e.serviceStatus() == HTTP_UNAUTHORIZED) {
                    ConnectionStatus.AuthenticationFailed
                } else {
                    ConnectionStatus.Offline
                }
                _state.value = _state.value.copy(connectionStatus = status)
            }
        }
    }

    /** Saves the address and token once the server accepts them. */
    fun connect(address: String, token: String) = operation(SettingsMessage.ConnectionFailed) {
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Checking)
        invalidAddress = false
        val candidate = try {
            settings.candidate(address, token)
        } catch (e: Exception) {
            invalidAddress = true
            throw e
        }
        repository.libraries(candidate)
        settings.connect(candidate.address, candidate.token)
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.Connected,
            connectedSerial = _state.value.connectedSerial + 1
        )
    }

    fun setBudget(bytes: Long) = operation {
        cache.configure(bytes)
        settings.setBudget(bytes)
    }

    /** Empties the media caches, which then start over within [budget]. */
    fun clearCache(budget: Long) = operation {
        val before = cache.usage()
        cache.configure(budget, clear = true)
        val after = cache.usage()
        _state.value = _state.value.copy(
            message = SettingsMessage.CacheCleared,
            releasedBytes = (before.total - after.total).coerceAtLeast(0)
        )
    }

    /**
     * Runs [block] unless another action is running. A failure is reported as
     * [failure]; a connect failure also becomes the connection status.
     */
    private fun operation(
        failure: SettingsMessage = SettingsMessage.CacheFailed,
        block: suspend () -> Unit
    ) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                block()
                _state.value = _state.value.copy(usage = cache.usage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when {
                    e.serviceStatus() == HTTP_UNAUTHORIZED -> SettingsMessage.AuthenticationFailed
                    invalidAddress -> SettingsMessage.InvalidAddress
                    else -> failure
                }
                _state.value = _state.value.copy(
                    message = message,
                    connectionStatus = if (failure == SettingsMessage.ConnectionFailed) {
                        message.connectionStatus()
                    } else {
                        _state.value.connectionStatus
                    }
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun SettingsMessage.connectionStatus(): ConnectionStatus = when (this) {
        SettingsMessage.AuthenticationFailed -> ConnectionStatus.AuthenticationFailed
        SettingsMessage.InvalidAddress -> ConnectionStatus.InvalidAddress
        else -> ConnectionStatus.ConnectionFailed
    }
}
