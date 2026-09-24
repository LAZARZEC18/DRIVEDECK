package com.drivedeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for places, music shortcuts, trip history and "next up".
 *
 * The phone app and the Android Auto screens run in the same process and read the same
 * StateFlows, so an edit on the phone updates the car screen immediately. Everything is stored
 * on-device. When sync is set up, [com.drivedeck.sync.SyncManager] merges it with the private
 * GitHub copy so the laptop dashboard and chat edits show up here too.
 */
class DeckRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("drivedeck", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    private val _places = MutableStateFlow(_state.value.visiblePlaces)
    private val _music = MutableStateFlow(_state.value.visibleMusic)
    private val _trips = MutableStateFlow(_state.value.trips)
    private val _nextUp = MutableStateFlow(_state.value.nextUp)
    private val _recents = MutableStateFlow(_state.value.recents)
    private val _drives = MutableStateFlow(_state.value.drives)
    private val _fillUps = MutableStateFlow(_state.value.visibleFillUps)
    private val _plays = MutableStateFlow(_state.value.plays)
    private val _settings = MutableStateFlow(_state.value.settings)
    private val _localEdits = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Full state including tombstones: what gets synced. */
    val state: StateFlow<DeckState> = _state.asStateFlow()
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    val music: StateFlow<List<MusicFavorite>> = _music.asStateFlow()
    val trips: StateFlow<List<TripEvent>> = _trips.asStateFlow()
    val nextUp: StateFlow<NextUp?> = _nextUp.asStateFlow()
    val recents: StateFlow<List<RecentSong>> = _recents.asStateFlow()
    val drives: StateFlow<List<Drive>> = _drives.asStateFlow()
    val fillUps: StateFlow<List<FillUp>> = _fillUps.asStateFlow()
    val plays: StateFlow<List<SongPlay>> = _plays.asStateFlow()
    val settings: StateFlow<DeckSettings> = _settings.asStateFlow()

    /** Fires after every edit made on this phone (not after applying synced data). */
    val localEdits: SharedFlow<Unit> = _localEdits.asSharedFlow()

    // ---------- Places ----------

    fun upsertPlace(place: Place) = edit { s -> s.copy(places = s.places.upsert(place.copy(updatedAt = now()), Place::id)) }

    fun deletePlace(id: String) = edit { s ->
        s.copy(places = s.places.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it })
    }

    // ---------- Music ----------

    fun upsertMusic(fav: MusicFavorite) = edit { s -> s.copy(music = s.music.upsert(fav.copy(updatedAt = now()), MusicFavorite::id)) }

    fun deleteMusic(id: String) = edit { s ->
        s.copy(music = s.music.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it })
    }

    // ---------- Trips ----------

    /**
     * Records that a trip to [placeId] started at [at]. Repeated taps on the same place within
     * [DEDUPE_WINDOW_MS] count as one trip so a double-tap doesn't skew the routine.
     * Driving to the queued "next up" place clears it.
     */
    fun logTrip(placeId: String, at: Long = now()) = edit { s ->
        val last = s.trips.lastOrNull()
        val trips = if (last != null && last.placeId == placeId && at - last.epochMillis in 0 until DEDUPE_WINDOW_MS) s.trips
        else (s.trips + TripEvent(placeId, at)).takeLast(DeckMerge.MAX_TRIPS)
        val clearNext = s.nextUp?.activePlaceId(at) == placeId
        s.copy(trips = trips, nextUp = if (clearNext) NextUp(null, 0, now()) else s.nextUp)
    }

    fun clearTrips() = edit { s -> s.copy(trips = emptyList(), tripsResetAt = now()) }

    // ---------- Next up ----------

    fun setNextUp(placeId: String?, forMillis: Long = TimeUnit.HOURS.toMillis(12)) = edit { s ->
        s.copy(nextUp = NextUp(placeId, if (placeId == null) 0 else now() + forMillis, now()))
    }

    // ---------- Recent songs ----------

    fun addRecent(song: RecentSong) = edit { s -> s.copy(recents = DeckMerge.mergeRecents(listOf(song) + s.recents)) }

    // ---------- Drives ----------

    fun addDrive(d: Drive) = edit { s ->
        s.copy(drives = (s.drives.filterNot { it.id == d.id } + d).sortedBy { it.startedAt }.takeLast(DeckMerge.MAX_DRIVES))
    }

    // ---------- Fuel ----------

    fun upsertFillUp(f: FillUp) = edit { s ->
        s.copy(fillUps = s.fillUps.upsert(f.copy(updatedAt = now()), FillUp::id))
    }

    fun deleteFillUp(id: String) = edit { s ->
        s.copy(fillUps = s.fillUps.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it })
    }

    // ---------- Song plays (stats) ----------

    /**
     * Logs a song YouTube Music started. Not "urgent": it rides along with the next regular sync
     * instead of making a GitHub commit per song.
     */
    fun logPlay(title: String, artist: String?, at: Long = now()) = edit(urgent = false) { s ->
        val last = s.plays.lastOrNull()
        if (last != null && last.title == title && at - last.at < TimeUnit.MINUTES.toMillis(2)) s
        else s.copy(plays = (s.plays + SongPlay(title, artist, at)).takeLast(DeckMerge.MAX_PLAYS))
    }

    // ---------- Settings ----------

    fun updateSettings(f: (DeckSettings) -> DeckSettings) = edit { s -> s.copy(settings = f(s.settings).copy(updatedAt = now())) }

    // ---------- Sync ----------

    /** Replaces local state with a merged copy from sync. Doesn't count as a local edit. */
    fun applySynced(merged: DeckState) = synchronized(this) {
        if (merged != _state.value) save(merged)
    }

    // ---------- Internals ----------

    private inline fun edit(urgent: Boolean = true, f: (DeckState) -> DeckState) {
        synchronized(this) {
            val next = f(_state.value)
            if (next == _state.value) return
            save(next)
        }
        if (urgent) _localEdits.tryEmit(Unit)
    }

    private fun save(s: DeckState) {
        prefs.edit { putString(KEY_STATE, DeckJson.encode(s)) }
        _state.value = s
        _places.value = s.visiblePlaces
        _music.value = s.visibleMusic
        _trips.value = s.trips
        _nextUp.value = s.nextUp
        _recents.value = s.recents
        _drives.value = s.drives
        _fillUps.value = s.visibleFillUps
        _plays.value = s.plays
        _settings.value = s.settings
    }

    private fun load(): DeckState {
        prefs.getString(KEY_STATE, null)?.let { raw -> runCatching { DeckJson.decode(raw) }.getOrNull()?.let { return it } }
        // v1.0 stored three separate arrays; migrate them once.
        val legacyPlaces = prefs.getString("places", null)
        if (legacyPlaces != null) {
            val s = DeckState(
                places = JSONArray(legacyPlaces).mapObjectsIndexed(Place::fromJson),
                music = prefs.getString("music", null)?.let { JSONArray(it).mapObjectsIndexed(MusicFavorite::fromJson) } ?: emptyList(),
                trips = prefs.getString("trips", null)?.let { JSONArray(it).mapObjectsIndexed { o, _ -> TripEvent.fromJson(o) } } ?: emptyList(),
            )
            prefs.edit { putString(KEY_STATE, DeckJson.encode(s)); remove("places"); remove("music"); remove("trips") }
            return s
        }
        return DefaultContent.state.also { prefs.edit { putString(KEY_STATE, DeckJson.encode(it)) } }
    }

    private fun now() = System.currentTimeMillis()

    private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
        val i = indexOfFirst { id(it) == id(item) }
        return if (i >= 0) toMutableList().also { it[i] = item } else this + item
    }

    companion object {
        private const val KEY_STATE = "deck_state"
        private val DEDUPE_WINDOW_MS = TimeUnit.MINUTES.toMillis(15)

        @Volatile private var instance: DeckRepository? = null

        fun get(context: Context): DeckRepository =
            instance ?: synchronized(this) { instance ?: DeckRepository(context).also { instance = it } }

        /** Tests only: forget the singleton so each test starts from fresh storage. */
        @VisibleForTesting
        internal fun resetForTests() { instance = null }
    }
}

/**
 * First-run content so the car screen isn't empty. updatedAt = 0, so anything already
 * synced from another device wins over these.
 */
private object DefaultContent {
    val state = DeckState(
        places = listOf(
            Place(id = "default-curtin", name = "Curtin Uni", address = "Curtin University, Kent Street, Bentley WA 6102", icon = PlaceIcon.SCHOOL, order = 0),
        ),
        music = listOf(
            MusicFavorite(id = "default-party", name = "Party Mix", kind = MusicKind.PLAYLIST, order = 0),
            MusicFavorite(id = "default-liked", name = "Liked music", kind = MusicKind.PLAYLIST, query = "my liked music", order = 1),
            MusicFavorite(id = "default-supermix", name = "My Supermix", kind = MusicKind.MIX, query = "my supermix", order = 2),
        ),
    )
}
