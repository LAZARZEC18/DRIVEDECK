@file:Suppress("DEPRECATION")

package com.drivedeck.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.drivedeck.cameras.Camera
import com.drivedeck.cameras.SpeedCameras
import com.drivedeck.location.LocationHelper
import com.drivedeck.nav.Geo
import kotlinx.coroutines.launch
import androidx.car.app.model.Place as CarPlace

/** Fixed speed and red-light cameras closest to you, on a map, with their speed zones. */
class CamerasScreen(carContext: CarContext) : Screen(carContext) {
    private var cams: List<Pair<Camera, Double>>? = null

    init {
        lifecycleScope.launch {
            val here = LocationHelper.lastKnown(carContext)
            cams = if (here == null) emptyList() else SpeedCameras.near(carContext, here.latitude, here.longitude)
                .map { it to Geo.distanceMeters(here.latitude, here.longitude, it.lat, it.lng) }
                .sortedBy { it.second }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = PlaceListMapTemplate.Builder().setTitle("Speed cameras nearby").setHeaderAction(Action.BACK)
        val list = cams ?: return b.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage("No camera data. Needs location and internet once")
        list.take(CarUi.listLimit(carContext)).forEach { (c, d) ->
            val text = SpannableString("  · ${if (c.redLight) "at the lights" else "fixed camera"}")
            text.setSpan(DistanceSpan.create(Distance.create(d / 1000.0, Distance.UNIT_KILOMETERS)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            items.addItem(
                Row.Builder().setTitle(c.label).addText(text)
                    .setMetadata(Metadata.Builder().setPlace(
                        CarPlace.Builder(CarLocation.create(c.lat, c.lng))
                            .setMarker(PlaceMarker.Builder().setLabel(c.maxSpeed?.toString() ?: "📷").build()).build(),
                    ).build())
                    .build(),
            )
        }
        return b.setItemList(items.build()).build()
    }
}
