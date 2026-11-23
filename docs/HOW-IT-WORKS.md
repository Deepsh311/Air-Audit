# How AirAudit works

A walkthrough of each part: what it does, why it's built this way, what was rejected, and what to search for to go deeper.

## The big picture

```
 Wi-Fi scanner ──┐
 RTT ranger ─────┤
 Bluetooth ──────┼──► ScanViewModel ──(snapshot every 0.7 s)──► UiState ──► Compose screens
 Cell scanner ───┤     one record per device:
 Compass ────────┘     smoothing, history, decoding, bearings
```

Scanners call back on their own threads with raw Android objects. The ViewModel keeps one mutable record per device (latest result, smoothed signal, history, decoded data). A loop turns those records into one immutable `UiState` every 0.7 seconds, and the screens draw that.

- **Why batch:** a busy café produces hundreds of Bluetooth advertisements a second. Redrawing on every packet would freeze the UI; 0.7 s still feels live.
- **Rejected:** pushing every result straight into Compose state.
- **Key takeaway:** keep *ingesting* data (fast, mutable, any thread) separate from *presenting* it (immutable snapshots on the main thread).
- **Google this:** "StateFlow collectAsStateWithLifecycle", "unidirectional data flow Android".

## Wi-Fi security grading

Access points broadcast beacons several times a second. Each beacon carries a list of *information elements* (IEs): small type, length, value blocks. Android 11 and later hands them over raw through `ScanResult.getInformationElements()`.

`InformationElements.kt` parses the ones that matter:

| Element | What it reveals |
|---|---|
| 48, RSN | Ciphers (CCMP, TKIP, GCMP), key management (PSK, SAE, 802.1X, OWE) and management-frame protection bits |
| 221, Microsoft type 1 | Legacy WPA |
| 221, Microsoft type 4 | WPS: version, lock state, manufacturer, model, serial number |
| 11, BSS Load | Connected clients and how busy the channel is |
| 54, 70, 127 | 802.11r, 802.11k and 802.11v fast roaming |

`WifiSecurityAnalyzer.kt` turns that into a security type, findings and a grade. The type sets a ceiling (WPA3 is A, WPA2-Personal B, WPA D, open and WEP F) and the worst finding can pull the grade lower. Afterwards the ViewModel groups access points by name: an open or legacy one sharing a name with a WPA2/WPA3 one is flagged as a possible evil twin.

- **Rejected:** only reading Android's `capabilities` string (`[WPA2-PSK-CCMP][ESS][WPS]`). It's simpler, but it can't show PMF status, client counts, or that a router broadcasts its serial number. It's still the fallback on Android 10.
- **Key takeaway:** broadcast data is attacker-controlled input. Every parser bounds-checks before it reads, so a malformed beacon can't crash the app.
- **Google this:** "802.11 RSN information element format", "WPS attribute types", "WPS Pixie Dust", "802.11w protected management frames", "evil twin access point".

## Bluetooth decoding

Bluetooth LE devices advertise every 20 ms to 10 s. An advertisement is a list of *AD structures* (length, type, data). Android parses the basics; `BleDecoder.kt` decodes the formats that reveal what a device is:

- **Apple Continuity** (manufacturer data, company 0x004C): a list of typed messages. Type 0x12 is Find My, and a 25-byte payload means the item is away from its owner.
- **iBeacon and Eddystone:** beacons that carry a calibrated "power at 1 m", which makes their distance estimates better.
- **Tracker networks:** service UUIDs 0xFD5A (Samsung SmartTag), 0xFEED and 0xFEEC (Tile), 0xFE33 (Chipolo), and Eddystone frames 0x40 and 0x41 (Google Find My Device).
- **Microsoft CDP:** the Windows Phone Link beacon, which includes a device-type field.

Address privacy: the top two bits of a random BLE address say whether it rotates (01, resolvable private) or stays fixed (11, static). Fixed addresses let a device be recognised over time, so they're flagged.

- **Rejected:** connecting to devices to read their GATT services. It would reveal more, but it's active, slow, and not what a passive audit tool should do.
- **Google this:** "BLE advertising data AD types", "Apple Continuity protocol furiousMAC", "BLE resolvable private address", "AirGuard tracker detection".

## Distance

Signal strength falls off predictably with distance, which is the log-distance path-loss model:

```
RSSI = RSSI_at_1m − 10 · n · log10(distance)
```

Solving for distance gives the estimate. `n` is the Surroundings setting on the Radar tab (2.0 open space, 2.7 indoors, 3.3 many walls). The signal is smoothed with an exponential moving average first, and because it still wobbles by several dB, the app shows a range rather than one number.

Wi-Fi RTT (802.11mc) is much better: it times radio round trips, which gives about a metre of accuracy. Both the phone and the access point must support it, so it's used where available and signal strength fills in elsewhere.

- **Rejected:** trilateration, which needs readings from several known positions, and fingerprinting, which needs a pre-built signal map.
- **Google this:** "log-distance path loss model", "Android Wi-Fi RTT".

## Direction

One phone antenna can't tell which way a signal comes from. But your body absorbs 2.4 GHz signals, so with the phone held in front of you, a device reads strongest when you face it. Radio direction-finders call this body shadowing.

During a sweep, `BearingSweep.kt` records (heading, signal) pairs for every device while you turn. It averages them into twelve 30° sectors, smooths around the circle, takes the strongest sector and refines it with a parabola through its neighbours. The difference between the strongest and weakest sectors becomes the confidence, and low-confidence results stay off the radar.

`HeadingSensor.kt` turns the rotation-vector sensor into a heading. It treats "top edge plus out of the back" as forward, so it works with the phone flat or upright. Each radio sample is matched to the heading at the moment it was received, using the clock both share (`elapsedRealtimeNanos`).

- **Rejected:** Bluetooth angle-of-arrival, which needs antenna arrays and isn't exposed by Android.
- **Key takeaway:** when you can't measure something directly, find a physical effect that correlates with it, and be honest about confidence. Devices without a measured direction are drawn hollow instead of being placed somewhere made up.
- **Google this:** "radio direction finding body fade", "Android rotation vector sensor".

## Cellular

`TelephonyManager.requestCellInfoUpdate()` returns every cell the modem hears. The phone doesn't know where towers are, but the network tells it a *timing advance* to compensate for distance: one LTE step is about 78 m, one GSM step about 550 m. A 2G connection is flagged because GSM's encryption is weak and the phone can't verify the tower, which is what fake base stations (IMSI catchers) exploit.

- **Google this:** "LTE timing advance distance", "IMSI catcher 2G downgrade".

## Android rules the app works around

- **Wi-Fi scan throttling:** apps may request 4 scans per 2 minutes. The app requests one every 30 s and also re-reads results produced by the system's own scans.
- **Screen-off scanning:** unfiltered Bluetooth scans pause when the screen turns off, so the app keeps the screen on while it's open. It also restarts the scan every 25 minutes, before Android quietly downgrades long-running scans.
- **Location:** both the permission and the location toggle are needed for any scan results.
- **Bluetooth broadcasts:** the discovery receiver is registered as exported, because the Bluetooth stack runs under its own user ID and a not-exported receiver can miss its broadcasts. It's a protected broadcast, so other apps can't fake it.

## Design choices in the code

- **No dependency-injection framework, database or navigation library.** Scanners are plain classes owned by the ViewModel, navigation is a tab enum plus the ID of the open device, and the session lives in memory. Hilt, Room and Navigation Compose are the natural upgrades if the app grows.
- **Deferred state reads on the radar.** The compass updates about 50 times a second. The radar reads the heading inside its draw phase, so turning the phone redraws the canvas without recomposing the screen. **Google this:** "Compose deferred state reads".
- **Lookup tables as assets.** The IEEE vendor registry (40,000+ entries) and the Bluetooth SIG lists ship as compact tab-separated files, rebuilt with `tools/generate_lookups.py`.

## Questions to be ready for

1. **How do you estimate distance from signal strength, and why is it unreliable?** Path-loss model; reflections, bodies, walls and unknown transmit power.
2. **How does the app find a direction with one antenna?** Body shadowing during a full turn, peak finding, contrast as confidence.
3. **What makes a Wi-Fi network insecure, and how can you tell passively?** RSN, WPA and WPS elements, PMF bits, TKIP, open networks, evil twins.
4. **How do trackers like AirTags work, and how would you spot one following someone?** Find My advertisements, the away-from-owner payload, time spent in range.
5. **Why treat scan data as untrusted?** Anyone can broadcast anything, so every parser checks lengths before reading.
