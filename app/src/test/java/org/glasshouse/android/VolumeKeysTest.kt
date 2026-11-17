package org.glasshouse.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {

    @Test
    fun everyPressIsAStep() {
        val keys = VolumeKeys(repeatMs = 160)
        assertTrue(keys.shouldStep(repeatCount = 0, now = 1_000, inFlight = 0))
        assertTrue(keys.shouldStep(repeatCount = 0, now = 1_010, inFlight = 1))
    }

    @Test
    fun heldKeyRepeatsAtTheSetRate() {
        val keys = VolumeKeys(repeatMs = 160)
        assertTrue(keys.shouldStep(0, 1_000, 0))
        assertFalse(keys.shouldStep(1, 1_050, 0))
        assertFalse(keys.shouldStep(2, 1_100, 0))
        assertTrue(keys.shouldStep(3, 1_160, 0))
        assertFalse(keys.shouldStep(4, 1_200, 0))
        assertTrue(keys.shouldStep(5, 1_320, 0))
    }

    @Test
    fun heldKeyWaitsForTheStepOnItsWay() {
        val keys = VolumeKeys(repeatMs = 160)
        assertTrue(keys.shouldStep(0, 1_000, 0))
        assertFalse(keys.shouldStep(1, 1_500, 1))
        assertTrue(keys.shouldStep(2, 1_550, 0))
    }
}
