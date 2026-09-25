package com.drivedeck.messages

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks through the car speakers (Android routes it to the car while Android Auto is connected).
 * Uses the navigation-guidance channel and briefly ducks the music, the same way Waze's voice does,
 * so an alert never stops your song.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun speak(context: Context, text: String) {
        val app = context.applicationContext
        val t = tts
        if (t != null && ready) {
            say(app, t, text)
            return
        }
        pending = text
        if (t == null) {
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                val engine = tts
                if (ready && engine != null) {
                    engine.language = Locale.getDefault()
                    engine.setAudioAttributes(attributes)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = release(app)
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = release(app)
                    })
                    pending?.let { say(app, engine, it) }
                    pending = null
                }
            }
        }
    }

    private fun say(ctx: Context, engine: TextToSpeech, text: String) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null && focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
                .also { am.requestAudioFocus(it) }
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drivedeck")
    }

    private fun release(ctx: Context) {
        val f = focus ?: return
        focus = null
        ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
    }

    fun stop() { tts?.stop() }
}
