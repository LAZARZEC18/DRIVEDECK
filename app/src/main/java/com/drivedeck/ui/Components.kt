package com.drivedeck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivedeck.R
import com.drivedeck.eta.EtaEngine
import com.drivedeck.eta.LiveNavEta
import com.drivedeck.eta.TripEta
import com.drivedeck.stats.Fmt
import com.drivedeck.trip.LiveTrip

/** A number with a label, the building block of the stats and trip screens. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, accent: Boolean = false) {
    Surface(
        color = if (accent) MaterialTheme.colorScheme.primaryContainer else DeckColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = if (accent) BorderStroke(1.dp, DeckColors.AccentDim) else null,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (accent) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Two tiles side by side. */
@Composable
fun StatRow(content: @Composable (Modifier) -> Unit, content2: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        content(Modifier.weight(1f))
        content2(Modifier.weight(1f))
    }
}

/** Simple rounded bar chart. The highest bar is drawn in the accent colour. */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, height: Int = 120) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val top = values.indexOf(values.maxOrNull() ?: 0.0)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.56f
            values.forEachIndexed { i, v ->
                val h = if (v <= 0) 4f else (v / max * (size.height - 6)).toFloat().coerceAtLeast(6f)
                drawRoundRect(
                    color = if (i == top && v > 0) DeckColors.Accent else if (v > 0) DeckColors.AccentDim.copy(alpha = 0.45f) else DeckColors.SurfaceHigh,
                    topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Ranked list like "Top destinations". */
@Composable
fun RankList(items: List<Pair<String, Int>>, unit: String, emptyText: String) {
    if (items.isEmpty()) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = items.maxOf { it.second }.toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, (name, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(22.dp), color = if (i == 0) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth(n / max).height(4.dp).padding(top = 2.dp)) {
                        Canvas(Modifier.fillMaxWidth().height(3.dp)) { drawRoundRect(DeckColors.Accent.copy(alpha = if (i == 0) 1f else 0.5f), cornerRadius = CornerRadius(4f, 4f)) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("$n $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Card container with a label, used across tabs. */
@Composable
fun DeckCard(title: String? = null, trailing: String? = null, content: @Composable () -> Unit) {
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeckColors.AccentDim) }
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** Live trip computer card (phone). */
@Composable
fun LiveTripCard(live: LiveTrip, onEnd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DeckColors.AccentDim),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_speed), null, tint = DeckColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("TRIP COMPUTER · LIVE", style = MaterialTheme.typography.labelSmall, color = DeckColors.Accent, modifier = Modifier.weight(1f))
                Text(Fmt.duration(live.elapsedMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            live.cameraAhead?.let { c ->
                Surface(color = DeckColors.Warn.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_camera), null, tint = DeckColors.Warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${c.camera.label} in ${com.drivedeck.nav.Geo.formatDistance(c.distanceM)}", color = DeckColors.Warn, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text("${live.speedKmh.toInt()}", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black))
                Text(" km/h", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("avg ${Fmt.kmh(live.avgKmh)}", style = MaterialTheme.typography.titleMedium)
                    Text("max ${Fmt.kmh(live.maxKmh)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${Fmt.km(live.distanceKm)} · moving ${Fmt.duration(live.movingMs)} · moving avg ${Fmt.kmh(live.avgMovingKmh)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            live.destination?.let { dest -> EtaBlock(dest, live) }
            OutlinedButton(onClick = onEnd, modifier = Modifier.padding(top = 10.dp)) { Text("End trip") }
        }
    }
}

@Composable
private fun EtaBlock(dest: String, live: LiveTrip) {
    val navState by LiveNavEta.current.collectAsStateWithLifecycle()
    val nav = navState?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000 }
    val est by TripEta.current.collectAsStateWithLifecycle()
    val since = est?.let { e -> (System.currentTimeMillis() - e.computedAt) / 60_000.0 } ?: 0.0
    Column(Modifier.padding(top = 12.dp)) {
        Text("TO ${dest.uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (live.arrivedAt != null) {
            Text("Arrived · took ${Fmt.duration(live.arrivedAt - live.startedAt)}", style = MaterialTheme.typography.titleMedium, color = DeckColors.Accent)
            return
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EtaPill("Traffic", nav?.arrival ?: nav?.minutes?.let { EtaEngine.clock(it.toDouble()) } ?: "—", nav?.app ?: "open Waze/Maps", Modifier.weight(1f), accent = true)
            EtaPill("Your ETA", est?.personalMin?.let { EtaEngine.clock(it - since) } ?: "—", est?.personalBasis ?: "learning", Modifier.weight(1f))
            EtaPill("No traffic", est?.freeFlowMin?.let { EtaEngine.clock(it - since) } ?: "—", live.remainingKm?.let { "${Fmt.km(it)} to go" } ?: "", Modifier.weight(1f))
        }
    }
}

@Composable
private fun EtaPill(label: String, value: String, sub: String, modifier: Modifier, accent: Boolean = false) {
    Surface(color = if (accent) DeckColors.Accent.copy(alpha = 0.14f) else DeckColors.SurfaceHigh, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = if (accent) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, lineHeight = 12.sp)
        }
    }
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = DeckColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(text) }
