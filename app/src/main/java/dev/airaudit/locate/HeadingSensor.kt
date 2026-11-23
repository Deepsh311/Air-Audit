package dev.airaudit.locate

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which way the phone is pointing, in degrees from magnetic north, from the fused rotation-vector
 * sensor (accelerometer + gyroscope + magnetometer). Keeps a short history so each radio sample can be
 * matched to the heading at the exact moment it was received.
 */
class HeadingSensor(context: Context) : SensorEventListener {
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    val available: Boolean get() = sensor != null

    private val rotation = FloatArray(9)
    private var smoothed = Float.NaN
    private val history = ArrayDeque<Pair<Long, Float>>() // (elapsedRealtimeNanos, heading), oldest first

    fun start() {
        sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        _heading.value = null
        smoothed = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Some devices send 5 values, which older Android versions reject here.
        val values = if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(rotation, values)
        // "Forward" = the top edge (device +Y) plus out of the back (device −Z). Held flat, the top edge
        // points ahead; held upright, the camera does; in between both contribute. Matrix rows 0 and 1
        // give the east and north components of a device-axis vector in world coordinates.
        val east = rotation[1] - rotation[2]
        val north = rotation[4] - rotation[5]
        if (hypot(east, north) < 0.2f) return // pointing almost straight up or down: no usable heading
        val raw = ((Math.toDegrees(atan2(east, north).toDouble()) + 360.0) % 360.0).toFloat()
        smoothed = if (smoothed.isNaN()) raw else blendAngles(smoothed, raw, 0.15f)
        _heading.value = smoothed
        synchronized(history) {
            history.addLast(event.timestamp to smoothed)
            while (history.size > 1500) history.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Heading at a past moment. Sensor and radio timestamps share the elapsedRealtimeNanos clock. */
    fun headingAt(elapsedNanos: Long): Float? {
        synchronized(history) {
            if (history.isEmpty()) return null
            val found = history.binarySearchBy(elapsedNanos) { it.first }
            val i = if (found >= 0) found else -found - 1
            val nearest = listOfNotNull(history.getOrNull(i - 1), history.getOrNull(i))
                .minByOrNull { abs(it.first - elapsedNanos) } ?: return null
            return if (abs(nearest.first - elapsedNanos) < 400_000_000L) nearest.second else null
        }
    }

    /** Moves `from` towards `to` by `alpha`, the short way round (359° → 1° is +2°, not −358°). */
    private fun blendAngles(from: Float, to: Float, alpha: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + alpha * diff + 360f) % 360f
    }
}
