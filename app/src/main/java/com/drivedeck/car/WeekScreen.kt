@file:Suppress("DEPRECATION")

package com.drivedeck.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.stats.Fmt
import com.drivedeck.stats.StatsEngine
import java.time.ZonedDateTime

/** This week at a glance, compared with last week. The full review is in the phone app and on the dashboard. */
class WeekScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = DeckRepository.get(carContext)

    override fun onGetTemplate(): Template {
        val now = ZonedDateTime.now()
        val w = StatsEngine.week(repo.state.value, now)
        val prev = StatsEngine.week(repo.state.value, now, 1)
        val kmChange = Fmt.pct(StatsEngine.change(w.distanceKm, prev.distanceKm))

        fun row(title: String, a: String, b: String, icon: Int) =
            Row.Builder().setTitle(title).addText(a).addText(b).setImage(CarUi.icon(carContext, icon, CarColor.DEFAULT)).build()

        val pane = Pane.Builder()
            .addRow(row("Driving", "${Fmt.km(w.distanceKm)} · ${w.drives} drives${kmChange?.let { " · $it vs last week" } ?: ""}",
                "${Fmt.duration(w.drivingMs)} behind the wheel · ${w.daysWithDriving} of 7 days", R.drawable.ic_road))
            .addRow(row("Speed", "Average ${Fmt.kmh(w.avgSpeedKmh)} · moving ${Fmt.kmh(w.avgMovingSpeedKmh)}",
                "Top speed ${Fmt.kmh(w.maxSpeedKmh)} · longest ${Fmt.km(w.longestDriveKm)}", R.drawable.ic_speed))
            .addRow(row("Fuel", if (w.fillUps > 0) "${Fmt.money(w.fuelDollars)} on ${w.fillUps} fill-up${if (w.fillUps == 1) "" else "s"}" else "No fill-ups logged",
                "Driving cost ≈ ${Fmt.money(w.estFuelCostDollars)}", R.drawable.ic_fuel))
            .addRow(row("Music", "${w.songsPlayed} songs played",
                w.topSongs.firstOrNull()?.let { "Top: ${it.first} (${it.second}×)" } ?: "Top song shows here", R.drawable.ic_music))
        return PaneTemplate.Builder(pane.build()).setTitle("This week").setHeaderAction(Action.BACK).build()
    }
}
