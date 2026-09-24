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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.Place
import com.drivedeck.location.LocationHelper
import com.drivedeck.music.YtMusicController
import com.drivedeck.nav.Geo
import com.drivedeck.nav.NavLinks
import com.drivedeck.smart.RoutinePredictor
import com.drivedeck.ui.iconRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The car's home screen: "Where to?"
 *
 *  - Tile 1 is the smart suggestion (highlighted green) when your routine makes one obvious.
 *  - The rest are your places, ordered by how likely you are to want them right now.
 *  - One tap hands the destination to the car's navigation app (Waze) and starts the drive.
 *  - Action strip: Music hub + quick play/pause.
 */
class HomeScreen(carContext: CarContext) : Screen(carContext) {

    private val repo = DeckRepository.get(carContext)
    private val predictor = RoutinePredictor()
    private val music = YtMusicController(carContext)
    private var musicWatch: AutoCloseable? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Coming back from Waze: time, location and data may all have changed.
                invalidate()
                launch { combine(repo.places, repo.trips) { _, _ -> }.drop(1).collect { invalidate() } }
                // Suggestions depend on the clock and on where you are, so refresh every minute.
                while (true) {
                    delay(60_000)
                    invalidate()
                }
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                musicWatch = music.observe { invalidate() }
            }
            override fun onStop(owner: LifecycleOwner) {
                musicWatch?.close(); musicWatch = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val places = repo.places.value
        if (places.isEmpty()) {
            return MessageTemplate.Builder("Open DRIVEDECK on your phone and add your places.")
                .setTitle("DRIVEDECK")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(CarUi.icon(carContext, R.drawable.ic_pin, CarUi.ACCENT))
                .build()
        }

        val here = LocationHelper.lastKnown(carContext)
        val atPlace = LocationHelper.placeAt(here, places)
        val now = ZonedDateTime.now()
        val ids = places.map { it.id }.toSet()
        val exclude = setOfNotNull(atPlace?.id)
        val trips = repo.trips.value

        val suggestion = predictor.suggest(trips, now, ids, exclude)
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
        ordered.take(CarUi.gridLimit(carContext)).forEach { place ->
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

        val playing = music.nowPlaying()
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Music")
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_music))
                    .setOnClickListener { screenManager.push(MusicScreen(carContext)) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarUi.icon(carContext, if (playing?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener {
                        if (!music.togglePlayPause()) {
                            CarToast.makeText(carContext, "Open Music to start something", CarToast.LENGTH_SHORT).show()
                        }
                    }
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
        repo.logTrip(place.id)
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, NavLinks.geo(place).toUri()))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
        }
    }
}
