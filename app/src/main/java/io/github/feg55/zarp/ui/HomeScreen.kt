package io.github.feg55.zarp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.feg55.zarp.core.EngineState

@Composable
fun HomeScreen(vm: MainViewModel, guarded: (() -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val state = status.state
    val busy = state.busy

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zarp", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("WARP over MASQUE with Zarp strategies", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        val color by animateColorAsState(
            when (state) {
                EngineState.Connected -> MaterialTheme.colorScheme.primary
                EngineState.Error -> MaterialTheme.colorScheme.error
                EngineState.Idle -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.tertiary
            },
            label = "power",
        )
        val action = when {
            state == EngineState.Connected -> "Disconnect"
            busy -> "Cancel"
            else -> "Connect"
        }
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(212.dp), strokeWidth = 4.dp)
            Surface(
                onClick = {
                    when {
                        state == EngineState.Connected -> vm.disconnect()
                        busy -> vm.cancel()
                        else -> guarded { vm.connect() }
                    }
                },
                shape = CircleShape,
                color = color,
                modifier = Modifier.size(196.dp).semantics { contentDescription = action },
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(96.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stateLabel(state), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(status.detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

        if (state == EngineState.Searching && status.progressTotal > 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { status.progressDone.toFloat() / status.progressTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${status.progressDone} / ${status.progressTotal}", style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = status.strategy
                val saved = results[s?.id]
                InfoRow("Strategy", s?.name ?: "—")
                InfoRow("Transport", s?.transport?.title ?: "—")
                InfoRow("Endpoint", status.endpoint ?: saved?.endpoint ?: "—")
                InfoRow("Connect time", (status.connectMs ?: saved?.connectMs?.takeIf { saved.ok })?.let { "$it ms" } ?: "—")
                InfoRow("Ping", (status.pingMs ?: saved?.pingMs?.takeIf { saved.ok })?.let { "$it ms" } ?: "—")
                InfoRow("Score", saved?.takeIf { it.ok }?.score?.toString() ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { guarded { vm.quickScan() } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Quick Scan")
            }
            OutlinedButton(onClick = { guarded { vm.fullScan() } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Full Scan")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

fun stateLabel(s: EngineState): String = when (s) {
    EngineState.Idle -> "Not connected"
    EngineState.Preparing -> "Preparing"
    EngineState.Searching -> "Finding a strategy"
    EngineState.Connecting -> "Connecting"
    EngineState.Connected -> "Connected"
    EngineState.Disconnecting -> "Disconnecting"
    EngineState.Error -> "Error"
}
