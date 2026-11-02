package com.chesereri.handmotiondetector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 深色 HUD 配色：近黑底 + 薄荷绿强调，仅状态用极小面积彩色。 */
private val HudColors = darkColorScheme(
    primary = Color(0xFF5BE0A0),
    onPrimary = Color(0xFF03130C),
    primaryContainer = Color(0xFF123026),
    onPrimaryContainer = Color(0xFF9CF3C8),
    secondary = Color(0xFF7FD8FF),
    onSecondary = Color(0xFF04181F),
    background = Color(0xFF070B10),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0E141A),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF161E26),
    onSurfaceVariant = Color(0xFF8A99A8),
    outline = Color(0xFF243039),
    outlineVariant = Color(0xFF182229),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF2A0806),
)

private val HudTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun HandMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HudColors, typography = HudTypography) {
        // Surface 负责提供 onBackground / onSurface 默认文字颜色，
        // 否则默认 LocalContentColor 是黑色，深色背景上文字会看不清。
        Surface(color = HudColors.background, contentColor = HudColors.onBackground, content = content)
    }
}
