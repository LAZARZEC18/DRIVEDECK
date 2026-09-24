package com.drivedeck.fuel

import android.content.Context
import com.drivedeck.data.FuelType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last FuelWatch result, shared by the car and phone. Refreshed at most every 20 minutes. */
object FuelCache {
    private val state = MutableStateFlow<FuelPrices?>(null)
    val prices: StateFlow<FuelPrices?> = state.asStateFlow()
    private var type: FuelType? = null
    private var tomorrow = false

    suspend fun refresh(context: Context, fuelType: FuelType, forTomorrow: Boolean = false): FuelPrices? {
        val result = runCatching { FuelWatch.nearby(context, fuelType, forTomorrow) }.getOrNull()
        if (result != null) { state.value = result; type = fuelType; tomorrow = forTomorrow }
        return result
    }

    suspend fun refreshIfStale(context: Context, fuelType: FuelType) {
        val p = state.value
        if (p == null || type != fuelType || tomorrow || System.currentTimeMillis() - p.fetchedAt > 20 * 60_000) refresh(context, fuelType)
    }
}
