package io.github.feg55.zarp.core

import io.github.feg55.zarp.net.Measurement
import io.github.feg55.zarp.net.WarpProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The VPN could not be started; this is not the strategy's fault. */
class VpnStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Port of Zarp's Engine (Engine.cs): strategy search, independent re-check,
 * scoring, remembering the best strategy and connecting with it.
 *
 * Differences from Windows: there is no warp-cli. Each test starts its own MASQUE
 * tunnel ([TunnelFactory]) on its own WARP endpoint, and WARP is verified through
 * that tunnel with cdn-cgi/trace ([WarpProbe]). Connecting keeps the tunnel and puts
 * the Android VPN in front of it ([VpnController]).
 */
class ZarpEngine(
    private val scope: CoroutineScope,
    private val store: ZarpStore,
    private val tunnels: TunnelFactory,
    private val probe: WarpProbe,
    private val vpn: VpnController,
    private val account: WarpAccount,
    private val network: NetworkInspector,
    private val log: LogBus,
    private val endpoints: WarpEndpoints = WarpEndpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _results = MutableStateFlow<Map<String, TestResult>>(emptyMap())
    val results: StateFlow<Map<String, TestResult>> = _results.asStateFlow()

    private val _strategies = MutableStateFlow(StrategyCatalog.builtIn)
    val strategies: StateFlow<List<Strategy>> = _strategies.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val busy = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var settings = AppSettings()

    @Volatile
    private var active: TunnelHandle? = null

    val selected: Strategy? get() = _strategies.value.firstOrNull { it.id == _selectedId.value }

    /** Quick scan stops after this many working strategies. */
    val quickStopAfter: Int get() = maxOf(1, settings.stopAfterWorking)

    /** Load persisted state; call once at startup. */
    suspend fun load() {
        settings = store.settings()
        reloadStrategies()
        // results of strategies that no longer exist are dropped, as in Zarp
        val known = _strategies.value.map { it.id }.toSet()
        _results.value = store.results().filterKeys { it in known }
        _selectedId.value = store.selectedStrategyId()
        val s = selected
        set(EngineState.Idle, if (s != null) "Strategy: ${s.name}" else "No strategy chosen yet")
        loaded.complete(Unit)
    }

    private fun reloadStrategies() {
        _strategies.value = StrategyCatalog.load(settings.customStrategies) { log.write("Custom strategy skipped: $it") }
    }

    // ------------------------------------------------------------ top-level scenarios

    /** Main button: connect with the saved strategy, then other confirmed ones, else Quick Scan. */
    fun connect(): Boolean = launchOp {
        prepare()
        val s = selected
        if (s != null) {
            if (apply(s)) return@launchOp
            markFailed(s)
            val others = confirmedStrategies(except = s)
            if (others.isNotEmpty()) {
                log.write("Saved strategy \"${s.name}\" did not connect, trying other confirmed strategies.")
                if (applyFirstWorking(others)) return@launchOp
            }
            log.write("Verified strategies did not work, searching again.")
        }
        searchAndApply(_strategies.value, quickStopAfter, "Quick scan: ${_strategies.value.size} strategies, stop after $quickStopAfter working")
    }

    /** Quick scan stops after [quickStopAfter] working strategies; Full scan tests all of them. */
    fun search(full: Boolean): Boolean = launchOp {
        prepare()
        val list = _strategies.value
        if (full) searchAndApply(list, 0, "Full scan: ${list.size} strategies")
        else searchAndApply(list, quickStopAfter, "Quick scan: ${list.size} strategies, stop after $quickStopAfter working")
    }

    /** Test only the given strategies (all of them, no early stop). */
    fun testStrategies(only: List<Strategy>): Boolean = launchOp {
        prepare()
        searchAndApply(only, 0, "Testing selected: ${only.size}")
    }

    /** Connect with a specific strategy and remember it. */
    fun useStrategy(s: Strategy): Boolean = launchOp {
        prepare()
        if (apply(s)) saveSelected(s)
    }

    fun disconnect(): Boolean = launchOp {
        set(EngineState.Disconnecting, "Disconnecting...")
        stopAll()
        set(EngineState.Idle, "Disconnected")
    }

    fun cancel() {
        job?.cancel()
    }

    /** The VPN went away (revoked by the system or another VPN app, or stopped from the notification). */
    fun onVpnStopped(reason: String) {
        scope.launch {
            job?.cancelAndJoin()
            busy.withLock {
                val had = active != null
                active?.close()
                active = null
                if (had) {
                    log.write(reason)
                    set(EngineState.Idle, reason)
                }
            }
        }
    }

    private fun launchOp(body: suspend () -> Unit): Boolean {
        if (!busy.tryLock()) return false // already busy
        job = scope.launch {
            try {
                loaded.await()
                settings = store.settings()
                reloadStrategies()
                body()
            } catch (e: CancellationException) {
                log.write("Operation cancelled.")
                withContext(NonCancellable) { stopAll() }
                set(EngineState.Idle, "Cancelled")
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                log.write("Error: $msg")
                withContext(NonCancellable) { stopAll() }
                set(EngineState.Error, msg)
            } finally {
                _status.update { it.copy(progressDone = 0, progressTotal = 0) }
                busy.unlock()
            }
        }
        return true
    }

    // ------------------------------------------------------------ steps

    private suspend fun prepare() {
        set(EngineState.Preparing, "Preparing...")
        // our own tunnel and VPN go first: tests must not run through them
        stopAll()
        if (network.foreignVpnActive()) {
            log.write("Warning: another VPN is active and WARP traffic goes through it.")
            log.write("Turn off the other VPN, otherwise WARP may not connect and the strategy will be chosen wrongly.")
        }
        if (!account.registered) log.write("WARP is not registered, registering...")
        try {
            account.ensureRegistered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.write("WARP registration failed: ${e.message}")
            throw Exception("Could not register WARP: ${e.message}", e)
        }
    }

    /**
     * Test one strategy: new tunnel on its own WARP endpoint, then cdn-cgi/trace through it.
     * Each test uses a different endpoint so it cannot inherit DPI state from the previous connection.
     */
    internal suspend fun test(s: Strategy): TestResult {
        val now = clock()
        s.plan.unsupportedReason?.let {
            return TestResult(strategyId = s.id, unsupported = true, error = "unsupported on Android: $it", timestamp = now)
        }
        val endpoint = if (settings.isolateTests) endpoints.next(s.transport) else null
        val tunnel = try {
            tunnels.open(s, endpoint, settings.testTimeoutSec * 1000, persistent = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TunnelException) {
            val msg = if (e.timeout) "no connection within ${settings.testTimeoutSec} s" else e.message.orEmpty()
            return TestResult.failed(s.id, msg, now, endpoint)
        } catch (e: Exception) {
            return TestResult.failed(s.id, e.message ?: e.javaClass.simpleName, now, endpoint)
        }
        try {
            return when (val m = probe.measure(tunnel.socksPort, 3)) {
                is Measurement.Ok -> TestResult(
                    strategyId = s.id, ok = true, connectMs = tunnel.connectMs, pingMs = m.pingMs,
                    endpoint = tunnel.endpoint, timestamp = now,
                )
                is Measurement.NotWarp -> TestResult.failed(
                    s.id, "cdn-cgi/trace says warp=${m.warp ?: "?"}: traffic does not go through WARP", now, tunnel.endpoint,
                )
                is Measurement.NoTraffic -> TestResult.failed(
                    s.id, "WARP is connected, but no traffic goes through it" + (m.lastError?.let { " ($it)" } ?: ""),
                    now, tunnel.endpoint,
                )
            }
        } finally {
            tunnel.close()
        }
    }

    /** @param stopAfter stop phase 1 after this many working strategies; 0 = test all. */
    private suspend fun searchAndApply(list: List<Strategy>, stopAfter: Int, title: String) {
        val candidates = mutableListOf<Pair<Strategy, TestResult>>()
        log.write(title)
        setProgress(0, list.size)

        // ---- phase 1: go through the list
        for (s in list) {
            coroutineContext.ensureActive()
            set(EngineState.Searching, "${progressDone() + 1}/${list.size}: ${s.name}", strategy = s)
            val r = test(s)
            putResult(r)
            setProgress(progressDone() + 1, list.size)
            log.write("  " + if (r.ok) "✔ ${s.name}: connection ${r.connectMs} ms, ping ${r.pingMs} ms" else "✘ ${s.name}: ${r.displayError}")
            if (r.ok) {
                candidates += s to r
                if (stopAfter > 0 && candidates.size >= stopAfter) break
            }
        }
        saveResults()

        // ---- phase 2: independent re-check of every candidate (another endpoint, fresh tunnel).
        // Weeds out strategies that "passed" only thanks to the previous successful connection.
        if (candidates.isNotEmpty()) {
            log.write("Candidates to re-check: ${candidates.size}")
            setProgress(0, candidates.size)
            for ((k, pair) in candidates.sortedBy { it.second.score }.withIndex()) {
                val (s, r1) = pair
                coroutineContext.ensureActive()
                set(EngineState.Searching, "Re-check ${k + 1}/${candidates.size}: ${s.name}", strategy = s)
                val r2 = test(s)
                setProgress(k + 1, candidates.size)
                if (r2.ok) {
                    val merged = TestResult.confirmed(r1, r2)
                    putResult(merged)
                    log.write("  ✔✔ ${s.name}: confirmed (connection ${r2.connectMs} ms, ping ${r2.pingMs} ms)")
                } else {
                    putResult(r2.copy(rechecked = true))
                    log.write("  ✘ ${s.name}: ${r2.copy(rechecked = true).displayError}")
                }
                saveResults()
            }
        }

        val confirmed = confirmedStrategies(except = null)
        if (confirmed.isEmpty()) {
            stopAll()
            log.write(
                if (candidates.isNotEmpty()) "The candidates failed the re-check, they probably passed by chance. Search again or increase the timeout."
                else "No strategy worked. Increase the timeout in settings or add your own strategies."
            )
            set(EngineState.Error, "No working strategy found")
            return
        }

        setProgress(0, 0)
        val best = confirmed[0]
        val br = _results.value.getValue(best.id)
        log.write("Best strategy: ${best.name} (connection ${br.connectMs} ms, ping ${br.pingMs} ms)")
        if (!applyFirstWorking(confirmed)) set(EngineState.Error, "Strategies found, but connecting failed")
    }

    /** Confirmed strategies, best (lowest score) first. */
    fun confirmedStrategies(except: Strategy?): List<Strategy> {
        val res = _results.value
        return _strategies.value
            .filter { it != except && res[it.id]?.let { r -> r.ok && r.confirmed } == true }
            .sortedBy { res.getValue(it.id).score }
    }

    /** Connect with the first strategy in the list that really connects; remember it. */
    private suspend fun applyFirstWorking(ordered: List<Strategy>): Boolean {
        for (s in ordered) {
            coroutineContext.ensureActive()
            if (apply(s)) {
                saveSelected(s)
                return true
            }
            markFailed(s)
            log.write("\"${s.name}\" did not connect, trying the next one.")
        }
        return false
    }

    private suspend fun markFailed(s: Strategy) {
        putResult(TestResult.failed(s.id, "failed to connect when applied", clock()))
        saveResults()
    }

    /** Connect with the given strategy: persistent tunnel, WARP check, then the VPN. */
    private suspend fun apply(s: Strategy): Boolean {
        set(EngineState.Connecting, "Connecting: ${s.name}", strategy = s)
        log.write("Connecting with strategy: ${s.name}")
        stopAll()
        s.plan.unsupportedReason?.let {
            log.write("\"${s.name}\" is unsupported on Android: $it")
            set(EngineState.Error, "Could not connect")
            return false
        }

        val timeoutMs = maxOf(30, settings.testTimeoutSec * 2) * 1000
        val tunnel = try {
            tunnels.open(s, null, timeoutMs, persistent = true, onStatus = ::onTunnelStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.write("WARP did not connect: ${e.message}")
            set(EngineState.Error, "Could not connect")
            return false
        }
        val m = try {
            probe.measure(tunnel.socksPort, 1)
        } catch (e: CancellationException) {
            tunnel.close()
            throw e
        }
        if (m !is Measurement.Ok) {
            tunnel.close()
            log.write(
                when (m) {
                    is Measurement.NotWarp -> "cdn-cgi/trace says warp=${m.warp ?: "?"}: traffic does not go through WARP."
                    else -> "WARP is connected, but no traffic goes through it."
                }
            )
            set(EngineState.Error, "Could not connect")
            return false
        }
        try {
            vpn.start(tunnel.socksPort, "Zarp: ${s.name}")
        } catch (e: Throwable) {
            tunnel.close()
            if (e is CancellationException) throw e
            throw if (e is VpnStartException) e else VpnStartException("VPN did not start: ${e.message}", e)
        }
        active = tunnel
        log.write("WARP connected in ${tunnel.connectMs} ms (ping ${m.pingMs} ms, ${tunnel.endpoint}).")
        set(
            EngineState.Connected, "Strategy: ${s.name}", strategy = s,
            connectMs = tunnel.connectMs, pingMs = m.pingMs, endpoint = tunnel.endpoint,
        )
        return true
    }

    private fun onTunnelStatus(connected: Boolean, message: String) {
        if (active == null) return
        _status.update {
            if (connected) it.copy(state = EngineState.Connected, detail = "Strategy: ${it.strategy?.name.orEmpty()}")
            else it.copy(state = EngineState.Connecting, detail = "Connection lost, reconnecting... ($message)")
        }
    }

    private suspend fun stopAll() {
        val t = active
        active = null
        t?.close()
        vpn.stop()
    }

    // ------------------------------------------------------------ state helpers

    private suspend fun saveSelected(s: Strategy) {
        _selectedId.value = s.id
        store.setSelectedStrategyId(s.id)
    }

    private fun putResult(r: TestResult) {
        _results.update { it + (r.strategyId to r) }
    }

    private suspend fun saveResults() = store.saveResults(_results.value)

    private fun progressDone() = _status.value.progressDone

    private fun setProgress(done: Int, total: Int) {
        _status.update { it.copy(progressDone = done, progressTotal = total) }
    }

    private fun set(
        state: EngineState,
        detail: String,
        strategy: Strategy? = _status.value.strategy,
        connectMs: Int? = null,
        pingMs: Int? = null,
        endpoint: String? = null,
    ) {
        _status.update {
            it.copy(state = state, detail = detail, strategy = strategy, connectMs = connectMs, pingMs = pingMs, endpoint = endpoint)
        }
    }
}
