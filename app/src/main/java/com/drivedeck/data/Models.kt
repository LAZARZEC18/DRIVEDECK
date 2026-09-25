package com.drivedeck.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
 * Every syncable item carries:
 *  - updatedAt: last edit time (ms). When two devices disagree, the newer edit wins.
 *  - deleted:   a "tombstone", so a delete on the laptop also deletes on the phone
 *               instead of the phone re-uploading the item.
 *  - order:     stable sort key (creation time) so every device shows the same order.
 */

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
    val order: Long = System.currentTimeMillis(),
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val hasCoords: Boolean get() = lat != null && lng != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("address", address); put("icon", icon.name)
        lat?.let { put("lat", it) }; lng?.let { put("lng", it) }
        put("order", order); put("updatedAt", updatedAt); put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JSONObject, index: Int = 0) = Place(
            id = o.getString("id"),
            name = o.getString("name"),
            address = o.optString("address", ""),
            lat = if (o.has("lat") && !o.isNull("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng") && !o.isNull("lng")) o.getDouble("lng") else null,
            icon = runCatching { PlaceIcon.valueOf(o.optString("icon")) }.getOrDefault(PlaceIcon.PIN),
            order = o.optLong("order", index.toLong()),
            updatedAt = o.optLong("updatedAt", 0),
            deleted = o.optBoolean("deleted", false),
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
    val order: Long = System.currentTimeMillis(),
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind.name); put("query", query)
        url?.let { put("url", it) }
        put("order", order); put("updatedAt", updatedAt); put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JSONObject, index: Int = 0) = MusicFavorite(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = runCatching { MusicKind.valueOf(o.optString("kind")) }.getOrDefault(MusicKind.PLAYLIST),
            query = o.optString("query", o.getString("name")),
            url = o.str("url"),
            order = o.optLong("order", index.toLong()),
            updatedAt = o.optLong("updatedAt", 0),
            deleted = o.optBoolean("deleted", false),
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

/**
 * "Send to car": a destination queued from the laptop, this chat or the phone. It shows up as
 * the first tile on the car screen until you drive there or it expires.
 * [placeId] null means "cleared".
 */
data class NextUp(val placeId: String?, val expiresAt: Long, val updatedAt: Long) {
    fun activePlaceId(now: Long): String? = placeId?.takeIf { now < expiresAt }

    fun toJson(): JSONObject = JSONObject().apply {
        put("placeId", placeId ?: JSONObject.NULL); put("expiresAt", expiresAt); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = NextUp(
            placeId = if (o.isNull("placeId")) null else o.str("placeId"),
            expiresAt = o.optLong("expiresAt", 0),
            updatedAt = o.optLong("updatedAt", 0),
        )
    }
}

internal inline fun <T> JSONArray.mapObjectsIndexed(f: (JSONObject, Int) -> T): List<T> =
    (0 until length()).mapNotNull { i -> runCatching { f(getJSONObject(i), i) }.getOrNull() }

/** A song you played by searching (in DRIVEDECK or captured from what YouTube Music then played). */
data class RecentSong(val title: String, val artist: String?, val query: String, val playedAt: Long) {
    val key: String get() = query.trim().lowercase()
    val label: String get() = if (artist.isNullOrBlank()) title else "$title · $artist"

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title); artist?.let { put("artist", it) }; put("query", query); put("playedAt", playedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = RecentSong(
            title = o.getString("title"),
            artist = o.str("artist"),
            query = o.optString("query", o.getString("title")),
            playedAt = o.optLong("playedAt", 0),
        )
    }
}

/** One finished drive, recorded by the trip computer. */
data class Drive(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    /** Time actually moving (above walking pace). Traffic-light stops excluded. */
    val movingMs: Long,
    val maxSpeedMps: Double,
    val destination: String? = null,
    /** When you got within ~150 m of the destination, if you navigated to one. */
    val arrivedAt: Long? = null,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    /** Average over the whole trip, like a car's trip computer. */
    val avgSpeedMps: Double get() = if (durationMs > 0) distanceM / (durationMs / 1000.0) else 0.0
    /** Average only while moving. */
    val avgMovingSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startedAt", startedAt); put("endedAt", endedAt); put("distanceM", distanceM)
        put("movingMs", movingMs); put("maxSpeedMps", maxSpeedMps)
        destination?.let { put("destination", it) }; arrivedAt?.let { put("arrivedAt", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Drive(
            id = o.getString("id"),
            startedAt = o.getLong("startedAt"),
            endedAt = o.getLong("endedAt"),
            distanceM = o.optDouble("distanceM", 0.0),
            movingMs = o.optLong("movingMs", 0),
            maxSpeedMps = o.optDouble("maxSpeedMps", 0.0),
            destination = o.str("destination"),
            arrivedAt = if (o.has("arrivedAt") && !o.isNull("arrivedAt")) o.getLong("arrivedAt") else null,
        )
    }
}

/** One fuel fill-up. Price in cents per litre, like the pump shows it. */
data class FillUp(
    val id: String = UUID.randomUUID().toString(),
    val at: Long,
    val litres: Double,
    val centsPerLitre: Double,
    val totalDollars: Double = litres * centsPerLitre / 100.0,
    /** Optional odometer reading, for real L/100km between full tanks. */
    val odometerKm: Double? = null,
    val station: String? = null,
    val fullTank: Boolean = true,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("at", at); put("litres", litres); put("centsPerLitre", centsPerLitre); put("totalDollars", totalDollars)
        odometerKm?.let { put("odometerKm", it) }; station?.let { put("station", it) }
        put("fullTank", fullTank); put("updatedAt", updatedAt); put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JSONObject) = FillUp(
            id = o.getString("id"),
            at = o.getLong("at"),
            litres = o.getDouble("litres"),
            centsPerLitre = o.getDouble("centsPerLitre"),
            totalDollars = o.optDouble("totalDollars", o.getDouble("litres") * o.getDouble("centsPerLitre") / 100.0),
            odometerKm = if (o.has("odometerKm") && !o.isNull("odometerKm")) o.getDouble("odometerKm") else null,
            station = o.str("station"),
            fullTank = o.optBoolean("fullTank", true),
            updatedAt = o.optLong("updatedAt", 0),
            deleted = o.optBoolean("deleted", false),
        )
    }
}

/** A song YouTube Music played (logged automatically, for stats). */
data class SongPlay(val title: String, val artist: String?, val at: Long) {
    fun toJson(): JSONObject = JSONObject().apply { put("t", title); artist?.let { put("a", it) }; put("at", at) }

    companion object {
        fun fromJson(o: JSONObject) = SongPlay(o.getString("t"), o.str("a"), o.getLong("at"))
    }
}

/** FuelWatch product codes. */
enum class FuelType(val code: Int, val label: String) {
    ULP(1, "Unleaded 91"), PULP(2, "Premium 95"), RON98(6, "Premium 98"), DIESEL(4, "Diesel"), E85(10, "E85"), LPG(5, "LPG")
}

enum class NavApp(val label: String) { WAZE("Waze"), GOOGLE_MAPS("Google Maps") }

/** Preferences that sync across devices. */
data class DeckSettings(
    val fuelType: FuelType = FuelType.PULP,
    /** Your car's typical consumption, used to estimate fuel cost per drive. */
    val litresPer100Km: Double = 7.4,
    val phoneNavApp: NavApp = NavApp.WAZE,
    /** Spoken "speed camera ahead" warnings during a trip. */
    val cameraAlerts: Boolean = true,
    /** Background mode: start recording by itself whenever Android Auto connects. */
    val autoRecord: Boolean = true,
    /** Fuel tank size, for the "fuel's getting low" estimate (2020 Cerato GT: 50 L). */
    val tankLitres: Double = 50.0,
    /** Spoken "fuel's probably low, cheapest nearby is…" reminder at the start of a drive. */
    val fuelReminder: Boolean = true,
    val updatedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fuelType", fuelType.name); put("litresPer100Km", litresPer100Km); put("phoneNavApp", phoneNavApp.name); put("cameraAlerts", cameraAlerts)
        put("autoRecord", autoRecord); put("tankLitres", tankLitres); put("fuelReminder", fuelReminder); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DeckSettings(
            fuelType = runCatching { FuelType.valueOf(o.optString("fuelType")) }.getOrDefault(FuelType.PULP),
            litresPer100Km = o.optDouble("litresPer100Km", 7.4),
            phoneNavApp = runCatching { NavApp.valueOf(o.optString("phoneNavApp")) }.getOrDefault(NavApp.WAZE),
            cameraAlerts = o.optBoolean("cameraAlerts", true),
            autoRecord = o.optBoolean("autoRecord", true),
            tankLitres = o.optDouble("tankLitres", 50.0).takeIf { it in 20.0..150.0 } ?: 50.0,
            fuelReminder = o.optBoolean("fuelReminder", true),
            updatedAt = o.optLong("updatedAt", 0),
        )
    }
}

/** Optional string field: missing, JSON null and blank all mean null. */
internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
