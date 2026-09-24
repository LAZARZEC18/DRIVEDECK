package com.drivedeck.stats

import com.drivedeck.data.DeckState
import com.drivedeck.data.Drive
import com.drivedeck.data.FillUp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Weekly review numbers, computed from local data. Pure Kotlin, unit tested, and mirrored in
 * the laptop dashboard so both show the same figures.
 */
data class PeriodStats(
    val from: ZonedDateTime,
    val to: ZonedDateTime,
    val drives: Int,
    val distanceKm: Double,
    val drivingMs: Long,
    val movingMs: Long,
    /** Distance / total driving time (what a trip computer shows). */
    val avgSpeedKmh: Double,
    val avgMovingSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val longestDriveKm: Double,
    /** Km per day, Monday..Sunday (index 0..6) for weeks, or per day for other ranges. */
    val kmPerDay: List<Double>,
    val drivesPerDay: List<Int>,
    /** Drives started per hour of day, 0..23. */
    val drivesPerHour: List<Int>,
    val topDestinations: List<Pair<String, Int>>,
    val navigations: Int,
    val songsPlayed: Int,
    val topSongs: List<Pair<String, Int>>,
    val topArtists: List<Pair<String, Int>>,
    val fuelDollars: Double,
    val fuelLitres: Double,
    val fillUps: Int,
    /** Estimated fuel cost of the driving done (distance × your L/100km × average price paid). */
    val estFuelCostDollars: Double,
) {
    val daysWithDriving: Int get() = drivesPerDay.count { it > 0 }
    val busiestDayIndex: Int? get() = kmPerDay.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
    val busiestHour: Int? get() = drivesPerHour.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
}

object StatsEngine {

    fun weekStart(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)

    /** Stats for the week (Mon–Sun) that is [weeksAgo] weeks before the current one. */
    fun week(state: DeckState, now: ZonedDateTime, weeksAgo: Int = 0): PeriodStats {
        val start = weekStart(now.toLocalDate(), now.zone).minusWeeks(weeksAgo.toLong())
        return period(state, start, start.plusWeeks(1))
    }

    fun period(state: DeckState, from: ZonedDateTime, to: ZonedDateTime): PeriodStats {
        val zone = from.zone
        val f = from.toInstant().toEpochMilli()
        val t = to.toInstant().toEpochMilli()
        val days = java.time.Duration.between(from, to).toDays().toInt().coerceAtLeast(1)

        val drives = state.drives.filter { it.startedAt in f until t }
        val kmPerDay = MutableList(days) { 0.0 }
        val drivesPerDay = MutableList(days) { 0 }
        val perHour = MutableList(24) { 0 }
        for (d in drives) {
            val at = Instant.ofEpochMilli(d.startedAt).atZone(zone)
            val idx = java.time.Duration.between(from, at).toDays().toInt().coerceIn(0, days - 1)
            kmPerDay[idx] += d.distanceM / 1000.0
            drivesPerDay[idx]++
            perHour[at.hour]++
        }
        val distanceKm = drives.sumOf { it.distanceM } / 1000.0
        val drivingMs = drives.sumOf { it.durationMs }
        val movingMs = drives.sumOf { it.movingMs }

        val placeNames = state.places.associate { it.id to it.name }
        val navs = state.trips.filter { it.epochMillis in f until t }
        val topDest = navs.mapNotNull { placeNames[it.placeId] }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

        val plays = state.plays.filter { it.at in f until t }
        val topSongs = plays.groupingBy { if (it.artist.isNullOrBlank()) it.title else "${it.title} · ${it.artist}" }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val topArtists = plays.mapNotNull { it.artist?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

        val fills = state.visibleFillUps.filter { it.at in f until t }
        val avgCents = averagePricePaid(state.visibleFillUps) ?: 0.0
        val estCost = distanceKm * state.settings.litresPer100Km / 100.0 * avgCents / 100.0

        return PeriodStats(
            from = from, to = to,
            drives = drives.size,
            distanceKm = distanceKm,
            drivingMs = drivingMs,
            movingMs = movingMs,
            avgSpeedKmh = if (drivingMs > 0) distanceKm / (drivingMs / 3_600_000.0) else 0.0,
            avgMovingSpeedKmh = if (movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else 0.0,
            maxSpeedKmh = (drives.maxOfOrNull { it.maxSpeedMps } ?: 0.0) * 3.6,
            longestDriveKm = (drives.maxOfOrNull { it.distanceM } ?: 0.0) / 1000.0,
            kmPerDay = kmPerDay,
            drivesPerDay = drivesPerDay,
            drivesPerHour = perHour,
            topDestinations = topDest,
            navigations = navs.size,
            songsPlayed = plays.size,
            topSongs = topSongs,
            topArtists = topArtists,
            fuelDollars = fills.sumOf { it.totalDollars },
            fuelLitres = fills.sumOf { it.litres },
            fillUps = fills.size,
            estFuelCostDollars = estCost,
        )
    }

    /** Litre-weighted average price paid, in cents per litre. */
    fun averagePricePaid(fills: List<FillUp>): Double? {
        val litres = fills.sumOf { it.litres }
        return if (litres > 0) fills.sumOf { it.litres * it.centsPerLitre } / litres else null
    }

    /**
     * Real consumption from full-tank to full-tank odometer readings, L/100km. Null until there
     * are two full fills with odometer readings.
     */
    fun measuredConsumption(fills: List<FillUp>): Double? {
        val withOdo = fills.filter { it.odometerKm != null }.sortedBy { it.at }
        val fulls = withOdo.filter { it.fullTank }
        if (fulls.size < 2) return null
        val first = fulls.first(); val last = fulls.last()
        val km = last.odometerKm!! - first.odometerKm!!
        if (km <= 0) return null
        // Fuel used = everything put in after the first full tank, up to and including the last.
        val litres = withOdo.filter { it.at > first.at && it.at <= last.at }.sumOf { it.litres }
        return litres / km * 100.0
    }

    fun estimateDriveCost(d: Drive, state: DeckState): Double? {
        val cents = averagePricePaid(state.visibleFillUps) ?: return null
        return d.distanceM / 1000.0 * state.settings.litresPer100Km / 100.0 * cents / 100.0
    }

    /** Percent change, or null when there's nothing to compare against. */
    fun change(now: Double, before: Double): Double? = if (before <= 0.0) null else (now - before) / before * 100.0

    /** Every week that has any data, newest first (for the history list). */
    fun weeksWithData(state: DeckState, zone: ZoneId): List<LocalDate> {
        val stamps = state.drives.map { it.startedAt } + state.plays.map { it.at } + state.visibleFillUps.map { it.at } + state.trips.map { it.epochMillis }
        return stamps.map { weekStart(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), zone).toLocalDate() }
            .distinct().sortedDescending()
    }
}

/** Formatting shared by the phone, car and notification. */
object Fmt {
    fun km(v: Double): String = when {
        v < 10 -> String.format(java.util.Locale.US, "%.1f km", v)
        else -> String.format(java.util.Locale.US, "%,.0f km", v)
    }

    fun kmh(v: Double): String = "${v.toInt()} km/h"

    fun money(v: Double): String = String.format(java.util.Locale.US, "$%,.2f", v)

    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60; val m = totalMin % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            else -> "${m} min"
        }
    }

    fun pct(v: Double?): String? = v?.let { (if (it >= 0) "▲ " else "▼ ") + "${kotlin.math.abs(it).toInt()}%" }

    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun hour(h: Int): String = when {
        h == 0 -> "12 am"; h < 12 -> "$h am"; h == 12 -> "12 pm"; else -> "${h - 12} pm"
    }
}
