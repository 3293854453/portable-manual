package com.portablemanual.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.portablemanual.app.data.prefs.SettingsRepository

private val LightScheme = lightColorScheme(
    primary = Ink.Ink900,
    onPrimary = Ink.White,
    primaryContainer = Ink.Gray150,
    onPrimaryContainer = Ink.Ink900,

    secondary = Ink.Gray600,
    onSecondary = Ink.White,
    secondaryContainer = Ink.Gray150,
    onSecondaryContainer = Ink.Ink900,

    tertiary = Ink.Gray500,
    onTertiary = Ink.White,
    tertiaryContainer = Ink.Gray150,
    onTertiaryContainer = Ink.Ink900,

    background = Ink.White,
    onBackground = Ink.Ink900,

    surface = Ink.White,
    onSurface = Ink.Ink900,
    surfaceVariant = Ink.Gray100,
    onSurfaceVariant = Ink.Gray600,

    surfaceContainerLowest = Ink.White,
    surfaceContainerLow = Ink.Gray50,
    surfaceContainer = Ink.Gray50,
    surfaceContainerHigh = Ink.Gray100,
    surfaceContainerHighest = Ink.Gray150,

    outline = Ink.Gray300,
    outlineVariant = Ink.Gray200,

    error = Ink.Danger,
    onError = Ink.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    scrim = Ink.Black,
    inverseSurface = Ink.Ink800,
    inverseOnSurface = Ink.Gray100
)

private val DarkScheme = darkColorScheme(
    primary = Ink.Gray100,
    onPrimary = Ink.Ink900,
    primaryContainer = Ink.Ink700,
    onPrimaryContainer = Ink.Gray100,

    secondary = Ink.Gray400,
    onSecondary = Ink.Ink900,
    secondaryContainer = Ink.Ink700,
    onSecondaryContainer = Ink.Gray100,

    tertiary = Ink.Gray400,
    onTertiary = Ink.Ink900,
    tertiaryContainer = Ink.Ink700,
    onTertiaryContainer = Ink.Gray100,

    background = Ink.Ink900,
    onBackground = Color(0xFFF2F2F3),

    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Ink.Ink700,
    onSurfaceVariant = Ink.Gray400,

    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1E1E22),
    surfaceContainerHighest = Color(0xFF26262B),

    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF2A2A2F),

    error = Ink.DangerDark,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    scrim = Ink.Black,
    inverseSurface = Ink.Gray100,
    inverseOnSurface = Ink.Ink900
)

@Composable
fun PortableManualTheme(
    themeMode: String = SettingsRepository.THEME_SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        SettingsRepository.THEME_LIGHT -> false
        SettingsRepository.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
