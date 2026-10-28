package com.ppcoach.tracker.ui.theme

/**
 * Color tokens of docs/diseno.md as plain ARGB, so ThemeContrastTest checks
 * them on the JVM. Color.kt turns them into Compose colors.
 */
data class PpTokens(
    val background: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val divider: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val accent: Long,
    val onAccent: Long,
    val accentText: Long,
    val accentSoft: Long,
    val onAccentSoft: Long,
    val positive: Long,
    val positiveSoft: Long,
    val warning: Long,
    val warningSoft: Long,
    val negative: Long,
    val negativeSoft: Long,
    val record: Long,
    val star: Long,
) {
    companion object {
        val LIGHT = PpTokens(
            background = 0xFFF0F2F5, surface = 0xFFFFFFFF, surfaceHigh = 0xFFE4E6EB, divider = 0xFFCED0D4,
            textPrimary = 0xFF080809, textSecondary = 0xFF65676B,
            accent = 0xFF0866FF, onAccent = 0xFFFFFFFF, accentText = 0xFF0866FF, accentSoft = 0xFFE7F0FF, onAccentSoft = 0xFF0054D1,
            positive = 0xFF1B7D35, positiveSoft = 0xFFE6F4EA, warning = 0xFF8C5300, warningSoft = 0xFFFFF4D6,
            negative = 0xFFC8102E, negativeSoft = 0xFFFDE7EA, record = 0xFFFF3B30, star = 0xFFF7B928,
        )
        val DARK = PpTokens(
            background = 0xFF18191A, surface = 0xFF242526, surfaceHigh = 0xFF3A3B3C, divider = 0xFF3E4042,
            textPrimary = 0xFFE4E6EB, textSecondary = 0xFFB0B3B8,
            accent = 0xFF0866FF, onAccent = 0xFFFFFFFF, accentText = 0xFF4599FF, accentSoft = 0xFF1D2A3D, onAccentSoft = 0xFF9DC4FF,
            positive = 0xFF5CC97A, positiveSoft = 0xFF16301F, warning = 0xFFFFD066, warningSoft = 0xFF3A2F12,
            negative = 0xFFFF8A9B, negativeSoft = 0xFF3D1A20, record = 0xFFFF3B30, star = 0xFFF7B928,
        )

        /** SparkGradient stops, 135°: what the AI (SAM 3.1, Muse Spark) produces. */
        val SPARK = listOf(0xFF0866FF, 0xFF7B2FF7, 0xFFC9356F)

        /** The text/background pairs the app uses (name, text, background), checked for WCAG 4.5:1. */
        fun pairs(t: PpTokens): List<Triple<String, Long, Long>> = listOf(
            Triple("textPrimary/background", t.textPrimary, t.background),
            Triple("textPrimary/surface", t.textPrimary, t.surface),
            Triple("textPrimary/surfaceHigh", t.textPrimary, t.surfaceHigh),
            Triple("textSecondary/background", t.textSecondary, t.background),
            Triple("textSecondary/surface", t.textSecondary, t.surface),
            Triple("textSecondary/surfaceHigh", t.textSecondary, t.surfaceHigh),
            Triple("onAccent/accent", t.onAccent, t.accent),
            Triple("accentText/surface", t.accentText, t.surface),
            Triple("onAccentSoft/accentSoft", t.onAccentSoft, t.accentSoft),
            Triple("positive/positiveSoft", t.positive, t.positiveSoft),
            Triple("warning/warningSoft", t.warning, t.warningSoft),
            Triple("negative/negativeSoft", t.negative, t.negativeSoft),
            Triple("negative/surface", t.negative, t.surface),
            Triple("white/spark", 0xFFFFFFFF, SPARK[0]),
            Triple("white/spark-mid", 0xFFFFFFFF, SPARK[1]),
            Triple("white/spark-end", 0xFFFFFFFF, SPARK[2]),
        )

        fun contrast(a: Long, b: Long): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun luminance(argb: Long): Double {
            fun ch(v: Long): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
        }
    }
}
