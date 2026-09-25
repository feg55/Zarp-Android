package io.github.feg55.zarp.masque

import io.github.feg55.zarp.core.AppSettings
import io.github.feg55.zarp.core.LogBus
import io.github.feg55.zarp.core.WarpAccount
import io.github.feg55.zarpcore.Zarpcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** WARP device registration (usque: register + enroll a P-256 MASQUE key). */
class MasqueAccount(
    private val configFile: File,
    private val settings: suspend () -> AppSettings,
    private val log: LogBus,
) : WarpAccount {

    override val registered: Boolean get() = Zarpcore.hasAccount(configFile.absolutePath)

    override suspend fun ensureRegistered() {
        if (registered) return
        if (!settings().tosAccepted) throw IllegalStateException("accept the Cloudflare WARP Terms of Service first")
        withContext(Dispatchers.IO) {
            Zarpcore.register(configFile.absolutePath, "Zarp Android")
        }
        log.write("WARP registered.")
    }

    /** Drops the registration; the next operation registers a new device. */
    fun reset() {
        configFile.delete()
    }
}
