package dev.airaudit.bluetooth

import android.bluetooth.BluetoothClass
import dev.airaudit.core.AddressKind
import dev.airaudit.core.BtCategory
import dev.airaudit.core.DistanceModel
import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.Lookups
import dev.airaudit.core.Mac
import dev.airaudit.core.Severity
import dev.airaudit.core.hex
import dev.airaudit.core.part
import dev.airaudit.core.text
import dev.airaudit.core.u16be
import dev.airaudit.core.u16le
import dev.airaudit.core.u32be
import dev.airaudit.core.u8
import dev.airaudit.core.uuidString
import java.util.UUID

/** The parts of a BLE advertisement the decoder needs, copied out of Android's ScanRecord. */
class BleAdvert(
    val manufacturerData: Map<Int, ByteArray>,
    val serviceUuids: List<UUID>,
    val serviceData: Map<UUID, ByteArray>,
    val txPower: Int?,
    val raw: ByteArray?,
)

data class BleDecoded(
    val category: BtCategory,
    val label: String?,
    val nameHint: String?,
    val vendor: String?,
    val tracker: Boolean,
    val trackerSeparated: Boolean,
    /** Expected RSSI at 1 m, when the advert carries calibration (iBeacon, Eddystone, Tx Power). */
    val ref1m: Double?,
    val appearance: String?,
    val facts: List<KeyValue>,
    val adStructures: List<KeyValue>,
    val findings: List<Finding>,
)

/**
 * Decodes BLE advertisements. An advertisement is a list of "AD structures": length(1) | type(1) | data.
 * Android already parses the common ones (name, service UUIDs, manufacturer data). This adds the formats
 * that reveal what a device actually is: Apple Continuity, iBeacon, Eddystone, Microsoft CDP, Google
 * Fast Pair and the tracker networks.
 */
object BleDecoder {

    private class Acc {
        var category = BtCategory.UNKNOWN
        var label: String? = null
        var nameHint: String? = null
        var vendor: String? = null
        var tracker = false
        var separated = false
        var ref1m: Double? = null
        var appearance: String? = null
        val facts = mutableListOf<KeyValue>()
        val findings = mutableListOf<Finding>()

        /** The first specific category sticks, except that "tracker" always wins. */
        fun classify(c: BtCategory, l: String? = null) {
            val upgrade = category == BtCategory.UNKNOWN || (c == BtCategory.TRACKER && category != BtCategory.TRACKER)
            if (upgrade) {
                category = c
                if (l != null) label = l
            } else if (label == null && l != null && c == category) {
                label = l
            }
        }

        fun tracker(name: String, awayFromOwner: Boolean = false) {
            tracker = true
            classify(BtCategory.TRACKER, name)
            if (awayFromOwner) {
                separated = true
                label = name
            }
        }
    }

    fun decode(ad: BleAdvert): BleDecoded {
        val a = Acc()
        ad.txPower?.let { a.ref1m = it - DistanceModel.ONE_METRE_LOSS_DB }

        for ((company, data) in ad.manufacturerData) {
            val name = Lookups.company(company)
            if (a.vendor == null) a.vendor = name
            a.facts += KeyValue("Manufacturer data, ${name ?: "company 0x%04X".format(company)}", data.hex(), mono = true)
            when (company) {
                0x004C -> apple(data, a)
                0x0006 -> microsoft(data, a)
            }
        }
        for (uuid in ad.serviceUuids) {
            val short = uuid.short16()
            a.facts += KeyValue("Advertised service", describeUuid(uuid), mono = short == null)
            if (short != null) serviceHint(short, a)
        }
        for ((uuid, data) in ad.serviceData) {
            val short = uuid.short16()
            a.facts += KeyValue("Service data, ${describeUuid(uuid)}", data.hex(), mono = true)
            when (short) {
                0xFEAA -> eddystone(data, a)
                0xFE2C -> fastPair(data, a)
                0x180F -> if (data.isNotEmpty() && data.u8(0) <= 100) a.facts += KeyValue("Battery", "${data.u8(0)}%")
                else -> if (short != null) serviceHint(short, a)
            }
        }
        val structures = adStructures(ad.raw)
        structures.firstOrNull { it.first == 0x19 && it.second.size >= 2 }?.let { (_, v) ->
            val value = v.u16le(0)
            a.appearance = Lookups.appearance(value) ?: "0x%04X".format(value)
            appearanceCategory(value shr 6)?.let { a.classify(it) }
        }
        if (a.tracker) {
            a.findings += if (a.separated) {
                Finding(
                    Severity.MEDIUM, "Tracker away from its owner",
                    "This item tracker isn't near the phone it belongs to. If it keeps turning up wherever you go, it may be travelling with you.",
                    "Use Find on this screen to home in on it. Android's unknown tracker alerts can also make it play a sound.",
                )
            } else {
                Finding(
                    Severity.LOW, "Item tracker nearby",
                    "Trackers report their location to their owner through a finding network. Your own tags show up here too.",
                    "If you don't recognise it and it keeps appearing, use Find to locate it.",
                )
            }
        }
        return BleDecoded(
            a.category, a.label, a.nameHint, a.vendor, a.tracker, a.separated, a.ref1m, a.appearance, a.facts,
            structures.map { (type, v) -> KeyValue(adTypeLabel(type), describeAd(type, v), mono = true) },
            a.findings,
        )
    }

    private val APPLE_TYPES = mapOf(
        0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity pairing",
        0x08 to "Hey Siri", 0x09 to "AirPlay target", 0x0A to "AirPlay source", 0x0B to "Magic Switch",
        0x0C to "Handoff", 0x0D to "Instant Hotspot target", 0x0E to "Instant Hotspot source",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My",
    )

    /** Apple packs several "Continuity" messages into one advert, each type(1) | length(1) | payload. */
    private fun apple(d: ByteArray, a: Acc) {
        var i = 0
        while (i + 2 <= d.size) {
            val type = d.u8(i)
            val len = d.u8(i + 1)
            val v = d.part(i + 2, len)
            a.facts += KeyValue("Apple Continuity message", "0x%02X %s".format(type, APPLE_TYPES[type] ?: "unknown"))
            when (type) {
                0x02 -> if (v.size >= 21) {
                    val power = v[20].toInt() // signed: the RSSI this beacon is calibrated to show at 1 m
                    a.classify(BtCategory.BEACON, "iBeacon")
                    a.ref1m = power.toDouble()
                    a.facts += KeyValue("iBeacon UUID", v.part(0, 16).uuidString(), mono = true)
                    a.facts += KeyValue("iBeacon major, minor", "${v.u16be(16)}, ${v.u16be(18)}")
                    a.facts += KeyValue("Calibrated power at 1 m", "$power dBm")
                }
                0x03 -> a.classify(BtCategory.IOT, "AirPrint printer")
                0x06 -> a.classify(BtCategory.IOT, "HomeKit accessory")
                0x07 -> a.classify(BtCategory.AUDIO, "AirPods or Beats")
                0x09 -> a.classify(BtCategory.MEDIA, "AirPlay receiver")
                0x0B -> a.classify(BtCategory.WEARABLE, "Apple Watch")
                0x0C, 0x10 -> a.classify(BtCategory.PHONE, "Apple phone, tablet or Mac")
                0x0E -> a.classify(BtCategory.PHONE, "iPhone offering a hotspot")
                // Away from its owner, a Find My item broadcasts its full public key: a 25-byte payload.
                0x12 -> if (len >= 25) a.tracker("Find My item away from its owner", awayFromOwner = true) else a.tracker("Find My item near its owner")
            }
            i += 2 + len
        }
    }

    private fun microsoft(d: ByteArray, a: Acc) {
        if (d.size >= 2 && d.u8(0) == 0x01) {
            // Connected Devices Platform beacon (Windows "Phone Link" etc.): low 5 bits of byte 1 = device type.
            val type = d.u8(1) and 0x1F
            val (category, name) = when (type) {
                1 -> BtCategory.MEDIA to "Xbox One"
                6 -> BtCategory.PHONE to "iPhone"
                7 -> BtCategory.PHONE to "iPad"
                8 -> BtCategory.PHONE to "Android device"
                9 -> BtCategory.COMPUTER to "Windows desktop"
                11 -> BtCategory.PHONE to "Windows phone"
                12 -> BtCategory.COMPUTER to "Linux device"
                13 -> BtCategory.IOT to "Windows IoT device"
                14 -> BtCategory.COMPUTER to "Surface Hub"
                15 -> BtCategory.COMPUTER to "Windows laptop"
                16 -> BtCategory.COMPUTER to "Windows tablet"
                else -> BtCategory.COMPUTER to "Windows device"
            }
            a.classify(category, name)
            a.facts += KeyValue("Microsoft CDP beacon", "Device type $type, $name")
        } else if (d.size >= 3 && d.u8(0) == 0x03) {
            a.facts += KeyValue("Microsoft Swift Pair", "Ready to pair with Windows")
            if (d.u8(1) == 0x00 && d.size > 3) a.nameHint = d.part(3, d.size - 3).text().ifBlank { null }
        }
    }

    private fun fastPair(d: ByteArray, a: Acc) {
        if (d.size == 3) {
            val model = (d.u8(0) shl 16) or (d.u8(1) shl 8) or d.u8(2)
            a.facts += KeyValue("Google Fast Pair", "In pairing mode, model ID %06X".format(model))
            a.classify(BtCategory.AUDIO, "Fast Pair accessory in pairing mode")
        } else {
            a.facts += KeyValue("Google Fast Pair", "Paired accessory, not discoverable")
            a.classify(BtCategory.AUDIO)
        }
    }

    private val URL_SCHEMES = listOf("http://www.", "https://www.", "http://", "https://")
    private val URL_EXPANSIONS = listOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
        ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov",
    )

    /** Eddystone frames: 0x00 UID, 0x10 URL, 0x20 telemetry, 0x30 ephemeral ID, 0x40/0x41 Find My Device. */
    private fun eddystone(d: ByteArray, a: Acc) {
        if (d.isEmpty()) return
        when (d.u8(0)) {
            0x00 -> if (d.size >= 18) {
                a.classify(BtCategory.BEACON, "Eddystone-UID beacon")
                a.ref1m = d[1] - DistanceModel.ONE_METRE_LOSS_DB // Eddystone calibrates power at 0 m
                a.facts += KeyValue("Eddystone namespace", d.part(2, 10).hex(""), mono = true)
                a.facts += KeyValue("Eddystone instance", d.part(12, 6).hex(""), mono = true)
            }
            0x10 -> if (d.size >= 3) {
                val url = buildString {
                    append(URL_SCHEMES.getOrElse(d.u8(2)) { "" })
                    for (k in 3 until d.size) {
                        val b = d.u8(k)
                        if (b < URL_EXPANSIONS.size) append(URL_EXPANSIONS[b]) else if (b in 0x21..0x7E) append(b.toChar())
                    }
                }
                a.classify(BtCategory.BEACON, "Eddystone-URL beacon")
                a.ref1m = d[1] - DistanceModel.ONE_METRE_LOSS_DB
                a.facts += KeyValue("Broadcast URL", url)
                a.findings += Finding(
                    Severity.LOW, "Beacon broadcasts a web link",
                    "It advertises $url. Links from unknown beacons can lead to phishing pages.",
                    "Only open it if you trust whoever placed the beacon.",
                )
            }
            0x20 -> if (d.size >= 14 && d.u8(1) == 0) {
                a.facts += KeyValue("Beacon battery", "${d.u16be(2)} mV")
                if (d.u16be(4) != 0x8000) a.facts += KeyValue("Beacon temperature", "%.1f °C".format(d[4] + d.u8(5) / 256.0))
                a.facts += KeyValue("Adverts sent since boot", "${d.u32be(6)}")
                a.facts += KeyValue("Beacon uptime", "${d.u32be(10) / 36_000} h")
            }
            0x30 -> if (d.size >= 10) {
                a.classify(BtCategory.BEACON, "Eddystone-EID beacon")
                a.facts += KeyValue("Ephemeral ID", d.part(2, 8).hex(""), mono = true)
            }
            // Google's Find My Device network; 0x41 = "unwanted tracking protection" mode (away from owner).
            0x40 -> a.tracker("Find My Device tracker")
            0x41 -> a.tracker("Find My Device tracker away from its owner", awayFromOwner = true)
        }
    }

    private fun serviceHint(uuid16: Int, a: Acc) {
        when (uuid16) {
            0xFD5A -> a.tracker("Samsung SmartTag")
            0xFEED, 0xFEEC -> a.tracker("Tile tracker")
            0xFE33 -> a.tracker("Chipolo tracker")
            0xFD6F -> a.classify(BtCategory.PHONE, "Exposure Notification beacon")
            0xFE95 -> a.classify(BtCategory.IOT, "Xiaomi device")
            0xFCD2 -> a.classify(BtCategory.IOT, "BTHome sensor")
            0x180D, 0x1814, 0x1816, 0x1818, 0x1826, 0x1809, 0x1810, 0x1808, 0x181D, 0x1822 -> a.classify(BtCategory.HEALTH)
            0x1812 -> a.classify(BtCategory.INPUT, "Keyboard, mouse or controller")
            0x181A -> a.classify(BtCategory.IOT)
            in 0x184E..0x1856 -> a.classify(BtCategory.AUDIO, "LE Audio device")
        }
    }

    private fun describeUuid(uuid: UUID): String {
        val short = uuid.short16() ?: return uuid.toString()
        return "0x%04X".format(short) + (Lookups.uuid16(short)?.let { " $it" } ?: "")
    }

    /** Splits raw advertisement bytes into (type, data) pairs. A zero length byte is padding: stop. */
    fun adStructures(raw: ByteArray?): List<Pair<Int, ByteArray>> {
        if (raw == null) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i < raw.size) {
            val len = raw.u8(i)
            if (len == 0 || i + 1 + len > raw.size) break
            out += raw.u8(i + 1) to raw.copyOfRange(i + 2, i + 1 + len)
            i += 1 + len
        }
        return out
    }

    private fun adTypeLabel(type: Int) = "0x%02X %s".format(type, Lookups.adType(type) ?: "AD structure")

    private fun describeAd(type: Int, v: ByteArray): String = when (type) {
        0x01 -> if (v.isNotEmpty()) flagsText(v.u8(0)) else v.hex()
        0x08, 0x09, 0x30 -> v.text()
        0x0A -> if (v.isNotEmpty()) "${v[0].toInt()} dBm" else v.hex()
        0x19 -> if (v.size >= 2) Lookups.appearance(v.u16le(0)) ?: v.hex() else v.hex()
        else -> v.hex()
    }

    private fun flagsText(f: Int): String = listOfNotNull(
        "limited discoverable".takeIf { (f and 0x01) != 0 },
        "general discoverable".takeIf { (f and 0x02) != 0 },
        "LE only".takeIf { (f and 0x04) != 0 },
        "dual-mode controller".takeIf { (f and 0x08) != 0 },
        "dual-mode host".takeIf { (f and 0x10) != 0 },
    ).joinToString().ifEmpty { "none set" }

    /** Bluetooth SIG appearance categories (value >> 6) mapped to our coarse categories. */
    private fun appearanceCategory(category: Int): BtCategory? = when (category) {
        1 -> BtCategory.PHONE
        2 -> BtCategory.COMPUTER
        3, 7, 81 -> BtCategory.WEARABLE
        5, 10, 39, 40, 42, 43 -> BtCategory.MEDIA
        6, 11, 15 -> BtCategory.INPUT
        8, 9 -> BtCategory.BEACON
        12, 13, 14, 16, 17, 18, 41, 49, 50, 52, 53, 54, 55 -> BtCategory.HEALTH
        4, 19, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 36, 82, 83, 84 -> BtCategory.IOT
        20 -> BtCategory.NETWORK
        33, 34, 37 -> BtCategory.AUDIO
        35, 38, 51 -> BtCategory.VEHICLE
        else -> null
    }

    /** Classic Bluetooth announces a "class of device" during discovery. */
    fun classicCategory(cls: BluetoothClass?): Pair<BtCategory, String?> {
        if (cls == null) return BtCategory.UNKNOWN to null
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.PHONE -> BtCategory.PHONE to "Phone"
            BluetoothClass.Device.Major.COMPUTER -> BtCategory.COMPUTER to "Computer"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> BtCategory.VEHICLE to "Car audio"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> BtCategory.AUDIO to "Hands-free kit"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> BtCategory.MEDIA to "Display"
                else -> BtCategory.AUDIO to "Audio device"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> BtCategory.INPUT to "Keyboard, mouse or controller"
            BluetoothClass.Device.Major.IMAGING -> BtCategory.IOT to "Printer, camera or scanner"
            BluetoothClass.Device.Major.WEARABLE -> BtCategory.WEARABLE to "Wearable"
            BluetoothClass.Device.Major.HEALTH -> BtCategory.HEALTH to "Health device"
            BluetoothClass.Device.Major.NETWORKING -> BtCategory.NETWORK to "Network access point"
            BluetoothClass.Device.Major.TOY -> BtCategory.IOT to "Toy"
            else -> BtCategory.UNKNOWN to null
        }
    }
}

private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** The 16-bit form of a Bluetooth base UUID (0000xxxx-0000-1000-8000-00805F9B34FB), or null. */
fun UUID.short16(): Int? {
    val s = toString()
    return if (s.startsWith("0000") && s.endsWith(BASE_UUID_SUFFIX)) s.substring(4, 8).toIntOrNull(16) else null
}

object BtAddress {
    /**
     * For random addresses the top two bits give the kind: 11 static, 01 resolvable private (rotates
     * roughly every 15 minutes), 00 non-resolvable. Before Android 15 the phone doesn't say whether an
     * address is public or random, so a match in the IEEE vendor registry is used as the hint.
     */
    fun kind(address: String, isRandom: Boolean?): AddressKind {
        val top = Mac.firstOctet(address) shr 6
        if (isRandom == false) return AddressKind.PUBLIC
        if (isRandom == null && top != 0b01 && Lookups.macVendor(address) != null) return AddressKind.PUBLIC
        return when (top) {
            0b11 -> AddressKind.STATIC_RANDOM
            0b01 -> AddressKind.RESOLVABLE_PRIVATE
            0b00 -> AddressKind.NON_RESOLVABLE
            else -> AddressKind.UNKNOWN
        }
    }
}
