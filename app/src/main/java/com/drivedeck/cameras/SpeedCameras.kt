package com.drivedeck.cameras

import android.content.Context
import androidx.core.content.edit
import com.drivedeck.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A fixed speed or red-light camera. */
data class Camera(val id: Long, val lat: Double, val lng: Double, val redLight: Boolean, val maxSpeed: Int?) {
    val label: String get() = when {
        redLight && maxSpeed != null -> "Red-light & speed camera · $maxSpeed zone"
        redLight -> "Red-light camera"
        maxSpeed != null -> "Speed camera · $maxSpeed zone"
        else -> "Speed camera"
    }
}

/** A camera coming up on your road. */
data class CameraAhead(val camera: Camera, val distanceM: Double)

/**
 * Fixed speed and red-light camera locations from OpenStreetMap (Perth has ~90 mapped, with
 * speed zones). Downloaded around you and cached on the phone for a week, so the warnings also
 * work with no signal. Mobile camera vans move daily, so they aren't included; Waze and Google
 * Maps still warn about those from user reports.
 */
object SpeedCameras {
    private const val PREFS = "drivedeck_cameras"
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    @Volatile private var cache: List<Camera>? = null

    /** Cameras within ~40 km, from cache when fresh and close enough. */
    suspend fun near(context: Context, lat: Double, lng: Double, forceRefresh: Boolean = false): List<Camera> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0)
        val cLat = prefs.getFloat("lat", 0f).toDouble(); val cLng = prefs.getFloat("lng", 0f).toDouble()
        val fresh = System.currentTimeMillis() - at < WEEK_MS && Geo.distanceMeters(lat, lng, cLat, cLng) < 20_000
        if (!forceRefresh && fresh) {
            cache?.let { return@withContext it }
            prefs.getString("json", null)?.let { raw -> decode(raw).also { cache = it }.let { return@withContext it } }
        }
        val downloaded = runCatching { download(lat, lng) }.getOrNull()
        if (downloaded != null) {
            prefs.edit {
                putString("json", encode(downloaded)); putLong("at", System.currentTimeMillis())
                putFloat("lat", lat.toFloat()); putFloat("lng", lng.toFloat())
            }
            cache = downloaded
            downloaded
        } else {
            cache ?: prefs.getString("json", null)?.let(::decode) ?: emptyList()
        }
    }

    /** Last loaded cameras without touching the network (for the trip computer's 1 s loop). */
    fun cached(): List<Camera> = cache.orEmpty()

    private fun download(lat: Double, lng: Double): List<Camera> {
        val d = 0.36 // ≈ 40 km
        val box = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lat - d, lng - d, lat + d, lng + d)
        val q = "[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"]($box);node[\"enforcement\"]($box););out 500;"
        val conn = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 40_000
        conn.setRequestProperty("User-Agent", "DRIVEDECK/1.1 (personal Android Auto app)")
        conn.setRequestProperty("Accept", "*/*")
        val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        return parse(body)
    }

    /** Parses an Overpass response. Public for tests. */
    fun parse(body: String): List<Camera> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            if (!e.has("lat")) return@mapNotNull null
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val enforcement = tags.optString("enforcement")
            Camera(
                id = e.getLong("id"),
                lat = e.getDouble("lat"), lng = e.getDouble("lon"),
                redLight = enforcement == "traffic_signals",
                maxSpeed = tags.optString("maxspeed").filter { it.isDigit() }.toIntOrNull(),
            )
        }.distinctBy { it.id }
    }

    private fun encode(list: List<Camera>) = JSONArray(list.map {
        JSONObject().put("id", it.id).put("lat", it.lat).put("lng", it.lng).put("red", it.redLight).apply { it.maxSpeed?.let { s -> put("max", s) } }
    }).toString()

    private fun decode(raw: String): List<Camera> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Camera(o.getLong("id"), o.getDouble("lat"), o.getDouble("lng"), o.optBoolean("red"), if (o.has("max")) o.getInt("max") else null)
        }
    }.getOrDefault(emptyList())

    /**
     * The nearest camera in front of you: within [rangeM] and within ±30° of your direction of
     * travel. Pure function, unit tested.
     */
    fun ahead(cameras: List<Camera>, lat: Double, lng: Double, headingDeg: Double?, rangeM: Double = 600.0): CameraAhead? {
        if (headingDeg == null) return null
        return cameras.asSequence()
            .map { it to Geo.distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { (_, d) -> d in 15.0..rangeM }
            .filter { (c, _) -> angleDiff(bearing(lat, lng, c.lat, c.lng), headingDeg) <= 30.0 }
            .minByOrNull { it.second }
            ?.let { CameraAhead(it.first, it.second) }
    }

    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }
}
