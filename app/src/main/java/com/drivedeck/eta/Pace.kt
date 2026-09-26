package com.drivedeck.eta

import com.drivedeck.data.Drive

/**
 * How your driving compares with Waze's estimates, learned from drives where Waze was
 * navigating: how long Waze said at the start vs how long it actually took you.
 * A factor of 1.12 means you usually take 12% longer than Waze says.
 */
object Pace {
    data class Factor(val factor: Double, val samples: Int) {
        val describe: String get() {
            val pct = kotlin.math.abs((factor - 1) * 100).let { kotlin.math.round(it).toInt() }
            return when {
                pct < 3 -> "you drive right on Waze's times"
                factor > 1 -> "you usually take $pct% longer than Waze"
                else -> "you're usually $pct% quicker than Waze"
            }
        }
    }

    const val MIN_SAMPLES = 2

    /** Pure function, unit tested. Uses your last 15 comparable drives. */
    fun factor(drives: List<Drive>): Factor? {
        val ratios = drives.asSequence()
            .filter { (it.wazeMin ?: 0.0) >= 5.0 && (it.wazeActualMin ?: 0.0) > 0.0 }
            .sortedBy { it.startedAt }
            .map { it.wazeActualMin!! / it.wazeMin!! }
            .filter { it in 0.6..1.8 } // a cancelled route or a big detour isn't your pace
            .toList()
            .takeLast(15)
        if (ratios.size < MIN_SAMPLES) return null
        val s = ratios.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return Factor(median, s.size)
    }

    /** Your ETA in minutes from Waze's remaining minutes. */
    fun yourMinutes(wazeMinutes: Int, f: Factor?): Double? = f?.let { wazeMinutes * it.factor }
}
