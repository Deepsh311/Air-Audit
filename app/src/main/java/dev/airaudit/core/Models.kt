package dev.airaudit.core

/** How serious a finding is, from worst to least. */
enum class Severity(val label: String, val rank: Int) {
    CRITICAL("Critical", 4),
    HIGH("High", 3),
    MEDIUM("Medium", 2),
    LOW("Low", 1),
    INFO("Note", 0),
}

/** A security or privacy observation, written for someone who isn't a wireless expert. */
data class Finding(
    val severity: Severity,
    val title: String,
    val detail: String,
    val advice: String? = null,
)

/** The worst real issue in a list. INFO-level notes don't count as issues. */
fun List<Finding>.worst(): Severity? =
    filter { it.severity != Severity.INFO }.maxByOrNull { it.severity.rank }?.severity

enum class SignalKind(val label: String) {
    WIFI("Wi-Fi"),
    BLE("Bluetooth LE"),
    CLASSIC("Bluetooth Classic"),
    CELL("Cellular"),
}

enum class DistanceSource(val label: String) {
    RTT("measured with Wi-Fi RTT"),
    CALIBRATED("estimated from signal strength and the device's advertised power"),
    MODEL("estimated from signal strength, so treat it as rough"),
    TIMING_ADVANCE("from the cell's timing advance"),
}

data class DistanceEstimate(
    val meters: Double,
    val low: Double,
    val high: Double,
    val source: DistanceSource,
)

enum class Confidence(val label: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

/** Direction from the phone to a device, in degrees clockwise from magnetic north. */
data class Bearing(
    val degrees: Double,
    val confidence: Confidence,
    val contrastDb: Double,
    val measuredAtMs: Long,
)

data class KeyValue(val key: String, val value: String, val mono: Boolean = false)

data class Section(val title: String, val rows: List<KeyValue>)

// ---------- Wi-Fi ----------

/** `strength` orders types from weakest to strongest; used for the evil-twin cross-check. */
enum class WifiSecurityType(val label: String, val strength: Int) {
    OPEN("Open", 0),
    WEP("WEP", 1),
    WPA("WPA", 2),
    OWE("Enhanced Open", 3),
    UNKNOWN("Unknown", 4),
    WAPI("WAPI", 4),
    WPA2_PERSONAL("WPA2-Personal", 5),
    WPA2_WPA3("WPA2/WPA3", 6),
    WPA2_ENTERPRISE("WPA2-Enterprise", 7),
    WPA3_PERSONAL("WPA3-Personal", 7),
    WPA3_ENTERPRISE("WPA3-Enterprise", 8),
}

data class WifiAp(
    val bssid: String,
    val ssid: String,
    val hidden: Boolean,
    val connected: Boolean,
    val rssi: Int,
    val smoothedRssi: Double,
    val frequencyMhz: Int,
    val channel: Int,
    val band: String,
    val securityType: WifiSecurityType,
    val grade: Char,
    val wpsOn: Boolean,
    val findings: List<Finding>,
    val vendor: String?,
    val distance: DistanceEstimate,
    val bearing: Bearing?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val history: List<Int>,
    val sections: List<Section>,
    val rawElements: List<KeyValue>,
    val capabilities: String,
) {
    val id: String get() = "wifi:$bssid"
    val displayName: String get() = if (hidden || ssid.isBlank()) "Hidden network" else ssid
    val worst: Severity? get() = findings.worst()
}

// ---------- Bluetooth ----------

enum class BtCategory(val label: String) {
    TRACKER("Item tracker"),
    PHONE("Phone or tablet"),
    COMPUTER("Computer"),
    WEARABLE("Wearable"),
    AUDIO("Audio"),
    MEDIA("TV or media"),
    BEACON("Beacon"),
    INPUT("Input device"),
    HEALTH("Health or fitness"),
    IOT("Smart home or sensor"),
    VEHICLE("Vehicle"),
    NETWORK("Network device"),
    UNKNOWN("Unidentified"),
}

/** `fixed` = the address never changes, so the device can be recognised over time. */
enum class AddressKind(val label: String, val fixed: Boolean) {
    PUBLIC("Fixed, registered to a manufacturer", true),
    STATIC_RANDOM("Static random, fixed until the device is reset", true),
    RESOLVABLE_PRIVATE("Rotating private address", false),
    NON_RESOLVABLE("Non-resolvable private address", false),
    UNKNOWN("Unknown", false),
}

data class BtDevice(
    val address: String,
    val kind: SignalKind,
    val label: String,
    val name: String?,
    val category: BtCategory,
    val vendor: String?,
    val addressKind: AddressKind,
    val rssi: Int,
    val smoothedRssi: Double,
    val tracker: Boolean,
    val bonded: Boolean,
    val findings: List<Finding>,
    val distance: DistanceEstimate,
    val bearing: Bearing?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val history: List<Int>,
    val sightings: Int,
    val sections: List<Section>,
) {
    val id: String get() = "bt:$address"
    val worst: Severity? get() = findings.worst()
}

// ---------- Cellular ----------

data class CellTower(
    val key: String,
    val tech: String,
    val registered: Boolean,
    val operator: String?,
    val title: String,
    val dbm: Int?,
    val level: Int,
    val distance: DistanceEstimate?,
    val findings: List<Finding>,
    val sections: List<Section>,
) {
    val id: String get() = "cell:$key"
}
