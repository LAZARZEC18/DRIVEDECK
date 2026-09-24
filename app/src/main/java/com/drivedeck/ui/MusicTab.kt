package com.drivedeck.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.MusicFavorite
import com.drivedeck.data.MusicKind
import com.drivedeck.music.MusicActions
import com.drivedeck.music.YtMusicController
import kotlinx.coroutines.launch

@Composable
fun MusicTab(modifier: Modifier, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val repo = remember { DeckRepository.get(ctx) }
    val music = remember { YtMusicController(ctx) }
    val favs by repo.music.collectAsStateWithLifecycle()
    val recents by repo.recents.collectAsStateWithLifecycle()
    var songQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<MusicFavorite?>(null) }
    var isNew by remember { mutableStateOf(false) }

    // Re-read "now playing" whenever YouTube Music changes track/state, or we come back to the app.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val hasAccess = remember(tick) { music.hasNotificationAccess() }
    // Re-register when access is granted: without it Android refuses the session listener.
    DisposableEffect(hasAccess) {
        val watch = music.observe { tick++ }
        onDispose { watch.close() }
    }
    val now = remember(tick) { music.nowPlaying() }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (!hasAccess) {
                    HintCard("Turn on music access in Setup so DRIVEDECK can start songs from the car screen and show what's playing.")
                } else {
                    NowPlayingCard(
                        title = now?.title, artist = now?.artist, playing = now?.isPlaying == true,
                        onToggle = { music.togglePlayPause() },
                        onPrev = { music.skipPrevious() },
                        onNext = { music.skipNext() },
                    )
                }
            }
            item {
                OutlinedTextField(
                    songQuery, { songQuery = it },
                    placeholder = { Text("Play any song, artist or album") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    trailingIcon = {
                        if (songQuery.isNotBlank()) TextButton(onClick = {
                            val q = songQuery
                            scope.launch { snackbar.showSnackbar(MusicActions.playSearch(ctx, q, allowActivityFallback = true).message) }
                            songQuery = ""
                        }) { Text("Play") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (recents.isNotEmpty()) {
                item { SectionLabel("RECENT SONGS", "${recents.size} / 20") }
                items(recents, key = { "r:" + it.key }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { scope.launch { snackbar.showSnackbar(MusicActions.playRecent(ctx, song).message) } }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_song), null, tint = DeckColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            song.artist?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Icon(painterResource(R.drawable.ic_play), "Play", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionLabel("PLAYLISTS & SHORTCUTS", "${minOf(favs.size, 4)} ON CAR HOME") }
            items(favs, key = { it.id }) { fav ->
                Card(
                    onClick = { isNew = false; editing = fav },
                    colors = CardDefaults.cardColors(containerColor = DeckColors.Surface),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = DeckColors.SurfaceHigh, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(painterResource(fav.kind.iconRes()), null, tint = DeckColors.Accent) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                fav.kind.label + if (fav.query != fav.name) " · \"${fav.query}\"" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalIconButton(onClick = {
                            scope.launch {
                                val r = music.play(fav, allowActivityFallback = true)
                                snackbar.showSnackbar(r.message)
                            }
                        }) { Icon(painterResource(R.drawable.ic_play), "Play") }
                    }
                }
            }
            item {
                Text(
                    "Tip: for an exact playlist, open it in YouTube Music → Share → Copy link, and paste it into the shortcut.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { isNew = true; editing = MusicFavorite(name = "", query = "") },
            icon = { Icon(painterResource(R.drawable.ic_add), null) },
            text = { Text("Add music") },
            containerColor = DeckColors.Accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { fav ->
        MusicDialog(
            initial = fav, isNew = isNew,
            onDismiss = { editing = null },
            onDelete = { repo.deleteMusic(fav.id); editing = null },
            onSave = { repo.upsertMusic(it); editing = null },
        )
    }
}

@Composable
private fun NowPlayingCard(
    title: String?, artist: String?, playing: Boolean,
    onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, DeckColors.AccentDim),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("NOW PLAYING · YOUTUBE MUSIC", style = MaterialTheme.typography.labelSmall, color = DeckColors.Accent)
            Text(title ?: "Nothing yet", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist ?: "Tap a shortcut below to start", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(painterResource(R.drawable.ic_next), "Previous", Modifier.size(28.dp).graphicsLayer { scaleX = -1f }) }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = onToggle, modifier = Modifier.size(60.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DeckColors.Accent),
                ) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), "Play/pause", Modifier.size(30.dp)) }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_next), "Next", Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
private fun MusicDialog(initial: MusicFavorite, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (MusicFavorite) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var query by remember { mutableStateOf(if (initial.query == initial.name) "" else initial.query) }
    var url by remember { mutableStateOf(initial.url.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        title = { Text(if (isNew) "New music shortcut" else "Edit shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (shown in the car)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MusicKind.entries.forEach { k ->
                        FilterChip(
                            selected = k == kind, onClick = { kind = k },
                            label = { Text(k.label.substringBefore(" /")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeckColors.Accent,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search for (optional)") },
                    placeholder = { Text(name.ifBlank { "e.g. Fred again.." }) },
                    supportingText = { Text("Leave empty to search the name") },
                )
                OutlinedTextField(
                    url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube Music link (optional)") },
                    placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                )
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                onSave(initial.copy(name = name.trim(), kind = kind, query = query.trim().ifBlank { name.trim() }, url = url.trim().ifBlank { null }))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
