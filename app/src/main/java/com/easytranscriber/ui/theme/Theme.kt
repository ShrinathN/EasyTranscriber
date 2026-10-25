package com.easytranscriber.ui.theme

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

private val Indigo = Color(0xFF3D5AFE)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFF00897B),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    secondary = Color(0xFF9FA8DA),
    tertiary = Color(0xFF4DB6AC),
)

/** Status colors that read well in both themes. */
object StatusColors {
    val ok = Color(0xFF2E7D32)
    val okDark = Color(0xFF81C784)
    val warn = Color(0xFFB26A00)
    val warnDark = Color(0xFFFFB74D)
}

@Composable
fun EasyTranscriberTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
