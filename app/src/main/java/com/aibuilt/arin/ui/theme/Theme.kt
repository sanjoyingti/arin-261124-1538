package com.aibuilt.arin.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFF57C00),
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC7FF),
    secondary = Color(0xFF4DB6AC),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A)
)

@Composable
fun ArinTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (darkTheme) {
        if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else DarkColors
    } else {
        if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(ctx) else LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
