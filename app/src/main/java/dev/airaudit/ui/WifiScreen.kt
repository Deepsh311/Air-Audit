@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.airaudit.core.WifiAp
import dev.airaudit.core.WifiSecurityType
import dev.airaudit.core.formatAgo
import dev.airaudit.core.formatMeters
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor

private enum class WifiSort(val label: String) { RISK("Risk"), SIGNAL("Signal"), NAME("Name") }

@Composable
fun WifiScreen(aps: List<WifiAp>, nowMs: Long, onOpen: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(WifiSort.RISK) }
    var issuesOnly by rememberSaveable { mutableStateOf(false) }
    if (aps.isEmpty()) {
        EmptyMessage("Looking for Wi-Fi networks", "Networks appear after the first scan, usually within a few seconds.")
        return
    }
    val shown = remember(aps, sort, issuesOnly) {
        val base = if (issuesOnly) aps.filter { it.worst != null } else aps
        when (sort) {
            WifiSort.RISK -> base // the ViewModel already sorts by risk, then signal
            WifiSort.SIGNAL -> base.sortedByDescending { it.smoothedRssi }
            WifiSort.NAME -> base.sortedBy { it.displayName.lowercase() }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { WifiSummary(aps) }
        item {
            SortAndFilter(
                options = WifiSort.entries, selected = sort, label = { it.label }, onSelect = { sort = it },
                filterLabel = "With issues", filterOn = issuesOnly, onFilterChange = { issuesOnly = it },
            )
        }
        items(shown, key = { it.bssid }) { ap ->
            WifiRow(ap, nowMs) { onOpen(ap.id) }
            HorizontalDivider(color = Palette.Rule.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun WifiSummary(aps: List<WifiAp>) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text("${aps.size} access points", style = MaterialTheme.typography.headlineSmall)
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Stat(aps.count { it.securityType == WifiSecurityType.OPEN }, "open", Palette.Critical)
            Stat(aps.count { it.securityType == WifiSecurityType.WEP }, "WEP", Palette.Critical)
            Stat(aps.count { it.securityType == WifiSecurityType.WPA }, "legacy WPA", Palette.High)
            Stat(aps.count { it.wpsOn }, "WPS on", Palette.Medium)
            Stat(aps.count { it.grade <= 'B' }, "graded A or B", Palette.Secure)
        }
    }
}

@Composable
internal fun Stat(count: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$count", style = MaterialTheme.typography.titleLarge, color = if (count > 0) color else Palette.Haze)
        Text(" $label", style = MaterialTheme.typography.bodySmall, color = Palette.Haze, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
internal fun <T> SortAndFilter(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    filterLabel: String,
    filterOn: Boolean,
    onFilterChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label(option), maxLines = 1) }
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = filterOn, onClick = { onFilterChange(!filterOn) }, label = { Text(filterLabel, maxLines = 1) })
    }
}

@Composable
private fun WifiRow(ap: WifiAp, nowMs: Long, onClick: () -> Unit) {
    val color = severityColor(ap.worst)
    val stale = nowMs - ap.lastSeenMs > 120_000L
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .alpha(if (stale) 0.5f else 1f),
    ) {
        SeverityRule(color)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ap.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ap.hidden) Palette.Haze else Palette.Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ap.connected) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Connected", Palette.Ultraviolet)
                }
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ap.securityType.label, style = MaterialTheme.typography.labelMedium, color = color)
                Text("${ap.band}, ch ${ap.channel}", style = MaterialTheme.typography.labelMedium, color = Palette.Haze)
                ap.vendor?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Haze, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.padding(end = 16.dp, top = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(ap.rssi, color)
                Spacer(Modifier.width(6.dp))
                Text("${ap.rssi}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (stale) formatAgo(nowMs - ap.lastSeenMs) else "≈ ${formatMeters(ap.distance.meters)}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Haze,
            )
        }
    }
}
