package com.drivedeck

import android.app.Application
import com.drivedeck.sync.SyncManager

/** Starts background sync for the whole process (phone UI and Android Auto share it). */
class DriveDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncManager.get(this).start()
    }
}
