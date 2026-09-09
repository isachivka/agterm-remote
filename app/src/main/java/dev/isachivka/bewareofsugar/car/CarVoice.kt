package dev.isachivka.bewareofsugar.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * The phone's speech recogniser, listening for the car.
 *
 * ### Why the phone's
 *
 * The car library has no speech of its own; what it has is a keyboard the host shows only when
 * parked. *"все, что я говорю, должно вводиться в поле ввода наше"* - REQ-0044 - so the words come
 * from the platform recogniser running on the phone, with partial results so the band moves while
 * the owner is still talking. Whether its audio comes from the car's microphone or the phone's while
 * projecting is REQ-0044 §5, question 3, and only a car can answer it.
 *
 * ### Two languages, and the recogniser decides which - REQ-0044 decision 11
 *
 * A request is recognised in one language, and with nothing said it is the phone's system language,
 * which mangles an English command spoken inside a Russian sentence. Android 14 lets a request name
 * the languages it may hear and switch between them as it goes; [LANGUAGES] is Russian and English,
 * in that order, and nothing in Settings chooses between them. *"русский и английский,
 * автоопределение, никаких переключателей"* - the owner, 2026-09-05. How well the switch holds a
 * Russian sentence with `git status` in the middle is a thing only the car will show.
 *
 * ### A beep when it is ready - *"давай сделай просто бип"*
 *
 * The band says "Listening" the moment the recogniser reports it is ready, but the owner is looking
 * at the road, not the band. So the same moment gets a short tone. It is played on the media stream,
 * which is the one Android Auto is most likely to carry to the car's speakers; whether it does, or
 * the phone beeps to itself in the cup holder, is one more line for the first-run script.
 *
 * ### What it never does
 *
 * It does not ask for the permission. That is the screen's job through the car library, which raises
 * the dialog on the phone; this class only reports whether it was granted. Refusal is a supported
 * state, as it is for the camera: the keys and the car's keyboard keep working without it.
 */
class CarVoice(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Main thread only, which is where the car's callbacks arrive. */
    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = beep()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onError(error)
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!heard.isNullOrEmpty()) onPartial(heard)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Android 14 and up, which is this app's floor: detect among these, and switch.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGES.first())
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(LANGUAGES))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(LANGUAGES))
            },
        )
    }

    private fun beep() {
        val t = tone ?: try {
            ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { tone = it }
        } catch (e: RuntimeException) {
            // No audio path for tones on this device. The band still says "Listening"; nothing else to do.
            return
        }
        t.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tone?.release()
        tone = null
    }

    companion object {
        /** The languages the car may hear, first one first. IETF tags, as the recogniser wants them. */
        val LANGUAGES: List<String> = listOf("ru-RU", "en-US")

        /** Loud enough to hear over a road, short enough not to be in the recording. */
        const val BEEP_VOLUME = 80
        const val BEEP_MS = 120
    }
}
