package com.drivedeck.messages

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads a chat aloud through the car speakers (Android routes TTS to the car while connected). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val t = tts
        if (t != null && ready) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drivedeck")
            return
        }
        pending = text
        if (t == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "drivedeck") }
                    pending = null
                }
            }
        }
    }

    fun stop() { tts?.stop() }
}
