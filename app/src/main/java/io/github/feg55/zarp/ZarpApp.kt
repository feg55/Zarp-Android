package io.github.feg55.zarp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.feg55.zarp.core.LogBus
import io.github.feg55.zarp.core.NetworkInspector
import io.github.feg55.zarp.core.ZarpEngine
import io.github.feg55.zarp.data.DataStoreZarpStore
import io.github.feg55.zarp.masque.MasqueAccount
import io.github.feg55.zarp.masque.MasqueTunnelFactory
import io.github.feg55.zarp.net.TraceClient
import io.github.feg55.zarp.strategy.BlobStore
import io.github.feg55.zarp.vpn.VpnServiceController
import io.github.feg55.zarpcore.Zarpcore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ZarpApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = LogBus { Log.i("Zarp", it) }
    lateinit var store: DataStoreZarpStore
        private set
    lateinit var account: MasqueAccount
        private set
    lateinit var engine: ZarpEngine
        private set

    override fun onCreate() {
        super.onCreate()
        store = DataStoreZarpStore(this)
        Zarpcore.setLogger { line -> log.write("core: $line") }

        val settings = suspend { store.settings() }
        val warpConfig = File(filesDir, "warp-masque.json")
        account = MasqueAccount(warpConfig, settings, log)
        val vpn = VpnServiceController(this, settings)
        engine = ZarpEngine(
            scope = appScope,
            store = store,
            tunnels = MasqueTunnelFactory(warpConfig.absolutePath, BlobStore { assets.open(it) }, settings, vpn, log),
            probe = TraceClient(),
            vpn = vpn,
            account = account,
            network = AndroidNetworkInspector(this),
            log = log,
        )
        log.write("Zarp ${BuildConfig.VERSION_NAME} started")
        appScope.launch { engine.load() }
    }
}

/** Port of Zarp's NetCheck: is another VPN carrying the default network? */
class AndroidNetworkInspector(context: Context) : NetworkInspector {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun foreignVpnActive(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
