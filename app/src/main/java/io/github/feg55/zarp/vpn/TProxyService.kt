package io.github.feg55.zarp.vpn

/**
 * JNI binding of hev-socks5-tunnel (TUN -> SOCKS5). The native side registers
 * these methods on io/github/feg55/zarp/vpn/TProxyService (PKGNAME in build.gradle.kts).
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    /** tx packets, tx bytes, rx packets, rx bytes */
    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
