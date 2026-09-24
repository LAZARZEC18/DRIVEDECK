package com.drivedeck.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Everything DRIVEDECK knows, in one snapshot. This is what gets synced as `deck.json`. */
data class DeckState(
    val places: List<Place> = emptyList(),
    val music: List<MusicFavorite> = emptyList(),
    val trips: List<TripEvent> = emptyList(),
    /** Trips before this time are forgotten everywhere ("Reset routine" on any device). */
    val tripsResetAt: Long = 0,
    val nextUp: NextUp? = null,
    /** Last 20 songs played by search, newest first. */
    val recents: List<RecentSong> = emptyList(),
    /** Trip computer history, newest last. */
    val drives: List<Drive> = emptyList(),
    val fillUps: List<FillUp> = emptyList(),
    /** Songs YouTube Music played, oldest first (for stats). */
    val plays: List<SongPlay> = emptyList(),
    val settings: DeckSettings = DeckSettings(),
) {
    val visibleFillUps: List<FillUp> get() = fillUps.filterNot { it.deleted }.sortedBy { it.at }
    val visiblePlaces: List<Place> get() = places.filterNot { it.deleted }.sortedWith(compareBy({ it.order }, { it.id }))
    val visibleMusic: List<MusicFavorite> get() = music.filterNot { it.deleted }.sortedWith(compareBy({ it.order }, { it.id }))
}

/**
 * Conflict-free merge of two copies of the deck (this phone vs GitHub). Pure function, unit tested.
 *
 *  - Places / music: per item, the newest `updatedAt` wins. Deletes are tombstones, so they sync too.
 *  - Trips: union of both sides (a trip logged on either device counts), minus anything before
 *    the latest routine reset.
 *  - Next up: the newest instruction wins.
 */
object DeckMerge {
    const val MAX_TRIPS = 600
    const val MAX_RECENTS = 20
    const val MAX_DRIVES = 400
    const val MAX_PLAYS = 2000
    private val TOMBSTONE_TTL = TimeUnit.DAYS.toMillis(60)

    fun merge(a: DeckState, b: DeckState, now: Long = System.currentTimeMillis()): DeckState {
        val resetAt = maxOf(a.tripsResetAt, b.tripsResetAt)
        return DeckState(
            places = mergeItems(a.places, b.places, now, Place::id, Place::updatedAt, Place::deleted, Place::order),
            music = mergeItems(a.music, b.music, now, MusicFavorite::id, MusicFavorite::updatedAt, MusicFavorite::deleted, MusicFavorite::order),
            trips = (a.trips + b.trips)
                .filter { it.epochMillis >= resetAt }
                .distinctBy { it.placeId to it.epochMillis }
                .sortedBy { it.epochMillis }
                .takeLast(MAX_TRIPS),
            tripsResetAt = resetAt,
            nextUp = listOfNotNull(a.nextUp, b.nextUp).maxByOrNull { it.updatedAt },
            recents = mergeRecents(a.recents + b.recents),
            drives = (a.drives + b.drives).distinctBy { it.id }.sortedBy { it.startedAt }.takeLast(MAX_DRIVES),
            fillUps = mergeItems(a.fillUps, b.fillUps, now, FillUp::id, FillUp::updatedAt, FillUp::deleted, FillUp::at),
            plays = (a.plays + b.plays).distinctBy { it.title to it.at }.sortedBy { it.at }.takeLast(MAX_PLAYS),
            settings = if (b.settings.updatedAt > a.settings.updatedAt) b.settings else a.settings,
        )
    }

    /** Newest play of each song first, max 20. */
    fun mergeRecents(all: List<RecentSong>): List<RecentSong> =
        all.groupBy { it.key }.values.map { same -> same.maxBy { it.playedAt } }
            .sortedByDescending { it.playedAt }
            .take(MAX_RECENTS)

    private fun <T> mergeItems(
        a: List<T>, b: List<T>, now: Long,
        id: (T) -> String, updatedAt: (T) -> Long, deleted: (T) -> Boolean, order: (T) -> Long,
    ): List<T> {
        val out = LinkedHashMap<String, T>()
        for (item in a + b) {
            val existing = out[id(item)]
            // Newer edit wins. On an exact tie, a delete wins (safer than resurrecting).
            if (existing == null || updatedAt(item) > updatedAt(existing) ||
                (updatedAt(item) == updatedAt(existing) && deleted(item) && !deleted(existing))
            ) {
                out[id(item)] = item
            }
        }
        return out.values
            .filterNot { deleted(it) && now - updatedAt(it) > TOMBSTONE_TTL }
            .sortedWith(compareBy(order, id))
    }
}

/** `deck.json` format, shared with the laptop dashboard and the chat CLI (tools/deckctl.py). */
object DeckJson {
    const val SCHEMA = 1

    fun encode(s: DeckState): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("places", JSONArray(s.places.map { it.toJson() }))
        put("music", JSONArray(s.music.map { it.toJson() }))
        put("trips", JSONArray(s.trips.map { it.toJson() }))
        put("tripsResetAt", s.tripsResetAt)
        put("nextUp", s.nextUp?.toJson() ?: JSONObject.NULL)
        put("recents", JSONArray(s.recents.map { it.toJson() }))
        put("drives", JSONArray(s.drives.map { it.toJson() }))
        put("fillUps", JSONArray(s.fillUps.map { it.toJson() }))
        put("plays", JSONArray(s.plays.map { it.toJson() }))
        put("settings", s.settings.toJson())
    }.toString(1)

    fun decode(raw: String): DeckState {
        val o = JSONObject(raw)
        return DeckState(
            places = o.optJSONArray("places")?.mapObjectsIndexed(Place::fromJson) ?: emptyList(),
            music = o.optJSONArray("music")?.mapObjectsIndexed(MusicFavorite::fromJson) ?: emptyList(),
            trips = o.optJSONArray("trips")?.mapObjectsIndexed { j, _ -> TripEvent.fromJson(j) } ?: emptyList(),
            tripsResetAt = o.optLong("tripsResetAt", 0),
            nextUp = o.optJSONObject("nextUp")?.let(NextUp::fromJson),
            recents = o.optJSONArray("recents")?.mapObjectsIndexed { j, _ -> RecentSong.fromJson(j) } ?: emptyList(),
            drives = o.optJSONArray("drives")?.mapObjectsIndexed { j, _ -> Drive.fromJson(j) } ?: emptyList(),
            fillUps = o.optJSONArray("fillUps")?.mapObjectsIndexed { j, _ -> FillUp.fromJson(j) } ?: emptyList(),
            plays = o.optJSONArray("plays")?.mapObjectsIndexed { j, _ -> SongPlay.fromJson(j) } ?: emptyList(),
            settings = o.optJSONObject("settings")?.let(DeckSettings::fromJson) ?: DeckSettings(),
        )
    }
}
