package dev.airaudit.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/**
 * Wraps WifiManager. Android lets a foreground app *request* 4 scans per 2 minutes, but anyone's scan
 * (the system's or another app's) refreshes the shared results, so we also re-read them often.
 */
class WifiScanner(
    private val context: Context,
    private val onResults: (List<ScanResult>) -> Unit,
) {
    private val wifi: WifiManager? = context.getSystemService(WifiManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = publish()
    }

    val isUsable: Boolean
        @Suppress("DEPRECATION")
        get() = wifi?.let { it.isWifiEnabled || it.isScanAlwaysAvailable } == true

    fun start() {
        if (!registered) {
            // RECEIVER_EXPORTED is safe here: SCAN_RESULTS_AVAILABLE is a protected broadcast that only
            // the system can send, so no other app can feed us fake results.
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        }
        publish()
    }

    fun stop() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    /** Returns false when Android throttles the request. Cached results still arrive via publish(). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun requestScan(): Boolean = try {
        wifi?.startScan() == true
    } catch (e: SecurityException) {
        false
    }

    @SuppressLint("MissingPermission")
    fun publish() {
        val results = try {
            wifi?.scanResults.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        onResults(results)
    }

    /** BSSID and RSSI of the network this phone is connected to; RSSI here updates every few seconds. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connectedInfo(): Pair<String, Int>? = try {
        val info = wifi?.connectionInfo
        info?.bssid?.takeIf { it != "02:00:00:00:00:00" }?.let { it.lowercase() to info.rssi }
    } catch (e: SecurityException) {
        null
    }
}
