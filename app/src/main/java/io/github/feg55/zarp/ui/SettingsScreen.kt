package io.github.feg55.zarp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.feg55.zarp.BuildConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val busy = vm.status.collectAsStateWithLifecycle().value.state.busy

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scanning", style = MaterialTheme.typography.titleMedium)
        Text("Test timeout: ${s.testTimeoutSec} s")
        Slider(
            value = s.testTimeoutSec.toFloat(), valueRange = 5f..60f, steps = 10,
            onValueChange = { v -> vm.updateSettings { it.copy(testTimeoutSec = v.roundToInt()) } },
        )
        Text("Quick scan stops after ${s.stopAfterWorking} working strategies")
        Slider(
            value = s.stopAfterWorking.toFloat(), valueRange = 1f..10f, steps = 8,
            onValueChange = { v -> vm.updateSettings { it.copy(stopAfterWorking = v.roundToInt()) } },
        )
        SwitchRow("Separate WARP endpoint for each test", s.isolateTests) { v -> vm.updateSettings { it.copy(isolateTests = v) } }

        HorizontalDivider()
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Connect on app start", s.autoConnect) { v -> vm.updateSettings { it.copy(autoConnect = v) } }
        SwitchRow("IPv6 inside the tunnel", s.tunnelIpv6) { v -> vm.updateSettings { it.copy(tunnelIpv6 = v) } }
        var dns by remember(s.dns) { mutableStateOf(s.dns) }
        OutlinedTextField(
            value = dns, onValueChange = { dns = it }, label = { Text("DNS servers (comma-separated IPs)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.updateSettings { it.copy(dns = dns.trim()) } }, enabled = dns != s.dns) { Text("Save DNS") }

        HorizontalDivider()
        Text("Custom strategies", style = MaterialTheme.typography.titleMedium)
        var custom by remember(s.customStrategies) { mutableStateOf(s.customStrategies) }
        OutlinedTextField(
            value = custom, onValueChange = { custom = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        OutlinedButton(onClick = { vm.updateSettings { it.copy(customStrategies = custom) } }, enabled = custom != s.customStrategies) {
            Text("Save strategies")
        }

        HorizontalDivider()
        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text(if (vm.accountRegistered) "WARP device: registered" else "WARP device: not registered yet")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clearResults() }, enabled = !busy) { Text("Clear results") }
            OutlinedButton(onClick = { vm.resetAccount() }, enabled = !busy) { Text("New WARP device") }
        }

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "Zarp for Android ${BuildConfig.VERSION_NAME}. GPL-3.0.\n" +
                "Uses usque (MIT) for WARP MASQUE, quic-go (MIT), hev-socks5-tunnel (MIT) and fake packets " +
                "from zapret2 (MIT). Cloudflare and WARP are trademarks of Cloudflare, Inc.; Zarp is not affiliated with Cloudflare.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
