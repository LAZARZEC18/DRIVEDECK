package com.drivedeck.fuel

import android.content.Context
import android.location.Location
import android.util.Xml
import com.drivedeck.data.FuelType
import com.drivedeck.location.LocationHelper
import com.drivedeck.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A servo and today's (or tomorrow's) price. */
data class FuelStation(
    val name: String,
    val brand: String,
    val address: String,
    val suburb: String,
    val centsPerLitre: Double,
    val lat: Double,
    val lng: Double,
    val distanceKm: Double? = null,
)

data class FuelPrices(val stations: List<FuelStation>, val area: String, val tomorrow: Boolean, val fetchedAt: Long) {
    val cheapest: FuelStation? get() = stations.minByOrNull { it.centsPerLitre }
    val average: Double? get() = stations.takeIf { it.isNotEmpty() }?.map { it.centsPerLitre }?.average()
}

/**
 * Live fuel prices from FuelWatch, the WA Government service every WA servo must report its
 * prices to (PetrolSpy's WA prices come from here too). Free and public, no account needed.
 * WA prices are fixed for 24 h from 6 am, and tomorrow's prices are published at 2:30 pm.
 */
object FuelWatch {

    suspend fun nearby(context: Context, type: FuelType, tomorrow: Boolean = false, near: Location? = null): FuelPrices =
        withContext(Dispatchers.IO) {
            val here = near ?: LocationHelper.lastKnown(context)
            val suburb = here?.let { LocationHelper.reverseSuburb(context, it.latitude, it.longitude) }
            val params = buildString {
                append("Product=${type.code}")
                if (suburb != null) append("&Suburb=${URLEncoder.encode(suburb, "UTF-8")}&Surrounding=yes")
                if (tomorrow) append("&Day=tomorrow")
            }
            var stations = fetch(params)
            // Unknown suburb name or a quiet area: fall back to the north-of-river metro region (25).
            if (stations.isEmpty() && suburb != null) stations = fetch("Product=${type.code}&Region=25" + if (tomorrow) "&Day=tomorrow" else "")
            val withDistance = stations.map { s ->
                s.copy(distanceKm = here?.let { Geo.distanceMeters(it.latitude, it.longitude, s.lat, s.lng) / 1000.0 })
            }
            FuelPrices(
                stations = withDistance.sortedWith(compareBy({ it.centsPerLitre }, { it.distanceKm ?: 0.0 })),
                area = suburb ?: "Perth north of river",
                tomorrow = tomorrow,
                fetchedAt = System.currentTimeMillis(),
            )
        }

    private fun fetch(params: String): List<FuelStation> {
        val conn = URL("https://www.fuelwatch.wa.gov.au/fuelwatch/fuelWatchRSS?$params").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "DRIVEDECK-Android")
        return try {
            if (conn.responseCode != 200) emptyList() else conn.inputStream.use(::parse)
        } finally {
            conn.disconnect()
        }
    }

    /** Parses the FuelWatch RSS feed. Public for tests. */
    fun parse(input: InputStream): List<FuelStation> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        val out = mutableListOf<FuelStation>()
        var fields: MutableMap<String, String>? = null
        var tag: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") fields = mutableMapOf()
                }
                XmlPullParser.TEXT -> {
                    val f = fields; val t = tag
                    if (f != null && t != null && p.text.isNotBlank()) f[t] = (f[t] ?: "") + p.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        fields?.let { f ->
                            val price = f["price"]?.toDoubleOrNull()
                            val lat = f["latitude"]?.toDoubleOrNull()
                            val lng = f["longitude"]?.toDoubleOrNull()
                            if (price != null && lat != null && lng != null) {
                                out += FuelStation(
                                    name = f["trading-name"] ?: f["title"].orEmpty(),
                                    brand = f["brand"].orEmpty(),
                                    address = f["address"].orEmpty(),
                                    suburb = f["location"].orEmpty().lowercase().replaceFirstChar { it.uppercase() },
                                    centsPerLitre = price,
                                    lat = lat, lng = lng,
                                )
                            }
                        }
                        fields = null
                    }
                    tag = null
                }
            }
        }
        return out
    }
}
