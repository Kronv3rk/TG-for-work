package com.flowseal.tgwsproxy.ui

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF80DEEA),
    background = Color(0xFF0F1B2D),
    surface = Color(0xFF14233A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0288D1),
    secondary = Color(0xFF00838F),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
