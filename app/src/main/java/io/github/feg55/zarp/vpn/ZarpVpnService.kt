package io.github.feg55.zarp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.feg55.zarp.MainActivity
import io.github.feg55.zarp.R
import io.github.feg55.zarp.ZarpApp
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * Android VPN: all apps except Zarp itself -> TUN -> hev-socks5-tunnel -> the
 * local SOCKS5 proxy of the MASQUE core -> Cloudflare WARP.
 *
 * Zarp's own app is excluded from the VPN, so the core's MASQUE sockets (and
 * scans) always go straight to the network; sockets are also protect()-ed.
 */
class ZarpVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var configFile: File? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(intent.getStringExtra(EXTRA_SESSION) ?: "Zarp")
                val result = runCatching {
                    start(
                        socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 0),
                        session = intent.getStringExtra(EXTRA_SESSION) ?: "Zarp",
                        dns = intent.getStringExtra(EXTRA_DNS).orEmpty(),
                        ipv6 = intent.getBooleanExtra(EXTRA_IPV6, true),
                    )
                }
                if (result.isFailure) shutdown()
                pendingStart?.complete(result)
                pendingStart = null
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                // "Disconnect" in the notification: the engine closes the tunnel and stops us
                (application as ZarpApp).engine.disconnect()
                return START_NOT_STICKY
            }
            SERVICE_INTERFACE -> {
                // started by the system (always-on VPN): let the engine connect with the saved strategy
                (application as ZarpApp).engine.connect()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun start(socksPort: Int, session: String, dns: String, ipv6: Boolean) {
        require(socksPort in 1..65535) { "bad SOCKS port $socksPort" }
        check(tun == null) { "VPN already running" }

        val builder = Builder()
            .setSession(session)
            .setMtu(MTU)
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        if (ipv6) builder.addAddress("fd66:7a61:7270::1", 128).addRoute("::", 0)
        dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = builder.establish() ?: throw IllegalStateException("VPN permission is not granted")
        tun = fd

        val cfg = File(cacheDir, "hev-socks5-tunnel.yml")
        cfg.writeText(
            """
            |misc:
            |  task-stack-size: 81920
            |tunnel:
            |  mtu: $MTU
            |socks5:
            |  address: 127.0.0.1
            |  port: $socksPort
            |  udp: 'udp'
            |""".trimMargin()
        )
        configFile = cfg
        if (!TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)) {
            throw IllegalStateException("hev-socks5-tunnel did not start")
        }
    }

    private fun shutdown() {
        if (tun != null) {
            runCatching { TProxyService.TProxyStopService() }
            runCatching { tun?.close() }
            tun = null
        }
        configFile?.delete()
        configFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // another VPN took over, or the user turned us off in system settings
        shutdown()
        (application as ZarpApp).engine.onVpnStopped("VPN turned off by the system")
    }

    override fun onDestroy() {
        shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startForegroundCompat(session: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ZarpVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_stat_zarp)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(session)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), disconnect).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_START = "io.github.feg55.zarp.START"
        const val ACTION_STOP = "io.github.feg55.zarp.STOP"
        const val ACTION_DISCONNECT = "io.github.feg55.zarp.DISCONNECT"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_SESSION = "session"
        const val EXTRA_DNS = "dns"
        const val EXTRA_IPV6 = "ipv6"
        private const val CHANNEL = "zarp_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 8500

        @Volatile
        var instance: ZarpVpnService? = null
            private set

        @Volatile
        internal var pendingStart: CompletableDeferred<Result<Unit>>? = null

        fun intent(context: Context, action: String): Intent = Intent(context, ZarpVpnService::class.java).setAction(action)
    }
}
