package dev.airaudit.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.airaudit.core.Permissions

/**
 * Two kinds of Bluetooth listening:
 *  - BLE scanning: continuous, hears advertisements (phones, earbuds, watches, trackers, beacons).
 *  - Classic discovery: a ~12 s inquiry that finds older devices currently in pairing mode.
 */
class BluetoothScanner(
    private val context: Context,
    private val onBle: (ScanResult) -> Unit,
    private val onClassic: (ClassicSighting) -> Unit,
    private val onError: (String?) -> Unit,
) {
    class ClassicSighting(val address: String, val name: String?, val rssi: Int?, val deviceClass: BluetoothClass?)

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var bleRunning = false
    private var bleStartedAt = 0L
    private var receiverRegistered = false

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onBle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onBle)

        override fun onScanFailed(errorCode: Int) {
            bleRunning = false
            onError(
                when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> null
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        "Bluetooth refused the scan. Turning Bluetooth off and on again usually fixes it."
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                        "This phone doesn't support the requested Bluetooth scan mode."
                    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                        "Android paused Bluetooth scanning for a moment. It resumes on its own."
                    else -> "Bluetooth scan failed (code $errorCode)."
                },
            )
        }
    }

    /** Starts a continuous, unfiltered BLE scan. Safe to call repeatedly: a running scan is left alone. */
    @SuppressLint("MissingPermission")
    fun startBle() {
        val a = adapter ?: return
        if (!a.isEnabled || !Permissions.canScanBluetooth(context)) return
        // Android quietly downgrades unfiltered scans after 30 minutes, so restart a little before that.
        if (bleRunning && SystemClock.elapsedRealtime() - bleStartedAt > 25 * 60_000L) stopBle()
        if (bleRunning) return
        val scanner = a.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
                // Also hear Bluetooth 5 extended adverts (long range, 2M PHY) when the chip supports them.
                if (a.isLeExtendedAdvertisingSupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        try {
            scanner.startScan(emptyList(), settings, callback)
            bleRunning = true
            bleStartedAt = SystemClock.elapsedRealtime()
            onError(null)
        } catch (e: SecurityException) {
            onError("The Nearby devices permission is off, so Bluetooth can't be scanned.")
        } catch (e: IllegalStateException) {
            // Bluetooth was switched off at that exact moment; the next loop retries.
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBle() {
        if (!bleRunning) return
        bleRunning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            // Bluetooth already off or permission revoked: the scan is gone either way.
        }
    }

    /** Bluetooth was switched off underneath us, so the next startBle() must really start a scan. */
    fun markStopped() {
        bleRunning = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                .takeIf { it != Short.MIN_VALUE.toInt() }
            val cls = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
            onClassic(ClassicSighting(device.address, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi, cls))
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        if (!a.isEnabled || !Permissions.canScanBluetooth(context)) return
        if (!receiverRegistered) {
            // RECEIVER_EXPORTED is needed: ACTION_FOUND is sent by the Bluetooth stack, which runs under its
            // own user ID, and a NOT_EXPORTED receiver may never hear it. It's a protected broadcast, so
            // other apps can't fake it.
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        try {
            if (!a.isDiscovering) a.startDiscovery()
        } catch (e: SecurityException) {
            onError("The Nearby devices permission is off, so Bluetooth can't be scanned.")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // Without permission there's nothing to cancel.
        }
        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    /** Devices paired with this phone, so the UI can tell "yours" from strangers'. */
    @SuppressLint("MissingPermission")
    fun bondedAddresses(): Set<String> = try {
        if (Permissions.canConnectBluetooth(context)) {
            adapter?.bondedDevices?.map { it.address }?.toSet().orEmpty()
        } else {
            emptySet()
        }
    } catch (e: SecurityException) {
        emptySet()
    }
}
