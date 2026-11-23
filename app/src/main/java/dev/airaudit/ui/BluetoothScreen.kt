@file:OptIn(ExperimentalLayoutApi::class)

package dev.airaudit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.airaudit.core.BtDevice
import dev.airaudit.core.SignalKind
import dev.airaudit.core.formatAgo
import dev.airaudit.core.formatMeters
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor

private enum class BtSort(val label: String) { RISK("Risk"), SIGNAL("Signal"), RECENT("Recent") }

@Composable
fun BluetoothScreen(devices: List<BtDevice>, nowMs: Long, onOpen: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(BtSort.RISK) }
    var trackersOnly by rememberSaveable { mutableStateOf(false) }
    if (devices.isEmpty()) {
        EmptyMessage(
            "Listening for Bluetooth devices",
            "Phones, earbuds, watches and trackers advertise several times a second. Check that Bluetooth is on.",
        )
        return
    }
    val shown = remember(devices, sort, trackersOnly) {
        val base = if (trackersOnly) devices.filter { it.tracker } else devices
        when (sort) {
            BtSort.RISK -> base
            BtSort.SIGNAL -> base.sortedByDescending { it.smoothedRssi }
            BtSort.RECENT -> base.sortedByDescending { it.lastSeenMs }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { BtSummary(devices, nowMs) }
        item {
            SortAndFilter(
                options = BtSort.entries, selected = sort, label = { it.label }, onSelect = { sort = it },
                filterLabel = "Trackers", filterOn = trackersOnly, onFilterChange = { trackersOnly = it },
            )
        }
        items(shown, key = { it.address }) { d ->
            BtRow(d, nowMs) { onOpen(d.id) }
            HorizontalDivider(color = Palette.Rule.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun BtSummary(devices: List<BtDevice>, nowMs: Long) {
    val inRange = devices.count { nowMs - it.lastSeenMs < 60_000L }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text("$inRange devices in range", style = MaterialTheme.typography.headlineSmall)
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Stat(devices.count { it.tracker }, "trackers", Palette.Medium)
            Stat(devices.count { it.addressKind.fixed }, "fixed addresses", Palette.Low)
            Stat(devices.count { it.name != null }, "broadcast a name", Palette.Low)
            Stat(devices.count { it.bonded }, "paired with this phone", Palette.Ultraviolet)
        }
    }
}

@Composable
private fun BtRow(d: BtDevice, nowMs: Long, onClick: () -> Unit) {
    val risk = d.worst
    val color = severityColor(risk, Palette.Haze)
    val stale = nowMs - d.lastSeenMs > 60_000L
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .alpha(if (stale) 0.5f else 1f),
    ) {
        SeverityRule(if (risk == null) Palette.Rule else color)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (d.bonded) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Paired", Palette.Ultraviolet)
                }
            }
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.category.label, style = MaterialTheme.typography.labelMedium, color = if (d.tracker) color else Palette.Haze)
                d.vendor?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Haze,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Tag(if (d.kind == SignalKind.CLASSIC) "Classic" else "LE")
            }
        }
        Column(Modifier.padding(end = 16.dp, top = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(d.rssi, if (risk == null) Palette.Paper else color)
                Spacer(Modifier.width(6.dp))
                Text("${d.rssi}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (stale) formatAgo(nowMs - d.lastSeenMs) else "≈ ${formatMeters(d.distance.meters)}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Haze,
            )
        }
    }
}
