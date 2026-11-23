package dev.airaudit.wifi

import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.Severity
import dev.airaudit.core.WifiSecurityType
import dev.airaudit.core.WifiSecurityType.OPEN
import dev.airaudit.core.WifiSecurityType.OWE
import dev.airaudit.core.WifiSecurityType.UNKNOWN
import dev.airaudit.core.WifiSecurityType.WAPI
import dev.airaudit.core.WifiSecurityType.WEP
import dev.airaudit.core.WifiSecurityType.WPA
import dev.airaudit.core.WifiSecurityType.WPA2_ENTERPRISE
import dev.airaudit.core.WifiSecurityType.WPA2_PERSONAL
import dev.airaudit.core.WifiSecurityType.WPA2_WPA3
import dev.airaudit.core.WifiSecurityType.WPA3_ENTERPRISE
import dev.airaudit.core.WifiSecurityType.WPA3_PERSONAL
import dev.airaudit.core.worst

/**
 * Turns what an access point advertises into a security type, a letter grade and plain-language findings.
 * Prefers the parsed RSN/WPA/WPS elements (Android 11+) and falls back to Android's `capabilities`
 * summary string, e.g. "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]", when they aren't available.
 */
object WifiSecurityAnalyzer {

    data class Assessment(
        val type: WifiSecurityType,
        val grade: Char,
        val wpsOn: Boolean,
        val findings: List<Finding>,
        val details: List<KeyValue>,
    )

    private val EAP_AKMS = setOf(1, 3, 5, 11, 12, 13, 14, 15, 16, 17)
    private val PSK_AKMS = setOf(2, 4, 6, 19, 20)
    private val SAE_AKMS = setOf(8, 9, 24, 25)

    fun assess(capabilities: String, ie: IeSummary?, hidden: Boolean): Assessment {
        val caps = capabilities.uppercase()
        val rsn = ie?.rsn
        val wpaIe = ie?.wpa
        val akm = rsn?.akmCodes.orEmpty()

        val hasRsn: Boolean
        val hasWpa1: Boolean
        val hasPsk: Boolean
        val hasSae: Boolean
        val hasEap: Boolean
        val hasOwe: Boolean
        val tkip: Boolean
        val mfpCapable: Boolean
        val mfpRequired: Boolean
        if (ie != null) {
            hasRsn = rsn != null
            hasWpa1 = wpaIe != null
            hasPsk = akm.any { it in PSK_AKMS } || wpaIe?.akms?.contains("PSK") == true
            hasSae = akm.any { it in SAE_AKMS }
            hasEap = akm.any { it in EAP_AKMS } || wpaIe?.akms?.any { it.startsWith("802.1X") } == true
            hasOwe = 18 in akm
            tkip = rsn?.usesTkip == true || wpaIe != null
            mfpCapable = rsn?.mfpCapable == true
            mfpRequired = rsn?.mfpRequired == true
        } else {
            hasRsn = "RSN-" in caps || "WPA2-" in caps
            hasWpa1 = "[WPA-" in caps
            hasPsk = "PSK" in caps
            hasSae = "SAE" in caps
            hasEap = "EAP" in caps
            hasOwe = "OWE" in caps && "OWE_TRANSITION" !in caps
            tkip = "TKIP" in caps
            mfpCapable = "MFPC" in caps || "MFPR" in caps
            mfpRequired = "MFPR" in caps
        }
        // WEP is signalled by the beacon's privacy bit, which only shows up in the capability string.
        val wep = "WEP" in caps && !hasRsn && !hasWpa1
        val wapi = "WAPI" in caps
        val suiteB = 12 in akm || "SUITE_B_192" in caps || "SUITE-B-192" in caps
        val wps = ie?.wps != null || "[WPS]" in caps

        val type = when {
            wapi -> WAPI
            hasEap && hasRsn && (suiteB || (ie != null && mfpRequired && 1 !in akm && 3 !in akm)) -> WPA3_ENTERPRISE
            hasEap && hasRsn -> WPA2_ENTERPRISE
            hasEap -> WPA
            hasSae && hasPsk -> WPA2_WPA3
            hasSae -> WPA3_PERSONAL
            hasPsk && hasRsn -> WPA2_PERSONAL
            hasPsk || hasWpa1 -> WPA
            hasOwe -> OWE
            wep -> WEP
            !hasRsn -> OPEN
            else -> UNKNOWN
        }

        val findings = mutableListOf<Finding>()
        when (type) {
            OPEN -> findings += if (ie?.oweTransition == true) {
                Finding(
                    Severity.HIGH, "Open, with an encrypted twin",
                    "Older devices connect here without encryption. Devices that support Enhanced Open are moved to a hidden encrypted twin automatically.",
                    "Avoid sensitive use on older devices, or use a VPN.",
                )
            } else {
                Finding(
                    Severity.CRITICAL, "No encryption",
                    "Anyone nearby can capture traffic on this network. Only HTTPS and VPN traffic stays private.",
                    "Use a VPN on this network. If it's yours, switch it to WPA3, or WPA2 with AES.",
                )
            }
            WEP -> findings += Finding(
                Severity.CRITICAL, "WEP is broken",
                "WEP keys can be recovered from captured traffic in minutes with freely available tools.",
                "Switch to WPA3, or WPA2 with AES. WEP was retired in 2004.",
            )
            WPA -> findings += Finding(
                Severity.HIGH, "Legacy WPA only",
                "First-generation WPA relies on TKIP, which has known weaknesses and is no longer allowed by the Wi-Fi Alliance.",
                "Upgrade the router to WPA2 (AES) or WPA3.",
            )
            OWE -> findings += Finding(
                Severity.LOW, "Encrypted, but anyone can join",
                "Enhanced Open encrypts each connection, but with no password it can't prove the access point is genuine.",
            )
            WPA2_PERSONAL -> findings += Finding(
                Severity.INFO, "Shared passphrase",
                "WPA2-Personal is only as strong as its passphrase: a captured login handshake can be guessed offline.",
                "Use a long random passphrase (12+ characters), or move to WPA3.",
            )
            WPA2_WPA3 -> findings += Finding(
                Severity.LOW, "WPA3 transition mode",
                "WPA2 devices are still accepted, so an attacker can try to push clients down to WPA2.",
                "Switch to WPA3-only once all your devices support it.",
            )
            WPA2_ENTERPRISE, WPA3_ENTERPRISE -> findings += Finding(
                Severity.INFO, "Enterprise login (802.1X)",
                "Each user signs in with their own credentials. It's strong when devices check the server's certificate.",
                "Make sure devices are set to validate the server certificate.",
            )
            else -> Unit
        }
        if (type != WPA && hasWpa1 && hasRsn) {
            findings += Finding(
                Severity.MEDIUM, "Legacy WPA still allowed",
                "The network accepts both WPA and WPA2, which keeps the weak TKIP cipher switched on.",
                "Set the router to WPA2 (AES) or WPA3 only.",
            )
        } else if (tkip && type != WPA) {
            findings += Finding(
                Severity.MEDIUM, "TKIP cipher enabled",
                "TKIP is deprecated. Broadcast traffic or older devices may fall back to it.",
                "Set encryption to AES (CCMP) only.",
            )
        }
        if (wps) {
            val w = ie?.wps
            val locked = w?.apSetupLocked == true
            val unconfigured = if (w?.configured == false) " The router also reports WPS as unconfigured." else ""
            findings += Finding(
                if (locked) Severity.LOW else Severity.MEDIUM,
                if (locked) "WPS on, temporarily locked" else "WPS is switched on",
                "WPS PIN logins can be brute-forced, and some router chips leak the PIN offline (the \"Pixie Dust\" flaw).$unconfigured",
                "Turn off WPS in the router's settings. Add devices with the passphrase or a QR code instead.",
            )
            val disclosed = listOfNotNull(w?.manufacturer, w?.modelName, w?.modelNumber, w?.serialNumber?.let { "serial number $it" }).distinct()
            if (disclosed.isNotEmpty()) {
                findings += Finding(
                    Severity.INFO, "Broadcasts its make and model",
                    "WPS advertises ${disclosed.joinToString(", ")}. That tells anyone nearby exactly which model-specific vulnerabilities to look up.",
                    "Turning off WPS stops this broadcast.",
                )
            }
        }
        if (hasRsn && !mfpCapable && (type == WPA2_PERSONAL || type == WPA2_ENTERPRISE)) {
            findings += Finding(
                Severity.LOW, "No protected management frames",
                "Control messages aren't signed, so any nearby device can forcibly disconnect clients. Attackers use this to capture login handshakes or push people onto a fake access point.",
                "Enable PMF (802.11w). Setting it to \"optional\" keeps older devices working.",
            )
        }
        if (hidden) {
            findings += Finding(
                Severity.INFO, "Hidden network name",
                "Hiding the name doesn't protect the network: devices that know it call out for it by name, which can also reveal where they've been.",
            )
        }

        val details = buildList {
            add(KeyValue("Security", type.label))
            if (rsn != null) {
                add(KeyValue("Key management", rsn.akms.joinToString()))
                add(KeyValue("Pairwise ciphers", rsn.pairwiseCiphers.joinToString()))
                add(KeyValue("Group cipher", rsn.groupCipher))
                add(
                    KeyValue(
                        "Protected management frames",
                        when {
                            rsn.mfpRequired -> "Required"
                            rsn.mfpCapable -> "Optional"
                            else -> "Off"
                        },
                    ),
                )
                rsn.groupMgmtCipher?.let { add(KeyValue("Management frame cipher", it)) }
            }
            if (wpaIe != null) {
                add(KeyValue("Legacy WPA element", "${wpaIe.akms.joinToString()} with ${wpaIe.pairwiseCiphers.joinToString()}"))
            }
            add(KeyValue("WPS", if (wps) "On" else "Off"))
            if (ie == null) add(KeyValue("Source", "Android capability flags (this Android version doesn't expose raw beacons)"))
        }
        return Assessment(type, grade(type, findings), wps, findings, details)
    }

    /** Letter grade: the security type sets a ceiling, and the worst finding can pull it down. */
    fun grade(type: WifiSecurityType, findings: List<Finding>): Char {
        val base = when (type) {
            WPA3_PERSONAL, WPA3_ENTERPRISE, WPA2_ENTERPRISE -> 'A'
            WPA2_WPA3, WPA2_PERSONAL, OWE, WAPI -> 'B'
            UNKNOWN -> 'C'
            WPA -> 'D'
            WEP, OPEN -> 'F'
        }
        val cap = when (findings.worst()) {
            Severity.CRITICAL -> 'F'
            Severity.HIGH -> 'D'
            Severity.MEDIUM -> 'C'
            else -> 'A'
        }
        return maxOf(base, cap)
    }
}
