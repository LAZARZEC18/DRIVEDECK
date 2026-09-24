package com.drivedeck.music

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.drivedeck.data.MusicFavorite
import com.drivedeck.data.MusicKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Drives YouTube Music without touching its UI, the same way Google Assistant does:
 * through YouTube Music's media session ("play from search" + transport controls).
 *
 * How we reach the session, in order:
 *  1. The active media session. Needs "Notification access" for DRIVEDECK. This is the normal
 *     path in the car, because Android Auto keeps YouTube Music's session alive.
 *  2. Binding YouTube Music's media browser service (YouTube Music may only allow Google callers).
 *  3. Waking YouTube Music with a media-button broadcast aimed only at YouTube Music (the same
 *     signal a Bluetooth headset sends), then waiting for its session to appear.
 *  4. The MEDIA_PLAY_FROM_SEARCH intent. Works when DRIVEDECK is in the foreground on the phone.
 */
class YtMusicController(context: Context) {

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(ctx, MediaListenerService::class.java)

    data class NowPlaying(val title: String?, val artist: String?, val isPlaying: Boolean)

    data class PlayResult(val ok: Boolean, val message: String)

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun isInstalled(): Boolean = runCatching { ctx.packageManager.getPackageInfo(YTM_PACKAGE, 0) }.isSuccess

    /** YouTube Music's live media session, if it has one and we're allowed to see it. */
    fun findSession(): MediaController? {
        if (!hasNotificationAccess()) return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        return try {
            msm.getActiveSessions(listenerComponent).firstOrNull { it.packageName == YTM_PACKAGE }
        } catch (_: SecurityException) {
            null
        }
    }

    fun nowPlaying(): NowPlaying? {
        val c = findSession() ?: return null
        val md = c.metadata
        return NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING ||
                c.playbackState?.state == PlaybackState.STATE_BUFFERING,
        )
    }

    fun togglePlayPause(): Boolean {
        val c = findSession() ?: return false
        val state = c.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) c.transportControls.pause()
        else c.transportControls.play()
        return true
    }

    fun skipNext(): Boolean = findSession()?.transportControls?.skipToNext()?.let { true } ?: false

    fun skipPrevious(): Boolean = findSession()?.transportControls?.skipToPrevious()?.let { true } ?: false

    /** Starts [fav] playing in YouTube Music. Safe to call from the car (no activity needed for paths 1 and 2). */
    suspend fun play(fav: MusicFavorite, allowActivityFallback: Boolean = false): PlayResult {
        if (!isInstalled()) return PlayResult(false, "YouTube Music isn't installed")
        val (query, extras) = searchRequest(fav)

        // 1) Live session
        findSession()?.let { c ->
            val uri = fav.url?.toUri()
            val canUri = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_URI != 0L
            if (uri != null && canUri) c.transportControls.playFromUri(uri, extras)
            else c.transportControls.playFromSearch(query, extras)
            return PlayResult(true, "Playing ${fav.name}")
        }

        // 2) Wake YouTube Music through its media browser service
        if (playViaBrowser(query, extras)) return PlayResult(true, "Playing ${fav.name}")

        // 3) Nudge YouTube Music awake with a media button, then use its fresh session
        wakeWithMediaButton()?.let { c ->
            c.transportControls.playFromSearch(query, extras)
            return PlayResult(true, "Playing ${fav.name}")
        }

        // 4) Activity intent (phone in foreground only)
        if (allowActivityFallback && openInYouTubeMusic(fav)) return PlayResult(true, "Opening ${fav.name}")

        return PlayResult(
            false,
            if (hasNotificationAccess()) "Open YouTube Music on the car screen once, then tap again"
            else "Turn on music access in the DRIVEDECK phone app",
        )
    }

    /** Opens YouTube Music on the phone at the favourite (link if we have one, otherwise play-from-search). */
    fun openInYouTubeMusic(fav: MusicFavorite): Boolean {
        val (query, extras) = searchRequest(fav)
        val intent = if (fav.url != null) {
            Intent(Intent.ACTION_VIEW, fav.url.toUri())
        } else {
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(extras).putExtra(SearchManager.QUERY, query)
        }.setPackage(YTM_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent); true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Watches YouTube Music's session (track changes, play/pause) and calls [onChange].
     * Close the returned handle when the screen goes away.
     */
    fun observe(onChange: () -> Unit): AutoCloseable {
        var current: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
            override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
            override fun onSessionDestroyed() { current = null; onChange() }
        }
        fun rebind(notify: Boolean) {
            val next = findSession()
            if (next?.sessionToken != current?.sessionToken) {
                current?.unregisterCallback(callback)
                current = next
                next?.registerCallback(callback, main)
            }
            if (notify) onChange()
        }
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { rebind(notify = true) }
        val registered = try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main); msm != null
        } catch (_: SecurityException) {
            false
        }
        rebind(notify = false)
        return AutoCloseable {
            current?.unregisterCallback(callback)
            if (registered) runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
    }

    private suspend fun playViaBrowser(query: String, extras: Bundle): Boolean {
        val service = findBrowserService() ?: return false
        var browser: MediaBrowserCompat? = null
        val connected = withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() { if (cont.isActive) cont.resume(true) }
                    override fun onConnectionFailed() { if (cont.isActive) cont.resume(false) }
                    override fun onConnectionSuspended() { if (cont.isActive) cont.resume(false) }
                }
                main.post {
                    browser = MediaBrowserCompat(ctx, service, cb, null).also { it.connect() }
                }
                cont.invokeOnCancellation { main.post { browser?.disconnect() } }
            }
        } ?: false
        val b = browser
        if (!connected || b == null) { main.post { b?.disconnect() }; return false }
        return try {
            MediaControllerCompat(ctx, b.sessionToken).transportControls.playFromSearch(query, extras)
            // Keep the connection briefly so playback actually starts, then let go.
            main.postDelayed({ b.disconnect() }, 15_000)
            true
        } catch (_: Exception) {
            main.post { b.disconnect() }
            false
        }
    }

    /**
     * Sends PLAY to YouTube Music's media-button receiver only (explicit package, so no other
     * music app starts) and waits up to 4 s for its session to appear.
     */
    private suspend fun wakeWithMediaButton(): MediaController? {
        if (!hasNotificationAccess()) return null
        val receivers = ctx.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(YTM_PACKAGE), 0,
        )
        if (receivers.isEmpty()) return null
        val r = receivers.first().activityInfo
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(r.packageName, r.name))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { ctx.sendBroadcast(intent) }
        }
        return withTimeoutOrNull(4_000) {
            var c = findSession()
            while (c == null) { delay(250); c = findSession() }
            c
        }
    }

    private fun findBrowserService(): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(YTM_PACKAGE)
        val info = ctx.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    companion object {
        const val YTM_PACKAGE = "com.google.android.apps.youtube.music"

        /** Structured search hints, same format Google Assistant uses ("play X playlist on YouTube Music"). */
        fun searchRequest(fav: MusicFavorite): Pair<String, Bundle> {
            val q = fav.query.ifBlank { fav.name }
            val extras = Bundle()
            when (fav.kind) {
                MusicKind.PLAYLIST -> {
                    extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                    @Suppress("DEPRECATION") extras.putString(MediaStore.EXTRA_MEDIA_PLAYLIST, q)
                }
                MusicKind.ARTIST -> {
                    extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, q)
                }
                MusicKind.SONG -> {
                    extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    extras.putString(MediaStore.EXTRA_MEDIA_TITLE, q)
                }
                MusicKind.MIX -> Unit // free-text search works best for mixes/radio
            }
            return q to extras
        }
    }
}
