package com.ppcoach.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ppcoach.tracker.data.ThemeMode

/** The tokens as Compose colors; read with PpTheme.colors. */
@Immutable
data class PpColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val positive: Color,
    val positiveSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val negative: Color,
    val negativeSoft: Color,
    val record: Color,
    val star: Color,
    val isDark: Boolean,
) {
    companion object {
        fun from(t: PpTokens, dark: Boolean) = PpColors(
            Color(t.background), Color(t.surface), Color(t.surfaceHigh), Color(t.divider), Color(t.textPrimary), Color(t.textSecondary),
            Color(t.accent), Color(t.onAccent), Color(t.accentText), Color(t.accentSoft), Color(t.onAccentSoft), Color(t.positive),
            Color(t.positiveSoft), Color(t.warning), Color(t.warningSoft), Color(t.negative), Color(t.negativeSoft), Color(t.record),
            Color(t.star), dark,
        )
    }
}

/** SparkGradient: linear at 135°, blue → violet → rose. Only for what the AI produces. */
val SparkColors: List<Color> = PpTokens.SPARK.map { Color(it) }
fun sparkBrush(): Brush = Brush.linearGradient(SparkColors, start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))

/** Tabular figures for times and statistics, so they do not jump. */
const val TABULAR = "tnum"

private val roboto = FontFamily.Default
val PpTypography = Typography(
    headlineSmall = TextStyle(fontFamily = roboto, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = roboto, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = roboto, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = roboto, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = roboto, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = roboto, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = roboto, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontFamily = roboto, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR),
)

val PpShapes = Shapes(
    small = RoundedCornerShape(8.dp), // buttons, fields, thumbnails
    medium = RoundedCornerShape(12.dp), // cards
    large = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), // sheets
)

val LocalPpColors = staticCompositionLocalOf { PpColors.from(PpTokens.LIGHT, false) }

object PpTheme {
    val colors: PpColors
        @Composable get() = LocalPpColors.current
}

/** Light and dark themes in the style of Meta's apps; dynamic colors off. */
@Composable
fun PpCoachTrackerTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = PpColors.from(if (dark) PpTokens.DARK else PpTokens.LIGHT, dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.onAccentSoft,
            secondary = c.accentText, background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textSecondary, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surfaceHigh, outline = c.divider, outlineVariant = c.divider, error = c.negative, errorContainer = c.negativeSoft,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.onAccentSoft,
            secondary = c.accentText, background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textSecondary, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surfaceHigh, outline = c.divider, outlineVariant = c.divider, error = c.negative, errorContainer = c.negativeSoft,
        )
    }
    CompositionLocalProvider(LocalPpColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = PpTypography, shapes = PpShapes, content = content)
    }
}
