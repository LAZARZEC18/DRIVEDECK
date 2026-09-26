package com.drivedeck.live

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import com.drivedeck.AppRole
import com.drivedeck.CrashLog
import com.drivedeck.data.DeckJson
import com.drivedeck.data.DeckRepository
import com.drivedeck.eta.LiveNavEta
import com.drivedeck.eta.Pace
import com.drivedeck.location.LocationHelper
import com.drivedeck.trip.TripService
import org.json.JSONObject
import java.security.MessageDigest

/** Everything the car screen shows, in one snapshot. */
data class LiveData(
    val recording: Boolean,
    val speedKmh: Double?,
    val avgKmh: Double?,
    val maxKmh: Double?,
    val distanceKm: Double?,
    val lat: Double?,
    val lng: Double?,
    val wazeApp: String?,
    val wazeArrival: String?,
    val wazeMinutes: Int?,
    val paceFactor: Double?,
    val paceSamples: Int,
    val cameraLabel: String?,
    val cameraDistanceM: Double?,
) {
    val pace: Pace.Factor? get() = paceFactor?.let { Pace.Factor(it, paceSamples) }
    val yourMinutes: Double? get() = wazeMinutes?.let { Pace.yourMinutes(it, pace) }

    fun toJson(): String = JSONObject().apply {
        put("recording", recording)
        speedKmh?.let { put("speed", it) }; avgKmh?.let { put("avg", it) }; maxKmh?.let { put("max", it) }
        distanceKm?.let { put("km", it) }; lat?.let { put("lat", it) }; lng?.let { put("lng", it) }
        wazeApp?.let { put("navApp", it) }; wazeArrival?.let { put("arrival", it) }; wazeMinutes?.let { put("navMin", it) }
        paceFactor?.let { put("pace", it) }; put("paceN", paceSamples)
        cameraLabel?.let { put("cam", it) }; cameraDistanceM?.let { put("camM", it) }
    }.toString()

    companion object {
        val EMPTY = LiveData(false, null, null, null, null, null, null, null, null, null, null, 0, null, null)

        fun fromJson(s: String): LiveData {
            val o = JSONObject(s)
            fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
            fun t(k: String) = if (o.has(k)) o.getString(k) else null
            return LiveData(
                recording = o.optBoolean("recording"), speedKmh = d("speed"), avgKmh = d("avg"), maxKmh = d("max"),
                distanceKm = d("km"), lat = d("lat"), lng = d("lng"),
                wazeApp = t("navApp"), wazeArrival = t("arrival"), wazeMinutes = if (o.has("navMin")) o.getInt("navMin") else null,
                paceFactor = d("pace"), paceSamples = o.optInt("paceN"), cameraLabel = t("cam"), cameraDistanceM = d("camM"),
            )
        }

        /** Built inside the main app, which is the one recording. */
        fun local(ctx: Context): LiveData {
            val trip = TripService.live.value
            val nav = LiveNavEta.fresh()
            val pace = Pace.factor(DeckRepository.get(ctx).drives.value)
            val here = LocationHelper.lastKnown(ctx)
            return LiveData(
                recording = trip != null,
                speedKmh = trip?.speedKmh, avgKmh = trip?.avgKmh, maxKmh = trip?.maxKmh, distanceKm = trip?.distanceKm,
                lat = here?.latitude, lng = here?.longitude,
                wazeApp = nav?.app, wazeArrival = nav?.arrival, wazeMinutes = nav?.minutes,
                paceFactor = pace?.factor, paceSamples = pace?.samples ?: 0,
                cameraLabel = trip?.cameraAhead?.camera?.label, cameraDistanceM = trip?.cameraAhead?.distanceM,
            )
        }
    }
}

/**
 * Where the car screen gets its numbers. In the car-screen app it asks the main app (which is
 * recording); anywhere else it reads them directly.
 */
object LiveClient {
    sealed interface Result {
        data class Ok(val data: LiveData) : Result
        data object MainAppMissing : Result
    }

    private val uri = Uri.parse("content://${AppRole.LIVE_AUTHORITY}")

    fun fetch(ctx: Context): Result {
        if (!AppRole.isCarCompanion(ctx)) return Result.Ok(LiveData.local(ctx))
        return try {
            val b = ctx.contentResolver.call(uri, LiveProvider.METHOD_LIVE, null, null) ?: return Result.MainAppMissing
            Result.Ok(LiveData.fromJson(b.getString(LiveProvider.KEY_JSON) ?: return Result.MainAppMissing))
        } catch (e: Exception) {
            Result.MainAppMissing
        }
    }

    /** Copies places, drives and settings from the main app, so the car screen has them without its own sync. */
    fun mirrorDeck(ctx: Context) {
        if (!AppRole.isCarCompanion(ctx)) return
        runCatching {
            val b = ctx.contentResolver.call(uri, LiveProvider.METHOD_DECK, null, null) ?: return
            val json = b.getString(LiveProvider.KEY_JSON) ?: return
            DeckRepository.get(ctx).applySynced(DeckJson.decode(json))
        }.onFailure { CrashLog.caught("mirror deck", it) }
    }
}

/**
 * Lives in the main app. Answers only DRIVEDECK's own car-screen app (checked by package name
 * and signing certificate), so no other app can read your speed or location.
 */
class LiveProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!callerAllowed(ctx)) return null
        return try {
            when (method) {
                METHOD_LIVE -> Bundle().apply { putString(KEY_JSON, LiveData.local(ctx).toJson()) }
                METHOD_DECK -> Bundle().apply { putString(KEY_JSON, DeckJson.encode(DeckRepository.get(ctx).state.value)) }
                else -> null
            }
        } catch (e: Exception) {
            CrashLog.caught("live provider", e); null
        }
    }

    private fun callerAllowed(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        return pkgs.any { pkg -> pkg == AppRole.CAR_PACKAGE && certs(pm, pkg).any { it in trusted(pm, ctx.packageName) } }
    }

    /** Our own certificate (dev builds) plus the Google Play app-signing certificate of the car-screen app. */
    private fun trusted(pm: PackageManager, self: String): Set<String> = certs(pm, self).toSet() + PLAY_SIGNING_SHA256

    @Suppress("DEPRECATION")
    private fun certs(pm: PackageManager, pkg: String): List<String> = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }
    }.getOrDefault(emptyList())

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val METHOD_LIVE = "live"
        const val METHOD_DECK = "deck"
        const val KEY_JSON = "json"
        /** Google Play app-signing key of com.lazarzec.drivedeck (Play Console → App integrity). */
        val PLAY_SIGNING_SHA256: Set<String> = setOf(
            "CCCE79D550BAA5CC633E3016C208BC10FE13169A52933E95C666A73AAE890553", // Play app signing key
            "11B29FF88E12294E89DD944179394246858643A1257F1F1D20902A3155585C0D", // upload key (local test builds)
        )
    }
}
