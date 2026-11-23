package dev.airaudit.locate

import dev.airaudit.core.Bearing
import dev.airaudit.core.Confidence

/**
 * Direction finding by "body shadowing". Hold the phone in front of you and turn on the spot: your body
 * absorbs 2.4 GHz signals, so each device reads strongest when you're facing it. During one full turn we
 * record (heading, RSSI) samples per device, then look for the peak.
 */
class BearingSweep {
    @Volatile var isActive = false
        private set
    private val covered = BooleanArray(COVERAGE_BINS)
    private val samples = HashMap<String, MutableList<Pair<Float, Int>>>()

    @Synchronized
    fun start() {
        covered.fill(false)
        samples.clear()
        isActive = true
    }

    @Synchronized
    fun markHeading(heading: Float) {
        if (isActive) covered[(heading / (360f / COVERAGE_BINS)).toInt().coerceIn(0, COVERAGE_BINS - 1)] = true
    }

    @Synchronized
    fun addSample(id: String, heading: Float, rssi: Int) {
        if (isActive) samples.getOrPut(id) { mutableListOf() }.add(heading to rssi)
    }

    @Synchronized
    fun coverage(): Float = covered.count { it } / COVERAGE_BINS.toFloat()

    @Synchronized
    fun coveredBins(): List<Boolean> = covered.toList()

    @Synchronized
    fun finish(nowMs: Long): Map<String, Bearing> {
        isActive = false
        return samples.mapNotNull { (id, list) -> estimate(list, nowMs)?.let { id to it } }.toMap()
    }

    companion object {
        const val COVERAGE_BINS = 36
        private const val SECTORS = 12 // 30° each

        /**
         * Average the RSSI in each 30° sector, smooth around the circle, take the strongest sector and
         * refine it with a parabola through its neighbours. Contrast (peak minus weakest) sets confidence.
         */
        fun estimate(samples: List<Pair<Float, Int>>, nowMs: Long): Bearing? {
            if (samples.size < 8) return null
            val sum = DoubleArray(SECTORS)
            val count = IntArray(SECTORS)
            for ((heading, rssi) in samples) {
                val sector = (heading / 30f).toInt().coerceIn(0, SECTORS - 1)
                sum[sector] += rssi.toDouble()
                count[sector]++
            }
            if (count.count { it > 0 } < 8) return null // needs at least 240° of the circle
            val overall = sum.sum() / count.sum()
            val mean = DoubleArray(SECTORS) { if (count[it] > 0) sum[it] / count[it] else Double.NaN }
            // Fill empty sectors from their neighbours so the circle has no holes.
            val filled = DoubleArray(SECTORS) { i ->
                if (!mean[i].isNaN()) {
                    mean[i]
                } else {
                    val near = listOf(mean[(i + SECTORS - 1) % SECTORS], mean[(i + 1) % SECTORS]).filter { !it.isNaN() }
                    if (near.isEmpty()) overall else near.average()
                }
            }
            val smooth = DoubleArray(SECTORS) { i ->
                0.25 * filled[(i + SECTORS - 1) % SECTORS] + 0.5 * filled[i] + 0.25 * filled[(i + 1) % SECTORS]
            }
            val peak = smooth.indices.maxBy { smooth[it] }
            val left = smooth[(peak + SECTORS - 1) % SECTORS]
            val centre = smooth[peak]
            val right = smooth[(peak + 1) % SECTORS]
            // Vertex of the parabola through (−1, left), (0, centre), (+1, right).
            val curvature = left - 2 * centre + right
            val offset = if (curvature < 0) (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5) else 0.0
            val degrees = (((peak + 0.5 + offset) * 30.0) % 360.0 + 360.0) % 360.0
            val contrast = centre - smooth.min()
            val confidence = when {
                contrast >= 8 -> Confidence.HIGH
                contrast >= 4 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            return Bearing(degrees, confidence, contrast, nowMs)
        }
    }
}
