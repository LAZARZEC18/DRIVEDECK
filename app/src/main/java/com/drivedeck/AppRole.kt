package com.drivedeck

import android.content.Context

/**
 * DRIVEDECK ships as two apps built from this one codebase:
 *  - the main app ("direct", from GitHub): records drives in the background, voice alerts,
 *    stats. It holds all the data.
 *  - the car-screen app (from Google Play, so Android Auto lists it): only shows screens on
 *    the car display. It reads live numbers from the main app and never records anything itself,
 *    so drives are never counted twice.
 */
object AppRole {
    const val MAIN_PACKAGE = "com.lazarzec.drivedeck.direct"
    const val CAR_PACKAGE = "com.lazarzec.drivedeck"
    const val LIVE_AUTHORITY = "$MAIN_PACKAGE.live"

    fun isCarCompanion(ctx: Context): Boolean = ctx.packageName == CAR_PACKAGE
}
