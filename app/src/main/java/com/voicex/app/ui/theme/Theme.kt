package com.voicex.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand tokens, shared with res/values/colors.xml and branding/make_icons.py. */
object Brand {
  val Violet = Color(0xFF6C4CF1) // white on it: 5.33:1
  val Blue = Color(0xFF2F6BF0) // white on it: 4.69:1
  val Ink = Color(0xFF16132E)
  val Lavender = Color(0xFFB9A8FF) // on Ink: 8.66:1
  val Recording = Color(0xFFE5383B)
  val Success = Color(0xFF1E9E5A)
  val Warning = Color(0xFFF28C28)

  val gradient: Brush
    get() = Brush.linearGradient(listOf(Violet, Blue))
}

private val Light =
  lightColorScheme(
    primary = Brand.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF21005E),
    secondary = Brand.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4FF),
    onSecondaryContainer = Color(0xFF001849),
    background = Color(0xFFF8F6FF),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFF8F6FF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE6E0F2),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceContainer = Color(0xFFEFEBFA),
    surfaceContainerHigh = Color(0xFFE9E4F6),
    error = Color(0xFFB3261E),
  )

private val Dark =
  darkColorScheme(
    primary = Brand.Lavender,
    onPrimary = Color(0xFF2A0C82),
    primaryContainer = Color(0xFF4B2FC8),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFB3C5FF),
    onSecondary = Color(0xFF002A77),
    secondaryContainer = Color(0xFF173F9E),
    onSecondaryContainer = Color(0xFFDCE4FF),
    background = Brand.Ink,
    onBackground = Color(0xFFE6E1F0),
    surface = Brand.Ink,
    onSurface = Color(0xFFE6E1F0),
    surfaceVariant = Color(0xFF2C2845),
    onSurfaceVariant = Color(0xFFCAC4D8),
    surfaceContainer = Color(0xFF1F1B3A),
    surfaceContainerHigh = Color(0xFF282443),
    error = Color(0xFFFFB4AB),
  )

@Composable
fun VoiceXTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
