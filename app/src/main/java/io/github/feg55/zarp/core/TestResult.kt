package io.github.feg55.zarp.core

import kotlinx.serialization.Serializable

/** Result of testing one strategy. Port of Zarp's TestResult. */
@Serializable
data class TestResult(
    val strategyId: String,
    val ok: Boolean = false,
    val connectMs: Int = 0,
    val pingMs: Int = 0,
    /** Error text as is (from the MASQUE core, English). */
    val error: String? = null,
    /** Error as a translation key and arguments: shown in the current language. */
    val errorKey: String? = null,
    val errorArgs: List<String> = emptyList(),
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

    val errorText: String
        get() = errorKey?.let { L.t(it, *errorArgs.toTypedArray()) } ?: error.orEmpty()

    val displayError: String
        get() = when {
            unsupported -> L.t("err.unsupported", errorText)
            rechecked -> L.t("result.notConfirmed", errorText)
            else -> errorText
        }

    /** For the log: the translated error plus the core's raw detail, if any. */
    val logError: String
        get() = if (errorKey != null && !error.isNullOrBlank()) "$displayError ($error)" else displayError

    companion object {
        fun failed(id: String, error: Msg, now: Long, endpoint: String? = null) = TestResult(
            strategyId = id, errorKey = error.key, errorArgs = error.args, timestamp = now, endpoint = endpoint,
        )

        fun failedRaw(id: String, error: String, now: Long, endpoint: String? = null) =
            TestResult(strategyId = id, error = error, timestamp = now, endpoint = endpoint)

        fun unsupported(id: String, reason: Msg, now: Long) = TestResult(
            strategyId = id, unsupported = true, errorKey = reason.key, errorArgs = reason.args, timestamp = now,
        )

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
