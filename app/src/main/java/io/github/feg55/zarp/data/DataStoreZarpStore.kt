package io.github.feg55.zarp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.feg55.zarp.core.AppSettings
import io.github.feg55.zarp.core.TestResult
import io.github.feg55.zarp.core.ZarpStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.zarpDataStore: DataStore<Preferences> by preferencesDataStore(name = "zarp")

/**
 * DataStore persistence: selected strategy, test results (ok/confirmed, connect time,
 * ping, score inputs, timestamp) and settings.
 */
class DataStoreZarpStore(context: Context) : ZarpStore {
    private val ds = context.applicationContext.zarpDataStore

    private object Keys {
        val selected = stringPreferencesKey("selected_strategy")
        val results = stringPreferencesKey("results_json")
        val timeout = intPreferencesKey("test_timeout_sec")
        val stopAfter = intPreferencesKey("stop_after_working")
        val isolate = booleanPreferencesKey("isolate_tests")
        val ipv6 = booleanPreferencesKey("tunnel_ipv6")
        val dns = stringPreferencesKey("dns")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val custom = stringPreferencesKey("custom_strategies")
        val tos = booleanPreferencesKey("tos_accepted")
    }

    val settingsFlow: Flow<AppSettings> = ds.data.map { p ->
        val d = AppSettings()
        AppSettings(
            testTimeoutSec = p[Keys.timeout] ?: d.testTimeoutSec,
            stopAfterWorking = p[Keys.stopAfter] ?: d.stopAfterWorking,
            isolateTests = p[Keys.isolate] ?: d.isolateTests,
            tunnelIpv6 = p[Keys.ipv6] ?: d.tunnelIpv6,
            dns = p[Keys.dns] ?: d.dns,
            autoConnect = p[Keys.autoConnect] ?: d.autoConnect,
            customStrategies = p[Keys.custom] ?: d.customStrategies,
            tosAccepted = p[Keys.tos] ?: d.tosAccepted,
        )
    }

    override suspend fun settings(): AppSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(settings())
        ds.edit { p ->
            p[Keys.timeout] = s.testTimeoutSec.coerceIn(5, 120)
            p[Keys.stopAfter] = s.stopAfterWorking.coerceIn(1, 20)
            p[Keys.isolate] = s.isolateTests
            p[Keys.ipv6] = s.tunnelIpv6
            p[Keys.dns] = s.dns
            p[Keys.autoConnect] = s.autoConnect
            p[Keys.custom] = s.customStrategies
            p[Keys.tos] = s.tosAccepted
        }
    }

    override suspend fun results(): Map<String, TestResult> =
        ds.data.first()[Keys.results]?.let { decodeResults(it) } ?: emptyMap()

    override suspend fun saveResults(results: Map<String, TestResult>) {
        ds.edit { it[Keys.results] = encodeResults(results) }
    }

    override suspend fun selectedStrategyId(): String? = ds.data.first()[Keys.selected]

    override suspend fun setSelectedStrategyId(id: String?) {
        ds.edit { if (id == null) it.remove(Keys.selected) else it[Keys.selected] = id }
    }

    suspend fun clearResults() {
        ds.edit {
            it.remove(Keys.results)
            it.remove(Keys.selected)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), TestResult.serializer())

        fun encodeResults(results: Map<String, TestResult>): String = json.encodeToString(serializer, results)

        /** Broken or old data must not break startup: it is dropped, as in Zarp's AppConfig.Load. */
        fun decodeResults(text: String): Map<String, TestResult> =
            runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
    }
}
