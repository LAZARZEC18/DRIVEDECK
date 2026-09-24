package com.drivedeck.testing

import android.content.Context
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.DeckState
import com.drivedeck.data.Drive
import com.drivedeck.data.FillUp
import com.drivedeck.data.MusicFavorite
import com.drivedeck.data.MusicKind
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import com.drivedeck.data.RecentSong
import com.drivedeck.data.SongPlay
import com.drivedeck.data.TripEvent
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Realistic demo content for screen tests and README screenshots (public places only). */
object DemoData {
    val work = Place(id = "work", name = "Greenhse", address = "Ellenbrook WA", lat = -31.7760, lng = 115.9690, icon = PlaceIcon.WORK, order = 1)
    val uni = Place(id = "uni", name = "Curtin Uni", address = "Kent St, Bentley WA 6102", lat = -32.0057, lng = 115.8940, icon = PlaceIcon.SCHOOL, order = 2)
    val gym = Place(id = "gym", name = "Gym", address = "Perth WA", lat = -31.9505, lng = 115.8605, icon = PlaceIcon.GYM, order = 3)
    val ball = Place(id = "ball", name = "Basketball", address = "Perth Arena, Perth WA", lat = -31.9483, lng = 115.8520, icon = PlaceIcon.SPORT, order = 4)
    val home = Place(id = "home", name = "Home", address = "Perth WA", lat = -31.9200, lng = 115.8700, icon = PlaceIcon.HOME, order = 0)

    fun seed(context: Context): DeckRepository {
        DeckRepository.resetForTests()
        context.getSharedPreferences("drivedeck", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = DeckRepository.get(context)
        repo.applySynced(state(ZonedDateTime.now()))
        return repo
    }

    /** A believable few weeks: commute to work around now, uni some days, gym, music, fuel. */
    fun state(now: ZonedDateTime): DeckState {
        val trips = mutableListOf<TripEvent>()
        val drives = mutableListOf<Drive>()
        val plays = mutableListOf<SongPlay>()
        val songs = listOf("Rumble" to "Skrillex", "Delilah (pull me out of this)" to "Fred again..", "Lose My Mind" to "Don Diablo",
            "Gecko (Overdrive)" to "Oliver Heldens", "Ten" to "Fred again..", "Pjanoo" to "Eric Prydz")
        for (d in 1L..21L) {
            val day = now.minusDays(d)
            val start = day.minusMinutes(10).toInstant().toEpochMilli()
            trips += TripEvent("work", start)
            val km = 17.5 + (d % 4)
            val mins = 22 + (d % 5) * 2
            drives += Drive("w$d", start, start + TimeUnit.MINUTES.toMillis(mins), km * 1000, TimeUnit.MINUTES.toMillis(mins - 4),
                (95 + d % 12) / 3.6, destination = "Greenhse", arrivedAt = start + TimeUnit.MINUTES.toMillis(mins))
            if (d % 3 == 0L) {
                val u = day.plusHours(3).toInstant().toEpochMilli()
                trips += TripEvent("uni", u)
                drives += Drive("u$d", u, u + TimeUnit.MINUTES.toMillis(31), 24_300.0, TimeUnit.MINUTES.toMillis(26), 102 / 3.6, destination = "Curtin Uni")
            }
            if (d % 2 == 0L) {
                val g = day.plusHours(8).toInstant().toEpochMilli()
                trips += TripEvent("gym", g)
                drives += Drive("g$d", g, g + TimeUnit.MINUTES.toMillis(12), 6_100.0, TimeUnit.MINUTES.toMillis(10), 72 / 3.6, destination = "Gym")
            }
            songs.forEachIndexed { i, (t, a) -> if ((d + i) % 2 == 0L) plays += SongPlay(t, a, start + i * 200_000L) }
        }
        val fills = listOf(4L, 11L, 18L).mapIndexed { i, d ->
            FillUp(id = "f$i", at = now.minusDays(d).toInstant().toEpochMilli(), litres = 41.0 + i, centsPerLitre = 229.9 + i * 3,
                odometerKm = 48_200.0 + (2 - i) * 560, station = "Vibe Bayswater", updatedAt = 1)
        }
        return DeckState(
            places = listOf(home, work, uni, gym, ball).map { it.copy(updatedAt = 1) },
            music = listOf(
                MusicFavorite(id = "m1", name = "Party Mix", kind = MusicKind.PLAYLIST, order = 0, updatedAt = 1),
                MusicFavorite(id = "m2", name = "Liked music", kind = MusicKind.PLAYLIST, query = "my liked music", order = 1, updatedAt = 1),
                MusicFavorite(id = "m3", name = "My Supermix", kind = MusicKind.MIX, query = "my supermix", order = 2, updatedAt = 1),
                MusicFavorite(id = "m4", name = "Fred again..", kind = MusicKind.ARTIST, order = 3, updatedAt = 1),
                MusicFavorite(id = "m5", name = "Drive Mix", kind = MusicKind.MIX, query = "driving mix", order = 4, updatedAt = 1),
            ),
            trips = trips.sortedBy { it.epochMillis },
            drives = drives.sortedBy { it.startedAt },
            plays = plays.sortedBy { it.at },
            fillUps = fills,
            recents = songs.mapIndexed { i, (t, a) -> RecentSong(t, a, "$t $a", now.minusHours(i.toLong()).toInstant().toEpochMilli()) },
        )
    }
}
