@file:OptIn(ExperimentalMaterial3Api::class)

package dev.airaudit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.airaudit.core.Bearing
import dev.airaudit.core.Confidence
import dev.airaudit.core.DistanceEstimate
import dev.airaudit.core.Environment
import dev.airaudit.core.SignalKind
import dev.airaudit.core.cardinal
import dev.airaudit.core.formatMeters
import dev.airaudit.ui.theme.Palette
import dev.airaudit.ui.theme.severityColor
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_RANGE_M = 50.0
private val RINGS = listOf(1.0, 3.0, 10.0, 30.0)
private const val BEARING_MAX_AGE_MS = 5 * 60_000L

data class RadarTarget(
    val id: String,
    val kind: SignalKind,
    val label: String,
    val detail: String,
    val distance: DistanceEstimate,
    val bearing: Bearing?,
    val color: Color,
    /** Stable pseudo-angle for devices with no measured direction, so they don't jump around. */
    val fallbackAngle: Float,
)

fun radarTargets(state: UiState, showWifi: Boolean, showBle: Boolean, showClassic: Boolean): List<RadarTarget> {
    val now = state.updatedAtMs
    fun usable(b: Bearing?) = b?.takeIf { it.confidence != Confidence.LOW && now - it.measuredAtMs < BEARING_MAX_AGE_MS }
    fun angleFor(id: String) = ((id.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    val out = mutableListOf<RadarTarget>()
    if (showWifi) {
        state.wifi.filter { now - it.lastSeenMs < 120_000L }.forEach { ap ->
            out += RadarTarget(
                ap.id, SignalKind.WIFI, ap.displayName, "${ap.securityType.label} Wi-Fi, ${ap.band}",
                ap.distance, usable(ap.bearing), severityColor(ap.worst), angleFor(ap.id),
            )
        }
    }
    state.bluetooth.forEach { d ->
        val visible = if (d.kind == SignalKind.CLASSIC) showClassic && now - d.lastSeenMs < 90_000L else showBle && now - d.lastSeenMs < 30_000L
        if (visible) {
            out += RadarTarget(
                d.id, d.kind, d.label, d.category.label, d.distance, usable(d.bearing),
                severityColor(d.worst, fallback = Palette.Haze), angleFor(d.id),
            )
        }
    }
    return out
}

/** Log scale, so 1 m and 30 m both get readable room on a phone-sized radar. */
private fun radiusFraction(meters: Double): Float =
    (ln(1 + meters) / ln(1 + MAX_RANGE_M)).toFloat().coerceIn(0.05f, 1f)

/** Screen angle, clockwise from the top. Heading-up: the top of the screen is the way you face. */
private fun screenAngle(t: RadarTarget, heading: Float?): Float =
    if (t.bearing != null && heading != null) t.bearing.degrees.toFloat() - heading else t.fallbackAngle

private fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + radius * sin(rad).toFloat(), center.y - radius * cos(rad).toFloat())
}

@Composable
fun RadarScreen(
    state: UiState,
    heading: () -> Float?,
    onOpen: (String) -> Unit,
    onStartSweep: () -> Unit,
    onFinishSweep: () -> Unit,
    onEnvironment: (Environment) -> Unit,
) {
    var showWifi by rememberSaveable { mutableStateOf(true) }
    var showBle by rememberSaveable { mutableStateOf(true) }
    var showClassic by rememberSaveable { mutableStateOf(true) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val targets = remember(state, showWifi, showBle, showClassic) { radarTargets(state, showWifi, showBle, showClassic) }
    val all = remember(state) { radarTargets(state, showWifi = true, showBle = true, showClassic = true) }
    val selected = targets.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeadingReadout(heading, state.status.compassAvailable)
        RadarCanvas(targets, heading, state.sweep, selectedId, onTap = { selectedId = it }, Modifier.padding(horizontal = 8.dp))
        Legend()
        if (selected != null) {
            SelectedTarget(selected, onOpen = { onOpen(selected.id) })
        } else {
            Text(
                "Tap a marker to identify it.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Haze,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = showWifi, onClick = { showWifi = !showWifi }, label = { Text("Wi-Fi ${all.count { it.kind == SignalKind.WIFI }}") })
            FilterChip(selected = showBle, onClick = { showBle = !showBle }, label = { Text("Bluetooth LE ${all.count { it.kind == SignalKind.BLE }}") })
            FilterChip(selected = showClassic, onClick = { showClassic = !showClassic }, label = { Text("Classic ${all.count { it.kind == SignalKind.CLASSIC }}") })
        }
        SweepPanel(state.sweep, state.status, onStartSweep, onFinishSweep)
        EnvironmentPicker(state.environment, onEnvironment)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeadingReadout(heading: () -> Float?, compassAvailable: Boolean) {
    val h = heading() // read here so only this small composable recomposes ~50 times a second
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (h == null) {
            Text(
                if (compassAvailable) "Waiting for the compass" else "This phone has no compass",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Haze,
            )
        } else {
            Text("${h.roundToInt() % 360}°", style = MaterialTheme.typography.displaySmall, color = Palette.Paper)
            Text(
                cardinal(h),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Haze,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun RadarCanvas(
    targets: List<RadarTarget>,
    heading: () -> Float?,
    sweep: SweepUi,
    selectedId: String?,
    onTap: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val ringLabel = MaterialTheme.typography.labelSmall.copy(color = Palette.Haze)
    val cardinalLabel = MaterialTheme.typography.labelMedium.copy(color = Palette.Paper)
    val current by rememberUpdatedState(targets)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Radar showing ${targets.size} nearby devices" }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val h = heading()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val outer = min(size.width, size.height) / 2f - 34.dp.toPx()
                    val nearest = current.minByOrNull { (polar(c, radiusFraction(it.distance.meters) * outer, screenAngle(it, h)) - tap).getDistance() }
                    val hit = nearest?.takeIf {
                        (polar(c, radiusFraction(it.distance.meters) * outer, screenAngle(it, h)) - tap).getDistance() < 28.dp.toPx()
                    }
                    onTap(hit?.id)
                }
            },
    ) {
        // Reading heading() inside the draw phase means turning the phone only redraws, never recomposes.
        val h = heading()
        val c = center
        val outer = size.minDimension / 2f - 34.dp.toPx()

        drawCircle(Palette.Deck, radius = outer, center = c)
        RINGS.forEach { m ->
            val r = radiusFraction(m) * outer
            drawCircle(Palette.Rule, radius = r, center = c, style = Stroke(1.dp.toPx()))
            val p = polar(c, r, 135f)
            drawText(measurer.measure("${m.toInt()} m", ringLabel), topLeft = Offset(p.x + 3.dp.toPx(), p.y))
        }
        drawCircle(Palette.Rule, radius = outer, center = c, style = Stroke(1.5.dp.toPx()))

        if (h != null) {
            // Compass rose: rotates against your heading so north stays north.
            for (deg in 0 until 360 step 10) {
                val major = deg % 90 == 0
                val length = if (major) 11.dp else if (deg % 30 == 0) 8.dp else 5.dp
                drawLine(
                    color = if (deg == 0) Palette.Ultraviolet else Palette.Haze.copy(alpha = if (major) 0.9f else 0.45f),
                    start = polar(c, outer + 3.dp.toPx(), deg - h),
                    end = polar(c, outer + 3.dp.toPx() + length.toPx(), deg - h),
                    strokeWidth = (if (major) 2.dp else 1.dp).toPx(),
                )
            }
            listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (deg, letter) ->
                val layout = measurer.measure(letter, if (deg == 0) cardinalLabel.copy(color = Palette.Ultraviolet) else cardinalLabel)
                val p = polar(c, outer + 24.dp.toPx(), deg - h)
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
        }

        if (sweep.active) {
            // Arcs fill in around the rim as you turn, showing which directions are covered.
            val span = 360f / sweep.covered.size
            val inset = 5.dp.toPx()
            sweep.covered.forEachIndexed { i, covered ->
                if (covered) {
                    drawArc(
                        color = Palette.Ultraviolet.copy(alpha = 0.6f),
                        startAngle = i * span - (h ?: 0f) - 90f,
                        sweepAngle = span,
                        useCenter = false,
                        topLeft = Offset(c.x - outer + inset, c.y - outer + inset),
                        size = Size((outer - inset) * 2, (outer - inset) * 2),
                        style = Stroke(4.dp.toPx()),
                    )
                }
            }
        }

        targets.forEach { t ->
            val p = polar(c, radiusFraction(t.distance.meters) * outer, screenAngle(t, h))
            val hollow = t.bearing == null
            drawGlyph(t.kind, p, 6.dp.toPx(), if (hollow) t.color.copy(alpha = 0.8f) else t.color, hollow)
        }
        targets.firstOrNull { it.id == selectedId }?.let { t ->
            val p = polar(c, radiusFraction(t.distance.meters) * outer, screenAngle(t, h))
            drawLine(
                Palette.Paper.copy(alpha = 0.5f), c, p, strokeWidth = 1.dp.toPx(),
                pathEffect = if (t.bearing == null) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null,
            )
            drawCircle(Palette.Paper, radius = 13.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
        }

        // You: an arrow at the centre pointing the way you face.
        val tip = 9.dp.toPx()
        val you = Path().apply {
            moveTo(c.x, c.y - tip)
            lineTo(c.x + tip * 0.7f, c.y + tip * 0.7f)
            lineTo(c.x, c.y + tip * 0.3f)
            lineTo(c.x - tip * 0.7f, c.y + tip * 0.7f)
            close()
        }
        drawPath(you, Palette.Ultraviolet)
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(SignalKind.WIFI, "Wi-Fi")
        LegendItem(SignalKind.BLE, "LE")
        LegendItem(SignalKind.CLASSIC, "Classic")
        LegendItem(SignalKind.WIFI, "Direction unknown", hollow = true)
    }
}

@Composable
private fun LegendItem(kind: SignalKind, text: String, hollow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(kind, Palette.Haze, hollow, size = 10.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.Haze)
    }
}

@Composable
private fun SelectedTarget(t: RadarTarget, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(t.kind, t.color, hollow = t.bearing == null, size = 14.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            val direction = t.bearing?.let { "${it.degrees.roundToInt()}° ${cardinal(it.degrees.toFloat())}" } ?: "direction unknown"
            Text(
                "${t.detail}. About ${formatMeters(t.distance.meters)} away, $direction.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Haze,
            )
        }
        Text("Details", style = MaterialTheme.typography.labelLarge, color = Palette.Ultraviolet)
    }
}

@Composable
private fun SweepPanel(sweep: SweepUi, status: ScanStatus, onStart: () -> Unit, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Find directions", style = MaterialTheme.typography.titleSmall, color = Palette.Paper)
        if (sweep.active) {
            Text(
                "Hold the phone flat in front of your chest and turn slowly on the spot.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            LinearProgressIndicator(
                progress = { sweep.coverage },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = Palette.Ultraviolet,
                trackColor = Palette.Rule,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(sweep.coverage * 100).roundToInt()}% of the circle covered",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Haze,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFinish) { Text("Finish now") }
            }
        } else {
            Text(
                "Your body blocks radio signals, so each device reads strongest when you face it. Turn one full circle and AirAudit marks the direction where each signal peaked.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Haze,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (status.wifiThrottled) {
                Text(
                    "Wi-Fi directions need faster scans: turn off \"Wi-Fi scan throttling\" in Developer options. Bluetooth works either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Haze,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Button(onClick = onStart, enabled = status.compassAvailable, modifier = Modifier.padding(top = 12.dp)) {
                Text("Locate by turning")
            }
            sweep.lastLocated?.let {
                Text(
                    if (it == 0) "The last turn found no clear directions. Turn more slowly, away from metal." else "The last turn placed $it devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Haze,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EnvironmentPicker(current: Environment, onChange: (Environment) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Surroundings", style = MaterialTheme.typography.titleSmall, color = Palette.Paper)
        Text(
            "Distances come from signal strength, which fades faster through walls.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Haze,
            modifier = Modifier.padding(top = 2.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Environment.entries.forEachIndexed { i, e ->
                SegmentedButton(
                    selected = e == current,
                    onClick = { onChange(e) },
                    shape = SegmentedButtonDefaults.itemShape(i, Environment.entries.size),
                ) { Text(e.label) }
            }
        }
    }
}
