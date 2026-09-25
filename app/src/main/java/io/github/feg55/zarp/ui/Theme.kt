package io.github.feg55.zarp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colors of the desktop Zarp (Theme.cs). Background and panels are half a step darker:
 * phone screens show dark grays brighter than a desktop monitor.
 */
object Zc {
    val Back = Color(0xFF0F1015)
    val Panel = Color(0xFF181B21)
    val PanelHover = Color(0xFF21252D)
    val Border = Color(0xFF2A2E38)
    val BorderHover = Color(0xFF3B404C)

    // not pure white: it hurts on a dark background
    val Text = Color(0xFFC4C8D0)
    val TextDim = Color(0xFF808692)
    val TextDisabled = Color(0xFF4E535E)
    val OnAccent = Color(0xFF1E140C)
    val Accent = Color(0xFFF48120) // Cloudflare orange
    val Busy = Color(0xFF5096FF)
    val Ok = Color(0xFF40C478)
    val Bad = Color(0xFFE85454)
    val Off = Color(0xFF464B58)
}

private val Colors = darkColorScheme(
    primary = Zc.Accent,
    onPrimary = Zc.OnAccent,
    secondary = Zc.Busy,
    tertiary = Zc.Ok,
    background = Zc.Back,
    onBackground = Zc.Text,
    surface = Zc.Panel,
    onSurface = Zc.Text,
    surfaceVariant = Zc.Panel,
    onSurfaceVariant = Zc.TextDim,
    surfaceContainer = Zc.Panel,
    surfaceContainerHigh = Zc.Panel,
    surfaceContainerHighest = Zc.PanelHover,
    surfaceContainerLow = Zc.Panel,
    outline = Zc.Border,
    outlineVariant = Zc.Border,
    error = Zc.Bad,
)

/** Zarp is dark only, like the desktop app. */
@Composable
fun ZarpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
