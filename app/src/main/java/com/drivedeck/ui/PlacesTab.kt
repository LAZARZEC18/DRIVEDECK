package com.drivedeck.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.NavApp
import com.drivedeck.data.Place
import com.drivedeck.data.PlaceIcon
import com.drivedeck.location.LocationHelper
import com.drivedeck.nav.PhoneNavigator
import com.drivedeck.nav.PlaceSearch
import com.drivedeck.nav.SearchResult
import com.drivedeck.smart.RoutinePredictor
import com.drivedeck.trip.Journey
import com.drivedeck.trip.TripService
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

@Composable
fun PlacesTab(modifier: Modifier, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val repo = remember { DeckRepository.get(ctx) }
    val places by repo.places.collectAsStateWithLifecycle()
    val trips by repo.trips.collectAsStateWithLifecycle()
    val nextUp by repo.nextUp.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Place?>(null) }
    var creating by remember { mutableStateOf<Place?>(null) }

    // Quietly fill in coordinates for any place saved by address only (e.g. first-run defaults),
    // so Waze can start driving immediately instead of searching.
    LaunchedEffect(places) {
        places.filter { !it.hasCoords && it.address.isNotBlank() }.forEach { p ->
            LocationHelper.geocode(ctx, p.address)?.let { (lat, lng) -> repo.upsertPlace(p.copy(lat = lat, lng = lng)) }
        }
    }

    val queuedId = nextUp?.activePlaceId(System.currentTimeMillis())?.takeIf { id -> places.any { it.id == id } }
    val suggestion = remember(places, trips, queuedId) {
        queuedId?.let { RoutinePredictor.Suggestion(it, 1.0, 1.0, "Queued for your next drive") }
            ?: RoutinePredictor().suggest(trips, ZonedDateTime.now(), places.map { it.id }.toSet())
    }
    val suggestedPlace = places.firstOrNull { it.id == suggestion?.placeId }

    val settings by repo.settings.collectAsStateWithLifecycle()
    // Only whether a trip is running: the live numbers are read inside the trip card itself, so
    // the whole tab doesn't redraw every second while you drive.
    val tripRunning by TripService.isRunning.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(500)
        searching = true
        results = PlaceSearch.search(ctx, query)
        searching = false
    }

    fun go(p: Place, app: NavApp = settings.phoneNavApp) {
        Journey.begin(ctx, p)
        PhoneNavigator.open(ctx, p, app)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search anywhere: servo, shop, address…") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                items(results, key = { "${it.lat},${it.lng}" }) { r ->
                    SearchResultRow(
                        r,
                        onWaze = { go(r.toPlace(), NavApp.WAZE) },
                        onMaps = { go(r.toPlace(), NavApp.GOOGLE_MAPS) },
                        onSave = { creating = Place(name = r.name, address = r.detail.ifBlank { r.name }, lat = r.lat, lng = r.lng) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { PhoneNavigator.searchIn(ctx, query, NavApp.WAZE) }, modifier = Modifier.weight(1f)) { Text("Search in Waze") }
                        OutlinedButton(onClick = { PhoneNavigator.searchIn(ctx, query, NavApp.GOOGLE_MAPS) }, modifier = Modifier.weight(1f)) { Text("Search in Maps") }
                    }
                }
            }
            if (tripRunning) item { LiveTripSlot { TripService.stop(ctx) } }
            item {
                if (suggestedPlace != null) {
                    SuggestionCard(
                        suggestedPlace, suggestion!!.reason,
                        queued = queuedId != null,
                        onClear = { repo.setNextUp(null) },
                    ) { go(suggestedPlace) }
                } else {
                    HintCard(
                        if (trips.isEmpty()) "Drive to your places through DRIVEDECK and it learns your routine, e.g. \"weekdays ~8 am → Work\"."
                        else "Still learning. ${trips.size} trip${if (trips.size == 1) "" else "s"} so far. Suggestions show up once a pattern is clear.",
                    )
                }
            }

            val missing = listOf(
                PlaceIcon.HOME to "Home", PlaceIcon.WORK to "Work", PlaceIcon.GYM to "Gym", PlaceIcon.SPORT to "Basketball",
            ).filter { (icon, _) -> places.none { it.icon == icon } }
            if (missing.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(missing) { (icon, name) ->
                            AssistChip(
                                onClick = { creating = Place(name = name, address = "", icon = icon) },
                                label = { Text("Add $name") },
                                leadingIcon = { Icon(painterResource(icon.iconRes()), null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }

            item { SectionLabel("YOUR PLACES", "${minOf(places.size, 5)} ON CAR SCREEN") }
            items(places, key = { it.id }) { p ->
                PlaceRow(p, onClick = { editing = p }, onGo = { go(p) })
            }
            item { CamerasCard() }
        }

        ExtendedFloatingActionButton(
            onClick = { creating = Place(name = "", address = "") },
            icon = { Icon(painterResource(R.drawable.ic_add), null) },
            text = { Text("Add place") },
            containerColor = DeckColors.Accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    val scope = rememberCoroutineScope()
    (editing ?: creating)?.let { target ->
        PlaceDialog(
            initial = target,
            isNew = creating != null,
            onDismiss = { editing = null; creating = null },
            onDelete = { repo.deletePlace(target.id); editing = null },
            onSendToCar = {
                repo.setNextUp(target.id)
                editing = null
                scope.launch { snackbar.showSnackbar("${target.name} is first on the car screen for the next 12 hours") }
            },
            onSave = { saved, note ->
                repo.upsertPlace(saved)
                editing = null; creating = null
                note?.let { scope.launch { snackbar.showSnackbar(it) } }
            },
        )
    }
}

@Composable
private fun SuggestionCard(place: Place, reason: String, queued: Boolean = false, onClear: () -> Unit = {}, onGo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, DeckColors.AccentDim),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_smart), null, tint = DeckColors.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (queued) "SENT TO CAR" else "RIGHT NOW", style = MaterialTheme.typography.labelSmall, color = DeckColors.Accent)
                    if (queued) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "CLEAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(place.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(
                onClick = onGo,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DeckColors.Accent),
            ) { Icon(painterResource(R.drawable.ic_navigate), "Go", Modifier.size(30.dp)) }
        }
    }
}

@Composable
internal fun HintCard(text: String) {
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DeckColors.Outline)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_smart), null, tint = DeckColors.Accent)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = DeckColors.AccentDim)
        }
    }
}

@Composable
private fun PlaceRow(p: Place, onClick: () -> Unit, onGo: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = DeckColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = DeckColors.SurfaceHigh, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(p.icon.iconRes()), null, tint = DeckColors.Accent) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    p.address.ifBlank { "Pinned location" } + if (!p.hasCoords) " · finding on map…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = onGo) { Icon(painterResource(R.drawable.ic_navigate), "Navigate with Waze") }
        }
    }
}

@Composable
private fun PlaceDialog(
    initial: Place,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSendToCar: () -> Unit,
    onSave: (Place, String?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var icon by remember { mutableStateOf(initial.icon) }
    var coords by remember { mutableStateOf(if (initial.hasCoords) initial.lat!! to initial.lng!! else null) }
    var coordsFromGps by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var locationNote by remember { mutableStateOf<String?>(null) }

    fun useCurrentLocation() {
        if (!LocationHelper.hasFinePermission(ctx)) {
            busy = null
            locationNote = "Pinning needs Precise location. Turn it on in DRIVEDECK's location permission."
            return
        }
        locationNote = null
        scope.launch {
            busy = "Getting your location…"
            val loc = LocationHelper.current(ctx)
            if (loc != null) {
                coords = loc.latitude to loc.longitude
                coordsFromGps = true
                LocationHelper.reverseGeocode(ctx, loc.latitude, loc.longitude)?.let { address = it }
            }
            busy = null
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) useCurrentLocation()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        title = { Text(if (isNew) "New place" else "Edit place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (shown in the car)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    address, { address = it; if (!coordsFromGps) coords = null },
                    label = { Text("Address or place") }, modifier = Modifier.fillMaxWidth(), maxLines = 2,
                )
                OutlinedButton(
                    onClick = {
                        if (LocationHelper.hasFinePermission(ctx)) useCurrentLocation()
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_gps), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("I'm here now: use my location")
                }
                Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PlaceIcon.entries.toList()) { opt ->
                        val selected = opt == icon
                        Surface(
                            shape = CircleShape,
                            color = if (selected) DeckColors.Accent else DeckColors.SurfaceHigh,
                            modifier = Modifier.size(42.dp).clickable { icon = opt },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painterResource(opt.iconRes()), opt.name,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
                busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(it)
                    }
                }
                locationNote?.let { Text(it, color = DeckColors.Warn, style = MaterialTheme.typography.bodySmall) }
                if (coords != null && busy == null) {
                    Text("✓ Pinned on the map", color = DeckColors.Accent, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && (address.isNotBlank() || coords != null) && busy == null,
                onClick = {
                    scope.launch {
                        var c = coords
                        var note: String? = null
                        if (c == null) {
                            busy = "Finding it on the map…"
                            c = LocationHelper.geocode(ctx, address)
                            busy = null
                            if (c == null) note = "Couldn't pin \"$address\" exactly. Waze will search for it instead."
                        }
                        onSave(initial.copy(name = name.trim(), address = address.trim(), icon = icon, lat = c?.first, lng = c?.second), note)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                if (!isNew) TextButton(onClick = onSendToCar) { Text("Send to car") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SearchResultRow(r: SearchResult, onWaze: () -> Unit, onMaps: () -> Unit, onSave: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = DeckColors.Surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_pin), null, tint = DeckColors.Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(r.distanceKm?.let { com.drivedeck.stats.Fmt.km(it) }, r.detail.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("Waze", onWaze, Modifier.weight(1f))
                AccentButton("Google Maps", onMaps, Modifier.weight(1.4f))
                OutlinedButton(onClick = onSave) { Text("Save") }
            }
        }
    }
}

/** Fixed speed & red-light cameras near you (OpenStreetMap), nearest first. */
@Composable
private fun CamerasCard() {
    val ctx = LocalContext.current
    var cams by remember { mutableStateOf<List<Pair<com.drivedeck.cameras.Camera, Double>>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val here = LocationHelper.lastKnown(ctx)
        cams = if (here == null) emptyList() else com.drivedeck.cameras.SpeedCameras.near(ctx, here.latitude, here.longitude)
            .map { it to com.drivedeck.nav.Geo.distanceMeters(here.latitude, here.longitude, it.lat, it.lng) }.sortedBy { it.second }
    }
    val list = cams
    DeckCard("Speed cameras near you", trailing = list?.let { "${it.size} MAPPED" }) {
        when {
            list == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            list.isEmpty() -> Text("Allow location in Setup to load camera locations (fixed speed + red-light cameras).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            else -> {
                list.take(if (expanded) 15 else 4).forEach { (c, d) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_camera), null, tint = DeckColors.Warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium)
                            Text(com.drivedeck.nav.Geo.formatDistance(d) + " away", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("geo:${c.lat},${c.lng}?q=${c.lat},${c.lng}(${android.net.Uri.encode(c.label)})"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Map") }
                    }
                }
                if (list.size > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show ${minOf(list.size, 15) - 4} more") }
                Text("You'll hear \"Speed camera ahead\" during a trip. Waze and Google Maps also warn about mobile cameras from driver reports.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
