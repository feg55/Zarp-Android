package io.github.feg55.zarp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Orange = Color(0xFFF38020)

private val DarkColors = darkColorScheme(primary = Orange, secondary = Color(0xFFFFB77C), tertiary = Color(0xFF8AB4F8))
private val LightColors = lightColorScheme(primary = Color(0xFFB35300), secondary = Color(0xFF8A5100), tertiary = Color(0xFF3B6EB5))

@Composable
fun ZarpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
