package com.drivedeck.trip

import com.drivedeck.nav.Geo

/** One GPS reading. [speedMps] is the chip's own Doppler speed when available (more accurate). */
data class Fix(val t: Long, val lat: Double, val lng: Double, val accuracyM: Float, val speedMps: Float?)

/**
 * The trip computer's maths: turns a stream of GPS fixes into distance, moving time and
 * current/max speed, while filtering out GPS noise:
 *  - fixes worse than 30 m accuracy are ignored,
 *  - jitter while parked (tiny moves inside the accuracy circle) doesn't add distance,
 *  - impossible jumps (> 250 km/h) are treated as glitches,
 *  - gaps in the signal (tunnels) don't count as moving time.
 */
class TripAccumulator(val startedAt: Long) {
    var distanceM = 0.0; private set
    var movingMs = 0L; private set
    var maxSpeedMps = 0.0; private set
    var currentSpeedMps = 0.0; private set
    var lastFixAt = startedAt; private set
    /** Last time we were actually moving (for auto-ending a trip after a long stop). */
    var lastMovingAt = startedAt; private set
    var lastLat: Double? = null; private set
    var lastLng: Double? = null; private set

    private var last: Fix? = null

    fun onFix(fix: Fix) {
        if (fix.accuracyM > MAX_ACCURACY_M) return
        val prev = last
        last = fix
        lastFixAt = fix.t
        lastLat = fix.lat; lastLng = fix.lng
        if (prev == null) {
            currentSpeedMps = (fix.speedMps ?: 0f).toDouble()
            return
        }
        val dtMs = fix.t - prev.t
        if (dtMs <= 0) return
        val d = Geo.distanceMeters(prev.lat, prev.lng, fix.lat, fix.lng)
        val derived = d / (dtMs / 1000.0)
        val speed = (fix.speedMps?.toDouble() ?: derived)
        if (speed > MAX_PLAUSIBLE_MPS || derived > MAX_PLAUSIBLE_MPS) return // glitch
        currentSpeedMps = speed

        val moving = speed >= MOVING_MPS
        val jitter = !moving && d < fix.accuracyM
        if (!jitter) distanceM += d
        if (moving) {
            movingMs += dtMs.coerceAtMost(MAX_GAP_MS)
            lastMovingAt = fix.t
        }
        // Only trust a new top speed from a good fix, so one bad reading can't set it.
        if (fix.accuracyM <= 20f && speed > maxSpeedMps) maxSpeedMps = speed
    }

    fun avgSpeedMps(now: Long): Double {
        val s = (now - startedAt) / 1000.0
        return if (s > 0) distanceM / s else 0.0
    }

    fun avgMovingSpeedMps(): Double = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    companion object {
        const val MAX_ACCURACY_M = 30f
        const val MOVING_MPS = 1.5          // ~5 km/h
        const val MAX_PLAUSIBLE_MPS = 70.0  // ~250 km/h
        const val MAX_GAP_MS = 10_000L
    }
}
