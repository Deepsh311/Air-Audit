package dev.airaudit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.airaudit.core.CellTower
import dev.airaudit.core.formatMeters
import dev.airaudit.core.worst
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor

@Composable
fun CellScreen(cells: List<CellTower>, onOpen: (String) -> Unit) {
    if (cells.isEmpty()) {
        EmptyMessage(
            "No cell information",
            "This needs a SIM with signal and location turned on. Wi-Fi-only tablets have no cellular radio.",
        )
        return
    }
    val serving = cells.filter { it.registered }
    val neighbours = cells.filterNot { it.registered }
    // No item keys here: Android can report the same neighbour cell twice (dual SIM), and duplicate keys crash.
    LazyColumn(Modifier.fillMaxSize()) {
        items(serving) { c -> ServingCell(c) { onOpen(c.id) } }
        if (neighbours.isNotEmpty()) {
            item { SectionTitle("Neighbouring cells (${neighbours.size})") }
            items(neighbours) { c ->
                NeighbourRow(c) { onOpen(c.id) }
                HorizontalDivider(color = Palette.Rule.copy(alpha = 0.5f))
            }
        }
        item {
            Text(
                "The phone doesn't know where towers are. Distance comes from timing advance, when the network shares it.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Haze,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ServingCell(c: CellTower, onClick: () -> Unit) {
    val color = severityColor(c.findings.worst(), Palette.Ultraviolet)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text("Connected cell", style = MaterialTheme.typography.titleSmall, color = Palette.Haze)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(c.dbm?.toString() ?: "–", style = MaterialTheme.typography.displaySmall)
            Text(" dBm", style = MaterialTheme.typography.titleMedium, color = Palette.Haze, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            LevelBars(c.level, color)
        }
        Text(c.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        c.operator?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Haze) }
        c.distance?.let {
            Text("Tower roughly ${formatMeters(it.meters)} away", style = MaterialTheme.typography.bodyMedium, color = Palette.Haze)
        }
        c.findings.forEach { FindingCard(it) }
    }
    HorizontalDivider(color = Palette.Rule.copy(alpha = 0.5f))
}

@Composable
private fun NeighbourRow(c: CellTower, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.operator?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Haze) }
        }
        Text(c.dbm?.let { "$it dBm" } ?: "–", style = MaterialTheme.typography.titleMedium)
    }
}

/** Android's own 0–4 signal level, drawn as bars. */
@Composable
private fun LevelBars(level: Int, color: Color) {
    Canvas(Modifier.size(width = 28.dp, height = 22.dp)) {
        val gap = 3.dp.toPx()
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
