package dev.vvideo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class VVColors(
    val bgPage: Color, val bgSurface: Color, val bgRaised: Color, val bgInput: Color,
    val bgPlaceholder: Color, val pinnedRow: Color, val divider: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color, val icon: Color,
    val accentText: Color, val accentFill: Color, val accentSoft: Color,
    val online: Color, val warn: Color, val error: Color,
    val datePill: Color, val datePillText: Color, val isDark: Boolean,
)

val NightBlue = VVColors(
    bgPage = Color(0xFF0E1621), bgSurface = Color(0xFF17212B), bgRaised = Color(0xFF1E2C3A),
    bgInput = Color(0xFF111A23), bgPlaceholder = Color(0xFF1A2531), pinnedRow = Color(0xFF1A2531),
    divider = Color(0xFF222F3C), textPrimary = Color(0xFFE9EEF3), textSecondary = Color(0xFF8B9BAB),
    textTertiary = Color(0xFF6A7B8C), icon = Color(0xFFAEBBC8), accentText = Color(0xFF6AB3F8),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFF1D3550), online = Color(0xFF5BC88A),
    warn = Color(0xFFE8B04B), error = Color(0xFFEF6B6B),
    datePill = Color(0xEB1E2C3A), datePillText = Color(0xFFD3DEE8), isDark = true,
)

val Day = VVColors(
    bgPage = Color(0xFFE3EAF0), bgSurface = Color.White, bgRaised = Color.White,
    bgInput = Color(0xFFF4F6F8), bgPlaceholder = Color(0xFFD5DEE6), pinnedRow = Color(0xFFF6F8FA),
    divider = Color(0xFFE8EBEF), textPrimary = Color(0xFF15191E), textSecondary = Color(0xFF6B7682),
    textTertiary = Color(0xFF9AA4AE), icon = Color(0xFF5B6670), accentText = Color(0xFF2575CC),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFFE3EFFB), online = Color(0xFF2E9E5E),
    warn = Color(0xFFB7791F), error = Color(0xFFD93F3F),
    datePill = Color(0x8C283E54), datePillText = Color.White, isDark = false,
)

val AvatarGradients: List<Brush> = listOf(
    0xFFF2836B to 0xFFD14C4C, 0xFFF5B256 to 0xFFE0782E, 0xFFA891F2 to 0xFF6A5BD6,
    0xFF86C566 to 0xFF3E9F4A, 0xFF4FCFC4 to 0xFF1E9C93, 0xFF5DB4EE to 0xFF2E7FCB,
    0xFFF287A6 to 0xFFC94F72,
).map { (top, bottom) -> Brush.verticalGradient(listOf(Color(top), Color(bottom))) }

val LocalVV = staticCompositionLocalOf { NightBlue }

enum class ThemeMode { System, Light, Dark }

@Composable
fun VVideoTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val c = if (dark) NightBlue else Day
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accentFill, onPrimary = Color.White, primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accentText, background = c.bgPage, surface = c.bgSurface,
        onBackground = c.textPrimary, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary, surfaceVariant = c.bgPlaceholder,
        surfaceContainer = c.bgRaised, surfaceContainerHigh = c.bgRaised, surfaceContainerLow = c.bgSurface,
        surfaceTint = Color.Transparent, secondary = c.accentText, onSecondary = Color.White, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentText, outline = c.divider, outlineVariant = c.divider, error = c.error,
    )
    CompositionLocalProvider(LocalVV provides c, LocalAnimations provides animationsEnabled()) {
        MaterialTheme(colorScheme = scheme, typography = VVTypography, content = content)
    }
}

private fun type(size: Int, line: Int, medium: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    fontFeatureSettings = "tnum",
)
val VVTypography = Typography(
    headlineSmall = type(22,30,true), titleLarge = type(20,26,true),
    titleMedium = type(17,22,true), titleSmall = type(16,22,true),
    bodyLarge = type(16,22), bodyMedium = type(14,20), bodySmall = type(13,18),
    labelLarge = type(15,20,true), labelMedium = type(12,16,true), labelSmall = type(13,18,true),
)
object VVDimens {
    val appBar = 56.dp
    val row = 72.dp
    val avatar = 52.dp
    val albumMargin = 8.dp
    val albumGap = 2.dp
    val albumRadius = 16.dp
    val touchTarget = 48.dp
}
