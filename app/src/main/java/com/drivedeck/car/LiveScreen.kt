@file:Suppress("DEPRECATION") // setTitle/setHeaderAction: compatible with every car API level.

package com.drivedeck.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.eta.EtaEngine
import com.drivedeck.live.LiveClient
import com.drivedeck.live.LiveData
import com.drivedeck.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The car screen's home: Waze's ETA next to *your* ETA, your speed, and the next camera.
 * One tap away from Waze in Android Auto's side bar. Waze keeps guiding you by voice.
 * Row titles never change (only the numbers do), so Android Auto treats updates as refreshes.
 */
class LiveScreen(carContext: CarContext) : Screen(carContext) {

    private var result: LiveClient.Result? = null

    init {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { LiveClient.mirrorDeck(carContext) }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    result = withContext(Dispatchers.IO) { LiveClient.fetch(carContext) }
                    invalidate()
                    delay(2_000)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val r = result
        if (r == null) {
            return PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
                .setTitle("DRIVEDECK").setHeaderAction(Action.APP_ICON).build()
        }
        if (r is LiveClient.Result.MainAppMissing) {
            return MessageTemplate.Builder("Open the main DRIVEDECK app on your phone once. The car screen reads your speed and ETA from it.")
                .setTitle("DRIVEDECK").setHeaderAction(Action.APP_ICON)
                .setIcon(CarUi.icon(carContext, R.drawable.ic_warn, CarColor.YELLOW))
                .addAction(Action.Builder().setTitle("Camera map").setOnClickListener { screenManager.push(CamerasScreen(carContext)) }.build())
                .build()
        }
        val d = (r as LiveClient.Result.Ok).data
        val pane = Pane.Builder()
        pane.addRow(wazeRow(d))
        pane.addRow(yourRow(d))
        pane.addRow(speedRow(d))
        pane.addRow(cameraRow(d))
        pane.addAction(
            Action.Builder().setTitle("Camera map").setBackgroundColor(CarUi.ACCENT)
                .setOnClickListener { screenManager.push(CamerasScreen(carContext)) }.build(),
        )
        pane.addAction(Action.Builder().setTitle("Places").setOnClickListener { screenManager.push(HomeScreen(carContext)) }.build())
        return PaneTemplate.Builder(pane.build())
            .setTitle("DRIVEDECK")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun wazeRow(d: LiveData): Row {
        val app = d.wazeApp ?: "Waze"
        return row(
            "$app ETA",
            when {
                d.wazeArrival != null -> d.wazeArrival
                d.wazeMinutes != null -> EtaEngine.clock(d.wazeMinutes.toDouble())
                else -> "Not navigating"
            },
            d.wazeMinutes?.let { "${fmtMin(it.toDouble())} · with live traffic" } ?: "Start a route in Waze",
            R.drawable.ic_road,
        )
    }

    private fun yourRow(d: LiveData): Row {
        val mins = d.yourMinutes
        val pace = d.pace
        return row(
            "Your ETA",
            when {
                mins != null -> EtaEngine.clock(mins)
                d.wazeMinutes == null -> "—"
                else -> "Learning your pace"
            },
            when {
                mins != null && pace != null -> "${fmtMin(mins)} · ${pace.describe}"
                pace != null -> "${pace.describe} (${pace.samples} drives)"
                else -> "Learns from your drives with Waze (needs 2)"
            },
            R.drawable.ic_smart,
        )
    }

    private fun speedRow(d: LiveData): Row = if (d.speedKmh == null) {
        row("Speed", "Not recording", "Starts by itself when Android Auto connects", R.drawable.ic_speed)
    } else {
        row(
            "Speed", "${d.speedKmh.roundToInt()} km/h",
            "avg ${d.avgKmh?.roundToInt() ?: 0} · max ${d.maxKmh?.roundToInt() ?: 0} · ${"%.1f".format(d.distanceKm ?: 0.0)} km",
            R.drawable.ic_speed,
        )
    }

    private fun cameraRow(d: LiveData): Row = row(
        "Camera",
        d.cameraDistanceM?.let { "In ${Geo.formatDistance(it)}" } ?: "None ahead",
        d.cameraLabel ?: "Fixed & red-light cameras on your road",
        R.drawable.ic_camera,
    )

    private fun row(title: String, value: String, detail: String, icon: Int): Row =
        Row.Builder()
            .setTitle(title)
            .addText(value)
            .addText(detail)
            .setImage(CarUi.icon(carContext, icon, CarUi.ACCENT))
            .build()

    private fun fmtMin(m: Double): String {
        val t = m.roundToInt()
        return if (t < 60) "$t min" else "${t / 60} h ${t % 60} min"
    }
}
