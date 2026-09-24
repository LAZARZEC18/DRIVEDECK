package com.drivedeck.music

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.drivedeck.data.DeckRepository
import com.drivedeck.eta.LiveNavEta
import com.drivedeck.messages.MessageHub

/**
 * Android only lets an app see other apps' media sessions (what YouTube Music is playing) and
 * WhatsApp notifications if it holds notification-listener access. DRIVEDECK uses it for
 * four things only:
 *  1. controlling YouTube Music from the car screen,
 *  2. counting the songs you play (for your weekly stats),
 *  3. showing WhatsApp chats on the car screen with quick replies,
 *  4. reading the live traffic ETA from Waze / Google Maps' navigation notification.
 * Message text stays in memory on the phone. It's never saved or synced.
 */
class MediaListenerService : NotificationListenerService() {

    private var watch: AutoCloseable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val yt = YtMusicController(this)
        val repo = DeckRepository.get(this)
        var lastTitle: String? = null
        watch = yt.observe {
            val np = yt.nowPlaying() ?: return@observe
            val title = np.title ?: return@observe
            if (np.isPlaying && title != lastTitle) {
                lastTitle = title
                repo.logPlay(title, np.artist)
            }
        }
        runCatching { activeNotifications?.forEach { MessageHub.onPosted(this, it) } }
    }

    override fun onListenerDisconnected() {
        watch?.close(); watch = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        MessageHub.onPosted(this, sbn)
        LiveNavEta.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        MessageHub.onRemoved(sbn)
        LiveNavEta.onRemoved(sbn)
    }
}
