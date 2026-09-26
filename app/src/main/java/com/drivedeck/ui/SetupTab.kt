package com.drivedeck.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.location.LocationHelper
import com.drivedeck.music.MediaListenerService
import com.drivedeck.music.YtMusicController
import com.drivedeck.nav.PhoneNavigator
import com.drivedeck.sync.SyncManager
import com.drivedeck.trip.AutoDrive

private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

@android.annotation.SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only used on Android 13+
@Composable
fun SetupTab(modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = remember { DeckRepository.get(ctx) }
    val music = remember { YtMusicController(ctx) }
    val places by repo.places.collectAsStateWithLifecycle()
    val trips by repo.trips.collectAsStateWithLifecycle()

    // Statuses can change in system settings, so re-check every time we come back.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val musicAccess = remember(tick) { music.hasNotificationAccess() }
    val location = remember(tick) { LocationHelper.hasFinePermission(ctx) }
    val coarseOnly = remember(tick) { !location && LocationHelper.hasPermission(ctx) }
    val waze = remember(tick) { isInstalled(ctx, PhoneNavigator.WAZE_PACKAGE) }
    val ytm = remember(tick) { music.isInstalled() }
    val notifs = remember(tick) {
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val sync = remember { SyncManager.get(ctx) }
    val syncStatus by sync.status.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val crashes = remember(tick) { com.drivedeck.CrashLog.read(ctx) }
    val bgLocation = remember(tick) { location && AutoDrive.hasBackgroundLocation(ctx) }
    val unrestricted = remember(tick) { AutoDrive.isUnrestricted(ctx) }
    val bgPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var confirmClear by remember { mutableStateOf(false) }
    var bgDisclosure by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val checks = listOf(musicAccess, bgLocation, unrestricted, notifs, waze, ytm, syncStatus !is SyncManager.Status.NotSetUp)
            ReadinessHeader(checks.count { it }, checks.size)
        }
        crashes?.let { log ->
            item {
                val lastEntry = log.substringAfterLast("=== ", log).lines().take(14).joinToString("\n")
                Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DeckColors.Warn.copy(alpha = 0.45f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (syncStatus is SyncManager.Status.NotSetUp) "Tap Copy and paste it to Claude so it can be fixed."
                            else "The report was sent to your private sync repo, so Claude can read it and fix it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(color = DeckColors.SurfaceHigh, shape = RoundedCornerShape(10.dp)) {
                            Text(lastEntry, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 14)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("DRIVEDECK crash", log.takeLast(8000)))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                            OutlinedButton(onClick = { com.drivedeck.CrashLog.clear(ctx); tick++ }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        item { SectionLabel("SYNC · LAPTOP, PHONE & CHAT") }
        item { SyncCard(sync, syncStatus) }
        item { SectionLabel("CHECKLIST") }
        item {
            CheckCard(
                ok = musicAccess,
                title = "Music & messages access",
                body = if (musicAccess) "Background mode, song stats and the live traffic ETA are on"
                else "Enable DRIVEDECK under Notification access. This is what lets DRIVEDECK notice Android Auto connecting, count your songs and read the traffic ETA.",
                note = if (musicAccess) null
                else "Messages are only kept in memory while they're unread, never saved or synced. " +
                    "Samsung: if the switch is greyed out, open App info → ⋮ → Allow restricted settings.",
                actions = if (musicAccess) emptyList() else listOf(
                    "Enable" to { openNotificationAccess(ctx) },
                    "App info" to { openAppInfo(ctx) },
                ),
            )
        }
        item {
            CheckCard(
                ok = bgLocation,
                title = "Location: Allow all the time",
                body = when {
                    bgLocation -> "Drives record by themselves when Android Auto connects, with DRIVEDECK closed"
                    coarseOnly -> "Set to Approximate. Turn on Precise, then \"Allow all the time\"."
                    location -> "Set to \"Only while using\". Choose \"Allow all the time\" so drives record without opening DRIVEDECK."
                    else -> "Needed to record your drives (speed, averages, distance) and for speed camera alerts."
                },
                note = if (bgLocation) null
                else "DRIVEDECK only uses location while a drive is recording. It's never shared, apart from your drive summaries in your own private sync repo.",
                actions = when {
                    bgLocation -> emptyList()
                    coarseOnly -> listOf("Settings" to { openAppInfo(ctx) })
                    location -> listOf(
                        "Allow all the time" to { bgDisclosure = true },
                        "Settings" to { openAppInfo(ctx) },
                    )
                    else -> listOf(
                        "Allow" to {
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                    )
                },
            )
        }
        item {
            CheckCard(
                ok = unrestricted,
                title = "Battery: Unrestricted",
                body = if (unrestricted) "Android won't put DRIVEDECK to sleep, so it's ready every time you plug in"
                else "Samsung puts apps to sleep. Set DRIVEDECK's battery to Unrestricted so it can start recording by itself.",
                note = if (unrestricted) null else "App info → Battery → Unrestricted.",
                actions = if (unrestricted) emptyList() else listOf("App info" to { openAppInfo(ctx) }),
            )
        }
        item {
            CheckCard(
                ok = waze, title = "Waze",
                body = if (waze) "Use it as normal in the car. DRIVEDECK reads its arrival time for your stats."
                else "Your navigation in the car",
                actions = if (waze) emptyList() else listOf("Install" to { openStore(ctx, PhoneNavigator.WAZE_PACKAGE) }),
            )
        }
        item {
            CheckCard(
                ok = ytm, title = "YouTube Music",
                body = if (ytm) "Use it as normal in the car. DRIVEDECK counts your songs for the weekly review."
                else "Your music in the car",
                actions = if (ytm) emptyList() else listOf("Install" to { openStore(ctx, YtMusicController.YTM_PACKAGE) }),
            )
        }

        item {
            CheckCard(
                ok = notifs, title = "Notifications",
                body = if (notifs) "Drive summaries and the trip computer can show"
                else "Android needs this to keep the trip computer running, and to show your \"drive saved\" summary.",
                actions = if (notifs) emptyList() else listOf("Allow" to { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }),
            )
        }
        item { SectionLabel("YOUR CAR") }
        item { CarSettingsCard(settings) { f -> repo.updateSettings(f) } }
        item { SectionLabel("OPTIONAL · DRIVEDECK ON THE CAR SCREEN") }
        item {
            StepsCard(
                listOf(
                    "You don't need this: background mode works with DRIVEDECK closed.",
                    "To have the tiles on the car screen anyway: Android Auto settings → Customise launcher → tick DRIVEDECK.",
                ),
                button = "Open Android Auto settings" to { openAndroidAuto(ctx) },
            )
        }

        item { SectionLabel("WHAT DRIVEDECK HAS LEARNED") }
        item {
            val counts = trips.groupingBy { it.placeId }.eachCount()
            val total = trips.size
            Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (total == 0) "No trips yet. Every drive you start from DRIVEDECK teaches it your routine."
                        else "$total trip${if (total == 1) "" else "s"} logged. Stored only on this phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    places.filter { (counts[it.id] ?: 0) > 0 }
                        .sortedByDescending { counts[it.id] }
                        .forEach { p ->
                            val n = counts[p.id] ?: 0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(p.icon.iconRes()), null, tint = DeckColors.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(p.name, Modifier.width(110.dp), maxLines = 1)
                                LinearProgressIndicator(
                                    progress = { n / total.toFloat() },
                                    modifier = Modifier.weight(1f).height(6.dp),
                                    color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh,
                                    gapSize = 0.dp, drawStopIndicator = {},
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    if (total > 0) {
                        TextButton(onClick = { confirmClear = true }) { Text("Reset routine", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            Text(
                "DRIVEDECK v${versionName(ctx)} · your data stays on your phone and your own private repo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (bgDisclosure) {
        AlertDialog(
            onDismissRequest = { bgDisclosure = false },
            containerColor = DeckColors.Surface,
            title = { Text("Record drives in the background") },
            text = {
                Text(
                    "DRIVEDECK collects location data to record your drives (speed, averages, distance) and give " +
                        "speed camera alerts, even when the app is closed or not in use. It only does this while " +
                        "Android Auto is connected or a trip is running. Location is never sold or shared; drive " +
                        "summaries only go to your own private sync repo.\n\nOn the next screen choose \"Allow all the time\".",
                )
            },
            confirmButton = {
                Button(onClick = {
                    bgDisclosure = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) bgPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { bgDisclosure = false }) { Text("Not now") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DeckColors.Surface,
            title = { Text("Reset routine?") },
            text = { Text("DRIVEDECK forgets all logged trips and starts learning again. Your places and music stay.") },
            confirmButton = { Button(onClick = { repo.clearTrips(); confirmClear = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReadinessHeader(ready: Int, total: Int) {
    val done = ready == total
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, DeckColors.Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                if (done) "READY TO DRIVE" else "SETUP",
                style = MaterialTheme.typography.labelSmall,
                color = if (done) DeckColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text("$ready of $total ready", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.sp))
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { ready / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh,
                                    gapSize = 0.dp, drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun CheckCard(ok: Boolean, title: String, body: String, actions: List<Pair<String, () -> Unit>>, note: String? = null) {
    Surface(
        color = DeckColors.Surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (ok) DeckColors.Outline else DeckColors.Warn.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(if (ok) R.drawable.ic_ok else R.drawable.ic_warn), null,
                    tint = if (ok) DeckColors.Accent else DeckColors.Warn,
                    modifier = Modifier.padding(top = 2.dp).size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (note != null) {
                Surface(color = DeckColors.SurfaceHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(start = 34.dp, top = 10.dp)) {
                    Text(note, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(start = 34.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { i, (label, action) ->
                        if (i == 0) Button(onClick = action) { Text(label) } else OutlinedButton(onClick = action) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsCard(steps: List<String>, button: Pair<String, () -> Unit>) {
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, s ->
                Row {
                    Surface(shape = RoundedCornerShape(50), color = DeckColors.SurfaceHigh, modifier = Modifier.size(24.dp)) {
                        Text("${i + 1}", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelLarge,
                            color = DeckColors.Accent, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = button.second, modifier = Modifier.fillMaxWidth()) { Text(button.first) }
        }
    }
}

// ---------- System intents ----------

private fun isInstalled(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

private fun versionName(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

private fun Context.tryStart(vararg intents: Intent): Boolean {
    for (i in intents) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
    }
    return false
}

private fun openNotificationAccess(ctx: Context) {
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, MediaListenerService::class.java).flattenToString(),
            )
    } else null
    ctx.tryStart(*listOfNotNull(direct, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).toTypedArray())
}

private fun openAppInfo(ctx: Context) =
    ctx.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))

private fun openStore(ctx: Context, pkg: String) = ctx.tryStart(
    Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()),
    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()),
)

private fun openAndroidAuto(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
    val ok = ctx.tryStart(
        *listOfNotNull(
            launch,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$ANDROID_AUTO_PACKAGE".toUri()),
        ).toTypedArray(),
    )
    if (!ok) Toast.makeText(ctx, "Open Settings → Connected devices → Android Auto", Toast.LENGTH_LONG).show()
}

@Composable
private fun SyncCard(sync: SyncManager, status: SyncManager.Status) {
    val cfg = remember(status) { sync.config() }
    var owner by remember { mutableStateOf(cfg?.owner ?: SyncManager.DEFAULT_OWNER) }
    var repoName by remember { mutableStateOf(cfg?.repo ?: SyncManager.DEFAULT_REPO) }
    var token by remember { mutableStateOf("") }
    var editing by remember(status) { mutableStateOf(status is SyncManager.Status.NotSetUp) }
    val (ok, text) = when (status) {
        is SyncManager.Status.NotSetUp -> false to "Not connected. Paste your DRIVEDECK GitHub token to sync with your laptop dashboard and chat."
        is SyncManager.Status.Syncing -> true to "Syncing…"
        is SyncManager.Status.Synced -> true to "Synced ${android.text.format.DateUtils.getRelativeTimeSpanString(status.at)} with $owner/$repoName"
        is SyncManager.Status.Error -> false to status.message
    }
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (ok) DeckColors.Outline else DeckColors.Warn.copy(alpha = 0.45f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sync), null, tint = if (ok) DeckColors.Accent else DeckColors.Warn)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(owner, { owner = it }, label = { Text("GitHub user") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(repoName, { repoName = it }, label = { Text("Data repo") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    token, { token = it }, label = { Text("Token (github_pat_…)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                AccentButton("Connect", enabled = token.isNotBlank() && owner.isNotBlank() && repoName.isNotBlank(), onClick = {
                    sync.saveConfig(owner, repoName, token); token = ""; editing = false
                })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("Sync now", onClick = { sync.requestSync() })
                    OutlinedButton(onClick = { editing = true }) { Text("Change") }
                    TextButton(onClick = { sync.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun CarSettingsCard(settings: com.drivedeck.data.DeckSettings, update: ((com.drivedeck.data.DeckSettings) -> com.drivedeck.data.DeckSettings) -> Unit) {
    var consumption by remember(settings.litresPer100Km) { mutableStateOf(settings.litresPer100Km.toString()) }
    var tank by remember(settings.tankLitres) { mutableStateOf(settings.tankLitres.toString().removeSuffix(".0")) }
    Surface(color = DeckColors.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingSwitch(
                "Record drives automatically",
                "Starts when Android Auto connects and stops when you unplug. No need to open DRIVEDECK.",
                settings.autoRecord,
            ) { on -> update { it.copy(autoRecord = on) } }
            SettingSwitch(
                "Low fuel reminder",
                "When the tank's probably low, it says so at the start of a drive, with the cheapest servo nearby",
                settings.fuelReminder,
            ) { on -> update { it.copy(fuelReminder = on) } }
            Text("Phone navigation app", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.drivedeck.data.NavApp.entries.forEach { app ->
                    FilterChip(
                        selected = settings.phoneNavApp == app, onClick = { update { it.copy(phoneNavApp = app) } },
                        label = { Text(app.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DeckColors.Accent, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                    )
                }
            }
            Text("In the car, Android Auto uses the navigation app you last opened on the car screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSwitch(
                "Speed camera voice alerts",
                "\"Speed camera ahead, 60 zone\" for fixed and red-light cameras on your road",
                settings.cameraAlerts,
            ) { on -> update { it.copy(cameraAlerts = on) } }
            OutlinedTextField(
                consumption, { v -> consumption = v; v.toDoubleOrNull()?.takeIf { it in 2.0..30.0 }?.let { d -> update { it.copy(litresPer100Km = d) } } },
                label = { Text("Fuel economy (L/100km)") }, singleLine = true,
                supportingText = { Text("Used to estimate fuel cost per drive until your fill-ups measure it") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                tank, { v -> tank = v; v.toDoubleOrNull()?.takeIf { it in 20.0..150.0 }?.let { d -> update { it.copy(tankLitres = d) } } },
                label = { Text("Fuel tank (litres)") }, singleLine = true,
                supportingText = { Text("For the low fuel reminder. Cerato GT: 50 L") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = DeckColors.Accent),
        )
    }
}
