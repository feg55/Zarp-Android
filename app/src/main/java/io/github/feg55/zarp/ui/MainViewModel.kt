package io.github.feg55.zarp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.feg55.zarp.ZarpApp
import io.github.feg55.zarp.core.AppSettings
import io.github.feg55.zarp.core.Strategy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val zarp = app as ZarpApp
    val engine = zarp.engine
    val log = zarp.log

    val status = engine.status
    val results = engine.results
    val strategies = engine.strategies
    val selectedId = engine.selectedId
    val lines = log.lines
    val settings: StateFlow<AppSettings?> =
        zarp.store.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val accountRegistered: Boolean get() = zarp.account.registered

    fun connect() = engine.connect()
    fun disconnect() = engine.disconnect()
    fun cancel() = engine.cancel()
    fun quickScan() = engine.search(full = false)
    fun fullScan() = engine.search(full = true)
    fun use(s: Strategy) = engine.useStrategy(s)
    fun test(list: List<Strategy>) = engine.testStrategies(list)

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { zarp.store.updateSettings(transform) }
    }

    fun clearResults() {
        viewModelScope.launch {
            zarp.store.clearResults()
            engine.load()
        }
    }

    fun resetAccount() {
        zarp.account.reset()
        log.write("WARP registration removed; a new device is registered on the next connect.")
    }
}
