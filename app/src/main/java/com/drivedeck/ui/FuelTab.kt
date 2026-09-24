package com.drivedeck.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.FillUp
import com.drivedeck.data.FuelType
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import com.drivedeck.fuel.FuelCache
import com.drivedeck.fuel.FuelStation
import com.drivedeck.nav.PhoneNavigator
import com.drivedeck.stats.Fmt
import com.drivedeck.stats.StatsEngine
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.Locale

/** Cheapest fuel near you (FuelWatch) + your fill-up log and fuel costs. */
@Composable
fun FuelTab(modifier: Modifier, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val repo = remember { DeckRepository.get(ctx) }
    val settings by repo.settings.collectAsStateWithLifecycle()
    val fills by repo.fillUps.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val prices by FuelCache.prices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tomorrow by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FillUp?>(null) }

    fun load() {
        scope.launch {
            loading = true
            FuelCache.refresh(ctx, settings.fuelType, tomorrow)
            loading = false
        }
    }
    LaunchedEffect(settings.fuelType, tomorrow) { load() }

    val now = remember { ZonedDateTime.now() }
    val monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone)
    val month = remember(state) { StatsEngine.period(state, monthStart, monthStart.plusMonths(1)) }
    val avgPaid = StatsEngine.averagePricePaid(fills)
    val measured = StatsEngine.measuredConsumption(fills)
    val perKm = avgPaid?.let { (measured ?: settings.litresPer100Km) / 100.0 * it } // cents per km

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(FuelType.entries.toList()) { t ->
                        FilterChip(
                            selected = t == settings.fuelType,
                            onClick = { repo.updateSettings { it.copy(fuelType = t) } },
                            label = { Text(t.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DeckColors.Accent, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                        )
                    }
                }
            }
            item {
                DeckCard(
                    "Cheapest near ${prices?.area ?: "you"}",
                    trailing = if (tomorrow) "TOMORROW" else "TODAY",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { tomorrow = false }) { Text("Today", color = if (!tomorrow) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { tomorrow = true }) { Text("Tomorrow", color = if (tomorrow) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.weight(1f))
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    val list = prices?.stations.orEmpty()
                    if (list.isEmpty() && !loading) {
                        Text("No prices yet. Tomorrow's prices are published at 2:30 pm.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    prices?.average?.let { avg ->
                        Text(String.format(Locale.US, "Area average %.1f¢ · cheapest saves %s on a 50 L tank", avg, Fmt.money((avg - (prices?.cheapest?.centsPerLitre ?: avg)) / 100 * 50)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    list.take(8).forEachIndexed { i, s -> StationRow(s, cheapest = i == 0, nav = { PhoneNavigator.open(ctx, s.toPlace(), settings.phoneNavApp) }) }
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://petrolspy.com.au/".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Compare on PetrolSpy") }
                }
            }
            item {
                StatRow(
                    { StatTile("This month", Fmt.money(month.fuelDollars), it, sub = "${month.fillUps} fill-ups · ${String.format(Locale.US, "%.0f", month.fuelLitres)} L", accent = true) },
                    { StatTile("Avg price paid", avgPaid?.let { String.format(Locale.US, "%.1f¢", it) } ?: "—", it, sub = prices?.average?.let { a -> String.format(Locale.US, "area now %.1f¢", a) } ?: "per litre") },
                )
            }
            item {
                StatRow(
                    { StatTile("Economy", String.format(Locale.US, "%.1f L/100km", measured ?: settings.litresPer100Km), it, sub = if (measured != null) "measured from fill-ups" else "set in Setup · add odometer for real") },
                    { StatTile("Cost per km", perKm?.let { String.format(Locale.US, "%.1f¢", it) } ?: "—", it, sub = perKm?.let { c -> "${Fmt.money(c * 100 / 100)} per 100 km" } ?: "log a fill-up") },
                )
            }
            item { SectionLabel("FILL-UPS", "${fills.size} LOGGED") }
            if (fills.isEmpty()) item { HintCard("Log each fill-up (takes 5 seconds) to track your fuel spend, price paid and real consumption.") }
            items(fills.reversed(), key = { it.id }) { f ->
                DeckCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_fuel), null, tint = DeckColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.money(f.totalDollars), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                String.format(Locale.US, "%.1f L @ %.1f¢", f.litres, f.centsPerLitre) + (f.station?.let { " · $it" } ?: "") +
                                    (f.odometerKm?.let { " · ${it.toInt()} km" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeLabel(f.at).substringBefore(","), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { editing = f }) { Text("Edit") }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                editing = FillUp(at = System.currentTimeMillis(), litres = 0.0, centsPerLitre = prices?.cheapest?.centsPerLitre ?: 0.0, station = null)
            },
            icon = { Icon(painterResource(R.drawable.ic_add), null) },
            text = { Text("Log fill-up") },
            containerColor = DeckColors.Accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { f ->
        FillUpDialog(
            initial = f, isNew = fills.none { it.id == f.id },
            onDismiss = { editing = null },
            onDelete = { repo.deleteFillUp(f.id); editing = null },
            onSave = { saved ->
                repo.upsertFillUp(saved); editing = null
                scope.launch { snackbar.showSnackbar("Saved: ${Fmt.money(saved.totalDollars)}") }
            },
        )
    }
}

private fun FuelStation.toPlace() = Place(id = "fuel:$lat,$lng", name = name, address = "$address, $suburb", lat = lat, lng = lng, icon = PlaceIcon.PIN)

@Composable
private fun StationRow(s: FuelStation, cheapest: Boolean, nav: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.US, "%.1f", s.centsPerLitre),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = if (cheapest) DeckColors.Accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(72.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(listOfNotNull(s.distanceKm?.let { Fmt.km(it) }, s.address, s.suburb).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        FilledTonalIconButton(onClick = nav) { Icon(painterResource(R.drawable.ic_navigate), "Navigate") }
    }
}

@Composable
private fun FillUpDialog(initial: FillUp, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (FillUp) -> Unit) {
    fun fmt(d: Double) = if (d == 0.0) "" else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
    var litres by remember { mutableStateOf(fmt(initial.litres)) }
    var cents by remember { mutableStateOf(fmt(initial.centsPerLitre)) }
    var total by remember { mutableStateOf(if (isNew) "" else fmt(initial.totalDollars)) }
    var odo by remember { mutableStateOf(initial.odometerKm?.let { fmt(it) } ?: "") }
    var station by remember { mutableStateOf(initial.station ?: "") }
    var full by remember { mutableStateOf(initial.fullTank) }

    val l = litres.toDoubleOrNull(); val c = cents.toDoubleOrNull(); val t = total.toDoubleOrNull()
    // Any two of litres / price / total give the third.
    val resolved = when {
        l != null && c != null -> Triple(l, c, t ?: (l * c / 100))
        l != null && t != null && l > 0 -> Triple(l, t / l * 100, t)
        c != null && t != null && c > 0 -> Triple(t / c * 100, c, t)
        else -> null
    }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        title = { Text(if (isNew) "Log fill-up" else "Edit fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(litres, { litres = it }, label = { Text("Litres") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
                    OutlinedTextField(cents, { cents = it }, label = { Text("¢ / L") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(total, { total = it }, label = { Text("Total $") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Fill any two of litres, price and total") })
                OutlinedTextField(odo, { odo = it }, label = { Text("Odometer km (optional)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(station, { station = it }, label = { Text("Station (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(full, { full = it }); Text("Filled to full", style = MaterialTheme.typography.bodyMedium)
                }
                resolved?.let { (rl, rc, rt) ->
                    Text(String.format(Locale.US, "%.2f L × %.1f¢ = %s", rl, rc, Fmt.money(rt)), color = DeckColors.Accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            AccentButton("Save", enabled = resolved != null && resolved.first > 0, onClick = {
                val (rl, rc, rt) = resolved!!
                onSave(initial.copy(litres = rl, centsPerLitre = rc, totalDollars = rt, odometerKm = odo.toDoubleOrNull(), station = station.trim().ifBlank { null }, fullTank = full))
            })
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
