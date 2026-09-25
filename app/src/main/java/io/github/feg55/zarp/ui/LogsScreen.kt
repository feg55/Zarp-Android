package io.github.feg55.zarp.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LogsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Zarp log", lines.joinToString("\n")))
            }) { Text("Copy") }
            OutlinedButton(onClick = { vm.log.clear() }) { Text("Clear") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
