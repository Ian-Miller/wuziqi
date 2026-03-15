package io.github.ian_miller.wuziqi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    secondary = md_theme_dark_secondary,
    tertiary = md_theme_dark_tertiary,
    background = md_theme_dark_background,
    surface = md_theme_dark_surface,
    error = md_theme_dark_error,
    onPrimary = md_theme_dark_onPrimary,
    onSecondary = md_theme_dark_onSecondary,
    onBackground = md_theme_dark_onBackground,
    onSurface = md_theme_dark_onSurface,
    onError = md_theme_dark_onError
)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    secondary = md_theme_light_secondary,
    tertiary = md_theme_light_tertiary,
    background = md_theme_light_background,
    surface = md_theme_light_surface,
    error = md_theme_light_error,
    onPrimary = md_theme_light_onPrimary,
    onSecondary = md_theme_light_onSecondary,
    onBackground = md_theme_light_onBackground,
    onSurface = md_theme_light_onSurface,
    onError = md_theme_light_onError
)

@Composable
fun GomokuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}