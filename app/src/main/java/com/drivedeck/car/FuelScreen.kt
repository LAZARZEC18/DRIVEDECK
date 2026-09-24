@file:Suppress("DEPRECATION")

package com.drivedeck.car

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place as CarPlace
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import com.drivedeck.fuel.FuelCache
import com.drivedeck.fuel.FuelPrices
import com.drivedeck.nav.NavLinks
import com.drivedeck.trip.Journey
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Cheapest fuel near you, live from FuelWatch, on a map. Tap a servo to drive there.
 * "Tomorrow" shows tomorrow's locked-in prices (published 2:30 pm), so you know whether to fill
 * up today or wait.
 */
class FuelScreen(carContext: CarContext) : Screen(carContext) {

    private val repo = DeckRepository.get(carContext)
    private var tomorrow = false
    private var loading = true
    private var prices: FuelPrices? = FuelCache.prices.value

    init { load() }

    private fun load() {
        loading = true
        invalidate()
        lifecycleScope.launch {
            prices = FuelCache.refresh(carContext, repo.settings.value.fuelType, tomorrow) ?: prices
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val type = repo.settings.value.fuelType
        val b = PlaceListMapTemplate.Builder()
            .setTitle("${type.label} · ${if (tomorrow) "tomorrow" else "today"}")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle(if (tomorrow) "Today" else "Tomorrow").setOnClickListener {
                        tomorrow = !tomorrow; load()
                    }.build(),
                ).build(),
            )
        val p = prices
        if (loading && p == null) return b.setLoading(true).build()

        val list = ItemList.Builder().setNoItemsMessage("No prices found. Check your internet connection")
        val cheapest = p?.cheapest?.centsPerLitre
        p?.stations?.take(CarUi.listLimit(carContext))?.forEach { s ->
            val title = String.format(Locale.US, "%.1f¢ · %s", s.centsPerLitre, s.name)
            val detail = SpannableString("  · ${s.address}, ${s.suburb}" + if (s.centsPerLitre == cheapest) " · cheapest" else "")
            s.distanceKm?.let {
                detail.setSpan(DistanceSpan.create(Distance.create(it, Distance.UNIT_KILOMETERS)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            list.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(detail)
                    .setMetadata(
                        Metadata.Builder().setPlace(
                            CarPlace.Builder(CarLocation.create(s.lat, s.lng))
                                .setMarker(PlaceMarker.Builder().setLabel(String.format(Locale.US, "%.0f", s.centsPerLitre)).build())
                                .build(),
                        ).build(),
                    )
                    .setOnClickListener {
                        val place = Place(id = "fuel:${s.lat},${s.lng}", name = s.name, address = "${s.address}, ${s.suburb}", lat = s.lat, lng = s.lng, icon = PlaceIcon.PIN)
                        Journey.begin(carContext, place)
                        try {
                            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, NavLinks.geo(place).toUri()))
                        } catch (_: Exception) {
                            CarToast.makeText(carContext, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
                        }
                    }
                    .build(),
            )
        }
        return b.setItemList(list.build()).setCurrentLocationEnabled(false).build()
    }
}
