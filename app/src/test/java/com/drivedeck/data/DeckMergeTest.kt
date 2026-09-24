package com.drivedeck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckMergeTest {
    private val now = 1_800_000_000_000L
    private fun place(id: String, name: String, at: Long, deleted: Boolean = false) =
        Place(id = id, name = name, address = "", order = 0, updatedAt = at, deleted = deleted)

    @Test fun `newer edit wins per place`() {
        val phone = DeckState(places = listOf(place("a", "Gym (phone)", 100)))
        val laptop = DeckState(places = listOf(place("a", "Gym (laptop)", 200)))
        assertEquals("Gym (laptop)", DeckMerge.merge(phone, laptop, now).places.single().name)
        assertEquals("Gym (laptop)", DeckMerge.merge(laptop, phone, now).places.single().name)
    }

    @Test fun `delete on one side removes it everywhere`() {
        val phone = DeckState(places = listOf(place("a", "Old", now - 100)))
        val laptop = DeckState(places = listOf(place("a", "Old", now - 50, deleted = true)))
        val merged = DeckMerge.merge(phone, laptop, now)
        assertTrue(merged.places.single().deleted)
        assertTrue(merged.visiblePlaces.isEmpty())
    }

    @Test fun `items added on different devices are both kept`() {
        val merged = DeckMerge.merge(DeckState(places = listOf(place("a", "A", 1))), DeckState(places = listOf(place("b", "B", 1))), now)
        assertEquals(setOf("A", "B"), merged.places.map { it.name }.toSet())
    }

    @Test fun `old tombstones are purged`() {
        val merged = DeckMerge.merge(DeckState(places = listOf(place("a", "A", now - 90L * 86_400_000, deleted = true))), DeckState(), now)
        assertTrue(merged.places.isEmpty())
    }

    @Test fun `trips union and reset`() {
        val a = DeckState(trips = listOf(TripEvent("x", 10), TripEvent("x", 20)))
        val b = DeckState(trips = listOf(TripEvent("x", 20), TripEvent("y", 30)))
        assertEquals(3, DeckMerge.merge(a, b, now).trips.size)
        val reset = DeckState(tripsResetAt = 25)
        assertEquals(listOf(30L), DeckMerge.merge(DeckMerge.merge(a, b, now), reset, now).trips.map { it.epochMillis })
    }

    @Test fun `recents keep newest 20 unique songs`() {
        val songs = (1..30).map { RecentSong("Song $it", null, "song $it", it.toLong()) } + RecentSong("Song 30", null, "SONG 30", 99)
        val merged = DeckMerge.mergeRecents(songs)
        assertEquals(20, merged.size)
        assertEquals(99L, merged.first().playedAt)
        assertEquals(1, merged.count { it.key == "song 30" })
    }

    @Test fun `newest next-up and settings win`() {
        val a = DeckState(nextUp = NextUp("gym", 5_000, 10), settings = DeckSettings(litresPer100Km = 7.0, updatedAt = 5))
        val b = DeckState(nextUp = NextUp(null, 0, 20), settings = DeckSettings(litresPer100Km = 8.0, updatedAt = 3))
        val m = DeckMerge.merge(a, b, now)
        assertNull(m.nextUp!!.placeId)
        assertEquals(7.0, m.settings.litresPer100Km, 0.0)
    }

    @Test fun `drives and plays are de-duplicated`() {
        val d = Drive("d1", 1, 2, 100.0, 1, 1.0)
        val m = DeckMerge.merge(DeckState(drives = listOf(d), plays = listOf(SongPlay("A", null, 5))), DeckState(drives = listOf(d), plays = listOf(SongPlay("A", null, 5))), now)
        assertEquals(1, m.drives.size); assertEquals(1, m.plays.size)
    }
}
