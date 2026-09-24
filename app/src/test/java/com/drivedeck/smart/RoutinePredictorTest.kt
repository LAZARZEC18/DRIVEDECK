package com.drivedeck.smart

import com.drivedeck.data.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutinePredictorTest {

    private val perth = ZoneId.of("Australia/Perth")
    private val p = RoutinePredictor()
    private val places = setOf("work", "home", "gym", "uni")

    /** Monday 2026-09-21 as the anchor week. */
    private fun at(daysAgo: Long, hour: Int, minute: Int = 0, base: ZonedDateTime = now()): Long =
        base.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun now(dow: Int = 1, hour: Int = 8, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 20 + dow, hour, minute, 0, 0, perth) // dow 1 = Monday 21 Sep

    @Test fun `no history gives no suggestion`() {
        assertNull(p.suggest(emptyList(), now(), places))
    }

    @Test fun `weekday morning commute wins in the morning`() {
        val n = now(dow = 1, hour = 8)
        val trips = (1L..20L).flatMap { d ->
            val day = n.minusDays(d)
            if (RoutinePredictor.isWeekend(day.dayOfWeek)) emptyList()
            else listOf(TripEvent("work", at(d, 7, 50, n)), TripEvent("home", at(d, 17, 30, n)))
        }
        val s = p.suggest(trips, n, places)
        assertNotNull(s)
        assertEquals("work", s!!.placeId)
        assertTrue(s.reason, s.reason.startsWith("Weekdays") || s.reason.startsWith("Mondays"))
    }

    @Test fun `same history suggests home in the evening`() {
        val n = now(dow = 2, hour = 17, minute = 20)
        val trips = (1L..20L).flatMap { d ->
            listOf(TripEvent("work", at(d, 7, 50, n)), TripEvent("home", at(d, 17, 30, n)))
        }
        assertEquals("home", p.suggest(trips, n, places)!!.placeId)
    }

    @Test fun `weekly habit is recognised by weekday`() {
        // Gym every Tuesday 6pm for 6 weeks, home on other evenings.
        val n = now(dow = 2, hour = 18)
        val trips = (1L..42L).map { d ->
            val day = n.minusDays(d)
            if (day.dayOfWeek == n.dayOfWeek) TripEvent("gym", at(d, 18, 0, n)) else TripEvent("home", at(d, 18, 0, n))
        }
        val s = p.suggest(trips, n, places)
        assertEquals("gym", s!!.placeId)
        assertEquals("Tuesdays ~6 pm", s.reason)
    }

    @Test fun `excluded place (where you are now) is skipped`() {
        val n = now(hour = 8)
        val trips = (1L..10L).map { TripEvent("work", at(it, 8, 0, n)) } +
            (1L..10L).map { TripEvent("uni", at(it, 8, 30, n)) }
        assertEquals("uni", p.suggest(trips, n, places, exclude = setOf("work"))!!.placeId)
    }

    @Test fun `deleted places are ignored`() {
        val n = now(hour = 8)
        val trips = (1L..10L).map { TripEvent("old-place", at(it, 8, 0, n)) }
        assertNull(p.suggest(trips, n, places))
    }

    @Test fun `ambiguous history gives no suggestion`() {
        val n = now(hour = 12)
        val trips = (1L..8L).flatMap { d ->
            listOf("work", "home", "gym", "uni").map { TripEvent(it, at(d, 12, 0, n)) }
        }
        assertNull(p.suggest(trips, n, places))
    }

    @Test fun `old habits fade`() {
        val n = now(hour = 9)
        val old = (200L..230L).map { TripEvent("work", at(it, 9, 0, n)) }
        val recent = (1L..5L).map { TripEvent("uni", at(it, 9, 0, n)) }
        assertEquals("uni", p.suggest(old + recent, n, places)!!.placeId)
    }

    @Test fun `time gap wraps around midnight`() {
        assertEquals(1.0, RoutinePredictor.hourGap(23.5, 0.5), 1e-9)
        assertEquals(12.0, RoutinePredictor.hourGap(0.0, 12.0), 1e-9)
    }

    @Test fun `circular mean handles midnight`() {
        val m = RoutinePredictor.circularMeanHour(listOf(23.0, 1.0))
        assertTrue("was $m", m < 0.01 || m > 23.99)
        assertEquals(8.0, RoutinePredictor.circularMeanHour(listOf(7.5, 8.5)), 1e-6)
    }

    @Test fun `formats hours nicely`() {
        assertEquals("8 am", RoutinePredictor.formatHour(8.05))
        assertEquals("6:30 pm", RoutinePredictor.formatHour(18.5))
        assertEquals("12 pm", RoutinePredictor.formatHour(12.0))
        assertEquals("12 am", RoutinePredictor.formatHour(23.95))
    }

    @Test fun `future trips are ignored`() {
        val n = now()
        val future = TripEvent("work", n.plusDays(1).toInstant().toEpochMilli())
        assertEquals(0.0, p.weight(future, n, perth), 0.0)
    }
}
