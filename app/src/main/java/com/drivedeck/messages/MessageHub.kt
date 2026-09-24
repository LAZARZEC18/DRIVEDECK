package com.drivedeck.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One WhatsApp chat with its unread messages. */
data class Conversation(
    val key: String,
    val app: String,
    val title: String,
    val messages: List<Message>,
    val postedAt: Long,
    val isGroup: Boolean,
    val reply: ReplyAction?,
    val markRead: PendingIntent?,
) {
    data class Message(val sender: String?, val text: String, val at: Long)

    val unread: Int get() = messages.size
    val canReply: Boolean get() = reply != null
    val lastText: String get() = messages.lastOrNull()?.text.orEmpty()
}

class ReplyAction internal constructor(internal val intent: PendingIntent, internal val remoteInput: RemoteInput)

/**
 * Reads WhatsApp (and WhatsApp Business) chat notifications so the car screen can list who
 * messaged, show the texts when you're parked, and send quick replies through WhatsApp's own
 * notification reply button, the same mechanism Android Auto uses.
 * Everything is kept in memory only and disappears when the notification is cleared.
 */
object MessageHub {
    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val chats = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = chats.asStateFlow()

    val quickReplies = listOf("Driving, I'll reply soon 🚗", "On my way", "Can't talk, call you later", "👍")

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        val n = sbn.notification ?: return
        // Skip the summary ("5 messages from 3 chats") and non-message notifications.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val title = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val messages = style?.messages?.map { m ->
            Conversation.Message(m.person?.name?.toString(), m.text?.toString().orEmpty(), m.timestamp)
        }?.filter { it.text.isNotBlank() }
            ?: listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { Conversation.Message(null, it, sbn.postTime) })
        if (messages.isEmpty()) return

        val actions = n.actions.orEmpty()
        val reply = actions.firstNotNullOfOrNull { a ->
            val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@firstNotNullOfOrNull null
            ReplyAction(a.actionIntent, ri)
        }
        val markRead = actions.firstOrNull { a ->
            (android.os.Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                a.title?.toString()?.contains("read", ignoreCase = true) == true
        }?.actionIntent

        val convo = Conversation(
            key = sbn.key, app = sbn.packageName, title = title,
            messages = messages.takeLast(20), postedAt = sbn.postTime,
            isGroup = style?.isGroupConversation == true,
            reply = reply, markRead = markRead,
        )
        chats.value = (chats.value.filterNot { it.key == sbn.key } + convo).sortedByDescending { it.postedAt }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        chats.value = chats.value.filterNot { it.key == sbn.key }
    }

    /** Sends [text] through WhatsApp's own reply action. */
    fun reply(context: Context, convo: Conversation, text: String): Boolean {
        val r = convo.reply ?: return false
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(r.remoteInput), fill, Bundle().apply { putCharSequence(r.remoteInput.resultKey, text) })
        return try {
            r.intent.send(context, 0, fill)
            chats.value = chats.value.filterNot { it.key == convo.key }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    fun markRead(convo: Conversation): Boolean {
        val pi = convo.markRead ?: return false
        return try {
            pi.send(); chats.value = chats.value.filterNot { it.key == convo.key }; true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    /** For tests and previews. */
    internal fun setForTests(list: List<Conversation>) { chats.value = list }
}
