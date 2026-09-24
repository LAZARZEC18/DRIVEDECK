package com.drivedeck.smart

import com.drivedeck.data.TripEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Learns "where do I usually go at this time?" from past trips.
 *
 * Every past trip votes for its destination. A vote counts more when:
 *  - it happened at a similar time of day (Gaussian on the hour gap, wraps around midnight),
 *  - recently (older trips fade out with a half-life).
 *
 * Votes are split into two pools: trips on *the same weekday* as today, and everything else
 * (weekdays count more than weekends on a weekday, and vice versa). When there's enough same-weekday
 * history, it leads the decision. That's how "Tuesday 6 pm = gym" beats "most evenings = home".
 *
 * Everything runs on-device. No server, no account. It's plain maths, so it is easy to test.
 */
class RoutinePredictor(
    private val halfLifeDays: Double = 45.0,
    private val hourSigma: Double = 1.25,
    private val sameDayWeight: Double = 1.0,
    private val sameDayTypeWeight: Double = 0.6,
    private val otherDayWeight: Double = 0.2,
    /** Minimum score before we dare to call something "usual". ~1 recent, well-matched trip. */
    private val minScore: Double = 0.9,
    /** The winner must hold at least this share of all the votes, or we're just guessing. */
    private val minShare: Double = 0.4,
    /** Weighted same-weekday trips needed before the weekday pattern takes the lead. */
    private val dowEvidenceMin: Double = 1.5,
    /** How much the same-weekday pool counts once it leads (the rest is general routine). */
    private val dowBlend: Double = 0.7,
) {

    /** [score] is a 0..1 share of the probability; [evidence] is the raw weighted trip count behind it. */
    data class Ranked(val placeId: String, val score: Double, val evidence: Double)

    data class Suggestion(
        val placeId: String,
        val score: Double,
        val confidence: Double,
        /** Short human reason, e.g. "Tuesdays ~6 pm" or "Weekdays ~8 am". */
        val reason: String,
    )

    /** Scores every place that has history. Highest first. Places in [exclude] are skipped. */
    fun rank(
        trips: List<TripEvent>,
        now: ZonedDateTime,
        validPlaceIds: Set<String>,
        exclude: Set<String> = emptySet(),
    ): List<Ranked> {
        val zone = now.zone
        val sameDay = HashMap<String, Double>()
        val general = HashMap<String, Double>()
        for (t in trips) {
            if (t.placeId !in validPlaceIds || t.placeId in exclude) continue
            val w = weight(t, now, zone)
            if (w <= 0) continue
            general.merge(t.placeId, w, Double::plus)
            val at = Instant.ofEpochMilli(t.epochMillis).atZone(zone)
            if (at.dayOfWeek == now.dayOfWeek) sameDay.merge(t.placeId, w, Double::plus)
        }
        val sTotal = sameDay.values.sum()
        val gTotal = general.values.sum()
        if (gTotal <= 0) return emptyList()
        val dowLeads = sTotal >= dowEvidenceMin
        return general.keys.map { id ->
            val g = general.getValue(id) / gTotal
            val score = if (dowLeads) dowBlend * ((sameDay[id] ?: 0.0) / sTotal) + (1 - dowBlend) * g else g
            Ranked(id, score, general.getValue(id))
        }.sortedByDescending { it.score }
    }

    /** The single most likely destination right now, or null when there's no clear pattern yet. */
    fun suggest(
        trips: List<TripEvent>,
        now: ZonedDateTime,
        validPlaceIds: Set<String>,
        exclude: Set<String> = emptySet(),
    ): Suggestion? {
        val top = rank(trips, now, validPlaceIds, exclude).firstOrNull() ?: return null
        if (top.evidence < minScore || top.score < minShare) return null
        val confidence = min(1.0, top.score * min(1.0, top.evidence / (minScore * 3)))
        return Suggestion(top.placeId, top.score, confidence, reasonFor(top.placeId, trips, now))
    }

    internal fun weight(t: TripEvent, now: ZonedDateTime, zone: ZoneId): Double {
        val at = Instant.ofEpochMilli(t.epochMillis).atZone(zone)
        val ageDays = (now.toInstant().toEpochMilli() - t.epochMillis) / 86_400_000.0
        if (ageDays < 0) return 0.0
        val recency = 0.5.pow(ageDays / halfLifeDays)
        val gapH = hourGap(hourOf(at), hourOf(now))
        val timeW = exp(-(gapH * gapH) / (2 * hourSigma * hourSigma))
        val dayW = when {
            at.dayOfWeek == now.dayOfWeek -> sameDayWeight
            isWeekend(at.dayOfWeek) == isWeekend(now.dayOfWeek) -> sameDayTypeWeight
            else -> otherDayWeight
        }
        return recency * timeW * dayW
    }

    /** Builds the "why" text from the trips that match the current time window. */
    internal fun reasonFor(placeId: String, trips: List<TripEvent>, now: ZonedDateTime): String {
        val zone = now.zone
        val nowHour = hourOf(now)
        val nearby = trips.filter { it.placeId == placeId }
            .map { Instant.ofEpochMilli(it.epochMillis).atZone(zone) }
            .filter { hourGap(hourOf(it), nowHour) <= 2.0 }
        if (nearby.isEmpty()) return "Your usual"

        val sameDow = nearby.count { it.dayOfWeek == now.dayOfWeek }
        val sameType = nearby.count { isWeekend(it.dayOfWeek) == isWeekend(now.dayOfWeek) }
        val dayLabel = when {
            sameDow >= 2 && sameDow * 10 >= sameType * 6 ->
                now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s"
            sameType * 2 >= nearby.size -> if (isWeekend(now.dayOfWeek)) "Weekends" else "Weekdays"
            else -> "Usually"
        }
        return "$dayLabel ~${formatHour(circularMeanHour(nearby.map { hourOf(it) }))}"
    }

    companion object {
        fun hourOf(t: ZonedDateTime): Double = t.hour + t.minute / 60.0

        /** Distance between two clock times in hours, wrapping around midnight (23:30 vs 00:30 = 1h). */
        fun hourGap(a: Double, b: Double): Double {
            val d = abs(a - b) % 24.0
            return min(d, 24.0 - d)
        }

        fun isWeekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

        /** Average of clock times that handles midnight (23:00 and 01:00 average to 00:00, not 12:00). */
        fun circularMeanHour(hours: List<Double>): Double {
            var x = 0.0; var y = 0.0
            for (h in hours) { val a = h / 24.0 * 2 * PI; x += cos(a); y += sin(a) }
            val mean = atan2(y, x) / (2 * PI) * 24.0
            return (mean + 24.0) % 24.0
        }

        /** 8.2 -> "8 am", 18.5 -> "6:30 pm". Rounded to the nearest 15 minutes. */
        fun formatHour(h: Double): String {
            val totalMin = ((h * 60) / 15.0).roundToInt() * 15 % (24 * 60)
            val hh = totalMin / 60; val mm = totalMin % 60
            val h12 = if (hh % 12 == 0) 12 else hh % 12
            val ampm = if (hh < 12) "am" else "pm"
            return if (mm == 0) "$h12 $ampm" else "$h12:${mm.toString().padStart(2, '0')} $ampm"
        }
    }
}
