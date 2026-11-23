package dev.airaudit.cell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.airaudit.core.CellTower
import dev.airaudit.core.DistanceEstimate
import dev.airaudit.core.DistanceSource
import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.Section
import dev.airaudit.core.Severity
import dev.airaudit.core.formatMeters

/**
 * Reads the cells the modem can hear. Android gives signal strength and identity but not tower
 * positions, so the only distance available is the serving cell's timing advance: the radio round-trip
 * delay the network asks the phone to compensate for.
 */
class CellScanner(
    private val context: Context,
    private val onCells: (List<CellTower>) -> Unit,
) {
    private val telephony: TelephonyManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            context.getSystemService(TelephonyManager::class.java)
        } else {
            null
        }

    @SuppressLint("MissingPermission")
    fun refresh() {
        val tm = telephony ?: return
        try {
            tm.requestCellInfoUpdate(ContextCompat.getMainExecutor(context), object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: List<CellInfo>) = onCells(cellInfo.mapNotNull(::toTower))

                override fun onError(errorCode: Int, detail: Throwable?) {
                    // Fresh data unavailable (airplane mode, modem busy): fall back to the cached list.
                    val cached = try {
                        tm.allCellInfo.orEmpty()
                    } catch (e: SecurityException) {
                        emptyList()
                    }
                    onCells(cached.mapNotNull(::toTower))
                }
            })
        } catch (e: SecurityException) {
            onCells(emptyList())
        }
    }

    private fun toTower(info: CellInfo): CellTower? = when (info) {
        is CellInfoLte -> lte(info)
        is CellInfoNr -> nr(info)
        is CellInfoWcdma -> wcdma(info)
        is CellInfoGsm -> gsm(info)
        else -> null
    }

    private fun Int.ok(): Int? = takeIf { it != CellInfo.UNAVAILABLE }
    private fun Long.ok(): Long? = takeIf { it != CellInfo.UNAVAILABLE_LONG }

    private fun lte(info: CellInfoLte): CellTower {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val ci = id.ci.ok()
        val pci = id.pci.ok()
        val earfcn = id.earfcn.ok()
        val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toList() else emptyList()
        val ta = s.timingAdvance.ok()?.takeIf { it in 0..1282 }
        val rows = buildList {
            network(id.mccString, id.mncString)?.let { add(KeyValue("Network code", it)) }
            ci?.let { add(KeyValue("Cell ID", "$it (eNodeB ${it shr 8}, sector ${it and 0xFF})")) }
            id.tac.ok()?.let { add(KeyValue("Tracking area", "$it")) }
            pci?.let { add(KeyValue("Physical cell ID", "$it")) }
            earfcn?.let { add(KeyValue("EARFCN (channel)", "$it")) }
            if (bands.isNotEmpty()) add(KeyValue("Band", bands.joinToString { "B$it" }))
            id.bandwidth.ok()?.let { add(KeyValue("Bandwidth", "${it / 1000} MHz")) }
            s.rsrp.ok()?.let { add(KeyValue("RSRP (signal)", "$it dBm")) }
            s.rsrq.ok()?.let { add(KeyValue("RSRQ (quality)", "$it dB")) }
            s.rssnr.ok()?.let { add(KeyValue("Signal-to-noise", "$it dB")) }
            s.rssi.ok()?.let { add(KeyValue("RSSI", "$it dBm")) }
            s.cqi.ok()?.let { add(KeyValue("Channel quality (CQI)", "$it")) }
            ta?.let { add(KeyValue("Timing advance", "$it, about ${formatMeters(it * LTE_TA_METERS)}")) }
        }
        // One LTE timing-advance step is 16 Ts ≈ 0.52 µs of round trip, which is ≈ 78 m of distance.
        val distance = ta?.let {
            val m = it * LTE_TA_METERS
            DistanceEstimate(m, (m - LTE_TA_METERS).coerceAtLeast(0.0), m + LTE_TA_METERS, DistanceSource.TIMING_ADVANCE)
        }
        val title = if (info.isRegistered) listOfNotNull("4G LTE", bands.firstOrNull()?.let { "band $it" }).joinToString(", ")
        else "4G LTE, cell ${pci ?: "?"} on channel ${earfcn ?: "?"}"
        return tower("LTE", id.mccString, id.mncString, ci?.toLong(), pci, earfcn, info, title, id.operatorAlphaLong, s.dbm.ok(), s.level, distance, rows, emptyList())
    }

    private fun nr(info: CellInfoNr): CellTower {
        val id = info.cellIdentity as CellIdentityNr
        val s = info.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci.ok()
        val pci = id.pci.ok()
        val arfcn = id.nrarfcn.ok()
        val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toList() else emptyList()
        val taMicros = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) s.timingAdvanceMicros.ok() else null
        val rows = buildList {
            network(id.mccString, id.mncString)?.let { add(KeyValue("Network code", it)) }
            nci?.let { add(KeyValue("NR cell ID", "$it")) }
            id.tac.ok()?.let { add(KeyValue("Tracking area", "$it")) }
            pci?.let { add(KeyValue("Physical cell ID", "$it")) }
            arfcn?.let { add(KeyValue("NR-ARFCN (channel)", "$it")) }
            if (bands.isNotEmpty()) add(KeyValue("Band", bands.joinToString { "n$it" }))
            s.ssRsrp.ok()?.let { add(KeyValue("SS-RSRP (signal)", "$it dBm")) }
            s.ssRsrq.ok()?.let { add(KeyValue("SS-RSRQ (quality)", "$it dB")) }
            s.ssSinr.ok()?.let { add(KeyValue("SS-SINR", "$it dB")) }
            s.csiRsrp.ok()?.let { add(KeyValue("CSI-RSRP", "$it dBm")) }
            taMicros?.let { add(KeyValue("Timing advance", "$it µs, about ${formatMeters(it * NR_METERS_PER_MICROSECOND)}")) }
        }
        val distance = taMicros?.let {
            val m = it * NR_METERS_PER_MICROSECOND
            DistanceEstimate(m, (m - 75).coerceAtLeast(0.0), m + 75, DistanceSource.TIMING_ADVANCE)
        }
        val title = if (info.isRegistered) listOfNotNull("5G NR", bands.firstOrNull()?.let { "n$it" }).joinToString(", ")
        else "5G NR, cell ${pci ?: "?"} on channel ${arfcn ?: "?"}"
        return tower("NR", id.mccString, id.mncString, nci, pci, arfcn, info, title, id.operatorAlphaLong, s.dbm.ok(), s.level, distance, rows, emptyList())
    }

    private fun wcdma(info: CellInfoWcdma): CellTower {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val cid = id.cid.ok()
        val psc = id.psc.ok()
        val uarfcn = id.uarfcn.ok()
        val rows = buildList {
            network(id.mccString, id.mncString)?.let { add(KeyValue("Network code", it)) }
            cid?.let { add(KeyValue("Cell ID", "$it (RNC ${it shr 16}, cell ${it and 0xFFFF})")) }
            id.lac.ok()?.let { add(KeyValue("Location area", "$it")) }
            psc?.let { add(KeyValue("Scrambling code", "$it")) }
            uarfcn?.let { add(KeyValue("UARFCN (channel)", "$it")) }
            s.dbm.ok()?.let { add(KeyValue("Signal", "$it dBm")) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.ecNo.ok()?.let { add(KeyValue("Ec/No", "$it dB")) }
        }
        val title = if (info.isRegistered) "3G WCDMA" else "3G WCDMA, scrambling code ${psc ?: "?"}"
        return tower("WCDMA", id.mccString, id.mncString, cid?.toLong(), psc, uarfcn, info, title, id.operatorAlphaLong, s.dbm.ok(), s.level, null, rows, emptyList())
    }

    private fun gsm(info: CellInfoGsm): CellTower {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val cid = id.cid.ok()
        val arfcn = id.arfcn.ok()
        val ta = s.timingAdvance.ok()?.takeIf { it in 0..63 }
        val rows = buildList {
            network(id.mccString, id.mncString)?.let { add(KeyValue("Network code", it)) }
            cid?.let { add(KeyValue("Cell ID", "$it")) }
            id.lac.ok()?.let { add(KeyValue("Location area", "$it")) }
            arfcn?.let { add(KeyValue("ARFCN (channel)", "$it")) }
            id.bsic.ok()?.let { add(KeyValue("Base station colour code", "$it")) }
            s.dbm.ok()?.let { add(KeyValue("Signal", "$it dBm")) }
            s.bitErrorRate.ok()?.takeIf { it in 0..7 }?.let { add(KeyValue("Bit error rate class", "$it of 7")) }
            ta?.let { add(KeyValue("Timing advance", "$it, about ${formatMeters(it * GSM_TA_METERS)}")) }
        }
        // One GSM timing-advance step is one bit period (3.69 µs) of round trip, which is ≈ 550 m.
        val distance = ta?.let {
            val m = it * GSM_TA_METERS
            DistanceEstimate(m, (m - GSM_TA_METERS).coerceAtLeast(0.0), m + GSM_TA_METERS, DistanceSource.TIMING_ADVANCE)
        }
        val findings = if (info.isRegistered) {
            listOf(
                Finding(
                    Severity.MEDIUM, "Connected over 2G (GSM)",
                    "2G uses decades-old encryption and the phone can't check that the tower is genuine. Fake base stations (IMSI catchers) exploit this by pushing phones down to 2G.",
                    "Many Android 12+ phones let you switch off \"Allow 2G\" in the SIM's network settings.",
                ),
            )
        } else {
            emptyList()
        }
        val title = if (info.isRegistered) "2G GSM" else "2G GSM, channel ${arfcn ?: "?"}"
        return tower("GSM", id.mccString, id.mncString, cid?.toLong(), null, arfcn, info, title, id.operatorAlphaLong, s.dbm.ok(), s.level, distance, rows, findings)
    }

    private fun tower(
        tech: String,
        mcc: String?,
        mnc: String?,
        cellId: Long?,
        pci: Int?,
        channel: Int?,
        info: CellInfo,
        title: String,
        operator: CharSequence?,
        dbm: Int?,
        level: Int,
        distance: DistanceEstimate?,
        rows: List<KeyValue>,
        findings: List<Finding>,
    ): CellTower {
        // Neighbour cells often report no cell ID, so physical ID + channel makes the key instead.
        val key = "$tech-${mcc.orEmpty()}${mnc.orEmpty()}-" + (cellId?.toString() ?: "pci$pci-ch$channel")
        return CellTower(
            key, tech, info.isRegistered, operator?.toString()?.ifBlank { null }, title, dbm, level,
            distance, findings, listOf(Section("Cell", rows)),
        )
    }

    private fun network(mcc: String?, mnc: String?): String? = if (mcc != null && mnc != null) "$mcc-$mnc" else null

    private companion object {
        const val LTE_TA_METERS = 78.12
        const val GSM_TA_METERS = 553.5
        const val NR_METERS_PER_MICROSECOND = 149.9
    }
}
