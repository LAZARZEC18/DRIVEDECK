package com.drivedeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for places, music shortcuts and trip history.
 *
 * The phone app and the Android Auto screens run in the same process, so both read the same
 * StateFlows: edit a place on the phone and the car screen updates immediately.
 * Everything is stored on-device in SharedPreferences as JSON. Nothing leaves the phone.
 */
class DeckRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("drivedeck", Context.MODE_PRIVATE)

    private val _places = MutableStateFlow(loadPlaces())
    private val _music = MutableStateFlow(loadMusic())
    private val _trips = MutableStateFlow(loadTrips())

    val places: StateFlow<List<Place>> = _places.asStateFlow()
    val music: StateFlow<List<MusicFavorite>> = _music.asStateFlow()
    val trips: StateFlow<List<TripEvent>> = _trips.asStateFlow()

    // ---------- Places ----------

    fun upsertPlace(place: Place) = synchronized(this) {
        val list = _places.value.toMutableList()
        val i = list.indexOfFirst { it.id == place.id }
        if (i >= 0) list[i] = place else list.add(place)
        savePlaces(list)
    }

    fun deletePlace(id: String) = synchronized(this) {
        savePlaces(_places.value.filterNot { it.id == id })
        saveTrips(_trips.value.filterNot { it.placeId == id })
    }

    fun movePlace(id: String, delta: Int) = synchronized(this) {
        val list = _places.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        savePlaces(list)
    }

    // ---------- Music ----------

    fun upsertMusic(fav: MusicFavorite) = synchronized(this) {
        val list = _music.value.toMutableList()
        val i = list.indexOfFirst { it.id == fav.id }
        if (i >= 0) list[i] = fav else list.add(fav)
        saveMusic(list)
    }

    fun deleteMusic(id: String) = synchronized(this) { saveMusic(_music.value.filterNot { it.id == id }) }

    fun moveMusic(id: String, delta: Int) = synchronized(this) {
        val list = _music.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        saveMusic(list)
    }

    // ---------- Trips ----------

    /**
     * Records that a trip to [placeId] started now. Repeated taps on the same place within
     * [DEDUPE_WINDOW_MS] count as one trip so a double-tap doesn't skew the routine.
     */
    fun logTrip(placeId: String, now: Long = System.currentTimeMillis()) = synchronized(this) {
        val last = _trips.value.lastOrNull()
        if (last != null && last.placeId == placeId && now - last.epochMillis < DEDUPE_WINDOW_MS) return
        saveTrips((_trips.value + TripEvent(placeId, now)).takeLast(MAX_TRIPS))
    }

    fun clearTrips() = synchronized(this) { saveTrips(emptyList()) }

    // ---------- Persistence ----------

    private fun loadPlaces(): List<Place> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return DefaultContent.places.also { savePlacesRaw(it) }
        return JSONArray(raw).mapObjects(Place::fromJson)
    }

    private fun loadMusic(): List<MusicFavorite> {
        val raw = prefs.getString(KEY_MUSIC, null) ?: return DefaultContent.music.also { saveMusicRaw(it) }
        return JSONArray(raw).mapObjects(MusicFavorite::fromJson)
    }

    private fun loadTrips(): List<TripEvent> =
        prefs.getString(KEY_TRIPS, null)?.let { JSONArray(it).mapObjects(TripEvent::fromJson) } ?: emptyList()

    private fun savePlaces(list: List<Place>) { savePlacesRaw(list); _places.value = list }
    private fun saveMusic(list: List<MusicFavorite>) { saveMusicRaw(list); _music.value = list }
    private fun saveTrips(list: List<TripEvent>) {
        prefs.edit { putString(KEY_TRIPS, JSONArray(list.map { it.toJson() }).toString()) }
        _trips.value = list
    }

    private fun savePlacesRaw(list: List<Place>) =
        prefs.edit { putString(KEY_PLACES, JSONArray(list.map { it.toJson() }).toString()) }

    private fun saveMusicRaw(list: List<MusicFavorite>) =
        prefs.edit { putString(KEY_MUSIC, JSONArray(list.map { it.toJson() }).toString()) }

    companion object {
        private const val KEY_PLACES = "places"
        private const val KEY_MUSIC = "music"
        private const val KEY_TRIPS = "trips"
        private const val MAX_TRIPS = 600
        private val DEDUPE_WINDOW_MS = TimeUnit.MINUTES.toMillis(15)

        @Volatile private var instance: DeckRepository? = null

        fun get(context: Context): DeckRepository =
            instance ?: synchronized(this) { instance ?: DeckRepository(context).also { instance = it } }

        /** Tests only: forget the singleton so each test starts from fresh storage. */
        @VisibleForTesting
        internal fun resetForTests() { instance = null }
    }
}

/** First-run content so the car screen isn't empty. Everything is editable in the phone app. */
private object DefaultContent {
    val places = listOf(
        Place(name = "Curtin Uni", address = "Curtin University, Kent Street, Bentley WA 6102", icon = PlaceIcon.SCHOOL),
    )
    val music = listOf(
        MusicFavorite(name = "Party Mix", kind = MusicKind.PLAYLIST),
        MusicFavorite(name = "Liked music", kind = MusicKind.PLAYLIST, query = "my liked music"),
        MusicFavorite(name = "My Supermix", kind = MusicKind.MIX, query = "my supermix"),
    )
}
