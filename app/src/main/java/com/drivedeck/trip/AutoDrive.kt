package com.drivedeck.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Observer
import com.drivedeck.CrashLog
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.fuel.FuelCache
import com.drivedeck.fuel.FuelEstimate
import com.drivedeck.messages.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background mode: DRIVEDECK works without being opened in the car.
 *
 * The notification listener (needed anyway for song stats and the live ETA) is kept running by
 * Android, so it's the one place that's always awake when you plug in. It watches for Android
 * Auto connecting and then:
 *  - starts the trip computer (speed, averages, max, distance, speed camera voice alerts),
 *  - after the car has settled, reads out a fuel reminder if the tank is probably low.
 * The trip computer already ends itself 3 minutes after Android Auto disconnects.
 *
 * For Android to allow this with the app closed it needs location "Allow all the time" and
 * battery "Unrestricted". If either is missing, a notification offers one tap to record instead.
 */
object AutoDrive {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connection: CarConnection? = null
    private var observer: Observer<Int>? = null
    private var lastType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    private var pending: Job? = null

    /** Call on the main thread. Safe to call repeatedly. */
    fun watch(context: Context) {
        if (connection != null) return
        val app = context.applicationContext
        val obs = Observer<Int> { type ->
            val was = lastType
            lastType = type
            if (type == CarConnection.CONNECTION_TYPE_PROJECTION && was != CarConnection.CONNECTION_TYPE_PROJECTION) {
                try { onCarConnected(app) } catch (e: Exception) { CrashLog.caught("car connected", e) }
            }
            if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) { pending?.cancel(); cancelPrompt(app) }
        }
        connection = CarConnection(app).also { it.type.observeForever(obs) }
        observer = obs
    }

    fun unwatch() {
        observer?.let { connection?.type?.removeObserver(it) }
        connection = null; observer = null
        pending?.cancel()
    }

    private fun onCarConnected(ctx: Context) {
        val settings = DeckRepository.get(ctx).settings.value
        pending?.cancel()
        pending = scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.caught("auto drive", e) }) {
            if (settings.autoRecord && TripService.hasLocation(ctx)) {
                TripService.start(ctx)
                delay(4_000)
                if (!TripService.isRunning.value) showTapToRecord(ctx)
            }
            // Give Android Auto, Waze and the music a moment before talking.
            delay(20_000)
            if (settings.fuelReminder) remindFuelIfLow(ctx)
        }
    }

    private suspend fun remindFuelIfLow(ctx: Context) {
        val repo = DeckRepository.get(ctx)
        val prefs = ctx.getSharedPreferences("drivedeck_auto", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_FUEL, 0) < 12 * 60 * 60_000L) return
        val level = FuelEstimate.estimate(repo.fillUps.value, repo.drives.value, repo.settings.value) ?: return
        if (!FuelEstimate.isLow(level)) return
        val prices = FuelCache.refresh(ctx, repo.settings.value.fuelType)
        if (lastType != CarConnection.CONNECTION_TYPE_PROJECTION) return
        prefs.edit { putLong(KEY_LAST_FUEL, now) }
        Speaker.speak(ctx, FuelEstimate.spoken(level, prices?.let(FuelEstimate::pick)))
    }

    // ---------- What Android needs for this to work with the app closed ----------

    fun hasBackgroundLocation(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    // ---------- Fallback: one tap on the phone to record ----------

    @SuppressLint("MissingPermission") // only posted when notifications are allowed
    private fun showTapToRecord(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(NotificationChannel(PROMPT_CHANNEL, "Drive recording", NotificationManager.IMPORTANCE_DEFAULT))
        // Starting the trip computer from a notification tap is always allowed by Android.
        val tap = PendingIntent.getForegroundService(
            ctx, 7, Intent(ctx, TripService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            PROMPT_ID,
            NotificationCompat.Builder(ctx, PROMPT_CHANNEL)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle("Record this drive?")
                .setContentText("Tap to start. Set location to \"Allow all the time\" so DRIVEDECK can start by itself.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build(),
        )
    }

    fun cancelPrompt(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(PROMPT_ID)
    }

    private const val KEY_LAST_FUEL = "last_fuel_reminder"
    private const val PROMPT_CHANNEL = "drive_prompt"
    private const val PROMPT_ID = 77
}
