# AirAudit

A passive wireless security scanner for Android. AirAudit listens to the Wi-Fi, Bluetooth and cellular signals around you, grades the security of nearby networks, spots item trackers and privacy leaks, and places devices on a radar relative to your phone.

It only listens. It never connects to, logs in to, or transmits anything at the networks and devices it finds.

## What it does

**Wi-Fi**
- Parses the raw 802.11 information elements in each access point's beacons (RSN, WPA, WPS, BSS Load, vendor elements) instead of relying on Android's summary string.
- Grades every network A–F and explains each issue in plain language: open networks, WEP, legacy WPA and TKIP, WPS (including routers that broadcast their make, model and serial number), missing management-frame protection, WPA3 transition mode and hidden names.
- Flags possible evil twins: an open or legacy access point using the same name as a properly secured one.
- Shows connected-client counts, channel load, 802.11k/r/v roaming support, Wi-Fi generation, and the manufacturer from the IEEE registry.
- Measures distance with Wi-Fi RTT (802.11mc) on access points that support it.

**Bluetooth**
- Continuous Bluetooth LE scanning, including Bluetooth 5 extended advertising, plus periodic Classic discovery.
- Decodes Apple Continuity, iBeacon, Eddystone, Microsoft CDP, Google Fast Pair and Bluetooth SIG appearance values to work out what each device is.
- Detects item trackers (Apple Find My items including AirTags, Samsung SmartTag, Tile, Chipolo, Google Find My Device) and warns when one is away from its owner or has stayed near you for 10 minutes or more.
- Flags privacy leaks: fixed addresses that make a device trackable, and device names that identify the owner.

**Cellular**
- The serving cell and neighbouring cells (2G to 5G) with signal metrics, cell identity and distance to the serving tower from timing advance.
- Warns when the phone is connected over 2G.

**Locating**
- Distance from signal strength using the log-distance path-loss model, shown as a range, or from Wi-Fi RTT where available.
- Direction from a 360° turn on the spot, using the compass and the way your body blocks radio signals.
- A heading-up radar, plus a hot/cold Find mode with optional beeps for tracking down a Bluetooth LE device.

**Export**: the whole scan as JSON (every parsed field, all findings and the raw beacon bytes) through the share sheet.

## Build and install

You need Android Studio with JDK 17 or newer, and a phone running Android 10 or later.

1. Open this folder in Android Studio and let Gradle sync.
2. Turn on Developer options and USB debugging on the phone, connect it, and press Run.

Or install the prebuilt `AirAudit-debug.apk`: copy it to the phone and open it. Android will ask you to allow installs from whichever app you opened it with.

For faster Wi-Fi updates, turn off **Wi-Fi scan throttling** in Developer options. Otherwise Android allows apps four Wi-Fi scans every two minutes.

## Permissions

| Permission | Why it's needed |
|---|---|
| Precise location | Android only returns Wi-Fi, Bluetooth and cell scan results to apps that hold it. AirAudit never stores your location. |
| Nearby devices | Bluetooth LE scanning, Classic discovery, names of paired devices, and Wi-Fi RTT ranging. |
| Wi-Fi state | Reading and requesting Wi-Fi scans. |

## Limitations

- Distances from signal strength are rough. Walls, bodies and antenna orientation can easily halve or double them, which is why the app shows a range.
- Directions need a compass and a slow, full turn. Wi-Fi directions also need scan throttling turned off.
- Many phones rotate their Bluetooth address about every 15 minutes, so one physical device can show up as several entries over time.
- Before Android 15 the phone doesn't say whether a Bluetooth address is public or random, so AirAudit infers it.
- Android 10 doesn't expose raw beacon data, so Wi-Fi grading falls back to Android's capability flags there.

## Project structure

```
app/src/main/java/dev/airaudit/
  core/        models, distance maths, permissions, vendor lookup tables
  wifi/        scanner, 802.11 information-element parser, security grader, RTT ranging
  bluetooth/   Bluetooth LE + Classic scanner, advertisement decoder
  cell/        cellular scanner
  locate/      compass heading, direction-finding sweep
  export/      JSON report
  ui/          ViewModel, screens and theme (Jetpack Compose)
app/src/main/assets/   vendor and Bluetooth SIG lookup tables
tools/generate_lookups.py   rebuilds those tables from the official registries
```

The design decisions behind each part are in [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## Data sources and licences

- Manufacturer names: IEEE MA-L (OUI) registry. Bluetooth company IDs, UUIDs, appearance values and advertising types: Bluetooth SIG Assigned Numbers.
- Fonts: Barlow Semi Condensed and IBM Plex Mono, both under the SIL Open Font License.

## Responsible use

AirAudit only reads what devices broadcast to everyone nearby. Exports contain identifiers of other people's devices, so handle them with care, and only assess networks you own or have permission to test.
