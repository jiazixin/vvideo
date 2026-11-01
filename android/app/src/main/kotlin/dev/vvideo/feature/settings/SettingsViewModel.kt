package dev.vvideo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.cache.CacheSettings
import dev.vvideo.data.library.LibraryData
import dev.vvideo.data.library.LibraryError
import dev.vvideo.data.library.libraryError
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsData
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val settings: SettingsData,
    private val cache: CacheSettings,
    private val repository: LibraryData,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    val theme: Flow<ThemeMode> = settings.theme
    private var verifyJob: Job? = null

    /** Set while a connect attempt fails because the typed address is not a server root. */
    private var invalidAddress = false

    fun setTheme(mode: ThemeMode) {
        operation(SettingsMessage.SaveFailed) { settings.setTheme(mode) }
    }

    fun dismissMessage() {
        _state.update { current -> current.copy(message = null) }
        invalidAddress = false
    }

    fun usage() {
        viewModelScope.launch {
            try {
                val usage = cache.usage()
                _state.update { current -> current.copy(usage = usage) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { current -> current.copy(message = SettingsMessage.CacheFailed) }
            }
        }
    }

    /** Checks that the saved [connection] still reaches the server. */
    fun verify(connection: Connection) {
        if (!connection.configured || _state.value.busy) return
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            _state.update { current -> current.copy(connectionStatus = ConnectionStatus.Checking) }
            try {
                val libraries = repository.libraries(connection)
                _state.update { current ->
                    current.copy(
                        libraryCount = libraries.size,
                        connectionStatus = ConnectionStatus.Connected,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = if (e.libraryError() is LibraryError.Unauthenticated) {
                    ConnectionStatus.AuthenticationFailed
                } else {
                    ConnectionStatus.Offline
                }
                _state.update { current -> current.copy(connectionStatus = status) }
            }
        }
    }

    /** Saves the address and token once the server accepts them. */
    fun connect(address: String, token: String) {
        if (_state.value.busy) return
        verifyJob?.cancel()
        operation(SettingsMessage.ConnectionFailed) {
            _state.update { current -> current.copy(connectionStatus = ConnectionStatus.Checking) }
            invalidAddress = false
            val candidate = try {
                settings.candidate(address, token)
            } catch (e: Exception) {
                invalidAddress = true
                throw e
            }
            repository.libraries(candidate)
            settings.connect(candidate.address, candidate.token)
            _state.update { current ->
                current.copy(
                    connectionStatus = ConnectionStatus.Connected,
                    connectedSerial = current.connectedSerial + 1,
                )
            }
        }
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
        _state.update { current ->
            current.copy(
                message = SettingsMessage.CacheCleared,
                releasedBytes = (before.total - after.total).coerceAtLeast(0),
            )
        }
    }

    /**
     * Runs [block] unless another action is running. A failure is reported as
     * [failure]; a connect failure also becomes the connection status.
     */
    private fun operation(failure: SettingsMessage = SettingsMessage.CacheFailed, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { current -> current.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
                val usage = cache.usage()
                _state.update { current -> current.copy(usage = usage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when {
                    e.libraryError() is LibraryError.Unauthenticated -> SettingsMessage.AuthenticationFailed
                    invalidAddress -> SettingsMessage.InvalidAddress
                    else -> failure
                }
                _state.update { current ->
                    current.copy(
                        message = message,
                        connectionStatus = if (failure == SettingsMessage.ConnectionFailed) {
                            message.connectionStatus()
                        } else {
                            current.connectionStatus
                        },
                    )
                }
            } finally {
                _state.update { current -> current.copy(busy = false) }
            }
        }
    }

    private fun SettingsMessage.connectionStatus(): ConnectionStatus = when (this) {
        SettingsMessage.AuthenticationFailed -> ConnectionStatus.AuthenticationFailed
        SettingsMessage.InvalidAddress -> ConnectionStatus.InvalidAddress
        else -> ConnectionStatus.ConnectionFailed
    }
}
