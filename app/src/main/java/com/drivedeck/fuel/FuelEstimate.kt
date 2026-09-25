package com.drivedeck.fuel

import com.drivedeck.data.DeckSettings
import com.drivedeck.data.Drive
import com.drivedeck.data.FillUp
import com.drivedeck.stats.StatsEngine
import java.util.Locale

/**
 * Rough fuel level, worked out from your fill-up log and the drives DRIVEDECK recorded since.
 * The Cerato doesn't share its fuel gauge with phone apps, so this is an estimate: it assumes
 * the last "full tank" fill-up filled the tank, and uses your measured L/100km when there's
 * enough fill-up history (otherwise the figure from Settings).
 */
object FuelEstimate {

    data class Level(
        val kmSinceFill: Double,
        val litresLeft: Double,
        val kmLeft: Double,
        /** 0.0 = empty, 1.0 = full. */
        val fraction: Double,
    )

    /** Below this, the start-of-drive reminder speaks up. */
    const val LOW_KM = 120.0
    const val LOW_FRACTION = 0.2

    fun estimate(fillUps: List<FillUp>, drives: List<Drive>, settings: DeckSettings): Level? {
        val live = fillUps.filterNot { it.deleted }
        val lastFull = live.filter { it.fullTank }.maxByOrNull { it.at } ?: return null
        val l100 = (StatsEngine.measuredConsumption(live) ?: settings.litresPer100Km).coerceIn(3.0, 25.0)
        val tank = settings.tankLitres
        val topUps = live.filter { !it.fullTank && it.at > lastFull.at }.sumOf { it.litres }
        val km = drives.filter { it.startedAt >= lastFull.at }.sumOf { it.distanceM } / 1000.0
        val left = (tank + topUps - km * l100 / 100.0).coerceIn(0.0, tank)
        return Level(kmSinceFill = km, litresLeft = left, kmLeft = left / l100 * 100.0, fraction = left / tank)
    }

    fun isLow(level: Level): Boolean = level.kmLeft < LOW_KM || level.fraction < LOW_FRACTION

    /** Nearby beats cheapest-in-the-region: a servo 25 km away isn't a saving. */
    fun pick(prices: FuelPrices): FuelStation? {
        val near = prices.stations.filter { (it.distanceKm ?: Double.MAX_VALUE) <= 6.0 }
        return (near.ifEmpty { prices.stations }).minByOrNull { it.centsPerLitre }
    }

    /** What gets read out, e.g. "Fuel's getting low, about 90 kilometres left. Cheapest nearby is Vibe Morley at 179.9." */
    fun spoken(level: Level, station: FuelStation?): String {
        val km = (level.kmLeft / 10).toInt() * 10
        val start = if (km > 0) "Fuel's getting low, about $km kilometres left." else "Fuel's getting low."
        val where = station?.let {
            val name = listOf(it.brand, it.suburb).filter { s -> s.isNotBlank() }.joinToString(" ").ifBlank { it.name }
            " Cheapest nearby is $name at ${String.format(Locale.US, "%.1f", it.centsPerLitre)}."
        } ?: ""
        return start + where
    }
}
