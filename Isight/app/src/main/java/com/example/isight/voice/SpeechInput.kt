package com.example.isight.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

private const val TAG = "SpeechInput"

/**
 * Wraps Android's built-in [SpeechRecognizer] for single-utterance voice
 * commands ("take me to the kitchen", "stop", etc).
 *
 * OFFLINE NOTE: requests on-device recognition via
 * [RecognizerIntent.EXTRA_PREFER_OFFLINE], but Android's SpeechRecognizer
 * does not guarantee fully offline operation on every device/OS version —
 * some devices fall back to network-based recognition if no offline
 * language pack is installed. This is the practical "appropriate
 * offline-capable speech system" this project's spec allows for, not a hard
 * on-device guarantee (a true fully-offline path would mean bundling a
 * separate on-device ASR model, a bigger separate integration).
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device")
            return
        }
        if (listening) return

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speechRecognizer
        listening = true

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                listening = false
                onError(speechErrorMessage(error))
                releaseRecognizer(speechRecognizer)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onError("Didn't catch that") else onResult(text)
                releaseRecognizer(speechRecognizer)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    fun cancel() {
        recognizer?.let { releaseRecognizer(it) }
        listening = false
    }

    private fun releaseRecognizer(instance: SpeechRecognizer) {
        instance.destroy()
        if (recognizer === instance) recognizer = null
    }

    private fun speechErrorMessage(error: Int): String {
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
            else -> "Speech recognition error ($error)"
        }
        Log.w(TAG, message)
        return message
    }
}
