package com.drivedeck.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Icon choices for a saved place. Each maps to a vector drawable (see [com.drivedeck.ui.iconRes]). */
enum class PlaceIcon { HOME, WORK, SCHOOL, GYM, SPORT, CAFE, SHOP, HEART, STAR, PIN }

/** A destination the driver goes to often. Coordinates are optional; without them we navigate by address. */
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val icon: PlaceIcon = PlaceIcon.PIN,
) {
    val hasCoords: Boolean get() = lat != null && lng != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("address", address); put("icon", icon.name)
        lat?.let { put("lat", it) }; lng?.let { put("lng", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Place(
            id = o.getString("id"),
            name = o.getString("name"),
            address = o.optString("address", ""),
            lat = if (o.has("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng")) o.getDouble("lng") else null,
            icon = runCatching { PlaceIcon.valueOf(o.optString("icon")) }.getOrDefault(PlaceIcon.PIN),
        )
    }
}

/** What kind of thing a music shortcut plays. Drives the search hints we send to YouTube Music. */
enum class MusicKind(val label: String) { PLAYLIST("Playlist"), ARTIST("Artist"), SONG("Song"), MIX("Mix") }

/** A one-tap music shortcut shown on the car screen. */
data class MusicFavorite(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: MusicKind = MusicKind.PLAYLIST,
    /** What to search for in YouTube Music. Defaults to the name. */
    val query: String = name,
    /** Optional music.youtube.com link (playlist/album/watch) for an exact match. */
    val url: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind.name); put("query", query)
        url?.let { put("url", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = MusicFavorite(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = runCatching { MusicKind.valueOf(o.optString("kind")) }.getOrDefault(MusicKind.PLAYLIST),
            query = o.optString("query", o.getString("name")),
            url = o.optString("url").ifBlank { null },
        )
    }
}

/** One trip started through DRIVEDECK: the raw material for the "usual route" predictions. */
data class TripEvent(val placeId: String, val epochMillis: Long) {
    fun toJson(): JSONObject = JSONObject().put("p", placeId).put("t", epochMillis)

    companion object {
        fun fromJson(o: JSONObject) = TripEvent(o.getString("p"), o.getLong("t"))
    }
}

internal inline fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> runCatching { f(getJSONObject(i)) }.getOrNull() }
