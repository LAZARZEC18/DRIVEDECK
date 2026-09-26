package com.drivedeck.eta

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.drivedeck.data.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Road route without traffic, from the free OSRM router (OpenStreetMap). */
data class Route(val distanceM: Double, val freeFlowS: Double)

/**
 * Three ETAs side by side:
 *  - **Traffic**: the live ETA Waze / Google Maps is showing (read from their navigation
 *    notification, so it includes current traffic).
 *  - **Your ETA**: from your own history. Your median time on past drives to this place at a
 *    similar time of day, or else the road distance at your personal average speed.
 *  - **No traffic**: the free-flow road time.
 */
data class EtaEstimate(
    val routeKm: Double?,
    val freeFlowMin: Double?,
    /** Personal estimate in minutes and what it's based on. */
    val personalMin: Double?,
    val personalBasis: String?,
    val computedAt: Long = System.currentTimeMillis(),
)

object EtaEngine {

    suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://router.project-osrm.org/route/v1/driving/$fromLng,$fromLat;$toLng,$toLat?overview=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "DRIVEDECK-Android")
            val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
            val r = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            Route(r.getDouble("distance"), r.getDouble("duration"))
        }.getOrNull()
    }

    /**
     * Your personal ETA in minutes. Pure function, unit tested.
     * 1. ≥ 2 past drives to [destination]: median duration, preferring drives that started
     *    within 2 h of [now]'s time of day (rush hour vs late night).
     * 2. Otherwise: road distance ÷ your overall average speed across all drives.
     * 3. Otherwise (no history yet): free-flow time + 15%.
     */
    fun personal(destination: String?, route: Route?, drives: List<Drive>, now: ZonedDateTime): Pair<Double, String>? {
        val zone = now.zone
        if (destination != null) {
            val past = drives.filter { it.destination == destination && (it.arrivedAt ?: it.endedAt) > it.startedAt }
            if (past.size >= 2) {
                val nowH = now.hour + now.minute / 60.0
                val similar = past.filter {
                    val at = Instant.ofEpochMilli(it.startedAt).atZone(zone)
                    val h = at.hour + at.minute / 60.0
                    minOf(kotlin.math.abs(h - nowH), 24 - kotlin.math.abs(h - nowH)) <= 2.0
                }
                val pool = if (similar.size >= 2) similar else past
                val mins = pool.map { ((it.arrivedAt ?: it.endedAt) - it.startedAt) / 60_000.0 }.sorted()
                val median = if (mins.size % 2 == 1) mins[mins.size / 2] else (mins[mins.size / 2 - 1] + mins[mins.size / 2]) / 2
                val basis = if (pool === similar) "your ${pool.size} drives at this time" else "your ${pool.size} past drives"
                return median to basis
            }
        }
        if (route != null) {
            val dist = drives.sumOf { it.distanceM }
            val time = drives.sumOf { it.durationMs } / 1000.0
            if (dist > 20_000 && time > 0) {
                val avgMps = dist / time
                return route.distanceM / avgMps / 60.0 to "your average speed (${(avgMps * 3.6).toInt()} km/h)"
            }
            return route.freeFlowS * 1.15 / 60.0 to "road time + typical stops"
        }
        return null
    }

    fun clock(minutesFromNow: Double, now: ZonedDateTime = ZonedDateTime.now()): String {
        val t = now.plusSeconds((minutesFromNow * 60).toLong())
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        return String.format(Locale.US, "%d:%02d %s", h12, t.minute, if (t.hour < 12) "am" else "pm")
    }
}

/**
 * Reads the live, traffic-aware ETA from Waze's or Google Maps' ongoing navigation notification
 * (e.g. "Arrive 8:42 am · 23 min · 18 km"). Parsing is best-effort because those apps
 * word it differently; the raw line is kept so the car screen can still show it.
 */
object LiveNavEta {
    data class NavEta(val app: String, val arrival: String?, val minutes: Int?, val raw: String, val at: Long)

    private val PACKAGES = mapOf("com.waze" to "Waze", "com.google.android.apps.maps" to "Google Maps")
    private val state = MutableStateFlow<NavEta?>(null)
    val current: StateFlow<NavEta?> = state.asStateFlow()

    private val clockRx = Regex("""\b(\d{1,2}:\d{2})\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
    private val minRx = Regex("""\b(?:(\d+)\s*h(?:r|rs|ours?)?\s*)?(\d+)\s*min""", RegexOption.IGNORE_CASE)

    fun onPosted(sbn: StatusBarNotification) {
        val app = PACKAGES[sbn.packageName] ?: return
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val e = sbn.notification.extras
        val raw = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT), e.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" · ").trim()
        parse(app, raw, sbn.postTime)?.let { state.value = it }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        // Only the ongoing navigation notification ending means the route is over.
        if (sbn.packageName in PACKAGES && sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) state.value = null
    }

    /** Public for tests. */
    fun parse(app: String, raw: String, at: Long): NavEta? {
        if (raw.isBlank()) return null
        val clock = clockRx.find(raw)?.value?.trim()
        val m = minRx.find(raw)
        val minutes = m?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + it.groupValues[2].toInt() }
        if (clock == null && minutes == null) return null
        return NavEta(app, clock, minutes, raw, at)
    }

    /** Fresh enough to show (navigation notifications update every few seconds). */
    fun fresh(now: Long = System.currentTimeMillis()): NavEta? = state.value?.takeIf { now - it.at < 5 * 60_000 }
}

/** The personal/free-flow ETA for the current trip, filled in when you pick a destination. */
object TripEta {
    private val state = MutableStateFlow<EtaEstimate?>(null)
    val current: StateFlow<EtaEstimate?> = state.asStateFlow()
    fun set(e: EtaEstimate?) { state.value = e }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
