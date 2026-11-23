package dev.airaudit.ui

import dev.airaudit.core.BtDevice
import dev.airaudit.core.CellTower
import dev.airaudit.core.Environment
import dev.airaudit.core.WifiAp
import dev.airaudit.locate.BearingSweep

/** Everything the screens draw, rebuilt as one immutable snapshot about every 0.7 s. */
data class UiState(
    val wifi: List<WifiAp> = emptyList(),
    val bluetooth: List<BtDevice> = emptyList(),
    val cells: List<CellTower> = emptyList(),
    val environment: Environment = Environment.INDOOR,
    val sweep: SweepUi = SweepUi(),
    val status: ScanStatus = ScanStatus(),
    val updatedAtMs: Long = 0L,
)

data class SweepUi(
    val active: Boolean = false,
    val coverage: Float = 0f,
    val covered: List<Boolean> = List(BearingSweep.COVERAGE_BINS) { false },
    val lastLocated: Int? = null,
)

data class ScanStatus(
    val locationOn: Boolean = true,
    val bluetoothAvailable: Boolean = true,
    val bluetoothOn: Boolean = true,
    val bluetoothPermitted: Boolean = true,
    val wifiScanUsable: Boolean = true,
    val wifiThrottled: Boolean = false,
    val bleError: String? = null,
    val rttSupported: Boolean = false,
    val compassAvailable: Boolean = true,
)
