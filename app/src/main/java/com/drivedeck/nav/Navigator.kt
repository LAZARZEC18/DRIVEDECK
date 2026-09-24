package com.drivedeck.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.drivedeck.data.Place
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Builds navigation links. Pure string functions so they can be unit-tested without a device. */
object NavLinks {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun coord(d: Double) = String.format(Locale.US, "%.6f", d)

    /**
     * geo: URI for Android Auto's ACTION_NAVIGATE. The car host hands it to whichever navigation
     * app you picked in Android Auto (set that to Waze). Coordinates start navigation straight away.
     * An address makes the nav app search first.
     */
    fun geo(place: Place): String =
        if (place.hasCoords) "geo:${coord(place.lat!!)},${coord(place.lng!!)}"
        else "geo:0,0?q=${enc(place.address.ifBlank { place.name })}"

    /** Waze deep link used from the phone. `navigate=yes` skips the preview and starts driving. */
    fun waze(place: Place): String =
        if (place.hasCoords) "https://waze.com/ul?ll=${coord(place.lat!!)},${coord(place.lng!!)}&navigate=yes"
        else "https://waze.com/ul?q=${enc(place.address.ifBlank { place.name })}&navigate=yes"
}

object Geo {
    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun formatDistance(m: Double): String = when {
        m < 1000 -> "${(m / 50).toInt() * 50} m"
        m < 10_000 -> String.format(Locale.US, "%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }
}

/** Phone-side navigation: opens Waze directly (falls back to the browser link if Waze isn't installed). */
object PhoneNavigator {
    fun openInWaze(context: Context, place: Place) {
        val intent = Intent(Intent.ACTION_VIEW, NavLinks.waze(place).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(intent).setPackage(WAZE_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
        }
    }

    const val WAZE_PACKAGE = "com.waze"
}
