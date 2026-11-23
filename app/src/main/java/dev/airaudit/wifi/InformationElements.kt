package dev.airaudit.wifi

import dev.airaudit.core.KeyValue
import dev.airaudit.core.Lookups
import dev.airaudit.core.hex
import dev.airaudit.core.text
import dev.airaudit.core.u16be
import dev.airaudit.core.u16le
import dev.airaudit.core.u8
import dev.airaudit.core.uuidString

/** One raw 802.11 information element, copied out of ScanResult.getInformationElements(). */
class RawIe(val id: Int, val idExt: Int, val data: ByteArray)

data class RsnInfo(
    val groupCipher: String,
    val pairwiseCiphers: List<String>,
    val akms: List<String>,
    /** AKM suite numbers under the IEEE OUI 00-0F-AC, e.g. 2 = PSK, 8 = SAE, 18 = OWE. */
    val akmCodes: Set<Int>,
    val usesTkip: Boolean,
    val mfpRequired: Boolean,
    val mfpCapable: Boolean,
    val groupMgmtCipher: String?,
)

data class WpaInfo(val groupCipher: String, val pairwiseCiphers: List<String>, val akms: List<String>)

data class WpsInfo(
    val version: String,
    val configured: Boolean?,
    val apSetupLocked: Boolean?,
    val selectedRegistrar: Boolean?,
    val deviceName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val serialNumber: String?,
    val uuid: String?,
    val primaryDeviceType: String?,
    val configMethods: List<String>,
)

data class BssLoad(val stations: Int, val utilizationPct: Int, val admissionCapacity: Int)

data class IeSummary(
    val rsn: RsnInfo?,
    val wpa: WpaInfo?,
    val wps: WpsInfo?,
    val bssLoad: BssLoad?,
    val country: String?,
    val ssidHidden: Boolean,
    val fastTransition: Boolean,
    val radioMeasurement: Boolean,
    val bssTransition: Boolean,
    val wmm: Boolean,
    val wifiDirect: Boolean,
    val hotspot20: Boolean,
    val mbo: Boolean,
    val multiAp: Boolean,
    val oweTransition: Boolean,
    val vendorElements: List<String>,
    val ratesMbps: List<Double>,
    val raw: List<KeyValue>,
)

/**
 * Parses the information elements an access point puts in its beacons and probe responses.
 * On air each element is: 1-byte ID, 1-byte length, body (IEEE 802.11-2020 §9.4.2).
 * Android has already split them apart, so each RawIe holds just the body bytes.
 */
object InformationElements {
    private const val OUI_IEEE = 0x000FAC      // RSN cipher and key-management suites
    private const val OUI_MICROSOFT = 0x0050F2 // legacy WPA, WMM and WPS
    private const val OUI_WFA = 0x506F9A       // Wi-Fi Alliance: P2P, Hotspot 2.0, MBO, OWE transition…
    private const val SUITE_TKIP = (OUI_IEEE shl 8) or 2

    fun parse(elements: List<RawIe>): IeSummary {
        var rsn: RsnInfo? = null
        var wpa: WpaInfo? = null
        var wps: WpsInfo? = null
        var bssLoad: BssLoad? = null
        var country: String? = null
        var hidden = false
        var fastTransition = false
        var radioMeasurement = false
        var bssTransition = false
        var wmm = false
        var wifiDirect = false
        var hotspot20 = false
        var mbo = false
        var multiAp = false
        var oweTransition = false
        val vendors = LinkedHashSet<String>()
        val rates = mutableListOf<Double>()
        val raw = mutableListOf<KeyValue>()

        for (ie in elements) {
            val d = ie.data
            when (ie.id) {
                0 -> hidden = d.isEmpty() || d.all { it.toInt() == 0 }
                // Rates are in 500 kb/s units; the top bit marks "basic" rates. Values ≥ 122 are
                // membership selectors (HT/VHT/SAE-H2E…), not rates.
                1, 50 -> d.forEach { b -> (b.toInt() and 0x7F).takeIf { it < 122 }?.let { rates += it / 2.0 } }
                7 -> if (d.size >= 2) {
                    country = String(d, 0, 2, Charsets.US_ASCII).takeIf { code -> code.all { it.isLetter() } }
                }
                11 -> if (d.size >= 5) bssLoad = BssLoad(d.u16le(0), (d.u8(2) * 100 + 127) / 255, d.u16le(3))
                48 -> rsn = parseRsn(d)
                54 -> fastTransition = true
                70 -> radioMeasurement = true
                // Extended Capabilities bit 19 (byte 2, bit 3) = BSS Transition Management (802.11v)
                127 -> if (d.size > 2 && (d.u8(2) and 0x08) != 0) bssTransition = true
                221 -> if (d.size >= 4) {
                    val oui = (d.u8(0) shl 16) or (d.u8(1) shl 8) or d.u8(2)
                    when (oui to d.u8(3)) {
                        OUI_MICROSOFT to 1 -> wpa = parseWpa(d)
                        OUI_MICROSOFT to 2 -> wmm = true
                        OUI_MICROSOFT to 4 -> wps = parseWps(d)
                        OUI_WFA to 0x09 -> wifiDirect = true
                        OUI_WFA to 0x10 -> hotspot20 = true
                        OUI_WFA to 0x16 -> mbo = true
                        OUI_WFA to 0x1B -> multiAp = true
                        OUI_WFA to 0x1C -> oweTransition = true
                    }
                    val ouiText = "%06X".format(oui).chunked(2).joinToString(":")
                    vendors += ouiText + (Lookups.ouiVendor(oui)?.let { " $it" } ?: "")
                }
            }
            raw += KeyValue(elementName(ie), d.hex(), mono = true)
        }
        return IeSummary(
            rsn, wpa, wps, bssLoad, country, hidden, fastTransition, radioMeasurement, bssTransition,
            wmm, wifiDirect, hotspot20, mbo, multiAp, oweTransition,
            vendors.toList(), rates.distinct().sorted(), raw,
        )
    }

    /** Walks a byte array, returning null instead of throwing when an element is truncated. */
    private class Cursor(private val d: ByteArray, var i: Int) {
        private fun remaining() = d.size - i

        fun u16le(): Int? = if (remaining() >= 2) d.u16le(i).also { i += 2 } else null

        /** A 4-byte suite selector: 3-byte OUI + 1-byte type, packed into one Int. */
        fun suite(): Int? = if (remaining() >= 4) {
            ((d.u8(i) shl 24) or (d.u8(i + 1) shl 16) or (d.u8(i + 2) shl 8) or d.u8(i + 3)).also { i += 4 }
        } else {
            null
        }

        fun suites(): List<Int> = List(u16le() ?: 0) { suite() }.filterNotNull()
    }

    /**
     * RSN element: version(2) | group cipher(4) | pairwise count(2) + suites | AKM count(2) + suites |
     * RSN capabilities(2) | PMKID count(2) + PMKIDs | group management cipher(4). Trailing fields are optional.
     */
    private fun parseRsn(d: ByteArray): RsnInfo? {
        if (d.size < 2) return null
        val c = Cursor(d, 2)
        val group = c.suite()
        val pairwise = c.suites()
        val akms = c.suites()
        val caps = c.u16le() ?: 0
        val pmkids = c.u16le() ?: 0
        c.i += 16 * pmkids
        val groupMgmt = c.suite()
        return RsnInfo(
            // When a field is left out, the standard's default applies (CCMP and 802.1X).
            groupCipher = group?.let(::cipherName) ?: "CCMP-128 (AES)",
            pairwiseCiphers = pairwise.map(::cipherName).ifEmpty { listOf("CCMP-128 (AES)") },
            akms = akms.map(::akmName).ifEmpty { listOf("802.1X (EAP)") },
            akmCodes = akms.filter { (it ushr 8) == OUI_IEEE }.map { it and 0xFF }.toSet().ifEmpty { setOf(1) },
            usesTkip = group == SUITE_TKIP || SUITE_TKIP in pairwise,
            mfpRequired = (caps and 0x40) != 0, // bit 6: management frame protection required
            mfpCapable = (caps and 0x80) != 0,  // bit 7: management frame protection capable
            groupMgmtCipher = groupMgmt?.let(::cipherName),
        )
    }

    /** Pre-standard WPA lives in a Microsoft vendor element: OUI(3) + type(1) + version(2) + suites. */
    private fun parseWpa(d: ByteArray): WpaInfo {
        val c = Cursor(d, 6)
        val group = c.suite()
        val pairwise = c.suites().map(::cipherName)
        val akms = c.suites().map(::akmName)
        return WpaInfo(group?.let(::cipherName) ?: "TKIP", pairwise, akms)
    }

    /** WPS uses big-endian TLV attributes: 2-byte type, 2-byte length, value. */
    private fun parseWps(d: ByteArray): WpsInfo {
        var version = "1.0"
        var state: Int? = null
        var locked: Boolean? = null
        var selectedRegistrar: Boolean? = null
        var deviceName: String? = null
        var manufacturer: String? = null
        var modelName: String? = null
        var modelNumber: String? = null
        var serial: String? = null
        var uuid: String? = null
        var deviceType: String? = null
        val methods = mutableListOf<String>()

        var i = 4
        while (i + 4 <= d.size) {
            val type = d.u16be(i)
            val len = d.u16be(i + 2)
            i += 4
            if (i + len > d.size) break
            val v = d.copyOfRange(i, i + len)
            i += len
            when (type) {
                0x104A -> if (v.isNotEmpty()) version = "${v.u8(0) shr 4}.${v.u8(0) and 0x0F}"
                0x1044 -> if (v.isNotEmpty()) state = v.u8(0)
                0x1057 -> if (v.isNotEmpty()) locked = v.u8(0) == 1
                0x1041 -> if (v.isNotEmpty()) selectedRegistrar = v.u8(0) == 1
                0x1011 -> deviceName = v.text().ifBlank { null }
                0x1021 -> manufacturer = v.text().ifBlank { null }
                0x1023 -> modelName = v.text().ifBlank { null }
                0x1024 -> modelNumber = v.text().ifBlank { null }
                0x1042 -> serial = v.text().takeIf { s -> s.length >= 4 && s.toSet().size > 1 }
                0x1047 -> if (v.size == 16) uuid = v.uuidString()
                0x1054 -> if (v.size >= 8) deviceType = deviceCategory(v.u16be(0))
                0x1008 -> if (v.size >= 2) methods += configMethods(v.u16be(0))
                // Wi-Fi Alliance vendor extension (OUI 00:37:2A): sub-element 0 is "Version2", 0x20 = WPS 2.0
                0x1049 -> if (v.size >= 3 && v.u8(0) == 0x00 && v.u8(1) == 0x37 && v.u8(2) == 0x2A) {
                    var j = 3
                    while (j + 2 <= v.size) {
                        val id = v.u8(j)
                        val l = v.u8(j + 1)
                        if (id == 0 && l >= 1 && j + 2 < v.size) version = "${v.u8(j + 2) shr 4}.${v.u8(j + 2) and 0x0F}"
                        j += 2 + l
                    }
                }
            }
        }
        return WpsInfo(
            version, state?.let { it == 2 }, locked, selectedRegistrar, deviceName,
            manufacturer, modelName, modelNumber, serial, uuid, deviceType, methods,
        )
    }

    private fun cipherName(suite: Int): String {
        val oui = suite ushr 8
        val type = suite and 0xFF
        if (oui != OUI_IEEE && oui != OUI_MICROSOFT) return "Vendor cipher %06X:%d".format(oui, type)
        return when (type) {
            0 -> "Use group cipher"
            1 -> "WEP-40"
            2 -> "TKIP"
            4 -> "CCMP-128 (AES)"
            5 -> "WEP-104"
            6 -> "BIP-CMAC-128"
            7 -> "Group traffic not allowed"
            8 -> "GCMP-128"
            9 -> "GCMP-256"
            10 -> "CCMP-256"
            11 -> "BIP-GMAC-128"
            12 -> "BIP-GMAC-256"
            13 -> "BIP-CMAC-256"
            else -> "Cipher $type"
        }
    }

    private fun akmName(suite: Int): String {
        val oui = suite ushr 8
        val type = suite and 0xFF
        if (oui == OUI_MICROSOFT) return if (type == 1) "802.1X (EAP)" else if (type == 2) "PSK" else "WPA AKM $type"
        if (oui != OUI_IEEE) return "Vendor AKM %06X:%d".format(oui, type)
        return when (type) {
            1 -> "802.1X (EAP)"
            2 -> "PSK"
            3 -> "FT over 802.1X"
            4 -> "FT-PSK"
            5 -> "802.1X SHA-256"
            6 -> "PSK SHA-256"
            7 -> "TDLS"
            8 -> "SAE"
            9 -> "FT-SAE"
            10 -> "AP PeerKey"
            11 -> "802.1X Suite B"
            12 -> "802.1X Suite B 192-bit"
            13 -> "FT over 802.1X SHA-384"
            14 -> "FILS SHA-256"
            15 -> "FILS SHA-384"
            16 -> "FT-FILS SHA-256"
            17 -> "FT-FILS SHA-384"
            18 -> "OWE"
            19 -> "FT-PSK SHA-384"
            20 -> "PSK SHA-384"
            24 -> "SAE (group-dependent hash)"
            25 -> "FT-SAE (group-dependent hash)"
            else -> "AKM $type"
        }
    }

    private fun configMethods(mask: Int): List<String> = listOfNotNull(
        "USB".takeIf { mask and 0x0001 != 0 },
        "Ethernet".takeIf { mask and 0x0002 != 0 },
        "Label PIN".takeIf { mask and 0x0004 != 0 },
        "Display PIN".takeIf { mask and 0x0008 != 0 },
        "External NFC".takeIf { mask and 0x0010 != 0 },
        "Integrated NFC".takeIf { mask and 0x0020 != 0 },
        "NFC interface".takeIf { mask and 0x0040 != 0 },
        "Push button".takeIf { mask and 0x0080 != 0 },
        "Keypad".takeIf { mask and 0x0100 != 0 },
    )

    private fun deviceCategory(category: Int): String = when (category) {
        1 -> "Computer"
        2 -> "Input device"
        3 -> "Printer, scanner or fax"
        4 -> "Camera"
        5 -> "Storage"
        6 -> "Network infrastructure (router or access point)"
        7 -> "Display"
        8 -> "Multimedia device"
        9 -> "Gaming device"
        10 -> "Telephone"
        11 -> "Audio device"
        12 -> "Docking device"
        else -> "Category $category"
    }

    private fun elementName(ie: RawIe): String =
        if (ie.id == 255) "255/${ie.idExt} ${EXTENSION_NAMES[ie.idExt] ?: "Extension"}"
        else "${ie.id} ${NAMES[ie.id] ?: "Element"}"

    private val NAMES = mapOf(
        0 to "SSID", 1 to "Supported rates", 3 to "DS parameter set", 5 to "Traffic indication map",
        7 to "Country", 11 to "BSS load", 32 to "Power constraint", 35 to "TPC report", 42 to "ERP information",
        45 to "HT capabilities", 48 to "RSN (security)", 50 to "Extended supported rates",
        54 to "Mobility domain (802.11r)", 59 to "Supported operating classes", 61 to "HT operation",
        70 to "RM enabled capabilities (802.11k)", 74 to "Overlapping BSS scan parameters",
        107 to "Interworking", 108 to "Advertisement protocol", 111 to "Roaming consortium",
        127 to "Extended capabilities", 191 to "VHT capabilities", 192 to "VHT operation",
        195 to "Transmit power envelope", 201 to "Reduced neighbor report", 221 to "Vendor specific",
    )

    private val EXTENSION_NAMES = mapOf(
        35 to "HE capabilities", 36 to "HE operation", 38 to "MU EDCA parameter set",
        39 to "Spatial reuse parameter set", 59 to "HE 6 GHz band capabilities",
        106 to "EHT operation", 107 to "Multi-link", 108 to "EHT capabilities",
    )
}
