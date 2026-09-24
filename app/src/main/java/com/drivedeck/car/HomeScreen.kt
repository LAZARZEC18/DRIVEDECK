@file:Suppress("DEPRECATION") // setTitle/setActionStrip: the non-deprecated Header API needs car API 7; this works on every car.

package com.drivedeck.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.Place
import com.drivedeck.location.LocationHelper
import com.drivedeck.nav.Geo
import com.drivedeck.nav.NavLinks
import com.drivedeck.smart.RoutinePredictor
import com.drivedeck.stats.Fmt
import com.drivedeck.sync.SyncManager
import com.drivedeck.trip.Journey
import com.drivedeck.trip.TripService
import com.drivedeck.ui.iconRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The car's home screen: "Where to?"
 *
 *  - Tile 1 is the smart suggestion (highlighted green): a place you sent to the car from the
 *    laptop/chat, or else the one your routine makes obvious.
 *  - The rest are your places, ordered by how likely you are to want them right now.
 *  - One tap hands the destination to the car's navigation app (Waze) and starts the drive.
 *  - Last tile "More": trip computer, fuel prices, WhatsApp, weekly stats, recent songs.
 *  - Action strip: search any destination + Music.
 *  - Opening this screen starts the trip computer.
 */
class HomeScreen(carContext: CarContext) : Screen(carContext) {

    private val repo = DeckRepository.get(carContext)
    private val predictor = RoutinePredictor()
    private val sync = SyncManager.get(carContext)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Coming back from Waze: time, location and data may all have changed.
                invalidate()
                sync.requestSync() // pick up anything sent from the laptop or chat
                TripService.start(carContext) // every drive gets recorded
                launch { combine(repo.places, repo.trips, repo.nextUp) { _, _, _ -> }.drop(1).collect { invalidate() } }
                // Live trip numbers on the More tile, and suggestions that follow the clock.
                while (true) {
                    delay(if (TripService.isRunning.value) 5_000 else 60_000)
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val places = repo.places.value
        if (places.isEmpty()) {
            return MessageTemplate.Builder("Open DRIVEDECK on your phone and add your places.")
                .setTitle("DRIVEDECK")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(CarUi.icon(carContext, R.drawable.ic_pin, CarUi.ACCENT))
                .addAction(Action.Builder().setTitle("More").setOnClickListener { screenManager.push(HubScreen(carContext)) }.build())
                .build()
        }

        val here = LocationHelper.lastKnown(carContext)
        val atPlace = LocationHelper.placeAt(here, places)
        val now = ZonedDateTime.now()
        val ids = places.map { it.id }.toSet()
        val exclude = setOfNotNull(atPlace?.id)
        val trips = repo.trips.value

        // A destination queued from the laptop/chat ("Send to car") beats the learned routine.
        val queuedId = repo.nextUp.value?.activePlaceId(System.currentTimeMillis())?.takeIf { it in ids }
        val suggestion = queuedId?.let { RoutinePredictor.Suggestion(it, 1.0, 1.0, "Sent to car") }
            ?: predictor.suggest(trips, now, ids, exclude)
        val rank = predictor.rank(trips, now, ids, exclude).withIndex().associate { it.value.placeId to it.index }

        // Suggested first, then by likelihood right now, then in your own order. The place you're
        // standing at goes last.
        val ordered = places.withIndex().sortedWith(
            compareBy<IndexedValue<Place>>(
                { if (it.value.id == suggestion?.placeId) 0 else 1 },
                { if (it.value.id == atPlace?.id) 1 else 0 },
                { rank[it.value.id] ?: Int.MAX_VALUE },
                { it.index },
            ),
        ).map { it.value }

        val items = ItemList.Builder()
        ordered.take(CarUi.gridLimit(carContext) - 1).forEach { place ->
            val isSuggested = place.id == suggestion?.placeId
            val distance = LocationHelper.distanceTo(here, place)?.let(Geo::formatDistance)
            val text = when {
                isSuggested -> listOfNotNull(suggestion!!.reason, distance).joinToString(" · ")
                place.id == atPlace?.id -> "You're here"
                distance != null -> distance
                else -> place.address.substringBefore(',').ifBlank { null }
            }
            items.addItem(
                GridItem.Builder()
                    .setTitle(place.name)
                    .apply { if (text != null) setText(text) }
                    .setImage(
                        CarUi.icon(
                            carContext,
                            if (isSuggested) R.drawable.ic_smart else place.icon.iconRes(),
                            if (isSuggested) CarUi.ACCENT else CarColor.DEFAULT,
                        ),
                        GridItem.IMAGE_TYPE_LARGE,
                    )
                    .setOnClickListener { navigateTo(place) }
                    .build(),
            )
        }

        val live = TripService.live.value
        items.addItem(
            GridItem.Builder()
                .setTitle("More")
                .setText(live?.let { "${Fmt.kmh(it.speedKmh)} · avg ${Fmt.kmh(it.avgKmh)}" } ?: "Trip · Fuel · WhatsApp · Stats")
                .setImage(CarUi.icon(carContext, R.drawable.ic_apps, CarColor.DEFAULT), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(HubScreen(carContext)) }
                .build(),
        )

        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_search))
                    .setOnClickListener { screenManager.push(PlaceSearchScreen(carContext)) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Music")
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_music))
                    .setOnClickListener { screenManager.push(MusicScreen(carContext)) }
                    .build(),
            )
            .build()

        return GridTemplate.Builder()
            .setTitle("Where to?")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(actions)
            .build()
    }

    private fun navigateTo(place: Place) {
        Journey.begin(carContext, place)
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, NavLinks.geo(place).toUri()))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
        }
    }
}
