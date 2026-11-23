package dev.airaudit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.SignalKind
import dev.airaudit.ui.theme.MonoText
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor

/** A thin coloured bar on a row's left edge. Its colour is the row's risk level. */
@Composable
fun SeverityRule(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(3.dp).fillMaxHeight().background(color))
}

@Composable
fun SignalBars(rssi: Int, color: Color, modifier: Modifier = Modifier) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -89 -> 1
        else -> 0
    }
    Canvas(modifier.size(width = 16.dp, height = 13.dp)) {
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRect(
                color = if (i < level) color else Palette.Rule,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
            )
        }
    }
}

@Composable
fun GradeBadge(grade: Char, color: Color) {
    Box(
        Modifier
            .size(56.dp)
            .border(1.5.dp, color, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Security grade $grade" },
        contentAlignment = Alignment.Center,
    ) {
        Text(grade.toString(), style = MaterialTheme.typography.displaySmall, color = color)
    }
}

@Composable
fun Tag(text: String, color: Color = Palette.Haze) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Palette.Haze,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
fun KeyValueRow(kv: KeyValue) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(kv.key, style = MaterialTheme.typography.labelMedium, color = Palette.Haze)
        SelectionContainer {
            Text(kv.value, style = if (kv.mono) MonoText else MaterialTheme.typography.bodyMedium, color = Palette.Paper)
        }
    }
}

/** RSSI history drawn on a fixed −100…−20 dBm scale, so lines are comparable between devices. */
@Composable
fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawLine(Palette.Rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (values.size < 2) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1f - (v.coerceIn(-100, -20) + 100) / 80f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun StatusBanner(icon: ImageVector, text: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.DeckHigh)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Medium, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Paper,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyMessage(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Haze, textAlign = TextAlign.Center)
    }
}

@Composable
fun FindingCard(f: Finding) {
    val color = severityColor(f.severity, Palette.Haze)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
        SeverityRule(color)
        Column(Modifier.padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Tag(f.severity.label, color)
            }
            Text(
                f.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Paper.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 2.dp),
            )
            f.advice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Haze, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Marker shapes: circle = Wi-Fi, diamond = Bluetooth LE, square = Classic. Hollow = direction unknown. */
fun DrawScope.drawGlyph(kind: SignalKind, center: Offset, radius: Float, color: Color, hollow: Boolean) {
    val style: DrawStyle = if (hollow) Stroke(width = radius * 0.3f) else Fill
    when (kind) {
        SignalKind.WIFI -> drawCircle(color, radius, center, style = style)
        SignalKind.BLE -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius * 1.2f)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius * 1.2f)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(path, color, style = style)
        }
        else -> drawRect(
            color,
            topLeft = Offset(center.x - radius * 0.85f, center.y - radius * 0.85f),
            size = Size(radius * 1.7f, radius * 1.7f),
            style = style,
        )
    }
}

@Composable
fun Glyph(kind: SignalKind, color: Color, hollow: Boolean = false, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        drawGlyph(kind, center, this.size.minDimension / 2.6f, color, hollow)
    }
}
