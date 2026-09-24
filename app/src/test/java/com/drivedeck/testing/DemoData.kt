package com.drivedeck.testing

import android.content.Context
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.MusicFavorite
import com.drivedeck.data.MusicKind
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import java.time.ZonedDateTime

/** Realistic demo content for screen tests and README screenshots (public places only). */
object DemoData {
    val work = Place(id = "work", name = "Greenhse", address = "Ellenbrook WA", lat = -31.7760, lng = 115.9690, icon = PlaceIcon.WORK)
    val uni = Place(id = "uni", name = "Curtin Uni", address = "Kent St, Bentley WA 6102", lat = -32.0057, lng = 115.8940, icon = PlaceIcon.SCHOOL)
    val gym = Place(id = "gym", name = "Gym", address = "Perth WA", lat = -31.9505, lng = 115.8605, icon = PlaceIcon.GYM)
    val ball = Place(id = "ball", name = "Basketball", address = "Perth Arena, Perth WA", lat = -31.9483, lng = 115.8520, icon = PlaceIcon.SPORT)
    val home = Place(id = "home", name = "Home", address = "Perth WA", lat = -31.9200, lng = 115.8700, icon = PlaceIcon.HOME)

    fun seed(context: Context): DeckRepository {
        DeckRepository.resetForTests()
        context.getSharedPreferences("drivedeck", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = DeckRepository.get(context)
        repo.places.value.forEach { repo.deletePlace(it.id) }
        repo.music.value.forEach { repo.deleteMusic(it.id) }
        listOf(home, work, uni, gym, ball).forEach(repo::upsertPlace)
        listOf(
            MusicFavorite(name = "Party Mix", kind = MusicKind.PLAYLIST),
            MusicFavorite(name = "Liked music", kind = MusicKind.PLAYLIST, query = "my liked music"),
            MusicFavorite(name = "My Supermix", kind = MusicKind.MIX, query = "my supermix"),
            MusicFavorite(name = "Fred again..", kind = MusicKind.ARTIST),
            MusicFavorite(name = "Drive Mix", kind = MusicKind.MIX, query = "driving mix"),
        ).forEach(repo::upsertMusic)

        // A routine centred on "now": work at this hour for the last three weeks, uni some days.
        val now = ZonedDateTime.now()
        for (d in 1L..21L) {
            val day = now.minusDays(d)
            repo.logTrip("work", day.minusMinutes(10).toInstant().toEpochMilli())
            if (d % 3 == 0L) repo.logTrip("uni", day.plusHours(3).toInstant().toEpochMilli())
            repo.logTrip("home", day.plusHours(9).toInstant().toEpochMilli())
        }
        return repo
    }
}
