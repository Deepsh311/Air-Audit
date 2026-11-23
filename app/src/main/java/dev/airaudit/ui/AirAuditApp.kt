@file:OptIn(ExperimentalMaterial3Api::class)

package dev.airaudit.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.airaudit.core.Permissions
import dev.airaudit.core.Severity
import dev.airaudit.export.ReportExporter
import dev.airaudit.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String, val icon: ImageVector) {
    RADAR("Radar", Icons.Rounded.Radar),
    WIFI("Wi-Fi", Icons.Rounded.Wifi),
    BLUETOOTH("Bluetooth", Icons.Rounded.Bluetooth),
    CELL("Cellular", Icons.Rounded.CellTower),
    FINDINGS("Findings", Icons.Rounded.Shield),
}

@Composable
fun AirAuditApp(vm: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(Permissions.hasEssential(context)) }
    if (!ready) {
        PermissionScreen(onGranted = { ready = true })
        return
    }

    // Scan only while the app is on screen: start on ON_START, stop on ON_STOP.
    LifecycleStartEffect(Unit) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // Android pauses unfiltered Bluetooth scans when the screen goes off
        onDispose { view.keepScreenOn = false }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val headingState = vm.heading.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.RADAR) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = openId != null) { openId = null }
    val detailId = openId
    if (detailId != null) {
        DetailScreen(detailId, state, onBack = { openId = null })
        return
    }
    val serious = remember(state) {
        collectFindings(state).count { it.finding.severity == Severity.CRITICAL || it.finding.severity == Severity.HIGH }
    }

    Scaffold(
        containerColor = Palette.Ink,
        topBar = {
            TopAppBar(
                title = { Text("AirAudit", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { ReportExporter.write(context, state) }
                                ReportExporter.share(context, uri)
                            }
                        },
                    ) { Icon(Icons.Rounded.IosShare, contentDescription = "Export scan as JSON") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Ink),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Palette.Deck) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.FINDINGS && serious > 0) {
                                BadgedBox(badge = { Badge(containerColor = Palette.Critical) { Text("$serious") } }) {
                                    Icon(t.icon, contentDescription = null)
                                }
                            } else {
                                Icon(t.icon, contentDescription = null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanners(state.status)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.RADAR -> RadarScreen(
                        state = state,
                        heading = { headingState.value },
                        onOpen = { openId = it },
                        onStartSweep = vm::startSweep,
                        onFinishSweep = vm::finishSweep,
                        onEnvironment = vm::setEnvironment,
                    )
                    Tab.WIFI -> WifiScreen(state.wifi, state.updatedAtMs) { openId = it }
                    Tab.BLUETOOTH -> BluetoothScreen(state.bluetooth, state.updatedAtMs) { openId = it }
                    Tab.CELL -> CellScreen(state.cells) { openId = it }
                    Tab.FINDINGS -> FindingsScreen(state) { openId = it }
                }
            }
        }
    }
}

@Composable
private fun StatusBanners(status: ScanStatus) {
    val context = LocalContext.current
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    if (!status.locationOn) {
        StatusBanner(Icons.Rounded.LocationOff, "Location is off, so Android hides Wi-Fi and Bluetooth results.", "Turn on") {
            open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }
    if (status.bluetoothAvailable && !status.bluetoothOn) {
        StatusBanner(Icons.Rounded.BluetoothDisabled, "Bluetooth is off.", "Turn on") { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    } else if (!status.bluetoothPermitted) {
        StatusBanner(Icons.Rounded.BluetoothDisabled, "Nearby devices permission is off, so Bluetooth isn't scanned.", "Settings") { open(appSettings) }
    } else {
        status.bleError?.let { StatusBanner(Icons.Rounded.Warning, it, null) {} }
    }
    if (!status.wifiScanUsable) {
        StatusBanner(Icons.Rounded.WifiOff, "Wi-Fi scanning is off.", "Turn on") { open(Intent(Settings.Panel.ACTION_WIFI)) }
    }
}
