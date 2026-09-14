package com.alx.moneytracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's accent (primary) color — Cornflower Blue `#6495ED`.
 *
 * This drives the accent across the UI: the Submit button, selected type/category/wallet chips, the
 * segmented type selector, the focused note field outline, and other Material 3 accent surfaces.
 */
val AccentBlue = Color(0xFF6495ED)

// Derived blue shades used across the Material 3 accent roles so nothing falls back to the
// default purple (e.g. selected FilterChip/SegmentedButton backgrounds use *secondaryContainer*).
private val AccentBlueDark = Color(0xFF3F6FD1)
private val BlueContainer = Color(0xFFD7E3FB)      // light container (selected chip background)
private val OnBlueContainer = Color(0xFF0A1F44)    // text/icon on the container
private val BlueContainerDark = Color(0xFF29406E)  // dark-theme container

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = OnBlueContainer,
    inversePrimary = AccentBlueDark,

    // Secondary drives selected chip/segmented-button containers — keep it blue, not purple.
    secondary = AccentBlue,
    onSecondary = Color.White,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = OnBlueContainer,

    // Tertiary is another accent role Material may reach for; keep it in the blue family.
    tertiary = AccentBlue,
    onTertiary = Color.White,
    tertiaryContainer = BlueContainer,
    onTertiaryContainer = OnBlueContainer,

    // Clean neutrals per design.md: off-white background, pure-white cards, muted grey text.
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFEFF1F4),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFFC7C9CE),
    outlineVariant = Color(0xFFE4E6EA)
)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = OnBlueContainer,
    primaryContainer = AccentBlueDark,
    onPrimaryContainer = BlueContainer,
    inversePrimary = AccentBlue,

    secondary = AccentBlue,
    onSecondary = OnBlueContainer,
    secondaryContainer = BlueContainerDark,
    onSecondaryContainer = BlueContainer,

    tertiary = AccentBlue,
    onTertiary = OnBlueContainer,
    tertiaryContainer = BlueContainerDark,
    onTertiaryContainer = BlueContainer,

    // Deep-slate neutrals per design.md.
    background = Color(0xFF0F0F13),
    onBackground = Color(0xFFE6E7EA),
    surface = Color(0xFF1C1C22),
    onSurface = Color(0xFFE6E7EA),
    surfaceVariant = Color(0xFF2A2A31),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF2A2A31)
)

/**
 * App theme that applies the Cornflower Blue accent on top of Material 3 defaults.
 *
 * All accent roles (primary/secondary/tertiary and their containers) are set to the blue family so
 * accent surfaces — including selected chip and segmented-button backgrounds, which use
 * *secondaryContainer* — never fall back to Material 3's default purple. Dynamic color is
 * intentionally NOT used so the accent stays `#6495ED` on all devices/versions.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
