package org.glasshouse.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The gyroscope and gravity sensors, listened to only between [start] and
 * [stop]: while the remote's Motion pad is held.
 */
class MotionSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    val turn = MotionTurn()
    private var running = false
    private var lastGyroscopeAt = 0L

    val available: Boolean get() = gyroscope != null

    @Synchronized
    fun start() {
        if (running || manager == null || gyroscope == null) return
        lastGyroscopeAt = 0L
        turn.clear()
        // 100 Hz. Faster than 200 Hz needs HIGH_SAMPLING_RATE_SENSORS, and
        // SENSOR_DELAY_FASTEST without it throws a SecurityException (Pixel 9a, Android 17).
        running = manager.registerListener(this, gyroscope, GYROSCOPE_PERIOD_US)
        if (running) gravity?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> turn.gravity(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_GYROSCOPE -> {
                val last = lastGyroscopeAt
                lastGyroscopeAt = event.timestamp
                if (last == 0L) return
                // A gap longer than 0.1 s is a stall, not a turn held that long.
                val seconds = ((event.timestamp - last) / 1e9).coerceIn(0.0, 0.1)
                turn.rotate(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), seconds)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private companion object {
        const val GYROSCOPE_PERIOD_US = 10_000
    }
}
