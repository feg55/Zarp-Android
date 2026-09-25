package io.github.feg55.zarp.masque

import io.github.feg55.zarp.core.L
import io.github.feg55.zarp.core.LogBus
import io.github.feg55.zarp.core.Strategy
import io.github.feg55.zarp.strategy.AndroidUdpSocket
import io.github.feg55.zarp.strategy.StrategySocket
import io.github.feg55.zarp.strategy.ZarpStrategy
import io.github.feg55.zarpcore.SocketFactory
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** Excludes sockets from the VPN (VpnService.protect while the VPN runs). */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

/**
 * Called by the Go core before every QUIC dial (first connect and each reconnect):
 * creates the UDP socket, lets the Zarp strategy send its fakes through it and
 * hands the very same socket to quic-go, which then sends the real Initial.
 */
class QuicSocketFactory(
    private val zarpStrategy: ZarpStrategy,
    private val strategy: Strategy,
    private val protector: SocketProtector,
    private val log: LogBus,
) : SocketFactory {

    override fun openUDP(host: String, port: Long): Long {
        val addr = InetAddress.getByName(host) // IP literal, no DNS lookup
        val socket = AndroidUdpSocket.open(addr)
        try {
            if (!protector.protect(socket.fdInt)) throw IOException("VpnService.protect failed")
            val counting = CountingSocket(socket)
            runBlocking { zarpStrategy.beforeHandshake(counting, InetSocketAddress(addr, port.toInt())) }
            if (counting.packets > 0) {
                val to = "$host:$port"
                val ttl = counting.lowTtl
                log.write(
                    "  " + if (ttl == null) L.t("log.fakes", strategy.name, counting.packets, counting.bytes, to)
                    else L.t("log.fakesTtl", strategy.name, counting.packets, counting.bytes, ttl, to)
                )
            }
            return socket.detach().toLong()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun protect(fd: Long): Boolean = protector.protect(fd.toInt())

    private class CountingSocket(private val inner: StrategySocket) : StrategySocket by inner {
        var packets = 0
        var bytes = 0
        var lowTtl: Int? = null
        private var normalTtl: Int? = null

        override fun send(payload: ByteArray, endpoint: InetSocketAddress) {
            inner.send(payload, endpoint)
            packets++
            bytes += payload.size
        }

        override fun getTtl(): Int = inner.getTtl().also { if (normalTtl == null) normalTtl = it }

        override fun setTtl(ttl: Int) {
            inner.setTtl(ttl)
            if (ttl != normalTtl) lowTtl = ttl
        }
    }
}
