package com.tylerhats.proxitap.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF82B1FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF4FC3F7),
    tertiary = androidx.compose.ui.graphics.Color(0xFF81D4FA),
    background = androidx.compose.ui.graphics.Color(0xFF121212),
    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF000000),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF000000),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
)

@Composable
fun ProxiTapTheme(
    // We enforce dark mode for this app as requested
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context) // Extracts colors from user's wallpaper (Expressive)
        }
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
