package com.drivedeck.stats

import com.drivedeck.data.DeckSettings
import com.drivedeck.data.DeckState
import com.drivedeck.data.Drive
import com.drivedeck.data.FillUp
import com.drivedeck.data.Place
import com.drivedeck.data.SongPlay
import com.drivedeck.data.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsEngineTest {
    private val zone = ZoneId.of("Australia/Perth")
    private val wed = ZonedDateTime.of(2026, 9, 23, 18, 0, 0, 0, zone) // Wednesday
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val state = DeckState(
        places = listOf(Place(id = "w", name = "Work", address = "")),
        drives = listOf(
            Drive("1", at(21, 8), at(21, 8) + 30 * 60_000, 20_000.0, 25 * 60_000, 30.0, "Work"),
            Drive("2", at(22, 17), at(22, 17) + 20 * 60_000, 10_000.0, 15 * 60_000, 25.0),
            Drive("old", at(14, 8), at(14, 8) + 60 * 60_000, 50_000.0, 50 * 60_000, 40.0),
        ),
        trips = listOf(TripEvent("w", at(21, 8)), TripEvent("w", at(22, 8))),
        plays = listOf(SongPlay("Rumble", "Skrillex", at(21, 8)), SongPlay("Rumble", "Skrillex", at(22, 9)), SongPlay("Ten", "Fred again..", at(22, 10))),
        fillUps = listOf(FillUp(id = "f", at = at(22, 12), litres = 40.0, centsPerLitre = 200.0)),
        settings = DeckSettings(litresPer100Km = 8.0),
    )

    @Test fun `weekly totals`() {
        val w = StatsEngine.week(state, wed)
        assertEquals(2, w.drives)
        assertEquals(30.0, w.distanceKm, 1e-9)
        assertEquals(36.0, w.avgSpeedKmh, 1e-6)           // 30 km in 50 min
        assertEquals(45.0, w.avgMovingSpeedKmh, 1e-6)     // 30 km in 40 min
        assertEquals(108.0, w.maxSpeedKmh, 1e-6)
        assertEquals(listOf(20.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0), w.kmPerDay)
        assertEquals(0, w.busiestDayIndex)
        assertEquals(listOf("Work" to 2), w.topDestinations)
        assertEquals(3, w.songsPlayed)
        assertEquals("Rumble · Skrillex" to 2, w.topSongs.first())
        assertEquals(80.0, w.fuelDollars, 1e-9)
        assertEquals(4.8, w.estFuelCostDollars, 1e-9)     // 30 km × 8 L/100 × $2.00
    }

    @Test fun `last week and change`() {
        val prev = StatsEngine.week(state, wed, 1)
        assertEquals(1, prev.drives)
        assertEquals(-40.0, StatsEngine.change(30.0, 50.0)!!, 1e-9)
        assertNull(StatsEngine.change(5.0, 0.0))
    }

    @Test fun `measured consumption full to full`() {
        val fills = listOf(
            FillUp(id = "a", at = 1, litres = 40.0, centsPerLitre = 200.0, odometerKm = 10_000.0),
            FillUp(id = "b", at = 2, litres = 20.0, centsPerLitre = 200.0, odometerKm = 10_300.0, fullTank = false),
            FillUp(id = "c", at = 3, litres = 22.0, centsPerLitre = 200.0, odometerKm = 10_600.0),
        )
        assertEquals(7.0, StatsEngine.measuredConsumption(fills)!!, 1e-9) // 42 L over 600 km
        assertEquals(200.0, StatsEngine.averagePricePaid(fills)!!, 1e-9)
    }

    @Test fun `formatting`() {
        assertEquals("1h 5m", Fmt.duration(65 * 60_000L))
        assertEquals("9 min", Fmt.duration(9 * 60_000L))
        assertEquals("▲ 25%", Fmt.pct(25.0))
        assertEquals("$1,234.50", Fmt.money(1234.5))
        assertEquals("6 pm", Fmt.hour(18))
    }
}
