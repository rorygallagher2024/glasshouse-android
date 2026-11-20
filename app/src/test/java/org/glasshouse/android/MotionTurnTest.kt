package org.glasshouse.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTurnTest {

    private fun read(turn: MotionTurn): Pair<Double, Double> {
        val parts = turn.take().split(",")
        return parts[0].toDouble() to parts[1].toDouble()
    }

    @Test
    fun flatPhoneTurningLeftIsYaw() {
        val turn = MotionTurn()
        turn.gravity(0.0, 0.0, 9.81)
        turn.rotate(0.0, 0.0, 1.0, 0.5)
        val (yaw, pitch) = read(turn)
        assertEquals(0.5, yaw, 1e-9)
        assertEquals(0.0, pitch, 1e-9)
    }

    @Test
    fun uprightPhoneTurningLeftIsYaw() {
        val turn = MotionTurn()
        turn.gravity(0.0, 9.81, 0.0)
        turn.rotate(0.0, 1.0, 0.0, 0.25)
        assertEquals(0.25, read(turn).first, 1e-9)
    }

    @Test
    fun tiltingTheTopUpIsPitch() {
        val turn = MotionTurn()
        turn.gravity(0.0, 6.9, 6.9)
        turn.rotate(2.0, 0.0, 0.0, 0.1)
        val (yaw, pitch) = read(turn)
        assertEquals(0.0, yaw, 1e-9)
        assertEquals(0.2, pitch, 1e-9)
    }

    @Test
    fun readingStartsAfresh() {
        val turn = MotionTurn()
        turn.rotate(1.0, 0.0, 0.0, 0.1)
        read(turn)
        assertEquals(0.0 to 0.0, read(turn))
    }
}
