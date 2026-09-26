package com.drivedeck.cameras

import android.content.Context
import com.drivedeck.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/** A set of traffic lights (one intersection, however many signal posts OSM has for it). */
data class Signal(val lat: Double, val lng: Double)

data class SignalAhead(val signal: Signal, val distanceM: Double)

/**
 * Traffic light locations from OpenStreetMap, so DRIVEDECK can say "lights ahead" as you
 * approach them at speed. Downloaded once around you (~30 km) and kept on the phone for a
 * week, so alerts also work with no signal.
 */
object TrafficSignals {
    private const val FILE = "traffic-signals.txt"
    private const val WEEK_MS = 7L * 24 * 3600 * 1000
    /** OSM often maps each approach of an intersection separately; merge posts this close. */
    private const val CLUSTER_M = 45.0

    @Volatile private var cache: List<Signal> = emptyList()

    fun cached(): List<Signal> = cache

    /** Loads from the phone, downloading when missing, stale or far from here. */
    suspend fun load(context: Context, lat: Double, lng: Double) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, FILE)
        val stored = if (f.exists()) runCatching { decode(f.readText()) }.getOrNull() else null
        val fresh = stored != null && System.currentTimeMillis() - stored.at < WEEK_MS &&
            Geo.distanceMeters(lat, lng, stored.lat, stored.lng) < 15_000
        if (fresh) { cache = stored!!.signals; return@withContext }
        val downloaded = runCatching { download(lat, lng) }.getOrNull()
        if (downloaded != null && downloaded.isNotEmpty()) {
            cache = downloaded
            runCatching { f.writeText(encode(Stored(System.currentTimeMillis(), lat, lng, downloaded))) }
        } else if (stored != null) {
            cache = stored.signals
        }
    }

    private fun download(lat: Double, lng: Double): List<Signal> {
        val d = 0.27 // ≈ 30 km
        val box = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lat - d, lng - d, lat + d, lng + d)
        val q = "[out:json][timeout:40];node[\"highway\"=\"traffic_signals\"]($box);out skel 20000;"
        val conn = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "DRIVEDECK/1.3 (personal driving app)")
        val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        return cluster(parse(body))
    }

    /** Parses an Overpass response. Public for tests. */
    fun parse(body: String): List<Signal> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            if (e.has("lat")) Signal(e.getDouble("lat"), e.getDouble("lon")) else null
        }
    }

    /** Merges signal posts of the same intersection into one point. Public for tests. */
    fun cluster(points: List<Signal>): List<Signal> {
        val out = ArrayList<Signal>()
        val groups = ArrayList<MutableList<Signal>>()
        for (p in points.sortedBy { it.lat }) {
            // Sorted by latitude, so only the most recent groups can be close enough.
            var g: MutableList<Signal>? = null
            for (i in groups.indices.reversed()) {
                val grp = groups[i]
                if (p.lat - grp.last().lat > 0.002) break
                if (grp.any { Geo.distanceMeters(it.lat, it.lng, p.lat, p.lng) < CLUSTER_M }) { g = grp; break }
            }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        groups.forEach { g -> out += Signal(g.map { it.lat }.average(), g.map { it.lng }.average()) }
        return out
    }

    /**
     * The nearest set of lights in front of you, within [rangeM] and ±25° of your heading.
     * Pure function, unit tested.
     */
    fun ahead(signals: List<Signal>, lat: Double, lng: Double, headingDeg: Double?, rangeM: Double): SignalAhead? {
        if (headingDeg == null) return null
        val dLat = rangeM / 111_000.0
        val dLng = rangeM / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return signals.asSequence()
            .filter { abs(it.lat - lat) <= dLat && abs(it.lng - lng) <= dLng }
            .map { it to Geo.distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { (_, d) -> d in 30.0..rangeM }
            .filter { (s, _) -> SpeedCameras.angleDiff(SpeedCameras.bearing(lat, lng, s.lat, s.lng), headingDeg) <= 25.0 }
            .minByOrNull { it.second }
            ?.let { SignalAhead(it.first, it.second) }
    }

    private data class Stored(val at: Long, val lat: Double, val lng: Double, val signals: List<Signal>)

    // Compact text: header line "at,lat,lng", then one "lat,lng" per line.
    private fun encode(s: Stored): String = buildString {
        append(s.at).append(',').append(s.lat).append(',').append(s.lng).append('\n')
        s.signals.forEach { append(String.format(Locale.US, "%.6f,%.6f\n", it.lat, it.lng)) }
    }

    private fun decode(text: String): Stored {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val h = lines.first().split(',')
        val signals = lines.drop(1).map { l -> l.split(',').let { Signal(it[0].toDouble(), it[1].toDouble()) } }
        return Stored(h[0].toLong(), h[1].toDouble(), h[2].toDouble(), signals)
    }
}
