package com.example.ui.theme

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

private val DarkColorScheme =
  darkColorScheme(
    primary = VibrantBlueContainer,
    secondary = VibrantBlueText,
    tertiary = VibrantEmeraldGreen,
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    onPrimary = VibrantBlueText,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
  )

private val LightColorScheme =
  lightColorScheme(
    primary = VibrantBlueText,
    secondary = VibrantBlueContainer,
    tertiary = VibrantEmeraldGreen,
    background = VibrantBg,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = VibrantBlueText,
    onBackground = VibrantTextPrimary,
    onSurface = VibrantTextPrimary,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false, // Default to false to show the light Vibrant Palette design
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
