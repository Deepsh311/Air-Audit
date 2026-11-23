@file:OptIn(ExperimentalMaterial3Api::class)

package dev.airaudit.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.airaudit.core.Bearing
import dev.airaudit.core.BtDevice
import dev.airaudit.core.CellTower
import dev.airaudit.core.DistanceEstimate
import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.Section
import dev.airaudit.core.SignalKind
import dev.airaudit.core.WifiAp
import dev.airaudit.core.cardinal
import dev.airaudit.core.formatAgo
import dev.airaudit.core.formatMeters
import dev.airaudit.ui.theme.MonoText
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DetailScreen(id: String, state: UiState, onBack: () -> Unit) {
    val wifi = state.wifi.firstOrNull { it.id == id }
    val bt = if (wifi == null) state.bluetooth.firstOrNull { it.id == id } else null
    val cell = if (wifi == null && bt == null) state.cells.firstOrNull { it.id == id } else null
    val title = wifi?.displayName ?: bt?.label ?: cell?.title ?: "Out of range"
    Scaffold(
        containerColor = Palette.Ink,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Ink),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            when {
                wifi != null -> wifiDetail(wifi, state.updatedAtMs)
                bt != null -> btDetail(bt, state.updatedAtMs)
                cell != null -> cellDetail(cell)
                else -> item {
                    EmptyMessage("No longer in range", "This device hasn't been heard for a while, so it was dropped from the list.")
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun LazyListScope.wifiDetail(ap: WifiAp, now: Long) {
    item {
        val color = severityColor(ap.worst)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GradeBadge(ap.grade, color)
            Column(Modifier.padding(start = 16.dp)) {
                Text(ap.securityType.label, style = MaterialTheme.typography.titleLarge, color = color)
                Text(ap.bssid.uppercase(), style = MonoText, color = Palette.Haze)
                Text(
                    listOfNotNull(ap.vendor, "${ap.band}, channel ${ap.channel}", "connected".takeIf { ap.connected }).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Haze,
                )
            }
        }
    }
    item { LiveSignal(ap.history, ap.rssi, ap.distance, ap.bearing, now) }
    findingsBlock(ap.findings)
    sectionsBlock(ap.sections)
    if (ap.rawElements.isNotEmpty()) item { RawBeacon(ap.rawElements, ap.capabilities) }
}

private fun LazyListScope.btDetail(d: BtDevice, now: Long) {
    item {
        val color = severityColor(d.worst, Palette.Haze)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).border(1.5.dp, color, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Glyph(d.kind, color, size = 22.dp) }
            Column(Modifier.padding(start = 16.dp)) {
                Text(d.category.label, style = MaterialTheme.typography.titleLarge, color = if (d.worst == null) Palette.Paper else color)
                Text(d.address, style = MonoText, color = Palette.Haze)
                Text(
                    listOfNotNull(d.vendor, d.kind.label, "paired with this phone".takeIf { d.bonded }).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Haze,
                )
            }
        }
    }
    item { LiveSignal(d.history, d.rssi, d.distance, d.bearing, now) }
    if (d.kind == SignalKind.BLE) item { FindPanel(d.smoothedRssi, d.history) }
    findingsBlock(d.findings)
    sectionsBlock(d.sections)
}

private fun LazyListScope.cellDetail(c: CellTower) {
    item {
        Column(Modifier.padding(16.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(c.operator, if (c.registered) "connected" else "neighbouring cell").joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Haze,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(c.dbm?.toString() ?: "–", style = MaterialTheme.typography.displaySmall)
                Text(" dBm", style = MaterialTheme.typography.titleMedium, color = Palette.Haze, modifier = Modifier.padding(bottom = 5.dp))
            }
            c.distance?.let {
                Text("Tower roughly ${formatMeters(it.meters)} away, ${it.source.label}.", style = MaterialTheme.typography.bodySmall, color = Palette.Haze)
            }
        }
    }
    findingsBlock(c.findings)
    sectionsBlock(c.sections)
}

private fun LazyListScope.findingsBlock(findings: List<Finding>) {
    if (findings.isEmpty()) return
    item { SectionTitle("Findings") }
    items(findings) { FindingCard(it) }
}

private fun LazyListScope.sectionsBlock(sections: List<Section>) {
    sections.forEach { s ->
        item { SectionTitle(s.title) }
        items(s.rows) { KeyValueRow(it) }
    }
}

@Composable
private fun LiveSignal(history: List<Int>, rssi: Int, distance: DistanceEstimate, bearing: Bearing?, now: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$rssi", style = MaterialTheme.typography.displaySmall)
            Text(" dBm", style = MaterialTheme.typography.titleMedium, color = Palette.Haze, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("≈ ${formatMeters(distance.meters)}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${formatMeters(distance.low)} to ${formatMeters(distance.high)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Haze,
                )
            }
        }
        Sparkline(history, Palette.Ultraviolet, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 8.dp))
        Text("Distance ${distance.source.label}.", style = MaterialTheme.typography.bodySmall, color = Palette.Haze)
        val direction = bearing?.let {
            "Direction ${it.degrees.roundToInt()}° ${cardinal(it.degrees.toFloat())}, ${it.confidence.label} confidence, measured ${formatAgo(now - it.measuredAtMs)}."
        } ?: "Direction unknown. Use Locate by turning on the Radar tab."
        Text(direction, style = MaterialTheme.typography.bodySmall, color = Palette.Haze, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Hot/cold finder: the bar fills as the signal gets stronger, with optional beeps that speed up. */
@Composable
private fun FindPanel(smoothedRssi: Double, history: List<Int>) {
    var sound by rememberSaveable { mutableStateOf(false) }
    val level = ((smoothedRssi + 100.0) / 65.0).coerceIn(0.0, 1.0).toFloat()
    val trend = remember(history) {
        if (history.size < 6) 0.0 else history.takeLast(3).average() - history.takeLast(6).take(3).average()
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Find", style = MaterialTheme.typography.titleSmall, color = Palette.Paper)
        Text(
            when {
                trend > 2 -> "Getting closer"
                trend < -2 -> "Moving away"
                else -> "Holding steady"
            },
            style = MaterialTheme.typography.titleMedium,
            color = when {
                trend > 2 -> Palette.Secure
                trend < -2 -> Palette.High
                else -> Palette.Haze
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            color = Palette.Ultraviolet,
            trackColor = Palette.Rule,
        )
        Text(
            "Walk slowly and watch the bar: it fills as you get closer. Stop, turn around once, and head the way it reads highest.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Haze,
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = sound, onCheckedChange = { sound = it })
            Text("Beep faster when closer", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
    }
    if (sound) {
        val latest by rememberUpdatedState(level)
        LaunchedEffect(Unit) {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull() ?: return@LaunchedEffect
            try {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                    delay((1_200 - latest * 1_050).toLong().coerceAtLeast(120L))
                }
            } finally {
                tone.release()
            }
        }
    }
}

@Composable
private fun RawBeacon(rows: List<KeyValue>, capabilities: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp)) {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (open) "Hide raw beacon data" else "Show raw beacon data (${rows.size} elements)")
        }
        if (open) {
            KeyValueRow(KeyValue("Android capability flags", capabilities, mono = true))
            rows.forEach { KeyValueRow(it) }
        }
    }
}
