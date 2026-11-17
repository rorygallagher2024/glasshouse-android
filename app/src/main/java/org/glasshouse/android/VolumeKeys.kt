package org.glasshouse.android

/**
 * Paces the phone's volume keys while they step the TV's volume: each press
 * is one step, and a key held down repeats, but no faster than [repeatMs]
 * and never while a step is still on its way, so a held key cannot queue up
 * more steps than the TV has taken. Plain JVM code, so it is unit-tested
 * without a device.
 */
class VolumeKeys(private val repeatMs: Long = REPEAT_MS) {

    private var lastStepAt = Long.MIN_VALUE

    /**
     * Whether this key-down event should send a step. [repeatCount] is the
     * KeyEvent's: 0 for a fresh press. [inFlight] is how many steps are sent
     * but not yet answered.
     */
    fun shouldStep(repeatCount: Int, now: Long, inFlight: Int): Boolean {
        val step = when {
            repeatCount == 0 -> true
            inFlight > 0 -> false
            else -> now - lastStepAt >= repeatMs
        }
        if (step) lastStepAt = now
        return step
    }

    companion object {
        /** About six steps a second while held. */
        const val REPEAT_MS = 160L
    }
}
