package com.drivedeck.nav

import android.content.Context
import android.location.Location
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import com.drivedeck.location.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A search hit: business, landmark or address. */
data class SearchResult(val name: String, val detail: String, val lat: Double, val lng: Double, val distanceKm: Double?) {
    fun toPlace(): Place = Place(id = "search:$lat,$lng", name = name, address = detail, lat = lat, lng = lng, icon = PlaceIcon.PIN)
}

/**
 * Destination search without an API key: OpenStreetMap places (via Photon, biased to where you
 * are) plus Android's own geocoder for street addresses. Every result can then go to Waze or
 * Google Maps. For anything these miss, "Search in Waze/Google Maps" hands the text straight to
 * that app's own search.
 */
object PlaceSearch {

    suspend fun search(context: Context, query: String, near: Location? = LocationHelper.lastKnown(context)): List<SearchResult> =
        coroutineScope {
            if (query.isBlank()) return@coroutineScope emptyList()
            val photon = async(Dispatchers.IO) { runCatching { photon(query, near) }.getOrDefault(emptyList()) }
            val geo = async(Dispatchers.IO) { runCatching { geocoder(context, query, near) }.getOrDefault(emptyList()) }
            val all = photon.await() + geo.await()
            // De-duplicate hits within ~80 m of each other, nearest first.
            val out = mutableListOf<SearchResult>()
            for (r in all.sortedBy { it.distanceKm ?: Double.MAX_VALUE }) {
                if (out.none { Geo.distanceMeters(it.lat, it.lng, r.lat, r.lng) < 80 }) out += r
            }
            out.take(8)
        }

    private fun photon(query: String, near: Location?): List<SearchResult> {
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: "&lat=-31.95&lon=115.86"
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(query, "UTF-8")}&limit=8&lang=en$bias"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "DRIVEDECK-Android")
        val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val lng = c.getDouble(0); val lat = c.getDouble(1)
            val street = listOfNotNull(p.optString("housenumber").ifBlank { null }, p.optString("street").ifBlank { null }).joinToString(" ")
            val area = p.optString("district").ifBlank { p.optString("city") }
            val name = p.optString("name").ifBlank { street.ifBlank { return@mapNotNull null } }
            SearchResult(
                name = name,
                detail = listOf(street, area).filter { it.isNotBlank() && it != name }.joinToString(", "),
                lat = lat, lng = lng,
                distanceKm = near?.let { Geo.distanceMeters(it.latitude, it.longitude, lat, lng) / 1000.0 },
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocoder(context: Context, query: String, near: Location?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!android.location.Geocoder.isPresent()) return@withContext emptyList()
        android.location.Geocoder(context).getFromLocationName(query, 3).orEmpty().map { a ->
            SearchResult(
                name = a.featureName?.takeIf { it != a.subThoroughfare } ?: a.getAddressLine(0).substringBefore(','),
                detail = a.getAddressLine(0) ?: "",
                lat = a.latitude, lng = a.longitude,
                distanceKm = near?.let { Geo.distanceMeters(it.latitude, it.longitude, a.latitude, a.longitude) / 1000.0 },
            )
        }
    }
}
