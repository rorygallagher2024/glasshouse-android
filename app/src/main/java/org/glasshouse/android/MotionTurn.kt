package org.glasshouse.android

import kotlin.math.sqrt

/**
 * The phone's turning, gathered between reads for the remote's Motion pad.
 * Yaw is turning about the vertical, as the gravity vector has it, so it reads
 * the same whether the phone is held flat, tilted or upright. Pitch is turning
 * about the phone's own left-right axis. Both in radians, positive to the left
 * and up. Plain JVM code, so it is unit-tested without a device.
 */
class MotionTurn {

    // Until the gravity sensor answers: tilted 45 degrees, top edge towards the TV.
    private var upX = 0.0
    private var upY = 0.7071
    private var upZ = 0.7071
    private var yaw = 0.0
    private var pitch = 0.0

    /** A gravity reading, in the phone's axes (any unit; only its direction counts). */
    @Synchronized
    fun gravity(x: Double, y: Double, z: Double) {
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1e-6) return
        upX = x / length
        upY = y / length
        upZ = z / length
    }

    /** A gyroscope reading: rad/s about the phone's x, y and z axes, held for [seconds]. */
    @Synchronized
    fun rotate(x: Double, y: Double, z: Double, seconds: Double) {
        yaw += (x * upX + y * upY + z * upZ) * seconds
        pitch += x * seconds
    }

    /** "yaw,pitch" since the last read, in a form the page parses in any locale. */
    @Synchronized
    fun take(): String {
        val out = "$yaw,$pitch"
        yaw = 0.0
        pitch = 0.0
        return out
    }

    @Synchronized
    fun clear() {
        yaw = 0.0
        pitch = 0.0
    }
}
