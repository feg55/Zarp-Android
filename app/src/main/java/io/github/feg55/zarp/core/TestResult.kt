package io.github.feg55.zarp.core

import kotlinx.serialization.Serializable

/** Result of testing one strategy. Port of Zarp's TestResult. */
@Serializable
data class TestResult(
    val strategyId: String,
    val ok: Boolean = false,
    val connectMs: Int = 0,
    val pingMs: Int = 0,
    val error: String? = null,
    /** The failure happened on the second, independent check. */
    val rechecked: Boolean = false,
    /** Passed the second, independent check (on another WARP endpoint). */
    val confirmed: Boolean = false,
    /** Strategy cannot run on Android without root; it was not tested. */
    val unsupported: Boolean = false,
    val endpoint: String? = null,
    val timestamp: Long = 0,
) {
    /** Lower is better. Ping weighs more: it affects all traffic, connecting happens once. */
    val score: Int get() = if (ok) connectMs + pingMs * 4 else Int.MAX_VALUE

    val displayError: String
        get() = if (rechecked) "not confirmed: ${error.orEmpty()}" else error.orEmpty()

    companion object {
        fun failed(id: String, error: String, now: Long, endpoint: String? = null) =
            TestResult(strategyId = id, ok = false, error = error, timestamp = now, endpoint = endpoint)

        /**
         * Zarp's merge of both checks: the slower connect time (pessimistic) and
         * the average ping.
         */
        fun confirmed(first: TestResult, second: TestResult) = TestResult(
            strategyId = second.strategyId,
            ok = true,
            confirmed = true,
            connectMs = maxOf(first.connectMs, second.connectMs),
            pingMs = (first.pingMs + second.pingMs) / 2,
            endpoint = second.endpoint,
            timestamp = second.timestamp,
        )
    }
}
