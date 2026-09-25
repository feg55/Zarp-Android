package io.github.feg55.zarp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.feg55.zarp.core.Strategy
import io.github.feg55.zarp.core.TestResult
import java.text.DateFormat
import java.util.Date

@Composable
fun StrategiesScreen(vm: MainViewModel, guarded: (() -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val strategies by vm.strategies.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var checked by remember { mutableStateOf(setOf<String>()) }
    var details by remember { mutableStateOf<Strategy?>(null) }
    val busy = status.state.busy

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(strategies, key = { it.id }) { s ->
                StrategyRow(
                    s = s,
                    r = results[s.id],
                    current = s.id == selectedId,
                    checked = s.id in checked,
                    onCheck = { on -> checked = if (on) checked + s.id else checked - s.id },
                    onClick = { details = s },
                )
            }
        }
        if (checked.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    val list = strategies.filter { it.id in checked }
                    guarded { vm.test(list) }
                    checked = emptySet()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text(if (busy) "Busy..." else "Test selected (${checked.size})") }
        }
    }

    details?.let { s ->
        val r = results[s.id]
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(s.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.transport.title, style = MaterialTheme.typography.labelLarge)
                    Text(s.args.ifBlank { "(no desync)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    s.plan.unsupportedReason?.let { Text("Unsupported on Android: $it", color = MaterialTheme.colorScheme.error) }
                    if (r != null) {
                        Text(resultLine(r))
                        if (r.ok) Text("Score: ${r.score} (connect + 4 × ping)")
                        r.endpoint?.let { Text("Endpoint: $it") }
                        if (r.timestamp > 0) Text("Tested: " + DateFormat.getDateTimeInstance().format(Date(r.timestamp)))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && s.supported, onClick = {
                    details = null
                    guarded { vm.use(s) }
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(enabled = !busy && s.supported, onClick = {
                    details = null
                    guarded { vm.test(listOf(s)) }
                }) { Text("Test") }
            },
        )
    }
}

@Composable
private fun StrategyRow(
    s: Strategy,
    r: TestResult?,
    current: Boolean,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = if (current) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck, enabled = s.supported)
            Column(Modifier.weight(1f)) {
                Text((if (current) "● " else "") + s.name, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = onClick, label = { Text(s.transport.shortName.uppercase()) })
                    val (text, color) = when {
                        !s.supported -> "unsupported" to MaterialTheme.colorScheme.outline
                        r == null -> "not tested" to MaterialTheme.colorScheme.outline
                        else -> resultLine(r) to when {
                            r.ok && r.confirmed -> Color(0xFF2E7D32)
                            r.ok -> Color(0xFFF9A825)
                            else -> MaterialTheme.colorScheme.error
                        }
                    }
                    Text(text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

fun resultLine(r: TestResult): String = when {
    r.unsupported -> r.error.orEmpty()
    r.ok && r.confirmed -> "works ✔✔  ${r.connectMs} ms / ping ${r.pingMs} ms"
    r.ok -> "works (1 check)  ${r.connectMs} ms / ping ${r.pingMs} ms"
    else -> "✘ " + r.displayError
}
