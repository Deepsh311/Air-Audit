package dev.airaudit.core

import kotlin.math.pow

object WifiMath {
    fun channel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5150..5895 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq in 5955..7115 -> (freq - 5950) / 5
        freq in 58320..70200 -> (freq - 56160) / 2160
        else -> -1
    }

    fun band(freq: Int): String = when {
        freq < 3000 -> "2.4 GHz"
        freq < 5925 -> "5 GHz"
        freq < 7200 -> "6 GHz"
        freq > 50000 -> "60 GHz"
        else -> "$freq MHz"
    }
}

/** How quickly signals fade with distance: the path-loss exponent "n". */
enum class Environment(val label: String, val exponent: Double) {
    OPEN("Open space", 2.0),
    INDOOR("Indoors", 2.7),
    DENSE("Many walls", 3.3),
}

object DistanceModel {
    /** Typical RSSI a phone reads 1 m from an access point. Real routers vary by ±6 dB. */
    fun wifiReferenceAt1m(freqMhz: Int): Double = when {
        freqMhz < 3000 -> -35.0
        freqMhz < 5925 -> -40.0
        else -> -42.0
    }

    const val BLE_REFERENCE_AT_1M = -59.0
    const val CLASSIC_REFERENCE_AT_1M = -55.0

    /** Free-space loss over the first metre at 2.4 GHz; turns "power at 0 m" into "RSSI at 1 m". */
    const val ONE_METRE_LOSS_DB = 41.0

    /**
     * Log-distance path loss: rssi = ref1m − 10·n·log10(d), solved for d.
     * The low/high range shows how far a normal ±fadeDb wobble in the signal moves the answer.
     */
    fun fromRssi(
        rssi: Double,
        ref1m: Double,
        n: Double,
        source: DistanceSource,
        fadeDb: Double = 6.0,
    ): DistanceEstimate {
        val d = 10.0.pow((ref1m - rssi) / (10 * n)).coerceIn(0.1, 500.0)
        val spread = 10.0.pow(fadeDb / (10 * n))
        return DistanceEstimate(d, d / spread, d * spread, source)
    }
}

/** Exponential moving average: each sample moves the estimate `alpha` of the way towards it. */
class Ema(private val alpha: Double) {
    var value = Double.NaN
        private set

    fun update(x: Double): Double {
        value = if (value.isNaN()) x else value + alpha * (x - value)
        return value
    }
}

object Mac {
    fun ouiKey(mac: String): Int? {
        val parts = mac.split(':', '-')
        if (parts.size < 3) return null
        return try {
            (parts[0].toInt(16) shl 16) or (parts[1].toInt(16) shl 8) or parts[2].toInt(16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun firstOctet(mac: String): Int = mac.take(2).toIntOrNull(16) ?: 0

    /** Bit 1 of the first octet marks an address the IEEE didn't assign (randomised or virtual). */
    fun isLocallyAdministered(mac: String): Boolean = (firstOctet(mac) and 0x02) != 0
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()
fun ByteArray.hex(separator: String = " "): String = joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

/** Bounds-safe sub-array: never throws, just returns fewer bytes near the end. */
fun ByteArray.part(from: Int, length: Int): ByteArray =
    copyOfRange(from.coerceIn(0, size), (from + length).coerceIn(0, size))

fun ByteArray.text(): String = String(this, Charsets.UTF_8).trim { it <= ' ' }

/** 16 bytes formatted as a standard UUID string. */
fun ByteArray.uuidString(): String {
    val h = hex("")
    if (h.length != 32) return h
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

fun formatMeters(m: Double): String = if (m < 10) "%.1f m".format(m) else "%.0f m".format(m)

fun formatAgo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

fun cardinal(degrees: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalised = ((degrees % 360) + 360) % 360
    return names[((normalised / 45f) + 0.5f).toInt() % 8]
}
