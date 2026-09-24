@file:Suppress("DEPRECATION") // setTitle/setHeaderAction: compatible with every car API level.

package com.drivedeck.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.fuel.FuelCache
import com.drivedeck.messages.MessageHub
import com.drivedeck.stats.Fmt
import com.drivedeck.stats.StatsEngine
import com.drivedeck.trip.TripService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** "More": everything beyond destinations, each tile showing a live number. */
class HubScreen(carContext: CarContext) : Screen(carContext) {

    private val repo = DeckRepository.get(carContext)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MessageHub.conversations.drop(1).collect { invalidate() } }
                launch { FuelCache.prices.drop(1).collect { invalidate() } }
                FuelCache.refreshIfStale(carContext, repo.settings.value.fuelType)
                while (true) { delay(5_000); invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val live = TripService.live.value
        val week = StatsEngine.week(repo.state.value, ZonedDateTime.now())
        val chats = MessageHub.conversations.value
        val fuel = FuelCache.prices.value?.cheapest

        fun tile(title: String, text: String, icon: Int, accent: Boolean = false, onClick: () -> Unit) =
            GridItem.Builder()
                .setTitle(title)
                .setText(text)
                .setImage(CarUi.icon(carContext, icon, if (accent) CarUi.ACCENT else CarColor.DEFAULT), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener(onClick)
                .build()

        val items = ItemList.Builder()
            .addItem(
                tile(
                    "Trip",
                    live?.let { "${Fmt.kmh(it.speedKmh)} · ${Fmt.km(it.distanceKm)}" } ?: "Start trip computer",
                    R.drawable.ic_speed, accent = live != null,
                ) { screenManager.push(TripScreen(carContext)) },
            )
            .addItem(
                tile("Fuel", fuel?.let { "${it.centsPerLitre}¢ · ${it.name}" } ?: "Cheapest nearby", R.drawable.ic_fuel) {
                    screenManager.push(FuelScreen(carContext))
                },
            )
            .addItem(
                tile(
                    "WhatsApp",
                    if (chats.isEmpty()) "No new messages" else "${chats.size} chat${if (chats.size == 1) "" else "s"} · ${chats.sumOf { it.unread }} new",
                    R.drawable.ic_chat, accent = chats.isNotEmpty(),
                ) { screenManager.push(MessagesScreen(carContext)) },
            )
            .addItem(
                tile("This week", "${Fmt.km(week.distanceKm)} · ${week.drives} drives", R.drawable.ic_chart) {
                    screenManager.push(WeekScreen(carContext))
                },
            )
            .addItem(
                tile("Recent songs", "${repo.recents.value.size} songs", R.drawable.ic_history) {
                    screenManager.push(RecentSongsScreen(carContext))
                },
            )
            .addItem(
                tile(
                    "Cameras",
                    live?.cameraAhead?.let { "⚠ ${com.drivedeck.nav.Geo.formatDistance(it.distanceM)} ahead" } ?: "Speed & red-light",
                    R.drawable.ic_camera, accent = live?.cameraAhead != null,
                ) { screenManager.push(CamerasScreen(carContext)) },
            )

        return GridTemplate.Builder()
            .setTitle("DRIVEDECK")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
