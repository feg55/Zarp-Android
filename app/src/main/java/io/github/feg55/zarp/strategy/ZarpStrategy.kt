package io.github.feg55.zarp.strategy

import io.github.feg55.zarp.core.Blob
import io.github.feg55.zarp.core.FakeStep
import io.github.feg55.zarp.core.Strategy
import io.github.feg55.zarp.core.Transport
import java.net.InetSocketAddress

/**
 * The UDP socket the MASQUE/QUIC connection will use. Strategies write their
 * fake packets through it, so fakes and the real QUIC Initial share one 5-tuple.
 */
interface StrategySocket {
    val isIpv6: Boolean

    fun send(payload: ByteArray, endpoint: InetSocketAddress)

    /** Current unicast TTL (IPv4) or hop limit (IPv6). */
    fun getTtl(): Int

    fun setTtl(ttl: Int)
}

/**
 * A Zarp strategy on Android. Runs on the fresh UDP socket after the WARP endpoint
 * is chosen and before the QUIC handshake:
 *
 *   create UDP socket -> choose endpoint -> beforeHandshake (fakes)
 *   -> real QUIC Initial from the same socket -> MASQUE handshake
 *
 * It runs again before every reconnect.
 */
interface ZarpStrategy {
    suspend fun beforeHandshake(socket: StrategySocket, endpoint: InetSocketAddress)
}

/** Zarp's "direct" control strategy: no desync, WARP connects as is. */
object DirectStrategy : ZarpStrategy {
    override suspend fun beforeHandshake(socket: StrategySocket, endpoint: InetSocketAddress) = Unit
}

/**
 * zapret2 `--lua-desync=fake:blob=...:repeats=N[:ip_ttl=N:ip6_ttl=N]`, applied in order.
 * TTL is lowered only for the fakes and restored before the real Initial leaves.
 */
class FakeStrategy(
    private val steps: List<FakeStep>,
    private val blobs: BlobSource,
) : ZarpStrategy {
    init {
        require(steps.isNotEmpty()) { "FakeStrategy needs at least one step" }
    }

    override suspend fun beforeHandshake(socket: StrategySocket, endpoint: InetSocketAddress) {
        for (step in steps) {
            val payload = blobs.bytes(step.blob)
            val ttl = if (socket.isIpv6) step.ip6Ttl else step.ipTtl
            if (ttl == null) {
                repeat(step.repeats) { socket.send(payload, endpoint) }
            } else {
                val normal = socket.getTtl()
                socket.setTtl(ttl)
                try {
                    repeat(step.repeats) { socket.send(payload, endpoint) }
                } finally {
                    socket.setTtl(normal)
                }
            }
        }
    }
}

/** Source of fake payload bytes. */
fun interface BlobSource {
    fun bytes(blob: Blob): ByteArray
}

class UnsupportedStrategyException(reason: String) : Exception(reason)

object ZarpStrategies {
    /** The socket-level strategy for an HTTP/3 strategy; HTTP/2 desync is done by the core. */
    fun forStrategy(s: Strategy, blobs: BlobSource): ZarpStrategy {
        s.plan.unsupportedReason?.let { throw UnsupportedStrategyException(it) }
        return when {
            s.transport != Transport.MasqueH3 -> DirectStrategy
            s.plan.fakes.isEmpty() -> DirectStrategy
            else -> FakeStrategy(s.plan.fakes, blobs)
        }
    }
}
