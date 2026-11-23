package dev.airaudit.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.airaudit.core.DistanceEstimate
import dev.airaudit.core.Finding
import dev.airaudit.core.Section
import dev.airaudit.ui.UiState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes the current scan to JSON in the app's cache folder and hands it to the share sheet.
 * Note that the file contains identifiers of other people's devices: treat it as sensitive.
 */
object ReportExporter {
    fun write(context: Context, state: UiState): Uri {
        val root = JSONObject()
            .put("app", "AirAudit")
            .put("generatedAt", Instant.ofEpochMilli(state.updatedAtMs).toString())
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            .put("surroundings", state.environment.label)
            .put(
                "wifi",
                JSONArray(
                    state.wifi.map { ap ->
                        JSONObject()
                            .put("bssid", ap.bssid)
                            .put("ssid", ap.ssid)
                            .put("hidden", ap.hidden)
                            .put("security", ap.securityType.label)
                            .put("grade", ap.grade.toString())
                            .put("rssi", ap.rssi)
                            .put("frequencyMhz", ap.frequencyMhz)
                            .put("channel", ap.channel)
                            .put("vendor", ap.vendor ?: JSONObject.NULL)
                            .put("distance", distance(ap.distance))
                            .put("bearingDeg", ap.bearing?.let { round1(it.degrees) } ?: JSONObject.NULL)
                            .put("findings", findings(ap.findings))
                            .put("details", sections(ap.sections))
                            .put("capabilities", ap.capabilities)
                            .put("informationElements", JSONArray(ap.rawElements.map { JSONObject().put("element", it.key).put("hex", it.value) }))
                    },
                ),
            )
            .put(
                "bluetooth",
                JSONArray(
                    state.bluetooth.map { d ->
                        JSONObject()
                            .put("address", d.address)
                            .put("type", d.kind.label)
                            .put("label", d.label)
                            .put("category", d.category.label)
                            .put("vendor", d.vendor ?: JSONObject.NULL)
                            .put("addressKind", d.addressKind.label)
                            .put("rssi", d.rssi)
                            .put("tracker", d.tracker)
                            .put("distance", distance(d.distance))
                            .put("bearingDeg", d.bearing?.let { round1(it.degrees) } ?: JSONObject.NULL)
                            .put("findings", findings(d.findings))
                            .put("details", sections(d.sections))
                    },
                ),
            )
            .put(
                "cellular",
                JSONArray(
                    state.cells.map { c ->
                        JSONObject()
                            .put("technology", c.tech)
                            .put("serving", c.registered)
                            .put("title", c.title)
                            .put("operator", c.operator ?: JSONObject.NULL)
                            .put("dbm", c.dbm ?: JSONObject.NULL)
                            .put("findings", findings(c.findings))
                            .put("details", sections(c.sections))
                    },
                ),
            )
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "airaudit-$stamp.json")
        file.writeText(root.toString(2))
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export scan"))
    }

    private fun round1(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else Math.round(x * 10) / 10.0

    private fun distance(d: DistanceEstimate) = JSONObject()
        .put("meters", round1(d.meters))
        .put("low", round1(d.low))
        .put("high", round1(d.high))
        .put("source", d.source.name)

    private fun findings(list: List<Finding>) = JSONArray(
        list.map { JSONObject().put("severity", it.severity.name).put("title", it.title).put("detail", it.detail) },
    )

    // Arrays rather than objects: one section can repeat a key (several advertised services, say).
    private fun sections(list: List<Section>) = JSONArray(
        list.map { s ->
            JSONObject().put("section", s.title).put(
                "rows",
                JSONArray(s.rows.map { JSONObject().put("key", it.key).put("value", it.value) }),
            )
        },
    )
}
