package com.ppcoach.tracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.ClipAnalysis
import com.ppcoach.tracker.data.ClipStatus
import com.ppcoach.tracker.data.JerseyColor
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme
import com.ppcoach.tracker.ui.theme.sparkBrush

// ── bars ─────────────────────────────────────────────────────────────────────

/** Root variant (app name, no back arrow) or secondary (back arrow and title). */
@Composable
fun PpTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = PpTheme.colors
    Column(Modifier.background(c.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, LocalStrings.current.back, tint = c.textPrimary) }
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.padding(start = 12.dp).weight(1f))
            }
            actions()
        }
        HorizontalDivider(color = c.divider, thickness = 1.dp)
    }
}

data class BottomItem(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Bottom bar like Instagram/Threads: outline icon, filled when selected, no pill. */
@Composable
fun PpBottomBar(items: List<BottomItem>, selected: String?, onSelect: (String) -> Unit) {
    val c = PpTheme.colors
    Column(Modifier.background(c.surface).navigationBarsPadding()) {
        HorizontalDivider(color = c.divider, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            for (item in items) {
                val isSel = item.key == selected
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable { onSelect(item.key) }.padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(if (isSel) item.selectedIcon else item.icon, item.label, tint = c.textPrimary, modifier = Modifier.size(26.dp))
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (isSel) c.textPrimary else c.textSecondary)
                }
            }
        }
    }
}

// ── buttons ──────────────────────────────────────────────────────────────────

@Composable
private fun BaseButton(text: String, onClick: () -> Unit, modifier: Modifier, background: Modifier, textColor: Color, enabled: Boolean, icon: ImageVector?) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).then(background)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColor)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = PpTheme.colors
    BaseButton(text, onClick, modifier, Modifier.background(if (enabled) c.accent else c.surfaceHigh), if (enabled) c.onAccent else c.textSecondary, enabled, icon)
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = PpTheme.colors
    BaseButton(text, onClick, modifier, Modifier.background(c.surfaceHigh), if (enabled) c.textPrimary else c.textSecondary, enabled, icon)
}

/** Only for "View analysis": the AI's result. */
@Composable
fun SparkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    BaseButton(text, onClick, modifier, Modifier.background(sparkBrush()), Color.White, true, icon)
}

@Composable
fun PpTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color ?: PpTheme.colors.accentText,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

// ── cards and rows ───────────────────────────────────────────────────────────

@Composable
fun PpCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, border: BorderStroke? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PpTheme.colors
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
    if (border != null) m = m.border(border, RoundedCornerShape(12.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(12.dp), content = content)
}

@Composable
fun TeamDot(color: JerseyColor, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(color.argb)).border(1.dp, PpTheme.colors.divider, CircleShape))
}

@Composable
fun Thumbnail(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val c = PpTheme.colors
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.Movie, null, tint = c.textSecondary)
        }
    }
}

@Composable
fun MatchCard(home: String, away: String, homeColor: JerseyColor, awayColor: JerseyColor, meta: String, thumb: Bitmap?, onClick: () -> Unit) {
    val c = PpTheme.colors
    PpCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamDot(homeColor)
                    Spacer(Modifier.width(4.dp))
                    TeamDot(awayColor)
                    Spacer(Modifier.width(8.dp))
                    Text("$home ${LocalStrings.current.vs} $away", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 2)
            }
            Spacer(Modifier.width(12.dp))
            Thumbnail(thumb, Modifier.width(96.dp).aspectRatio(16f / 9f))
        }
    }
}

@Composable
fun ClipRow(title: String, meta: String, status: ClipStatus, starred: Boolean, thumb: Bitmap?, onClick: () -> Unit) {
    val c = PpTheme.colors
    PpCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(thumb, Modifier.width(96.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (starred) Icon(Icons.Rounded.Star, null, tint = c.star, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                }
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                StatusChip(status)
            }
        }
    }
}

/** Stories-like row of clips: ring with the Spark gradient if analyzed, accent while uploading. */
@Composable
fun ClipCarousel(items: List<Triple<String, ClipStatus, Bitmap?>>, onClick: (Int) -> Unit) {
    val c = PpTheme.colors
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(items.size) { i ->
            val (title, status, thumb) = items[i]
            val ring = when (status) {
                ClipStatus.READY -> Modifier.border(2.5.dp, sparkBrush(), CircleShape)
                ClipStatus.UPLOADING, ClipStatus.UPLOADED, ClipStatus.PREPARING, ClipStatus.SEGMENTING -> Modifier.border(2.5.dp, c.accent, CircleShape)
                else -> Modifier.border(2.5.dp, c.divider, CircleShape)
            }
            Column(Modifier.width(72.dp).clickable { onClick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).then(ring).padding(4.dp)) {
                    Thumbnail(thumb, Modifier.matchParentSize().clip(CircleShape))
                }
                Text(title, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── chips ────────────────────────────────────────────────────────────────────

@Composable
fun StatusChip(status: ClipStatus) {
    val c = PpTheme.colors
    val s = LocalStrings.current
    val (label, bg, fg) = when (status) {
        ClipStatus.LOCAL -> Triple(s.statusLocal, c.surfaceHigh, c.textSecondary)
        ClipStatus.PREPARING -> Triple(s.statusPreparing, c.accentSoft, c.onAccentSoft)
        ClipStatus.UPLOADING -> Triple(s.statusUploading, c.accentSoft, c.onAccentSoft)
        ClipStatus.UPLOADED -> Triple(s.statusUploaded, c.accentSoft, c.onAccentSoft)
        ClipStatus.SEGMENTING -> Triple(s.statusSegmenting, c.surface, c.textPrimary)
        ClipStatus.READY -> Triple(s.statusReady, c.positiveSoft, c.positive)
        ClipStatus.FAILED -> Triple(s.statusFailed, c.negativeSoft, c.negative)
    }
    val border = if (status == ClipStatus.SEGMENTING) Modifier.border(1.5.dp, sparkBrush(), CircleShape) else Modifier
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).then(border).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun PpChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = PpTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) c.onAccentSoft else c.textPrimary,
        modifier = Modifier.clip(CircleShape).background(if (selected) c.accentSoft else c.surfaceHigh).clickable(onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun FilterChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, label) in options) PpChip(label, key == selected) { onSelect(key) }
    }
}

// ── states ───────────────────────────────────────────────────────────────────

@Composable
fun EmptyState(icon: ImageVector, title: String, help: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = PpTheme.colors
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(help, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(action, onAction)
        }
    }
}

/** Indeterminate (analyzing: Spark) or determinate (preparing/sending: accent) progress. */
@Composable
fun PpProgress(progress: Float?, spark: Boolean = false) {
    val c = PpTheme.colors
    if (progress == null && spark) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(sparkBrush()))
    } else if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = c.accent, trackColor = c.surfaceHigh)
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = c.accent, trackColor = c.surfaceHigh)
    }
}

@Composable
fun SheetOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = PpTheme.colors
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.textPrimary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = PpTheme.colors.textPrimary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun KeyValue(key: String, value: String) {
    val c = PpTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
    }
}

// ── analysis ─────────────────────────────────────────────────────────────────

@Composable
fun SummaryCard(summary: String, footer: String) {
    val c = PpTheme.colors
    PpCard(border = BorderStroke(2.dp, sparkBrush())) {
        Text(summary, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text("✦ $footer", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}

@Composable
fun PossessionBar(home: Double, homeColor: JerseyColor, awayColor: JerseyColor) {
    val c = PpTheme.colors
    val h = home.coerceIn(0.0, 1.0).toFloat()
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("${Math.round(h * 100)} %", style = MaterialTheme.typography.displaySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text("${Math.round((1 - h) * 100)} %", style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
        }
        Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).border(1.dp, c.divider, CircleShape)) {
            if (h > 0f) Box(Modifier.weight(h).height(12.dp).background(Color(homeColor.argb)))
            if (h < 1f) Box(Modifier.weight(1f - h).height(12.dp).background(Color(awayColor.argb)))
        }
    }
}

@Composable
fun SeverityChip(severity: ClipAnalysis.Severity) {
    val c = PpTheme.colors
    val (bg, fg) = when (severity) {
        ClipAnalysis.Severity.HIGH -> c.negativeSoft to c.negative
        ClipAnalysis.Severity.MEDIUM -> c.warningSoft to c.warning
        ClipAnalysis.Severity.LOW -> c.accentSoft to c.onAccentSoft
        ClipAnalysis.Severity.INFO -> c.surfaceHigh to c.textSecondary
    }
    Text(
        LocalStrings.current.severityLabel(severity.key),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun InsightCard(insight: ClipAnalysis.Insight, time: String, onWatch: () -> Unit) {
    val c = PpTheme.colors
    val s = LocalStrings.current
    PpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", style = MaterialTheme.typography.titleMedium.copy(brush = sparkBrush()))
            Spacer(Modifier.width(8.dp))
            SeverityChip(insight.severity)
            Spacer(Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(insight.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(insight.description, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
        if (insight.recommendation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).padding(10.dp)) {
                Text(s.whatToDo, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, fontWeight = FontWeight.Bold)
                Text(insight.recommendation, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.divider)
        PpTextButton(s.watchInVideo, onWatch)
    }
}

/** 8×12 heatmap of a team as the camera sees it, in the team's color. */
@Composable
fun Heatmap(values: List<List<Double>>, color: JerseyColor, modifier: Modifier = Modifier) {
    val c = PpTheme.colors
    val max = values.maxOf { r -> r.maxOrNull() ?: 0.0 }.takeIf { it > 0 } ?: 1.0
    val team = Color(color.argb)
    Canvas(modifier.fillMaxWidth().aspectRatio(12f / 8f).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh)) {
        val cw = size.width / ClipAnalysis.HEAT_COLS
        val ch = size.height / ClipAnalysis.HEAT_ROWS
        values.forEachIndexed { r, row ->
            row.forEachIndexed { col, v ->
                if (v > 0) drawRect(team.copy(alpha = (0.15 + 0.85 * v / max).toFloat()), topLeft = androidx.compose.ui.geometry.Offset(col * cw, r * ch), size = androidx.compose.ui.geometry.Size(cw, ch))
            }
        }
    }
}
