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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

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

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val ready = listOf(musicAccess, location, waze, ytm).count { it }
            ReadinessHeader(ready, 4)
        }
        item { SectionLabel("CHECKLIST") }
        item {
            CheckCard(
                ok = musicAccess,
                title = "Music control",
                body = if (musicAccess) "Can start and control YouTube Music"
                else "Lets the car screen start playlists. Enable DRIVEDECK under Notification access.",
                note = if (musicAccess) null
                else "DRIVEDECK never reads notifications. Android keeps media control under that switch. " +
                    "Samsung: if the switch is greyed out, open App info → ⋮ → Allow restricted settings.",
                actions = if (musicAccess) emptyList() else listOf(
                    "Enable" to { openNotificationAccess(ctx) },
                    "App info" to { openAppInfo(ctx) },
                ),
            )
        }
        item {
            CheckCard(
                ok = location,
                title = "Location",
                body = when {
                    location -> "Distances on, and no suggestion for where you already are"
                    coarseOnly -> "Set to Approximate. Turn on Precise so pins and \"you're here\" are accurate."
                    else -> "Optional. Adds distances and skips suggesting where you already are."
                },
                actions = when {
                    location -> emptyList()
                    coarseOnly -> listOf("Settings" to { openAppInfo(ctx) })
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
                ok = waze, title = "Waze",
                body = if (waze) "Open it once on the car screen so Android Auto uses it for navigation"
                else "Receives your destination and drives the route",
                actions = if (waze) emptyList() else listOf("Install" to { openStore(ctx, PhoneNavigator.WAZE_PACKAGE) }),
            )
        }
        item {
            CheckCard(
                ok = ytm, title = "YouTube Music",
                body = if (ytm) "Ready for the music hub" else "Powers the music hub",
                actions = if (ytm) emptyList() else listOf("Install" to { openStore(ctx, YtMusicController.YTM_PACKAGE) }),
            )
        }

        item { SectionLabel("SHOW DRIVEDECK IN THE CAR (ONE TIME)") }
        item {
            StepsCard(
                listOf(
                    "Open Android Auto settings: Settings → Connected devices → Android Auto (the button below tries to take you there).",
                    "Scroll to the bottom and tap \"Version\" about 10 times, then OK to allow developer settings.",
                    "Tap ⋮ (top right) → Developer settings → tick \"Unknown sources\".",
                    "Back in Android Auto settings → Customise launcher → make sure DRIVEDECK is ticked.",
                    "Plug into the car. DRIVEDECK is in the app launcher. Drag it next to Waze and YouTube Music.",
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
                "DRIVEDECK v${versionName(ctx)} · everything stays on your phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
