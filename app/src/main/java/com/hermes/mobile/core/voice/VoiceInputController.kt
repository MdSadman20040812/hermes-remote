package com.hermes.mobile.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hold-to-talk voice input (Phase 6): Android on-device SpeechRecognizer →
 * text → the cockpit composer. TTS playback via /api/audio/speak-stream is a
 * later increment; STT covers the hands-free dispatch case first.
 */
@Singleton
class VoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    var onFinalResult: ((String) -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (_listening.value) return
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onError(error: Int) { _listening.value = false }
            override fun onPartialResults(partialResults: Bundle?) {
                _partial.value = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onFinalResult?.invoke(text)
                _partial.value = ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    fun stop() {
        recognizer?.stopListening()
        _listening.value = false
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
