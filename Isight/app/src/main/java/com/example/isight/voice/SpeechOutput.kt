package com.example.isight.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Wraps Android's [TextToSpeech] for spoken guidance/alerts.
 *
 * [speak] with `flushQueue = true` interrupts anything currently playing —
 * used for RED-tier safety alerts, since a stale navigation sentence must
 * never delay an urgent obstacle warning.
 */
class SpeechOutput(context: Context, private val onReady: (Boolean) -> Unit = {}) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = ConcurrentLinkedQueue<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                while (true) {
                    val next = pendingUtterances.poll() ?: break
                    speakNow(next, flush = false)
                }
            }
            onReady(ready)
        }
    }

    fun speak(text: String, flushQueue: Boolean = false) {
        if (!ready) {
            pendingUtterances.add(text)
            return
        }
        speakNow(text, flushQueue)
    }

    private fun speakNow(text: String, flush: Boolean) {
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, text.hashCode().toString())
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
