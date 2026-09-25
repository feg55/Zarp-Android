package io.github.feg55.zarp.strategy

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A plain UDP socket created with android.system.Os, so the descriptor can be
 * handed to the Go core after the strategy used it.
 */
class AndroidUdpSocket private constructor(
    private val pfd: ParcelFileDescriptor,
    override val isIpv6: Boolean,
) : StrategySocket, AutoCloseable {
    private val fd: FileDescriptor get() = pfd.fileDescriptor

    /** Raw descriptor number, e.g. for VpnService.protect. */
    val fdInt: Int get() = pfd.fd

    override fun send(payload: ByteArray, endpoint: InetSocketAddress) {
        val sent = Os.sendto(fd, payload, 0, payload.size, 0, endpoint.address, endpoint.port)
        if (sent != payload.size) throw java.io.IOException("short send: $sent of ${payload.size}")
    }

    // Only this socket's own changes are tracked: a fresh socket uses the system
    // default, and -1 tells Linux to go back to it (IP_TTL / IPV6_UNICAST_HOPS).
    // (Os.getsockoptInt is not public API.)
    private var ttl = SYSTEM_DEFAULT_TTL

    override fun getTtl(): Int = ttl

    override fun setTtl(ttl: Int) {
        if (isIpv6) Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS, ttl)
        else Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, IP_TTL, ttl)
        this.ttl = ttl
    }

    /** Gives up ownership of the descriptor (to the Go core). */
    fun detach(): Int = pfd.detachFd()

    override fun close() = pfd.close()

    companion object {
        // Linux IP_TTL; OsConstants does not expose it on all API levels
        private const val IP_TTL = 2

        /** setsockopt value that restores the kernel default TTL / hop limit. */
        const val SYSTEM_DEFAULT_TTL = -1

        /** Unconnected UDP socket bound to an ephemeral port, same family as [remote]. */
        fun open(remote: InetAddress): AndroidUdpSocket {
            val v6 = remote is Inet6Address
            val raw = Os.socket(if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
            val pfd = try {
                ParcelFileDescriptor.dup(raw)
            } finally {
                Os.close(raw)
            }
            try {
                val any: InetAddress = if (v6) Inet6Address.getByName("::") else Inet4Address.getByName("0.0.0.0")
                Os.bind(pfd.fileDescriptor, any, 0)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            return AndroidUdpSocket(pfd, v6)
        }
    }
}
