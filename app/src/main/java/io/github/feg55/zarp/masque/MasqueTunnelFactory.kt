package io.github.feg55.zarp.masque

import io.github.feg55.zarp.core.AppSettings
import io.github.feg55.zarp.core.LogBus
import io.github.feg55.zarp.core.Strategy
import io.github.feg55.zarp.core.TunnelException
import io.github.feg55.zarp.core.TunnelFactory
import io.github.feg55.zarp.core.TunnelHandle
import io.github.feg55.zarp.core.Transport
import io.github.feg55.zarp.strategy.BlobSource
import io.github.feg55.zarp.strategy.UnsupportedStrategyException
import io.github.feg55.zarp.strategy.ZarpStrategies
import io.github.feg55.zarpcore.StatusListener
import io.github.feg55.zarpcore.Tunnel
import io.github.feg55.zarpcore.Zarpcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opens tunnels in the Go core (usque MASQUE) with the strategy applied to every handshake. */
class MasqueTunnelFactory(
    private val configPath: String,
    private val blobs: BlobSource,
    private val settings: suspend () -> AppSettings,
    private val protector: SocketProtector,
    private val log: LogBus,
) : TunnelFactory {

    override suspend fun open(
        strategy: Strategy,
        endpoint: String?,
        timeoutMs: Int,
        persistent: Boolean,
        onStatus: (connected: Boolean, message: String) -> Unit,
    ): TunnelHandle {
        val zarpStrategy = try {
            ZarpStrategies.forStrategy(strategy, blobs)
        } catch (e: UnsupportedStrategyException) {
            throw TunnelException("unsupported on Android: ${e.message}")
        }
        val s = settings()
        val opts = Zarpcore.newTunnelOptions().apply {
            setEndpoint(endpoint.orEmpty())
            setHTTP2(strategy.transport == Transport.MasqueH2)
            setTCPDesync(strategy.plan.tcpDesync.orEmpty())
            setConnectTimeoutMs(timeoutMs.toLong())
            setIPv6(s.tunnelIpv6)
            setDNS(s.dns)
            setReconnect(persistent)
        }
        val sockets = QuicSocketFactory(zarpStrategy, strategy, protector, log)
        val listener = StatusListener { connected, message -> onStatus(connected, message) }
        val tunnel = withContext(Dispatchers.IO) {
            try {
                Zarpcore.newTunnel(configPath, opts, sockets, listener)
            } catch (e: Exception) {
                throw TunnelException(e.message ?: "tunnel setup failed")
            }
        }
        connect(tunnel)
        return GoTunnel(tunnel)
    }

    /** Tunnel.connect blocks in Go; cancelling the coroutine closes the tunnel, which aborts the dial. */
    private suspend fun connect(tunnel: Tunnel) = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { tunnel.close() }
        thread(name = "zarp-connect", isDaemon = true) {
            try {
                tunnel.connect()
                if (cont.isActive) cont.resume(Unit) else tunnel.close()
            } catch (e: Exception) {
                tunnel.close()
                val msg = e.message ?: "connect failed"
                if (cont.isActive) cont.resumeWithException(TunnelException(msg, timeout = msg.startsWith("timeout")))
            }
        }
    }

    private class GoTunnel(private val t: Tunnel) : TunnelHandle {
        override val connectMs: Int = t.connectMillis().toInt()
        override val socksPort: Int = t.socksPort().toInt()
        override val endpoint: String = t.endpointString()
        override fun close() = t.close()
    }
}
