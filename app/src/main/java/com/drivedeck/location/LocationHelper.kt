package com.drivedeck.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.drivedeck.data.Place
import com.drivedeck.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {

    /** How close counts as "you're already here" (so we don't suggest driving to where you are). */
    const val AT_PLACE_RADIUS_M = 300.0

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Precise location. Needed to pin a place ("approximate" can be ~2 km off). */
    fun hasFinePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Freshest cached fix from any provider. Instant and battery-free; null if we have nothing. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        }
    }

    /** The saved place you're currently at, if any. */
    fun placeAt(location: Location?, places: List<Place>): Place? {
        location ?: return null
        return places.filter { it.hasCoords }
            .map { it to Geo.distanceMeters(location.latitude, location.longitude, it.lat!!, it.lng!!) }
            .filter { it.second <= AT_PLACE_RADIUS_M }
            .minByOrNull { it.second }?.first
    }

    fun distanceTo(location: Location?, place: Place): Double? {
        if (location == null || !place.hasCoords) return null
        return Geo.distanceMeters(location.latitude, location.longitude, place.lat!!, place.lng!!)
    }

    /** A fresh fix (up to ~10 s), falling back to the last known one. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                val fresh = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        try {
                            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                                if (cont.isActive) cont.resume(loc)
                            }
                        } catch (_: SecurityException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return lastKnown(context)
    }

    /** Address -> coordinates using Android's built-in geocoder. Null if offline or not found. */
    @Suppress("DEPRECATION")
    suspend fun geocode(context: Context, address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (address.isBlank() || !Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(address, 1)
                ?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** Coordinates -> a readable one-line address. */
    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                ?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
    }
}
