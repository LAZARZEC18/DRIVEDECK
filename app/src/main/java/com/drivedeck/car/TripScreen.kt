@file:Suppress("DEPRECATION")

package com.drivedeck.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.eta.EtaEngine
import com.drivedeck.eta.LiveNavEta
import com.drivedeck.eta.TripEta
import com.drivedeck.stats.Fmt
import com.drivedeck.trip.LiveTrip
import com.drivedeck.trip.TripService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Trip computer on the car display: live speed, average (overall and moving), max speed,
 * distance and time. When you're navigating it also shows three ETAs: Waze/Maps with traffic,
 * yours from your own driving history, and the no-traffic time.
 * Row titles stay fixed and only the numbers change, so Android Auto treats each update as a refresh.
 */
class TripScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { invalidate(); delay(2_000) }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val live = TripService.live.value
        val pane = Pane.Builder()
        if (live == null) {
            pane.addRow(row("Trip computer", "Not running", "Starts by itself when DRIVEDECK opens on the car screen"))
            pane.addAction(
                Action.Builder().setTitle("Start").setBackgroundColor(CarUi.ACCENT).setOnClickListener {
                    if (!TripService.hasLocation(carContext)) {
                        CarToast.makeText(carContext, "Allow location in the phone app first", CarToast.LENGTH_LONG).show()
                    } else TripService.start(carContext)
                    invalidate()
                }.build(),
            )
        } else {
            val cam = live.cameraAhead
            pane.addRow(
                row(
                    "Speed", "${Fmt.kmh(live.speedKmh)} now",
                    cam?.let { "⚠ ${it.camera.label} in ${com.drivedeck.nav.Geo.formatDistance(it.distanceM)}" } ?: "Max ${Fmt.kmh(live.maxKmh)}",
                    R.drawable.ic_speed,
                ),
            )
            pane.addRow(row("Average", "${Fmt.kmh(live.avgKmh)} overall", "${Fmt.kmh(live.avgMovingKmh)} while moving", R.drawable.ic_chart))
            pane.addRow(
                row(
                    "Trip", "${Fmt.km(live.distanceKm)} · ${Fmt.duration(live.elapsedMs)}",
                    "Moving ${Fmt.duration(live.movingMs)} · stopped ${Fmt.duration(live.elapsedMs - live.movingMs)}",
                    R.drawable.ic_road,
                ),
            )
            etaRow(live)?.let(pane::addRow)
            pane.addAction(
                Action.Builder().setTitle("End trip").setOnClickListener {
                    TripService.stop(carContext)
                    CarToast.makeText(carContext, "Trip saved to your stats", CarToast.LENGTH_SHORT).show()
                    invalidate()
                }.build(),
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Trip")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun etaRow(live: LiveTrip): Row? {
        val dest = live.destination ?: return null
        if (live.arrivedAt != null) {
            val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(live.arrivedAt), ZoneId.systemDefault())
            return row("Arrival", "Arrived at $dest", "Took ${Fmt.duration(live.arrivedAt - live.startedAt)} · ${EtaEngine.clock(0.0, at)}", R.drawable.ic_clock)
        }
        val traffic = LiveNavEta.fresh()?.let { n ->
            n.arrival?.let { "Traffic ${it.lowercase()} (${n.app})" } ?: n.minutes?.let { "Traffic ${EtaEngine.clock(it.toDouble())} (${n.app})" }
        }
        val est = TripEta.current.value
        val minsSinceCalc = est?.let { (System.currentTimeMillis() - it.computedAt) / 60_000.0 } ?: 0.0
        val yours = est?.personalMin?.let { "Yours ${EtaEngine.clock(it - minsSinceCalc)}" }
        val free = est?.freeFlowMin?.let { "no traffic ${EtaEngine.clock(it - minsSinceCalc)}" }
        val remaining = live.remainingKm?.let { "${Fmt.km(it)} to go" }
        val line1 = listOfNotNull(traffic, yours).joinToString(" · ").ifBlank { "To $dest" }
        val line2 = listOfNotNull(remaining, free).joinToString(" · ").ifBlank { dest }
        return row("Arrival", line1, line2, R.drawable.ic_clock)
    }

    private fun row(title: String, line1: String, line2: String? = null, icon: Int? = null): Row =
        Row.Builder().setTitle(title).addText(line1).apply {
            line2?.let { addText(it) }
            icon?.let { setImage(CarUi.icon(carContext, it, CarColor.DEFAULT)) }
        }.build()
}
