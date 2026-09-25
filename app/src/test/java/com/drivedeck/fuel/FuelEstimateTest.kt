package com.drivedeck.fuel

import com.drivedeck.data.DeckSettings
import com.drivedeck.data.Drive
import com.drivedeck.data.FillUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEstimateTest {
    private val day = 24 * 60 * 60_000L
    private val settings = DeckSettings(litresPer100Km = 8.0, tankLitres = 50.0)

    private fun drive(at: Long, km: Double) = Drive("d$at", at, at + 1_800_000, km * 1000, 1_500_000, 25.0)

    @Test fun `no fill-ups means no estimate`() {
        assertNull(FuelEstimate.estimate(emptyList(), listOf(drive(day, 30.0)), settings))
    }

    @Test fun `counts only drives since the last full tank`() {
        val fills = listOf(FillUp(id = "a", at = 10 * day, litres = 45.0, centsPerLitre = 180.0))
        val drives = listOf(drive(5 * day, 200.0), drive(11 * day, 100.0), drive(12 * day, 150.0))
        val level = FuelEstimate.estimate(fills, drives, settings)!!
        assertEquals(250.0, level.kmSinceFill, 0.01)
        assertEquals(30.0, level.litresLeft, 0.01) // 50 - 250 km × 8 L/100km
        assertEquals(375.0, level.kmLeft, 0.01)
        assertFalse(FuelEstimate.isLow(level))
    }

    @Test fun `low after a long stretch, and top-ups count`() {
        val fills = listOf(FillUp(id = "a", at = day, litres = 45.0, centsPerLitre = 180.0))
        val drives = (2..11).map { drive(it * day, 55.0) } // 550 km → 44 L used
        val level = FuelEstimate.estimate(fills, drives, settings)!!
        assertTrue(FuelEstimate.isLow(level))
        assertEquals(75.0, level.kmLeft, 0.01)

        val topped = fills + FillUp(id = "b", at = 12 * day, litres = 20.0, centsPerLitre = 175.0, fullTank = false)
        assertFalse(FuelEstimate.isLow(FuelEstimate.estimate(topped, drives, settings)!!))
    }

    @Test fun `prefers a close servo and speaks plainly`() {
        val far = FuelStation("Far", "Costco", "", "Perth Airport", 169.9, 0.0, 0.0, distanceKm = 22.0)
        val near = FuelStation("Near", "Vibe", "", "Morley", 179.9, 0.0, 0.0, distanceKm = 2.1)
        val picked = FuelEstimate.pick(FuelPrices(listOf(far, near), "Morley", false, 0))
        assertEquals("Near", picked!!.name)
        val level = FuelEstimate.Level(500.0, 7.0, 87.5, 0.14)
        assertEquals("Fuel's getting low, about 80 kilometres left. Cheapest nearby is Vibe Morley at 179.9.", FuelEstimate.spoken(level, picked))
    }
}
