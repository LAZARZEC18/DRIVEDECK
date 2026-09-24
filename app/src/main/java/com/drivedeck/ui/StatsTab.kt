package com.drivedeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.Drive
import com.drivedeck.stats.Fmt
import com.drivedeck.stats.StatsEngine
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Weekly review: driving, speed, fuel and music, week by week, compared with the week before. */
@Composable
fun StatsTab(modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = remember { DeckRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    var weeksAgo by rememberSaveable { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val now = remember { ZonedDateTime.now() }
    val w = remember(state, weeksAgo) { StatsEngine.week(state, now, weeksAgo) }
    val prev = remember(state, weeksAgo) { StatsEngine.week(state, now, weeksAgo + 1) }
    val range = DateTimeFormatter.ofPattern("d MMM")

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weeksAgo++ }) { Icon(painterResource(R.drawable.ic_back_arrow), "Previous week") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (weeksAgo) { 0 -> "This week"; 1 -> "Last week"; else -> "$weeksAgo weeks ago" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("${w.from.format(range)} – ${w.to.minusDays(1).format(range)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { if (weeksAgo > 0) weeksAgo-- }, enabled = weeksAgo > 0) {
                    Icon(painterResource(R.drawable.ic_back_arrow), "Next week", Modifier.graphicsLayer { scaleX = -1f })
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Overview", "Drives", "Music").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = section == i, onClick = { section = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = DeckColors.Accent, activeContentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { Text(label) }
                }
            }
        }

        when (section) {
            0 -> {
                item {
                    StatRow(
                        { StatTile("Distance", Fmt.km(w.distanceKm), it, sub = Fmt.pct(StatsEngine.change(w.distanceKm, prev.distanceKm))?.let { p -> "$p vs prev" } ?: "—", accent = true) },
                        { StatTile("Drives", "${w.drives}", it, sub = "${w.daysWithDriving}/7 days · ${w.navigations} navigations") },
                    )
                }
                item {
                    StatRow(
                        { StatTile("Time driving", Fmt.duration(w.drivingMs), it, sub = "moving ${Fmt.duration(w.movingMs)}") },
                        { StatTile("Avg speed", Fmt.kmh(w.avgSpeedKmh), it, sub = "moving ${Fmt.kmh(w.avgMovingSpeedKmh)}") },
                    )
                }
                item {
                    StatRow(
                        { StatTile("Top speed", Fmt.kmh(w.maxSpeedKmh), it, sub = "longest ${Fmt.km(w.longestDriveKm)}") },
                        { StatTile("Fuel", Fmt.money(w.fuelDollars), it, sub = "driving ≈ ${Fmt.money(w.estFuelCostDollars)}") },
                    )
                }
                item {
                    DeckCard("Km per day", w.busiestDayIndex?.let { "Busiest: ${Fmt.DAYS[it]}" }) {
                        BarChart(w.kmPerDay, Fmt.DAYS)
                    }
                }
                item {
                    DeckCard("When you drive", w.busiestHour?.let { "Peak: ${Fmt.hour(it)}" }) {
                        BarChart(
                            (0 until 24 step 3).map { h -> (h until h + 3).sumOf { w.drivesPerHour[it] }.toDouble() },
                            listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"), height = 80,
                        )
                    }
                }
                item { DeckCard("Top destinations") { RankList(w.topDestinations, "×", "Navigate with DRIVEDECK to see your top places") } }
                item {
                    DeckCard("vs previous week") {
                        Compare("Distance", Fmt.km(w.distanceKm), Fmt.km(prev.distanceKm), StatsEngine.change(w.distanceKm, prev.distanceKm))
                        Compare("Drives", "${w.drives}", "${prev.drives}", StatsEngine.change(w.drives.toDouble(), prev.drives.toDouble()))
                        Compare("Time", Fmt.duration(w.drivingMs), Fmt.duration(prev.drivingMs), StatsEngine.change(w.drivingMs.toDouble(), prev.drivingMs.toDouble()))
                        Compare("Fuel", Fmt.money(w.fuelDollars), Fmt.money(prev.fuelDollars), StatsEngine.change(w.fuelDollars, prev.fuelDollars))
                        Compare("Songs", "${w.songsPlayed}", "${prev.songsPlayed}", StatsEngine.change(w.songsPlayed.toDouble(), prev.songsPlayed.toDouble()))
                    }
                }
            }
            1 -> {
                val drives = state.drives.filter { it.startedAt >= w.from.toInstant().toEpochMilli() && it.startedAt < w.to.toInstant().toEpochMilli() }.reversed()
                if (drives.isEmpty()) item { HintCard("No drives recorded this week. The trip computer records every drive automatically once DRIVEDECK is open on the car screen.") }
                items(drives, key = { it.id }) { d -> DriveRow(d, StatsEngine.estimateDriveCost(d, state)) }
            }
            else -> {
                item {
                    StatRow(
                        { StatTile("Songs played", "${w.songsPlayed}", it, sub = Fmt.pct(StatsEngine.change(w.songsPlayed.toDouble(), prev.songsPlayed.toDouble()))?.let { p -> "$p vs prev" } ?: "—", accent = true) },
                        { StatTile("Artists", "${state.plays.filter { p -> p.at >= w.from.toInstant().toEpochMilli() && p.at < w.to.toInstant().toEpochMilli() }.mapNotNull { p -> p.artist }.distinct().size}", it, sub = "different artists") },
                    )
                }
                item { DeckCard("Top songs") { RankList(w.topSongs, "plays", "Play music in YouTube Music and your top songs show up here") } }
                item { DeckCard("Top artists") { RankList(w.topArtists, "plays", "—") } }
                item {
                    DeckCard("Recently played") {
                        val recent = state.plays.takeLast(15).reversed()
                        if (recent.isEmpty()) Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recent.forEach { p ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                    p.artist?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Text(timeLabel(p.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Compare(label: String, now: String, before: String, pct: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(before, Modifier.width(80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(now, Modifier.width(80.dp), fontWeight = FontWeight.SemiBold)
        Text(
            Fmt.pct(pct) ?: "—", Modifier.width(56.dp),
            color = when { pct == null -> MaterialTheme.colorScheme.onSurfaceVariant; pct >= 0 -> DeckColors.Accent; else -> DeckColors.Warn },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DriveRow(d: Drive, cost: Double?) {
    DeckCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_road), null, tint = DeckColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.destination?.let { "To $it" } ?: "Drive", style = MaterialTheme.typography.titleMedium)
                Text(timeLabel(d.startedAt) + " · " + Fmt.duration(d.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Fmt.km(d.distanceM / 1000), style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Avg", Fmt.kmh(d.avgSpeedMps * 3.6), Modifier.weight(1f))
            StatTile("Moving", Fmt.kmh(d.avgMovingSpeedMps * 3.6), Modifier.weight(1f))
            StatTile("Max", Fmt.kmh(d.maxSpeedMps * 3.6), Modifier.weight(1f))
        }
        val extra = listOfNotNull(
            d.arrivedAt?.let { "Arrived in ${Fmt.duration(it - d.startedAt)}" },
            "stopped ${Fmt.duration(d.durationMs - d.movingMs)}",
            cost?.let { "fuel ≈ ${Fmt.money(it)}" },
        ).joinToString(" · ")
        Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

internal fun timeLabel(ms: Long): String =
    DateTimeFormatter.ofPattern("EEE d MMM, h:mm a").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
