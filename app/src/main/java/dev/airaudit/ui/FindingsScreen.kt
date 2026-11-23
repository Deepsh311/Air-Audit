package dev.airaudit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.airaudit.core.Finding
import dev.airaudit.core.Severity
import dev.airaudit.core.SignalKind
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor

data class FindingItem(val finding: Finding, val deviceId: String, val deviceName: String, val kind: SignalKind)

fun collectFindings(state: UiState): List<FindingItem> = buildList {
    state.wifi.forEach { ap -> ap.findings.forEach { add(FindingItem(it, ap.id, ap.displayName, SignalKind.WIFI)) } }
    state.bluetooth.forEach { d -> d.findings.forEach { add(FindingItem(it, d.id, d.label, d.kind)) } }
    state.cells.forEach { c -> c.findings.forEach { add(FindingItem(it, c.id, c.title, SignalKind.CELL)) } }
}

private val ISSUE_LEVELS = listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW)

@Composable
fun FindingsScreen(state: UiState, onOpen: (String) -> Unit) {
    val bySeverity = remember(state) { collectFindings(state).groupBy { it.finding.severity } }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { FindingsHeader(bySeverity) }
        ISSUE_LEVELS.forEach { severity ->
            val list = bySeverity[severity].orEmpty()
            if (list.isNotEmpty()) {
                item { SectionTitle("${severity.label} (${list.size})") }
                items(list) { entry -> FindingRow(entry) { onOpen(entry.deviceId) } }
            }
        }
        val notes = bySeverity[Severity.INFO].orEmpty()
        if (notes.isNotEmpty()) {
            item {
                TextButton(onClick = { showNotes = !showNotes }, modifier = Modifier.padding(8.dp)) {
                    Text(if (showNotes) "Hide ${notes.size} notes" else "Show ${notes.size} notes")
                }
            }
            if (showNotes) items(notes) { entry -> FindingRow(entry) { onOpen(entry.deviceId) } }
        }
    }
}

@Composable
private fun FindingsHeader(bySeverity: Map<Severity, List<FindingItem>>) {
    val serious = (bySeverity[Severity.CRITICAL]?.size ?: 0) + (bySeverity[Severity.HIGH]?.size ?: 0)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            if (serious == 0) "No serious issues nearby" else "$serious serious ${if (serious == 1) "issue" else "issues"} nearby",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Findings describe what nearby devices broadcast. AirAudit never connects to them.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Haze,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ISSUE_LEVELS.forEach { s ->
                val n = bySeverity[s]?.size ?: 0
                Column {
                    Text("$n", style = MaterialTheme.typography.titleLarge, color = if (n > 0) severityColor(s) else Palette.Haze)
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = Palette.Haze)
                }
            }
        }
    }
}

@Composable
private fun FindingRow(entry: FindingItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick)) {
        SeverityRule(severityColor(entry.finding.severity, Palette.Haze))
        Column(Modifier.padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Text(entry.finding.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${entry.deviceName}, ${entry.kind.label}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Haze,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Paper.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
