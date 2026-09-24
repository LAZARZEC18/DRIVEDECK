package com.drivedeck.trip

import android.content.Context
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.Place
import com.drivedeck.eta.EtaEngine
import com.drivedeck.eta.EtaEstimate
import com.drivedeck.eta.TripEta
import com.drivedeck.location.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Everything that happens when you pick a destination, from the car or the phone:
 * log it for your routine, tell the trip computer where you're heading, and work out the
 * no-traffic and personal ETAs in the background.
 */
object Journey {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun begin(context: Context, place: Place) {
        val ctx = context.applicationContext
        val repo = DeckRepository.get(ctx)
        if (repo.places.value.any { it.id == place.id }) repo.logTrip(place.id)
        TripService.setDestination(ctx, place)
        TripEta.set(null)
        scope.launch {
            val here = LocationHelper.lastKnown(ctx)
            val route = if (here != null && place.hasCoords) EtaEngine.route(here.latitude, here.longitude, place.lat!!, place.lng!!) else null
            val personal = EtaEngine.personal(place.name, route, repo.drives.value, ZonedDateTime.now())
            TripEta.set(
                EtaEstimate(
                    routeKm = route?.distanceM?.div(1000.0),
                    freeFlowMin = route?.freeFlowS?.div(60.0),
                    personalMin = personal?.first,
                    personalBasis = personal?.second,
                ),
            )
        }
    }
}
