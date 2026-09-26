package com.drivedeck.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.drivedeck.R
import com.drivedeck.cameras.CameraAhead
import com.drivedeck.cameras.SpeedCameras
import com.drivedeck.data.DeckRepository
import com.drivedeck.messages.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.drivedeck.data.Drive
import com.drivedeck.data.Place
import com.drivedeck.nav.Geo
import com.drivedeck.stats.Fmt
import com.drivedeck.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Live numbers for the car and phone screens. */
data class LiveTrip(
    val startedAt: Long,
    val now: Long,
    val speedKmh: Double,
    val avgKmh: Double,
    val avgMovingKmh: Double,
    val maxKmh: Double,
    val distanceKm: Double,
    val movingMs: Long,
    val destination: String?,
    val arrivedAt: Long?,
    val remainingKm: Double?,
    val cameraAhead: CameraAhead? = null,
) {
    val elapsedMs: Long get() = now - startedAt
}

/**
 * The trip computer. Runs as a foreground service (so Android keeps GPS going while Waze is on
 * screen) from the moment DRIVEDECK opens on the car display until Android Auto disconnects,
 * you tap End, or the car has been parked for 20 minutes. Short hops under 300 m are discarded.
 */
class TripService : Service() {

    private lateinit var lm: LocationManager
    private var acc: TripAccumulator? = null
    private var destination: Place? = null
    private var arrivedAt: Long? = null
    private var lastNotify = 0L
    private var carConnection: CarConnection? = null
    private var disconnectedSince: Long? = null
    private var cameraAhead: CameraAhead? = null
    private val alerted = HashMap<Long, Long>()
    private var lastLoc: Location? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val listener = LocationListener { loc ->
        try { onLocation(loc) } catch (e: Exception) { com.drivedeck.CrashLog.caught("trip location", e) }
    }
    private val handler = android.os.Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            checkAutoEnd()
            if (acc != null) { publish(); handler.postDelayed(this, 30_000) }
        }
    }
    private val carObserver = Observer<Int> { type ->
        disconnectedSince = if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) System.currentTimeMillis() else null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_DESTINATION -> {
                val id = intent.getStringExtra(EXTRA_PLACE_ID)
                destination = DeckRepository.get(this).places.value.firstOrNull { it.id == id }
                    ?: intent.getStringExtra(EXTRA_PLACE_NAME)?.let { name ->
                        Place(id = "search", name = name, address = "",
                            lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN).takeIf { !it.isNaN() },
                            lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN).takeIf { !it.isNaN() })
                    }
                arrivedAt = null
            }
        }
        if (acc == null && !begin()) return START_NOT_STICKY
        publish()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin(): Boolean {
        if (!hasLocation(this)) { stopSelf(); return false }
        val notification = buildNotification("Trip started")
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Android refused (e.g. started from the background). Nothing to record.
            stopSelf(); return false
        }
        val now = System.currentTimeMillis()
        acc = TripAccumulator(now)
        running.value = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper()) }
        }
        carConnection = CarConnection(this).also { it.type.observeForever(carObserver) }
        handler.postDelayed(ticker, 30_000)
        AutoDrive.cancelPrompt(this)
        // Load speed cameras around here (cached for a week, works offline afterwards).
        scope.launch {
            val here = com.drivedeck.location.LocationHelper.lastKnown(this@TripService)
            if (here != null) SpeedCameras.near(this@TripService, here.latitude, here.longitude)
        }
        return true
    }

    private fun onLocation(loc: Location) {
        val a = acc ?: return
        a.onFix(Fix(loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(), loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 50f, if (loc.hasSpeed()) loc.speed else null))

        checkCameras(loc)

        val dest = destination
        if (dest?.hasCoords == true && arrivedAt == null) {
            val d = Geo.distanceMeters(loc.latitude, loc.longitude, dest.lat!!, dest.lng!!)
            if (d < ARRIVED_RADIUS_M) arrivedAt = System.currentTimeMillis()
        }
        publish()
        checkAutoEnd()
    }

    private fun checkCameras(loc: Location) {
        val prev = lastLoc
        lastLoc = loc
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val heading = when {
            loc.hasBearing() && speed > 3 -> loc.bearing.toDouble()
            prev != null && prev.distanceTo(loc) > 8 -> SpeedCameras.bearing(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
            else -> null
        }
        // Look further ahead at higher speed (~20 s of driving, 300–900 m).
        val range = (speed * 20).coerceIn(300.0, 900.0)
        cameraAhead = if (speed > 4) SpeedCameras.ahead(SpeedCameras.cached(), loc.latitude, loc.longitude, heading, range) else null
        val c = cameraAhead ?: return
        val now = System.currentTimeMillis()
        if (now - (alerted[c.camera.id] ?: 0) < 5 * 60_000) return
        alerted[c.camera.id] = now
        if (DeckRepository.get(this).settings.value.cameraAlerts) {
            val what = if (c.camera.redLight) "Red light camera" else "Speed camera"
            val limit = c.camera.maxSpeed?.let { ", $it zone" } ?: ""
            Speaker.speak(this, "$what ahead$limit")
        }
    }

    private fun checkAutoEnd() {
        val a = acc ?: return
        val now = System.currentTimeMillis()
        val disconnectedFor = disconnectedSince?.let { now - it } ?: 0
        val parkedFor = now - a.lastMovingAt
        if (disconnectedFor > AUTO_END_DISCONNECTED_MS || parkedFor > AUTO_END_PARKED_MS) finish()
    }

    private fun publish() {
        val a = acc ?: return
        val now = System.currentTimeMillis()
        val dest = destination
        val remaining = if (dest?.hasCoords == true && a.lastLat != null) {
            Geo.distanceMeters(a.lastLat!!, a.lastLng!!, dest.lat!!, dest.lng!!) / 1000.0
        } else null
        val live = LiveTrip(
            startedAt = a.startedAt, now = now,
            speedKmh = a.currentSpeedMps * 3.6,
            avgKmh = a.avgSpeedMps(now) * 3.6,
            avgMovingKmh = a.avgMovingSpeedMps() * 3.6,
            maxKmh = a.maxSpeedMps * 3.6,
            distanceKm = a.distanceM / 1000.0,
            movingMs = a.movingMs,
            destination = dest?.name,
            arrivedAt = arrivedAt,
            remainingKm = remaining,
            cameraAhead = cameraAhead,
        )
        state.value = live
        if (now - lastNotify > 10_000 || (cameraAhead != null && now - lastNotify > 2_000)) {
            lastNotify = now
            val text = cameraAhead?.let { "⚠ ${it.camera.label} in ${Geo.formatDistance(it.distanceM)}" }
                ?: "${Fmt.km(live.distanceKm)} · avg ${Fmt.kmh(live.avgKmh)} · max ${Fmt.kmh(live.maxKmh)}"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun finish() {
        val a = acc
        if (a != null && a.distanceM >= MIN_SAVE_DISTANCE_M) {
            val end = if (a.lastMovingAt > a.startedAt) minOf(System.currentTimeMillis(), a.lastMovingAt + 60_000) else System.currentTimeMillis()
            val drive = Drive(
                id = UUID.randomUUID().toString(),
                startedAt = a.startedAt,
                endedAt = end,
                distanceM = a.distanceM,
                movingMs = a.movingMs,
                maxSpeedMps = a.maxSpeedMps,
                destination = destination?.name,
                arrivedAt = arrivedAt,
            )
            DeckRepository.get(this).addDrive(drive)
            showSummary(drive)
        }
        acc = null
        handler.removeCallbacks(ticker)
        cameraAhead = null; lastLoc = null
        runCatching { lm.removeUpdates(listener) }
        carConnection?.type?.removeObserver(carObserver)
        state.value = null
        running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (acc != null) finish()
        scope.cancel()
        super.onDestroy()
    }

    /** Quiet "drive saved" card, so you see your numbers without opening the app. */
    private fun showSummary(d: Drive) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL_ID, "Drive summaries", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val line = "${Fmt.km(d.distanceM / 1000.0)} · ${Fmt.duration(d.durationMs)} · avg ${Fmt.kmh(d.avgSpeedMps * 3.6)} · max ${Fmt.kmh(d.maxSpeedMps * 3.6)}"
        nm.notify(
            SUMMARY_ID,
            NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle(d.destination?.let { "Drive to $it saved" } ?: "Drive saved")
                .setContentText(line)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle("DRIVEDECK trip computer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "End trip", stop)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "trip"
        private const val SUMMARY_CHANNEL_ID = "drive_summary"
        private const val SUMMARY_ID = 43
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.drivedeck.trip.STOP"
        private const val ACTION_DESTINATION = "com.drivedeck.trip.DESTINATION"
        private const val EXTRA_PLACE_ID = "place_id"
        private const val EXTRA_PLACE_NAME = "place_name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val ARRIVED_RADIUS_M = 150.0
        private const val MIN_SAVE_DISTANCE_M = 300.0
        private const val AUTO_END_DISCONNECTED_MS = 3 * 60_000L
        private const val AUTO_END_PARKED_MS = 20 * 60_000L

        private val state = MutableStateFlow<LiveTrip?>(null)
        private val running = MutableStateFlow(false)
        val live: StateFlow<LiveTrip?> = state.asStateFlow()
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts the trip computer if it isn't running. Safe to call repeatedly. */
        fun start(ctx: Context) {
            if (running.value || !hasLocation(ctx)) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java)) }
        }

        /** Sets where you're heading, so the trip records how long it took to get there. */
        fun setDestination(ctx: Context, place: Place) {
            if (!hasLocation(ctx)) return
            val i = Intent(ctx, TripService::class.java).setAction(ACTION_DESTINATION)
                .putExtra(EXTRA_PLACE_ID, place.id).putExtra(EXTRA_PLACE_NAME, place.name)
            place.lat?.let { i.putExtra(EXTRA_LAT, it) }; place.lng?.let { i.putExtra(EXTRA_LNG, it) }
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            if (!running.value) return
            runCatching { ctx.startService(Intent(ctx, TripService::class.java).setAction(ACTION_STOP)) }
        }

        fun ensureChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trip computer", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
