package dev.airaudit.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import androidx.core.content.ContextCompat

/**
 * Wi-Fi RTT (802.11mc "fine timing measurement") times radio round trips to an access point, giving
 * distance to roughly a metre. It needs support on both the phone and the access point, so most APs
 * fall back to the signal-strength estimate.
 */
class RttRanger(
    private val context: Context,
    private val onReading: (Reading) -> Unit,
) {
    class Reading(
        val bssid: String,
        val meters: Double,
        val stdDevMeters: Double,
        val atMs: Long,
        val successes: Int,
        val attempts: Int,
    )

    private val manager: WifiRttManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            context.getSystemService(WifiRttManager::class.java)
        } else {
            null
        }

    val supported: Boolean get() = manager != null

    @SuppressLint("MissingPermission")
    fun range(candidates: List<ScanResult>) {
        val rtt = manager ?: return
        if (!rtt.isAvailable || candidates.isEmpty()) return
        val request = RangingRequest.Builder().apply {
            candidates.take(RangingRequest.getMaxPeers()).forEach { addAccessPoint(it) }
        }.build()
        try {
            rtt.startRanging(request, ContextCompat.getMainExecutor(context), object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) = Unit

                override fun onRangingResults(results: List<RangingResult>) {
                    val now = System.currentTimeMillis()
                    results.filter { it.status == RangingResult.STATUS_SUCCESS }.forEach { r ->
                        val mac = r.macAddress?.toString() ?: return@forEach
                        onReading(
                            Reading(
                                mac, r.distanceMm / 1000.0, r.distanceStdDevMm / 1000.0, now,
                                r.numSuccessfulMeasurements, r.numAttemptedMeasurements,
                            ),
                        )
                    }
                }
            })
        } catch (e: SecurityException) {
            // Permission was revoked while running; distances fall back to signal strength.
        } catch (e: IllegalArgumentException) {
            // An access point dropped out of range between the scan and the request.
        }
    }
}
