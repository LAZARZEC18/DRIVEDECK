package com.drivedeck.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAccumulatorTest {
    // ~0.001° latitude ≈ 111 m
    private fun fix(sec: Int, latOffset: Double, speed: Float? = null, acc: Float = 5f) =
        Fix(sec * 1000L, -31.9 + latOffset, 115.9, acc, speed)

    @Test fun `steady 60 kmh for a minute`() {
        val a = TripAccumulator(0)
        // 16.67 m/s → 0.00015° per second
        for (s in 0..60) a.onFix(fix(s, s * 0.00015, 16.67f))
        assertEquals(1000.0, a.distanceM, 30.0)
        assertEquals(60.0, a.maxSpeedMps * 3.6, 1.0)
        assertEquals(60_000L, a.movingMs)
        assertEquals(60.0, a.avgMovingSpeedMps() * 3.6, 2.0)
    }

    @Test fun `parked jitter adds no distance`() {
        val a = TripAccumulator(0)
        for (s in 0..120) a.onFix(fix(s, if (s % 2 == 0) 0.0 else 0.00003, 0.2f, acc = 8f))
        assertTrue("was ${a.distanceM}", a.distanceM < 1.0)
        assertEquals(0L, a.movingMs)
    }

    @Test fun `gps glitch jump is ignored`() {
        val a = TripAccumulator(0)
        a.onFix(fix(0, 0.0, 10f)); a.onFix(fix(1, 0.00009, 10f))
        a.onFix(fix(2, 0.05, null)) // 5 km in 1 s
        assertTrue(a.distanceM < 20)
        assertTrue(a.maxSpeedMps < 11)
    }

    @Test fun `inaccurate fixes are skipped`() {
        val a = TripAccumulator(0)
        a.onFix(fix(0, 0.0, 10f)); a.onFix(fix(1, 0.001, 30f, acc = 80f))
        assertEquals(0.0, a.distanceM, 0.0)
    }

    @Test fun `overall average includes stops`() {
        val a = TripAccumulator(0)
        for (s in 0..60) a.onFix(fix(s, s * 0.00015, 16.67f))       // drive 1 km
        for (s in 61..120) a.onFix(fix(s, 60 * 0.00015, 0f))         // wait a minute at lights
        assertEquals(30.0, a.avgSpeedMps(120_000) * 3.6, 2.0)
        assertEquals(60.0, a.avgMovingSpeedMps() * 3.6, 2.0)
    }
}
