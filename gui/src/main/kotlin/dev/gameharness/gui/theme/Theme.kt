package dev.gameharness.gui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DD0E1),          // Cyan
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFCE93D8),        // Purple accent
    onSecondary = Color(0xFF3B1046),
    secondaryContainer = Color(0xFF53285E),
    onSecondaryContainer = Color(0xFFEBB8F5),
    tertiary = Color(0xFF81C784),          // Green for approve
    error = Color(0xFFEF9A9A),            // Soft red for deny
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF555555)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00796B),          // Teal
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00251A),
    secondary = Color(0xFF7B1FA2),        // Purple accent
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1BEE7),
    onSecondaryContainer = Color(0xFF38006B),
    tertiary = Color(0xFF388E3C),          // Green for approve
    error = Color(0xFFD32F2F),            // Red for deny
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF444444),
    outline = Color(0xFFBDBDBD)
)

private val AppTypography = Typography()

/**
 * Applies the GameDeveloperHarness Material 3 theme.
 *
 * Provides a teal/cyan primary palette with purple secondary accents. Semantic colors
 * are mapped as: `tertiary` = approve/success green, `error` = deny/destructive red.
 *
 * @param darkTheme `true` for the dark color scheme, `false` for light
 */
@Composable
fun GameHarnessTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
