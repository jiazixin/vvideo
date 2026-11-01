package dev.vvideo.app

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VVideoApplication : Application() {
    internal val graph by lazy { AppGraph(this) }
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _failure = MutableStateFlow(false)
    val failure = _failure.asStateFlow()
    private var initializing = false
    fun initialize() {
        if (initializing) return
        initializing = true
        _failure.value = false
        graph.scope.launch {
            try {
                graph.initialize()
                _ready.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _failure.value = true
            } finally {
                initializing = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initialize()
    }
}
