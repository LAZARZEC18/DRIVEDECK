@file:Suppress("DEPRECATION")

package com.drivedeck.car

import android.text.format.DateUtils
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.messages.Conversation
import com.drivedeck.messages.MessageHub
import com.drivedeck.messages.Speaker
import com.drivedeck.music.YtMusicController
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** WhatsApp chats waiting for you: who, how many, how long ago. */
class MessagesScreen(carContext: CarContext) : Screen(carContext) {

    init {
        marker = QuickReplyScreen.MESSAGES_ROOT
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { MessageHub.conversations.drop(1).collect { invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        if (!YtMusicController(carContext).hasNotificationAccess()) {
            return MessageTemplate.Builder("Turn on \"Music & messages access\" in the DRIVEDECK phone app to see WhatsApp here.")
                .setTitle("WhatsApp").setHeaderAction(Action.BACK).build()
        }
        val chats = MessageHub.conversations.value
        val list = ItemList.Builder().setNoItemsMessage("No new WhatsApp messages")
        chats.take(CarUi.listLimit(carContext)).forEach { c ->
            val ago = DateUtils.getRelativeTimeSpanString(c.postedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            list.addItem(
                Row.Builder()
                    .setTitle(c.title)
                    .addText("${c.unread} new${if (c.isGroup) " · group" else ""} · $ago")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_chat, CarUi.ACCENT))
                    .setOnClickListener { screenManager.push(ConversationScreen(carContext, c.key)) }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("WhatsApp")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * One chat. While driving: sender, count, read-aloud and quick replies. The full text
 * ("Show messages") opens a view that Android Auto only allows when you're parked.
 */
class ConversationScreen(carContext: CarContext, private val key: String) : Screen(carContext) {

    private fun convo(): Conversation? = MessageHub.conversations.value.firstOrNull { it.key == key }

    override fun onGetTemplate(): Template {
        val c = convo() ?: return MessageTemplate.Builder("This chat was read or cleared on your phone.")
            .setTitle("WhatsApp").setHeaderAction(Action.BACK).build()

        val pane = Pane.Builder()
            .addRow(
                Row.Builder().setTitle("${c.unread} new message${if (c.unread == 1) "" else "s"}")
                    .addText(if (c.isGroup) "Group chat" else "From ${c.title}")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_chat, CarUi.ACCENT))
                    .build(),
            )
            .addAction(
                Action.Builder().setTitle("Read aloud").setBackgroundColor(CarUi.ACCENT)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_volume, CarColor.DEFAULT))
                    .setOnClickListener { Speaker.speak(carContext, spoken(c)) }.build(),
            )
            .addAction(
                Action.Builder().setTitle("Reply")
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_reply, CarColor.DEFAULT))
                    .setOnClickListener {
                        if (c.canReply) screenManager.push(QuickReplyScreen(carContext, key))
                        else CarToast.makeText(carContext, "WhatsApp doesn't allow replies to this chat here", CarToast.LENGTH_LONG).show()
                    }.build(),
            )
        return PaneTemplate.Builder(pane.build())
            .setTitle(c.title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Show messages").setOnClickListener {
                        if (carContext.carAppApiLevel >= 2) screenManager.push(FullTextScreen(carContext, key))
                        else CarToast.makeText(carContext, "Not supported by this car", CarToast.LENGTH_SHORT).show()
                    }.build(),
                ).build(),
            )
            .build()
    }

    private fun spoken(c: Conversation) = buildString {
        append(if (c.isGroup) "In ${c.title}. " else "${c.title} says. ")
        c.messages.forEach { m -> if (c.isGroup && m.sender != null) append("${m.sender}: ") ; append(m.text).append(". ") }
    }
}

/** Full message text. Android Auto only shows this template while parked. */
class FullTextScreen(carContext: CarContext, private val key: String) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val c = MessageHub.conversations.value.firstOrNull { it.key == key }
            ?: return MessageTemplate.Builder("Chat cleared.").setTitle("WhatsApp").setHeaderAction(Action.BACK).build()
        val text = c.messages.joinToString("\n\n") { m ->
            val time = DateUtils.formatDateTime(carContext, m.at, DateUtils.FORMAT_SHOW_TIME)
            (if (m.sender != null && c.isGroup) "${m.sender} · $time\n" else "$time\n") + m.text
        }
        val b = LongMessageTemplate.Builder(text).setTitle(c.title).setHeaderAction(Action.BACK)
        if (c.canReply) {
            b.addAction(Action.Builder().setTitle("Reply").setBackgroundColor(CarUi.ACCENT)
                .setOnClickListener(ParkedOnlyOnClickListener.create { screenManager.push(QuickReplyScreen(carContext, key)) }).build())
        }
        b.addAction(Action.Builder().setTitle("Mark read").setOnClickListener(ParkedOnlyOnClickListener.create {
            MessageHub.markRead(c); screenManager.pop()
        }).build())
        return b.build()
    }
}

/** One-tap replies sent through WhatsApp itself. */
class QuickReplyScreen(carContext: CarContext, private val key: String) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        MessageHub.quickReplies.forEach { text ->
            list.addItem(
                Row.Builder().setTitle(text).setOnClickListener {
                    val c = MessageHub.conversations.value.firstOrNull { it.key == key }
                    val ok = c != null && MessageHub.reply(carContext, c, text)
                    CarToast.makeText(carContext, if (ok) "Sent" else "Couldn't send", CarToast.LENGTH_SHORT).show()
                    screenManager.popTo(MESSAGES_ROOT)
                }.build(),
            )
        }
        return ListTemplate.Builder().setTitle("Quick reply").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }

    companion object { const val MESSAGES_ROOT = "messages" }
}
