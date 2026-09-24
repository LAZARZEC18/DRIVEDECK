package com.drivedeck.eta

import com.drivedeck.data.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EtaTest {
    private val zone = ZoneId.of("Australia/Perth")
    private val now = ZonedDateTime.of(2026, 9, 24, 8, 0, 0, 0, zone)
    private fun drive(daysAgo: Long, hour: Int, mins: Int, dest: String = "Greenhse"): Drive {
        val s = now.minusDays(daysAgo).withHour(hour).toInstant().toEpochMilli()
        return Drive("$daysAgo-$hour", s, s + mins * 60_000L, 18_000.0, mins * 50_000L, 25.0, dest, s + mins * 60_000L)
    }

    @Test fun `uses your past drives at the same time of day`() {
        val drives = listOf(drive(1, 8, 30), drive(2, 8, 34), drive(3, 8, 32), drive(4, 22, 18), drive(5, 22, 17))
        val (min, basis) = EtaEngine.personal("Greenhse", null, drives, now)!!
        assertEquals(32.0, min, 1e-9)
        assertTrue(basis, basis.contains("at this time"))
    }

    @Test fun `falls back to your average speed`() {
        val drives = listOf(drive(1, 8, 20, "Elsewhere"), drive(2, 9, 20, "Elsewhere")) // 36 km in 40 min = 54 km/h
        val (min, basis) = EtaEngine.personal("New place", Route(27_000.0, 1_200.0), drives, now)!!
        assertEquals(30.0, min, 0.1)
        assertTrue(basis.contains("54 km/h"))
    }

    @Test fun `no history uses road time plus stops`() {
        val (min, _) = EtaEngine.personal("X", Route(10_000.0, 600.0), emptyList(), now)!!
        assertEquals(11.5, min, 1e-9)
        assertNull(EtaEngine.personal("X", null, emptyList(), now))
    }

    @Test fun `reads live eta from nav notifications`() {
        val waze = LiveNavEta.parse("Waze", "Greenhse · 23 min · 18 km · Arrive 8:42 am", 0)
        assertNotNull(waze)
        assertEquals("8:42 am", waze!!.arrival)
        assertEquals(23, waze.minutes)
        val maps = LiveNavEta.parse("Google Maps", "1 hr 5 min · 92 km · ETA 9:47", 0)!!
        assertEquals(65, maps.minutes)
        assertEquals("9:47", maps.arrival)
        assertNull(LiveNavEta.parse("Waze", "Drive safely", 0))
    }

    @Test fun `clock formatting`() {
        assertEquals("8:30 am", EtaEngine.clock(30.0, now))
        assertEquals("12:15 pm", EtaEngine.clock(255.0, now))
    }
}
