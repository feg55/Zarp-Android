package io.github.feg55.zarp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.feg55.zarp.core.EngineState

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Strategies("Strategies", Icons.AutoMirrored.Filled.List),
    Logs("Logs", Icons.Filled.Terminal),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * Root of the UI. [withVpnPermission] runs an engine action after the VPN
 * permission is granted; actions also wait for the WARP Terms of Service consent.
 */
@Composable
fun ZarpRoot(vm: MainViewModel, withVpnPermission: (() -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var pendingTos by remember { mutableStateOf<(() -> Unit)?>(null) }

    // engine action -> ToS consent (registration) -> VPN permission -> action
    val guarded: (() -> Unit) -> Unit = { action ->
        val run = { withVpnPermission(action) }
        if (settings?.tosAccepted == true || vm.accountRegistered) run() else pendingTos = run
    }

    // auto-connect once per process start, only when nothing needs the user's answer
    var autoTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings?.autoConnect) {
        val s = settings ?: return@LaunchedEffect
        if (!autoTried && s.autoConnect && status.state == EngineState.Idle && (s.tosAccepted || vm.accountRegistered)) {
            autoTried = true
            withVpnPermission { vm.connect() }
        }
    }

    pendingTos?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingTos = null },
            title = { Text("Cloudflare WARP") },
            text = {
                Text(
                    "Zarp registers a free Cloudflare WARP device for you and connects to it over MASQUE. " +
                        "By continuing you accept the Cloudflare WARP Terms of Service: " +
                        "https://www.cloudflare.com/application/terms/\n\n" +
                        "Zarp is an independent project, not affiliated with Cloudflare."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(tosAccepted = true) }
                    pendingTos = null
                    next()
                }) { Text("Accept") }
            },
            dismissButton = { TextButton(onClick = { pendingTos = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.Home -> HomeScreen(vm, guarded, m)
            Tab.Strategies -> StrategiesScreen(vm, guarded, m)
            Tab.Logs -> LogsScreen(vm, m)
            Tab.Settings -> SettingsScreen(vm, m)
        }
    }
}
