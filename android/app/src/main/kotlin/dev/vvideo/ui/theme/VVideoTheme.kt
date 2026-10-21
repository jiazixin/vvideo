package dev.vvideo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.vvideo.model.ThemeMode

@Immutable
data class VVColors(
    val bgPage: Color, val bgSurface: Color, val bgRaised: Color, val bgInput: Color,
    val bgPlaceholder: Color, val pinnedRow: Color, val divider: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color, val icon: Color,
    val accentText: Color, val accentFill: Color, val accentSoft: Color,
    val online: Color, val warn: Color, val error: Color,
    val datePill: Color, val datePillText: Color,
    val bannerBackground: Color, val bannerContent: Color, val isDark: Boolean,
)

val NightBlue = VVColors(
    bgPage = Color(0xFF0E1621), bgSurface = Color(0xFF17212B), bgRaised = Color(0xFF1E2C3A),
    bgInput = Color(0xFF111A23), bgPlaceholder = Color(0xFF1A2531), pinnedRow = Color(0xFF1A2531),
    divider = Color(0xFF222F3C), textPrimary = Color(0xFFE9EEF3), textSecondary = Color(0xFF8B9BAB),
    textTertiary = Color(0xFF6A7B8C), icon = Color(0xFFAEBBC8), accentText = Color(0xFF6AB3F8),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFF1D3550), online = Color(0xFF5BC88A),
    warn = Color(0xFFE8B04B), error = Color(0xFFEF6B6B),
    datePill = Color(0xEB1E2C3A), datePillText = Color(0xFFD3DEE8),
    bannerBackground = Color(0xFF3A2F17), bannerContent = Color(0xFFF1D59C), isDark = true,
)

val Day = VVColors(
    bgPage = Color(0xFFE3EAF0), bgSurface = Color.White, bgRaised = Color.White,
    bgInput = Color(0xFFF4F6F8), bgPlaceholder = Color(0xFFD5DEE6), pinnedRow = Color(0xFFF6F8FA),
    divider = Color(0xFFE8EBEF), textPrimary = Color(0xFF15191E), textSecondary = Color(0xFF6B7682),
    textTertiary = Color(0xFF9AA4AE), icon = Color(0xFF5B6670), accentText = Color(0xFF2575CC),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFFE3EFFB), online = Color(0xFF2E9E5E),
    warn = Color(0xFFB7791F), error = Color(0xFFD93F3F),
    datePill = Color(0x8C283E54), datePillText = Color.White,
    bannerBackground = Color(0xFFFFF4DE), bannerContent = Color(0xFF7A4E00), isDark = false,
)

val AvatarGradients: List<Brush> = listOf(
    0xFFF2836B to 0xFFD14C4C, 0xFFF5B256 to 0xFFE0782E, 0xFFA891F2 to 0xFF6A5BD6,
    0xFF86C566 to 0xFF3E9F4A, 0xFF4FCFC4 to 0xFF1E9C93, 0xFF5DB4EE to 0xFF2E7FCB,
    0xFFF287A6 to 0xFFC94F72,
).map { (top, bottom) -> Brush.verticalGradient(listOf(Color(top), Color(bottom))) }

/** The blue avatar gradient, also behind the server and app icons in Settings and setup. */
val ServerGradient: Brush = AvatarGradients[5]

val LocalVVColors = staticCompositionLocalOf { NightBlue }

@Composable
fun VVideoTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = if (dark) NightBlue else Day
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accentFill,
        onPrimary = Color.White,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.accentText,
        background = colors.bgPage,
        surface = colors.bgSurface,
        onBackground = colors.textPrimary,
        onSurface = colors.textPrimary,
        onSurfaceVariant = colors.textSecondary,
        surfaceVariant = colors.bgPlaceholder,
        surfaceContainer = colors.bgRaised,
        surfaceContainerHigh = colors.bgRaised,
        surfaceContainerLow = colors.bgSurface,
        surfaceTint = Color.Transparent,
        secondary = colors.accentText,
        onSecondary = Color.White,
        secondaryContainer = colors.accentSoft,
        onSecondaryContainer = colors.accentText,
        outline = colors.divider,
        outlineVariant = colors.divider,
        error = colors.error,
    )
    CompositionLocalProvider(
        LocalVVColors provides colors,
        LocalAnimations provides animationsEnabled()
    ) {
        MaterialTheme(colorScheme = scheme, typography = VVTypography, content = content)
    }
}

private fun type(size: Int, line: Int, medium: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    fontFeatureSettings = "tnum",
)

val VVTypography = Typography(
    headlineSmall = type(22, 30, true),
    titleLarge = type(20, 26, true),
    titleMedium = type(17, 22, true),
    titleSmall = type(16, 22, true),
    bodyLarge = type(16, 22),
    bodyMedium = type(14, 20),
    bodySmall = type(13, 18),
    labelLarge = type(15, 20, true),
    labelMedium = type(12, 16, true),
    labelSmall = type(13, 18, true),
)
