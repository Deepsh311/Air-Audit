package dev.airaudit.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.airaudit.bluetooth.BleAdvert
import dev.airaudit.bluetooth.BleDecoded
import dev.airaudit.bluetooth.BleDecoder
import dev.airaudit.bluetooth.BluetoothScanner
import dev.airaudit.bluetooth.BtAddress
import dev.airaudit.cell.CellScanner
import dev.airaudit.core.AddressKind
import dev.airaudit.core.Bearing
import dev.airaudit.core.BtDevice
import dev.airaudit.core.CellTower
import dev.airaudit.core.Confidence
import dev.airaudit.core.DistanceEstimate
import dev.airaudit.core.DistanceModel
import dev.airaudit.core.DistanceSource
import dev.airaudit.core.Ema
import dev.airaudit.core.Environment
import dev.airaudit.core.Finding
import dev.airaudit.core.KeyValue
import dev.airaudit.core.Lookups
import dev.airaudit.core.Mac
import dev.airaudit.core.Permissions
import dev.airaudit.core.Section
import dev.airaudit.core.Severity
import dev.airaudit.core.SignalKind
import dev.airaudit.core.WifiAp
import dev.airaudit.core.WifiMath
import dev.airaudit.core.WifiSecurityType
import dev.airaudit.core.formatAgo
import dev.airaudit.core.formatMeters
import dev.airaudit.locate.BearingSweep
import dev.airaudit.locate.HeadingSensor
import dev.airaudit.wifi.IeSummary
import dev.airaudit.wifi.InformationElements
import dev.airaudit.wifi.RawIe
import dev.airaudit.wifi.RttRanger
import dev.airaudit.wifi.WifiScanner
import dev.airaudit.wifi.WifiSecurityAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import android.bluetooth.le.ScanResult as BleScanResult
import android.net.wifi.ScanResult as WifiScanResult

/**
 * The hub of the app. Scanners push raw results into mutable "records" (one per device) from their own
 * threads; a loop turns those records into an immutable UiState snapshot every 0.7 s for Compose to draw.
 * Batching matters: busy places produce hundreds of BLE callbacks per second.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext

    // Working state. Written by scanner callbacks, read by the snapshot builder, always under `lock`.
    private val lock = Any()
    private val wifiRecords = HashMap<String, WifiRecord>()
    private val bleRecords = HashMap<String, BleRecord>()
    private val classicRecords = HashMap<String, ClassicRecord>()
    private val bearings = HashMap<String, Bearing>()
    private var cells: List<CellTower> = emptyList()
    private var rawWifi: List<WifiScanResult> = emptyList()
    @Volatile private var connected: Pair<String, Int>? = null
    @Volatile private var bonded: Set<String> = emptySet()
    @Volatile private var bleError: String? = null
    @Volatile private var wifiThrottled = false
    @Volatile private var nextWifiScanAt = 0L
    @Volatile private var sweepStartedElapsedMs = 0L
    @Volatile private var environment = Environment.INDOOR
    @Volatile private var lastLocated: Int? = null

    private val headingSensor = HeadingSensor(ctx)
    val heading: StateFlow<Float?> = headingSensor.heading
    private val sweep = BearingSweep()
    private val wifiScanner = WifiScanner(ctx, ::onWifiResults)
    private val rtt = RttRanger(ctx, ::onRtt)
    private val bt = BluetoothScanner(ctx, ::onBle, ::onClassic, ::onBtError)
    private val cellScanner = CellScanner(ctx, ::onCells)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val jobs = mutableListOf<Job>()

    init {
        viewModelScope.launch(Dispatchers.IO) { Lookups.load(ctx.assets) }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Called when the app becomes visible. Scanning only runs in the foreground. */
    fun start() {
        if (jobs.isNotEmpty()) return
        headingSensor.start()
        wifiScanner.start()
        bt.startBle()
        jobs += viewModelScope.launch { wifiLoop() }
        jobs += viewModelScope.launch { bluetoothLoop() }
        jobs += viewModelScope.launch { slowLoop() }
        jobs += viewModelScope.launch { publishLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (sweep.isActive) finishSweep()
        headingSensor.stop()
        wifiScanner.stop()
        bt.stopBle()
        bt.stopDiscovery()
    }

    override fun onCleared() {
        stop()
    }

    // ---------------------------------------------------------------- user actions

    fun startSweep() {
        if (!headingSensor.available || sweep.isActive) return
        sweepStartedElapsedMs = SystemClock.elapsedRealtime()
        sweep.start()
        bt.stopDiscovery() // classic inquiry shares the radio and slows BLE scanning down
        nextWifiScanAt = 0L
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (sweep.isActive) {
                headingSensor.heading.value?.let(sweep::markHeading)
                val elapsed = SystemClock.elapsedRealtime() - started
                if ((sweep.coverage() >= 0.97f && elapsed > 8_000L) || elapsed > 60_000L) {
                    finishSweep()
                    break
                }
                delay(100L)
            }
        }
        publishSoon()
    }

    fun finishSweep() {
        if (!sweep.isActive) return
        val result = sweep.finish(System.currentTimeMillis())
        synchronized(lock) { bearings.putAll(result) }
        lastLocated = result.values.count { it.confidence != Confidence.LOW }
        publishSoon()
    }

    fun setEnvironment(value: Environment) {
        environment = value
        publishSoon()
    }

    // ---------------------------------------------------------------- loops

    private suspend fun wifiLoop() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextWifiScanAt) {
                val accepted = wifiScanner.requestScan()
                wifiThrottled = !accepted
                nextWifiScanAt = now + when {
                    sweep.isActive -> 2_500L
                    accepted -> 30_000L // 4 requests per 2 minutes is the foreground budget
                    else -> 10_000L
                }
            }
            wifiScanner.publish()
            val info = wifiScanner.connectedInfo()
            connected = info
            // The connected network's RSSI refreshes every few seconds even when scans are throttled.
            if (sweep.isActive && info != null) {
                headingSensor.heading.value?.let { sweep.addSample("wifi:${info.first}", it, info.second) }
            }
            delay(if (sweep.isActive) 500L else 2_000L)
        }
    }

    private suspend fun bluetoothLoop() {
        var nextDiscovery = SystemClock.elapsedRealtime() + 4_000L
        while (true) {
            if (bt.isEnabled) bt.startBle() else bt.markStopped()
            val now = SystemClock.elapsedRealtime()
            if (!sweep.isActive && now >= nextDiscovery) {
                bt.startDiscovery()
                nextDiscovery = now + 45_000L
            }
            delay(2_000L)
        }
    }

    private suspend fun slowLoop() {
        while (true) {
            cellScanner.refresh()
            bonded = bt.bondedAddresses()
            val candidates = synchronized(lock) { rawWifi.filter { it.is80211mcResponder } }
            if (candidates.isNotEmpty()) rtt.range(candidates)
            delay(5_000L)
        }
    }

    private suspend fun publishLoop() {
        while (true) {
            publish()
            delay(700L)
        }
    }

    private suspend fun publish() {
        _state.value = withContext(Dispatchers.Default) { buildState() }
    }

    private fun publishSoon() {
        viewModelScope.launch { publish() }
    }

    // ---------------------------------------------------------------- scanner callbacks

    private fun onWifiResults(results: List<WifiScanResult>) {
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            rawWifi = results
            for (r in results) {
                val bssid = r.BSSID?.lowercase() ?: continue
                val rec = wifiRecords.getOrPut(bssid) { WifiRecord(bssid) }
                val observedElapsed = r.timestamp / 1000 // µs since boot → ms
                if (observedElapsed <= rec.lastObservedElapsedMs) continue // cached repeat, not a new reading
                rec.lastObservedElapsedMs = observedElapsed
                val seenMs = nowMs - (nowElapsed - observedElapsed)
                rec.result = r
                rec.ema.update(r.level.toDouble())
                rec.history.push(r.level)
                if (rec.firstSeenMs == 0L) rec.firstSeenMs = seenMs
                rec.lastSeenMs = seenMs
                rec.observations++
                rec.ie = informationElementsOf(r)
                if (sweep.isActive && observedElapsed >= sweepStartedElapsedMs) {
                    headingSensor.headingAt(r.timestamp * 1000)?.let { sweep.addSample("wifi:$bssid", it, r.level) }
                }
            }
        }
    }

    private fun informationElementsOf(r: WifiScanResult): IeSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val elements = r.informationElements
        if (elements.isEmpty()) return null
        return InformationElements.parse(
            elements.map { e ->
                val buffer = e.bytes.duplicate()
                RawIe(e.id, e.idExt, ByteArray(buffer.remaining()).also { buffer.get(it) })
            },
        )
    }

    private fun onRtt(reading: RttRanger.Reading) {
        synchronized(lock) { wifiRecords[reading.bssid.lowercase()]?.rtt = reading }
    }

    private fun onBle(r: BleScanResult) {
        val address = r.device?.address ?: return
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val rec = bleRecords.getOrPut(address) { BleRecord(address) }
            rec.last = r
            rec.ema.update(r.rssi.toDouble())
            rec.count++
            if (rec.firstSeenMs == 0L) rec.firstSeenMs = nowMs
            rec.lastSeenMs = nowMs
            if (nowMs - rec.lastHistoryMs >= 1_000L) {
                rec.history.push(r.rssi)
                rec.lastHistoryMs = nowMs
            }
            // Only re-decode when the payload changed (or the name tables finished loading).
            val raw = r.scanRecord?.bytes
            if (rec.decoded == null || !raw.contentEquals(rec.decodedFrom) || (!rec.decodedWithTables && Lookups.isLoaded)) {
                rec.decoded = BleDecoder.decode(advertOf(r))
                rec.decodedFrom = raw
                rec.decodedWithTables = Lookups.isLoaded
            }
        }
        if (sweep.isActive) {
            (headingSensor.headingAt(r.timestampNanos) ?: headingSensor.heading.value)
                ?.let { sweep.addSample("bt:$address", it, r.rssi) }
        }
    }

    private fun advertOf(r: BleScanResult): BleAdvert {
        val record = r.scanRecord
        val manufacturer = HashMap<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) manufacturer[data.keyAt(i)] = data.valueAt(i)
        }
        val tx = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
            ?: r.txPower.takeIf { it != BleScanResult.TX_POWER_NOT_PRESENT }
        return BleAdvert(
            manufacturerData = manufacturer,
            serviceUuids = record?.serviceUuids?.map { it.uuid }.orEmpty(),
            serviceData = record?.serviceData?.mapKeys { it.key.uuid }.orEmpty(),
            txPower = tx,
            raw = record?.bytes,
        )
    }

    private fun onClassic(s: BluetoothScanner.ClassicSighting) {
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val rec = classicRecords.getOrPut(s.address) { ClassicRecord(s.address) }
            s.name?.let { rec.name = it }
            s.deviceClass?.let { rec.deviceClass = it }
            s.rssi?.let {
                rec.rssi = it
                rec.ema.update(it.toDouble())
                rec.history.push(it)
            }
            if (rec.firstSeenMs == 0L) rec.firstSeenMs = nowMs
            rec.lastSeenMs = nowMs
            rec.count++
        }
    }

    private fun onCells(list: List<CellTower>) {
        synchronized(lock) { cells = list }
    }

    private fun onBtError(message: String?) {
        bleError = message
    }

    // ---------------------------------------------------------------- snapshot

    private fun buildState(): UiState {
        val now = System.currentTimeMillis()
        val n = environment.exponent
        val snapshot = synchronized(lock) {
            pruneLocked(now)
            Triple(buildWifiLocked(now, n), buildBluetoothLocked(now, n), cells)
        }
        val location = ctx.getSystemService(LocationManager::class.java)
        return UiState(
            wifi = snapshot.first,
            bluetooth = snapshot.second,
            cells = snapshot.third,
            environment = environment,
            sweep = SweepUi(sweep.isActive, sweep.coverage(), sweep.coveredBins(), lastLocated),
            status = ScanStatus(
                locationOn = location != null && LocationManagerCompat.isLocationEnabled(location),
                bluetoothAvailable = bt.isAvailable,
                bluetoothOn = bt.isEnabled,
                bluetoothPermitted = Permissions.canScanBluetooth(ctx),
                wifiScanUsable = wifiScanner.isUsable,
                wifiThrottled = wifiThrottled,
                bleError = bleError,
                rttSupported = rtt.supported,
                compassAvailable = headingSensor.available,
            ),
            updatedAtMs = now,
        )
    }

    /** Rotating BLE addresses pile up in busy places; forget anything unheard for 30 minutes. */
    private fun pruneLocked(now: Long) {
        bleRecords.values.removeAll { now - it.lastSeenMs > 30 * 60_000L }
        bearings.entries.removeAll { now - it.value.measuredAtMs > 30 * 60_000L }
    }

    private fun buildWifiLocked(now: Long, n: Double): List<WifiAp> {
        val connectedBssid = connected?.first
        val aps = wifiRecords.values.mapNotNull { wifiAp(it, now, n, it.bssid == connectedBssid) }
        // Evil-twin check: an open or legacy-WPA access point sharing a name with a properly secured one.
        val strongest = aps.filter { it.ssid.isNotBlank() }.groupBy { it.ssid }
            .mapValues { (_, group) -> group.maxOf { it.securityType.strength } }
        val enhancedOpen = aps.filter { it.securityType == WifiSecurityType.OWE }.map { it.ssid }.toSet()
        return aps.map { ap ->
            val best = strongest[ap.ssid] ?: return@map ap
            val weak = ap.securityType.strength <= WifiSecurityType.WPA.strength
            if (!weak || best < WifiSecurityType.WPA2_PERSONAL.strength || ap.ssid in enhancedOpen) return@map ap
            val findings = ap.findings + Finding(
                Severity.HIGH, "Weaker copy of a protected network",
                "Another access point called \"${ap.ssid}\" is secured, but this one is ${ap.securityType.label}. It could be an impostor (an \"evil twin\") or a misconfigured access point.",
                "Don't connect to this one. If the network is yours, check every access point's settings.",
            )
            ap.copy(findings = findings, grade = WifiSecurityAnalyzer.grade(ap.securityType, findings))
        }.sortedWith(compareByDescending<WifiAp> { it.worst?.rank ?: -1 }.thenByDescending { it.smoothedRssi })
    }

    @Suppress("DEPRECATION")
    private fun wifiAp(rec: WifiRecord, now: Long, n: Double, isConnected: Boolean): WifiAp? {
        val r = rec.result ?: return null
        val ssid = (r.SSID ?: "").filter { it >= ' ' }
        val ie = rec.ie
        val hidden = ssid.isBlank() || ie?.ssidHidden == true
        val assessment = WifiSecurityAnalyzer.assess(r.capabilities.orEmpty(), ie, hidden)
        val vendor = Lookups.macVendor(rec.bssid)
        val randomised = Mac.isLocallyAdministered(rec.bssid)
        val rttReading = rec.rtt?.takeIf { now - it.atMs < 30_000L }
        val distance = if (rttReading != null) {
            val spread = max(0.5, 2 * rttReading.stdDevMeters)
            DistanceEstimate(
                rttReading.meters.coerceAtLeast(0.0), (rttReading.meters - spread).coerceAtLeast(0.0),
                rttReading.meters + spread, DistanceSource.RTT,
            )
        } else {
            DistanceModel.fromRssi(rec.ema.value, DistanceModel.wifiReferenceAt1m(r.frequency), n, DistanceSource.MODEL)
        }

        val radio = buildList {
            add(KeyValue("Signal", "${r.level} dBm now, ${rec.ema.value.roundToInt()} dBm average"))
            add(KeyValue("Frequency", "${r.frequency} MHz, ${WifiMath.band(r.frequency)}, channel ${WifiMath.channel(r.frequency)}"))
            add(KeyValue("Channel width", widthLabel(r.channelWidth)))
            add(KeyValue("Standard", standardLabel(r)))
            ie?.ratesMbps?.takeIf { it.isNotEmpty() }?.let { rates ->
                add(KeyValue("Legacy rates", rates.joinToString { it.toString().removeSuffix(".0") } + " Mbps"))
            }
            add(KeyValue("Wi-Fi RTT ranging", if (r.is80211mcResponder) "Supported" else "Not supported"))
            rttReading?.let {
                add(KeyValue("RTT distance", "${formatMeters(it.meters)} ± ${formatMeters(it.stdDevMeters)}, ${it.successes} of ${it.attempts} measurements"))
            }
            add(KeyValue("Seen", "${rec.observations} times, first ${formatAgo(now - rec.firstSeenMs)}, last ${formatAgo(now - rec.lastSeenMs)}"))
        }
        val network = buildList {
            ie?.bssLoad?.let {
                add(KeyValue("Connected clients", "${it.stations}"))
                add(KeyValue("Channel busy", "${it.utilizationPct}%"))
            }
            ie?.country?.let { add(KeyValue("Country code", it)) }
            if (ie != null) {
                val roaming = listOfNotNull(
                    "802.11k".takeIf { ie.radioMeasurement },
                    "802.11r".takeIf { ie.fastTransition },
                    "802.11v".takeIf { ie.bssTransition },
                )
                add(KeyValue("Fast roaming", roaming.joinToString().ifEmpty { "None advertised" }))
                val extras = listOfNotNull(
                    "WMM (QoS)".takeIf { ie.wmm },
                    "Wi-Fi Direct".takeIf { ie.wifiDirect },
                    "Hotspot 2.0".takeIf { ie.hotspot20 },
                    "MBO".takeIf { ie.mbo },
                    "EasyMesh".takeIf { ie.multiAp },
                    "OWE transition".takeIf { ie.oweTransition },
                )
                if (extras.isNotEmpty()) add(KeyValue("Features", extras.joinToString()))
            }
            if (r.isPasspointNetwork) {
                val names = listOfNotNull(r.operatorFriendlyName?.toString(), r.venueName?.toString()).filter { it.isNotBlank() }
                add(KeyValue("Passpoint", names.joinToString().ifEmpty { "Yes" }))
            }
        }
        val hardware = buildList {
            add(KeyValue("BSSID", rec.bssid.uppercase(), mono = true))
            add(KeyValue("Manufacturer, from the BSSID", vendor ?: if (randomised) "None: the address is locally administered" else "Not in the IEEE registry"))
            if (randomised) add(KeyValue("Address type", "Locally administered, typical of extra networks on one router, mesh nodes and phone hotspots"))
            ie?.vendorElements?.takeIf { it.isNotEmpty() }?.let { add(KeyValue("Vendor elements", it.joinToString("\n"), mono = true)) }
        }
        val wps = ie?.wps?.let { w ->
            buildList {
                add(KeyValue("Version", w.version))
                w.configured?.let { add(KeyValue("State", if (it) "Configured" else "Not configured")) }
                w.apSetupLocked?.let { add(KeyValue("PIN lockout", if (it) "Locked" else "Not locked")) }
                if (w.selectedRegistrar == true) add(KeyValue("Pairing", "Active right now"))
                w.deviceName?.let { add(KeyValue("Device name", it)) }
                w.manufacturer?.let { add(KeyValue("Manufacturer", it)) }
                listOfNotNull(w.modelName, w.modelNumber).takeIf { it.isNotEmpty() }?.let { add(KeyValue("Model", it.joinToString(" "))) }
                w.serialNumber?.let { add(KeyValue("Serial number", it, mono = true)) }
                w.primaryDeviceType?.let { add(KeyValue("Device type", it)) }
                if (w.configMethods.isNotEmpty()) add(KeyValue("Methods", w.configMethods.joinToString()))
                w.uuid?.let { add(KeyValue("UUID", it, mono = true)) }
            }
        }
        val sections = listOfNotNull(
            Section("Security", assessment.details),
            wps?.let { Section("WPS", it) },
            Section("Radio", radio),
            network.takeIf { it.isNotEmpty() }?.let { Section("Network", it) },
            Section("Hardware", hardware),
        )
        return WifiAp(
            bssid = rec.bssid,
            ssid = ssid,
            hidden = hidden,
            connected = isConnected,
            rssi = r.level,
            smoothedRssi = rec.ema.value,
            frequencyMhz = r.frequency,
            channel = WifiMath.channel(r.frequency),
            band = WifiMath.band(r.frequency),
            securityType = assessment.type,
            grade = assessment.grade,
            wpsOn = assessment.wpsOn,
            findings = assessment.findings,
            vendor = vendor,
            distance = distance,
            bearing = bearings["wifi:${rec.bssid}"],
            firstSeenMs = rec.firstSeenMs,
            lastSeenMs = rec.lastSeenMs,
            history = rec.history.toList(),
            sections = sections,
            rawElements = ie?.raw.orEmpty(),
            capabilities = r.capabilities.orEmpty(),
        )
    }

    private fun widthLabel(width: Int): String = when (width) {
        WifiScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
        WifiScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
        WifiScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
        WifiScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
        WifiScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
        WifiScanResult.CHANNEL_WIDTH_320MHZ -> "320 MHz"
        else -> "Unknown"
    }

    private fun standardLabel(r: WifiScanResult): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Unknown"
        return when (r.wifiStandard) {
            WifiScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            WifiScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
            WifiScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
            WifiScanResult.WIFI_STANDARD_11AX -> if (r.frequency >= 5925) "Wi-Fi 6E (802.11ax)" else "Wi-Fi 6 (802.11ax)"
            WifiScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
            WifiScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
            else -> "Unknown"
        }
    }

    private fun buildBluetoothLocked(now: Long, n: Double): List<BtDevice> {
        val list = ArrayList<BtDevice>()
        for (rec in bleRecords.values) bleDevice(rec, now, n)?.let { list += it }
        for (rec in classicRecords.values) if (rec.address !in bleRecords) list += classicDevice(rec, now, n)
        return list.sortedWith(
            compareByDescending<BtDevice> { it.worst?.rank ?: -1 }
                .thenByDescending { now - it.lastSeenMs < 30_000L }
                .thenByDescending { it.smoothedRssi },
        )
    }

    private fun bleDevice(rec: BleRecord, now: Long, n: Double): BtDevice? {
        val r = rec.last ?: return null
        val d = rec.decoded ?: return null
        val classic = classicRecords[rec.address]
        val advertisedName = r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
        val name = advertisedName ?: classic?.name ?: systemName(r) ?: d.nameHint
        val addressKind = BtAddress.kind(rec.address, isRandomAddress(r))
        val vendor = d.vendor ?: if (addressKind == AddressKind.PUBLIC) Lookups.macVendor(rec.address) else null
        val label = name ?: d.label ?: vendor?.let { "$it device" } ?: "Unnamed device"
        val calibrated = d.ref1m != null
        val distance = DistanceModel.fromRssi(
            rec.ema.value, d.ref1m ?: DistanceModel.BLE_REFERENCE_AT_1M, n,
            if (calibrated) DistanceSource.CALIBRATED else DistanceSource.MODEL,
            fadeDb = if (calibrated) 5.0 else 7.0,
        )
        val findings = d.findings + privacyFindings(name, addressKind, d.tracker) +
            trackerDuration(d.tracker, rec.firstSeenMs, rec.lastSeenMs)
        val identity = buildList {
            add(KeyValue("Address", rec.address, mono = true))
            add(KeyValue("Address type", addressKind.label))
            vendor?.let { add(KeyValue("Manufacturer", it)) }
            d.appearance?.let { add(KeyValue("Appearance", it)) }
            advertisedName?.let { add(KeyValue("Advertised name", it)) }
            classic?.name?.takeIf { it != advertisedName }?.let { add(KeyValue("Name from discovery", it)) }
            add(KeyValue("Paired with this phone", if (rec.address in bonded) "Yes" else "No"))
        }
        val signal = buildList {
            add(KeyValue("Signal", "${r.rssi} dBm now, ${rec.ema.value.roundToInt()} dBm average"))
            d.ref1m?.let { add(KeyValue("Calibrated power at 1 m", "${it.roundToInt()} dBm")) }
            add(KeyValue("Connectable", if (r.isConnectable) "Yes" else "No"))
            add(KeyValue("Advertising", if (r.isLegacy) "Legacy (Bluetooth 4 style)" else "Extended (Bluetooth 5)"))
            if (!r.isLegacy) {
                val secondary = if (r.secondaryPhy != BleScanResult.PHY_UNUSED) ", then ${phyLabel(r.secondaryPhy)}" else ""
                add(KeyValue("Radio mode", phyLabel(r.primaryPhy) + secondary))
            }
            if (r.periodicAdvertisingInterval != BleScanResult.PERIODIC_INTERVAL_NOT_PRESENT) {
                add(KeyValue("Periodic advertising", "every ${r.periodicAdvertisingInterval * 1.25} ms"))
            }
            add(KeyValue("Seen", "${rec.count} adverts, first ${formatAgo(now - rec.firstSeenMs)}, last ${formatAgo(now - rec.lastSeenMs)}"))
        }
        val sections = listOfNotNull(
            Section("Identity", identity),
            d.facts.takeIf { it.isNotEmpty() }?.let { Section("Decoded advertisement", it) },
            Section("Signal", signal),
            d.adStructures.takeIf { it.isNotEmpty() }?.let { Section("Raw advertising data", it) },
        )
        return BtDevice(
            address = rec.address,
            kind = SignalKind.BLE,
            label = label,
            name = name,
            category = d.category,
            vendor = vendor,
            addressKind = addressKind,
            rssi = r.rssi,
            smoothedRssi = rec.ema.value,
            tracker = d.tracker,
            bonded = rec.address in bonded,
            findings = findings,
            distance = distance,
            bearing = bearings["bt:${rec.address}"],
            firstSeenMs = rec.firstSeenMs,
            lastSeenMs = rec.lastSeenMs,
            history = rec.history.toList(),
            sightings = rec.count,
            sections = sections,
        )
    }

    private fun classicDevice(rec: ClassicRecord, now: Long, n: Double): BtDevice {
        val (category, classLabel) = BleDecoder.classicCategory(rec.deviceClass)
        val vendor = Lookups.macVendor(rec.address)
        val label = rec.name ?: listOfNotNull(vendor, classLabel?.lowercase()).joinToString(" ").ifBlank { "Unnamed device" }
        val smoothed = rec.ema.value.takeIf { !it.isNaN() } ?: -90.0
        val findings = buildList {
            add(
                Finding(
                    Severity.INFO, "Discoverable",
                    "This device is in pairing or discoverable mode, so it shares its name and type with everyone nearby.",
                    "Most devices leave this mode on their own after a few minutes.",
                ),
            )
            addAll(privacyFindings(rec.name, AddressKind.PUBLIC, tracker = false).filter { it.severity != Severity.INFO })
        }
        val rows = buildList {
            add(KeyValue("Address", rec.address, mono = true))
            add(KeyValue("Address type", AddressKind.PUBLIC.label))
            vendor?.let { add(KeyValue("Manufacturer", it)) }
            classLabel?.let { add(KeyValue("Device class", it)) }
            add(KeyValue("Signal", "${rec.rssi} dBm"))
            add(KeyValue("Paired with this phone", if (rec.address in bonded) "Yes" else "No"))
            add(KeyValue("Seen", "${rec.count} times, first ${formatAgo(now - rec.firstSeenMs)}, last ${formatAgo(now - rec.lastSeenMs)}"))
        }
        return BtDevice(
            address = rec.address,
            kind = SignalKind.CLASSIC,
            label = label,
            name = rec.name,
            category = category,
            vendor = vendor,
            addressKind = AddressKind.PUBLIC,
            rssi = rec.rssi,
            smoothedRssi = smoothed,
            tracker = false,
            bonded = rec.address in bonded,
            findings = findings,
            distance = DistanceModel.fromRssi(smoothed, DistanceModel.CLASSIC_REFERENCE_AT_1M, n, DistanceSource.MODEL, fadeDb = 7.0),
            bearing = bearings["bt:${rec.address}"],
            firstSeenMs = rec.firstSeenMs,
            lastSeenMs = rec.lastSeenMs,
            history = rec.history.toList(),
            sightings = rec.count,
            sections = listOf(Section("Identity", rows)),
        )
    }

    private val possessive = Regex("""\p{L}+['’]s\b""")

    private fun privacyFindings(name: String?, kind: AddressKind, tracker: Boolean): List<Finding> = buildList {
        if (name != null && possessive.containsMatchIn(name)) {
            add(
                Finding(
                    Severity.LOW, "Name may identify its owner",
                    "It broadcasts the name \"$name\" to everyone nearby.",
                    "Rename the device to something generic in its Bluetooth settings.",
                ),
            )
        }
        if (kind.fixed && !tracker) {
            add(
                Finding(
                    Severity.INFO, "Fixed Bluetooth address",
                    "This device's address never changes, so it can be recognised again wherever it goes.",
                ),
            )
        }
    }

    private fun trackerDuration(tracker: Boolean, firstMs: Long, lastMs: Long): List<Finding> {
        val minutes = (lastMs - firstMs) / 60_000
        if (!tracker || minutes < 10) return emptyList()
        return listOf(
            Finding(
                Severity.MEDIUM, "Tracker has stayed near you for $minutes min",
                "The same tracker has been in range for $minutes minutes. If you've been moving and it came with you, it may be travelling with you.",
                "Use Find to locate it. Trackers you own will show here too.",
            ),
        )
    }

    private fun phyLabel(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "LE 1M"
        BluetoothDevice.PHY_LE_2M -> "LE 2M"
        BluetoothDevice.PHY_LE_CODED -> "LE Coded (long range)"
        else -> "PHY $phy"
    }

    /** Android 15 finally reports whether an address is public or random; older versions return null. */
    private fun isRandomAddress(r: BleScanResult): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            when (r.device.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> false
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> true
                else -> null
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /** The name Android has cached for a device (from pairing or discovery); needs BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    private fun systemName(r: BleScanResult): String? {
        if (!Permissions.canConnectBluetooth(ctx)) return null
        return try {
            r.device?.name?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        }
    }
}

private const val HISTORY_SIZE = 120

private fun ArrayDeque<Int>.push(value: Int) {
    addLast(value)
    while (size > HISTORY_SIZE) removeFirst()
}

private class WifiRecord(val bssid: String) {
    var result: WifiScanResult? = null
    var lastObservedElapsedMs = 0L
    val ema = Ema(0.5)
    val history = ArrayDeque<Int>()
    var firstSeenMs = 0L
    var lastSeenMs = 0L
    var observations = 0
    var ie: IeSummary? = null
    var rtt: RttRanger.Reading? = null
}

private class BleRecord(val address: String) {
    var last: BleScanResult? = null
    val ema = Ema(0.2)
    val history = ArrayDeque<Int>()
    var lastHistoryMs = 0L
    var firstSeenMs = 0L
    var lastSeenMs = 0L
    var count = 0
    var decoded: BleDecoded? = null
    var decodedFrom: ByteArray? = null
    var decodedWithTables = false
}

private class ClassicRecord(val address: String) {
    var name: String? = null
    var deviceClass: BluetoothClass? = null
    var rssi = -100
    val ema = Ema(0.5)
    val history = ArrayDeque<Int>()
    var firstSeenMs = 0L
    var lastSeenMs = 0L
    var count = 0
}
