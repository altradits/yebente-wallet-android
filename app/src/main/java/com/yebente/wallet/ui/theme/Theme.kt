package com.yebente.wallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
  primary = ObsidianPlum.AccentPrimary,
  onPrimary = ObsidianPlum.TextPrimary,
  secondary = ObsidianPlum.AccentSecondary,
  onSecondary = ObsidianPlum.TextPrimary,
  background = ObsidianPlum.BgRoot,
  onBackground = ObsidianPlum.TextPrimary,
  surface = ObsidianPlum.BgSurface,
  onSurface = ObsidianPlum.TextPrimary,
  surfaceVariant = ObsidianPlum.BgInput,
  onSurfaceVariant = ObsidianPlum.TextMuted,
  error = ObsidianPlum.StatusError,
  onError = ObsidianPlum.TextPrimary,
  outline = ObsidianPlum.BorderSubtle,
)

@Composable
fun YeBenteTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Scheme, content = content)
}
