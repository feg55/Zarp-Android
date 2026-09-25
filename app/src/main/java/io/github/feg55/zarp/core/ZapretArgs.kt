package io.github.feg55.zarp.core

/** Fake-packet blobs Zarp uses; names match Zarp's Zapret.Blobs. */
enum class Blob(val key: String, val asset: String?) {
    QuicGoogle("quic_google", "quic_initial_www_google_com.bin"),
    QuicVk("quic_vk", "quic_initial_vk_com.bin"),
    TlsGoogle("tls_google", "tls_clienthello_www_google_com.bin"),
    TlsVk("tls_vk", "tls_clienthello_vk_com.bin"),
    StunFake("stun_fake", "stun.bin"),

    /** 64 zero bytes (Zarp: "0x" + 128 zeros). */
    Zero64("zero64", null);

    companion object {
        fun byKey(key: String): Blob? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One `--lua-desync=fake:...` step: send [blob] [repeats] times from the tunnel's
 * UDP socket before the real QUIC Initial. [ipTtl]/[ip6Ttl] apply to the fakes only.
 */
data class FakeStep(
    val blob: Blob,
    val repeats: Int,
    val ipTtl: Int? = null,
    val ip6Ttl: Int? = null,
)

/**
 * What a strategy's zapret arguments mean on Android.
 *
 * [fakes] run in ZarpStrategy.beforeHandshake on the QUIC socket (HTTP/3).
 * [tcpDesync] is handed to the Go core for the TLS ClientHello (HTTP/2).
 * A non-null [unsupportedReason] means the strategy cannot be reproduced without
 * root (raw sockets) and must not be tested as if it were something else.
 */
data class DesyncPlan(
    val fakes: List<FakeStep> = emptyList(),
    val tcpDesync: String? = null,
    val unsupported: Msg? = null,
) {
    val unsupportedReason: String? get() = unsupported?.toString()
}

/** Parser for the subset of zapret2 (winws2) profile syntax that Zarp strategies use. */
object ZapretArgs {
    private const val QUIC_PAYLOAD = "quic_initial"
    private const val TLS_PAYLOAD = "tls_client_hello"
    private const val MAX_REPEATS = 50

    fun parse(transport: Transport, args: String): DesyncPlan {
        if (transport == Transport.WireGuard) {
            return DesyncPlan(unsupported = Msg("unsup.wireguard"))
        }
        val tokens = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return DesyncPlan()

        val fakes = mutableListOf<FakeStep>()
        var tcpDesync: String? = null
        for (tok in tokens) {
            val (key, value) = tok.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (key) {
                "--payload" -> {
                    val want = if (transport == Transport.MasqueH3) QUIC_PAYLOAD else TLS_PAYLOAD
                    if (value.split(",").none { it == want }) {
                        return syntax("payload '$value' never matches the ${transport.title} handshake")
                    }
                }
                "--lua-desync" -> {
                    val parts = value.split(":")
                    val fn = parts[0]
                    val params = parseParams(parts.drop(1)) ?: return syntax("bad arguments in '$tok'")
                    when {
                        transport == Transport.MasqueH3 && fn == "fake" -> {
                            val step = parseFake(params) ?: return DesyncPlan(unsupported = fakeProblem(params))
                            fakes += step
                        }
                        transport == Transport.MasqueH2 && (fn == "multisplit" || fn == "multidisorder") -> {
                            if (tcpDesync != null) return syntax("only one TCP split per strategy is supported")
                            val extra = params.keys - "pos"
                            if (extra.isNotEmpty()) {
                                return raw("$fn:${extra.joinToString(":")}")
                            }
                            val pos = params["pos"] ?: "2" // zapret default
                            if (!validPositions(pos)) return syntax("bad split positions '$pos'")
                            tcpDesync = (if (fn == "multisplit") "split:" else "disorder:") + pos
                        }
                        else -> return raw("$fn (${transport.title})")
                    }
                }
                else -> return syntax("unknown option '$key'")
            }
        }
        return DesyncPlan(fakes = fakes, tcpDesync = tcpDesync)
    }

    /** Needs raw sockets (root): impossible from a normal Android socket. */
    private fun raw(feature: String) = DesyncPlan(unsupported = Msg("unsup.raw", feature))

    /** Arguments this port cannot parse; details are technical and stay in English. */
    private fun syntax(detail: String) = DesyncPlan(unsupported = Msg("unsup.syntax", detail))

    /** "blob=quic_google", "repeats=6", "badsum" -> map; flags get an empty value. */
    private fun parseParams(items: List<String>): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (item in items) {
            if (item.isEmpty()) return null
            val kv = item.split("=", limit = 2)
            out[kv[0]] = kv.getOrElse(1) { "" }
        }
        return out
    }

    private val fakeKeys = setOf("blob", "repeats", "ip_ttl", "ip6_ttl")

    private fun parseFake(p: Map<String, String>): FakeStep? {
        if ((p.keys - fakeKeys).isNotEmpty()) return null
        val blob = Blob.byKey(p["blob"] ?: return null) ?: return null
        val repeats = p["repeats"]?.toIntOrNull() ?: 1
        if (repeats !in 1..MAX_REPEATS) return null
        val ttl = p["ip_ttl"]?.let { it.toIntOrNull() ?: return null }
        val ttl6 = p["ip6_ttl"]?.let { it.toIntOrNull() ?: return null }
        if (ttl != null && ttl !in 1..255 || ttl6 != null && ttl6 !in 1..255) return null
        return FakeStep(blob, repeats, ttl, ttl6)
    }

    private fun fakeProblem(p: Map<String, String>): Msg {
        val extra = p.keys - fakeKeys
        return when {
            "badsum" in extra -> Msg("unsup.badsum")
            extra.isNotEmpty() -> Msg("unsup.raw", "fake:" + extra.joinToString(":"))
            p["blob"] == null -> Msg("unsup.syntax", "fake needs blob=")
            Blob.byKey(p["blob"]!!) == null -> Msg("unsup.syntax", "unknown blob '${p["blob"]}'")
            else -> Msg("unsup.syntax", "bad fake parameters")
        }
    }

    private val markers = setOf("host", "endhost", "sld", "midsld")

    private fun validPositions(pos: String): Boolean =
        pos.split(",").all { it in markers || it.toIntOrNull() != null }
}
