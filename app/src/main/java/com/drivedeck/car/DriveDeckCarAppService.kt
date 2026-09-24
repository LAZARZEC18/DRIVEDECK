package com.drivedeck.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to. Registered in the manifest under the POI
 * (points-of-interest) category, which lets the app show lists/grids of places on the car display
 * and hand a destination to the car's navigation app (Waze).
 */
class DriveDeckCarAppService : CarAppService() {

    // The sample allowlist ships inside the Car App Library and covers Android Auto + the desktop head unit.
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = DriveDeckSession()
}

class DriveDeckSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
}
