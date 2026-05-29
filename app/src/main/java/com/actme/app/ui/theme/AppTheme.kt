package com.actme.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Logo color scale (preserved for branding / accent use) ──
// Based on logo primary #2178E6 (H=214, S=80%, L=52%)
val LogoBlue50 = Color(0xFFF2F4F8)
val LogoBlue100 = Color(0xFFD9E4F2)
val LogoBlue200 = Color(0xFFA8C8F0)
val LogoBlue300 = Color(0xFF68A5F3)
val LogoBlue400 = Color(0xFF1F82FF)
val LogoBlue500 = Color(0xFF2178E6)
val LogoBlue600 = Color(0xFF055BC7)
val LogoBlue700 = Color(0xFF08499B)
val LogoBlue800 = Color(0xFF093771)
val LogoBlue900 = Color(0xFF082549)
val LogoCyan = Color(0xFF3ACDF1)

// ── Material3 theme colors (subdued — darker / less saturated than logo scale) ──
private val ThemePrimary = Color(0xFF2662B8)
private val ThemeOnPrimary = Color.White
private val ThemePrimaryContainer = Color(0xFFD7E2F1)
private val ThemeOnPrimaryContainer = Color(0xFF07356D)
private val ThemeSecondary = Color(0xFF4F79B0)
private val ThemeOnSecondary = Color.White
private val ThemeSecondaryContainer = Color(0xFFD3E2F8)
private val ThemeOnSecondaryContainer = Color(0xFF062954)
private val ThemeTertiary = Color(0xFF3A8ABF)
private val ThemeOnTertiary = Color.White
private val ThemeBackground = Color(0xFFF6F7FA)
private val ThemeOnBackground = Color(0xFF181C21)
private val ThemeSurface = Color.White
private val ThemeOnSurface = Color(0xFF181C21)
private val ThemeSurfaceVariant = Color(0xFFE7E9EF)
private val ThemeOnSurfaceVariant = Color(0xFF434851)
private val ThemeOutline = Color(0xFF737882)
private val ThemeOutlineVariant = Color(0xFFC3C7CE)

private val AppLightColorScheme = lightColorScheme(
    primary = ThemePrimary,
    onPrimary = ThemeOnPrimary,
    primaryContainer = ThemePrimaryContainer,
    onPrimaryContainer = ThemeOnPrimaryContainer,
    secondary = ThemeSecondary,
    onSecondary = ThemeOnSecondary,
    secondaryContainer = ThemeSecondaryContainer,
    onSecondaryContainer = ThemeOnSecondaryContainer,
    tertiary = ThemeTertiary,
    onTertiary = ThemeOnTertiary,
    background = ThemeBackground,
    onBackground = ThemeOnBackground,
    surface = ThemeSurface,
    onSurface = ThemeOnSurface,
    surfaceVariant = ThemeSurfaceVariant,
    onSurfaceVariant = ThemeOnSurfaceVariant,
    outline = ThemeOutline,
    outlineVariant = ThemeOutlineVariant
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppLightColorScheme,
        content = content
    )
}
