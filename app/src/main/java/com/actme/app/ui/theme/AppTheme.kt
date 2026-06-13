package com.actme.app.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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

// ── Light scheme ──
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

// ── Dark scheme ──
private val DarkPrimary = Color(0xFF9FBCF5)
private val DarkOnPrimary = Color(0xFF003060)
private val DarkPrimaryContainer = Color(0xFF08499B)
private val DarkOnPrimaryContainer = Color(0xFFD7E2F1)
private val DarkSecondary = Color(0xFF99BCEF)
private val DarkOnSecondary = Color(0xFF003062)
private val DarkSecondaryContainer = Color(0xFF093771)
private val DarkOnSecondaryContainer = Color(0xFFD3E2F8)
private val DarkTertiary = Color(0xFF7AC3EF)
private val DarkOnTertiary = Color(0xFF003A56)
private val DarkBackground = Color(0xFF111418)
private val DarkOnBackground = Color(0xFFE1E2E8)
private val DarkSurface = Color(0xFF181C21)
private val DarkOnSurface = Color(0xFFE1E2E8)
private val DarkSurfaceVariant = Color(0xFF40444D)
private val DarkOnSurfaceVariant = Color(0xFFC3C7CE)
private val DarkOutline = Color(0xFF8D9199)
private val DarkOutlineVariant = Color(0xFF40444D)

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

private val AppDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            if (isLandscape) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) AppDarkColorScheme else AppLightColorScheme,
        content = content
    )
}
