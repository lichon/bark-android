package io.github.lichon.abark.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.lichon.abark.bark.NotificationSpec
import io.github.lichon.abark.bark.Urgency
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads pushes aloud with the platform TextToSpeech engine.
 *
 * The engine is created on first use and released again after [IDLE_SHUTDOWN_MILLIS] of silence: a TTS service holds a
 * binder connection to the synthesis engine, so keeping one alive forever in a process that spends most of its life
 * idle on a socket is not worth it. Anything requested before the engine finishes initialising is queued, which is why
 * [speak] can be called from the courier thread the moment a push arrives.
 *
 * Speaking is skipped when the phone is silent or vibrate-only and when a push carries no readable text; the service
 * additionally only speaks pushes it managed to post as a notification.
 */
class NotificationSpeaker(private val context: Context) {

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val utterance = AtomicInteger()

    private var engine: TextToSpeech? = null
    private var initStatus: Int? = null
    private var ready = false

    /** The engine refused to initialise; do not keep retrying on every push for the rest of the process's life. */
    private var unavailable = false

    private val pending = ArrayDeque<String>()

    private val shutdown = Runnable { release() }

    /** Speak a push, if it has something to say and the user wants to hear it. */
    fun speak(spec: NotificationSpec) {
        // Passive means "notification shade only": speaking it would contradict the level the sender asked for.
        if (spec.urgency == Urgency.PASSIVE) return
        val text = spec.spokenText ?: return
        speak(text)
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isQuiet()) return

        synchronized(lock) {
            if (unavailable) return
            ensureEngineLocked()
            if (!ready) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(clean)
                return
            }
            handler.removeCallbacks(shutdown)
            engine?.speak(clean, TextToSpeech.QUEUE_ADD, null, nextUtteranceId())
        }
    }

    /** Release the engine now; safe to call more than once. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun ensureEngineLocked() {
        if (engine != null || unavailable) return
        // Set the listener before any speak() call; callbacks arrive on a binder thread.
        val created = TextToSpeech(context.applicationContext) { status ->
            synchronized(lock) {
                initStatus = status
                applyInitLocked()
            }
        }
        engine = created
        created.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = scheduleShutdown()
                override fun onError(utteranceId: String?) = scheduleShutdown()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = scheduleShutdown()
            },
        )
        // Some engines answer synchronously from the constructor, before `engine` was assigned.
        applyInitLocked()
    }

    private fun applyInitLocked() {
        val status = initStatus ?: return
        val tts = engine ?: return
        if (ready || unavailable) return

        if (status != TextToSpeech.SUCCESS) {
            failLocked()
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        if (!useLanguage(tts, Locale.getDefault()) && !useLanguage(tts, Locale.US)) {
            failLocked()
            return
        }

        ready = true
        while (pending.isNotEmpty()) {
            tts.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, nextUtteranceId())
        }
        scheduleShutdownLocked()
    }

    private fun useLanguage(tts: TextToSpeech, locale: Locale): Boolean {
        val result = tts.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun failLocked() {
        unavailable = true
        pending.clear()
        releaseLocked()
    }

    private fun releaseLocked() {
        handler.removeCallbacks(shutdown)
        pending.clear()
        ready = false
        initStatus = null
        runCatching { engine?.shutdown() }
        engine = null
    }

    private fun scheduleShutdown() {
        synchronized(lock) { scheduleShutdownLocked() }
    }

    private fun scheduleShutdownLocked() {
        handler.removeCallbacks(shutdown)
        handler.postDelayed(shutdown, IDLE_SHUTDOWN_MILLIS)
    }

    private fun nextUtteranceId(): String = "abark-${utterance.incrementAndGet()}"

    /** Silent and vibrate-only phones should stay quiet; the notification channel already decided the sound. */
    private fun isQuiet(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    private companion object {
        /** Long enough for a burst of pushes to share one engine session. */
        const val IDLE_SHUTDOWN_MILLIS: Long = 30_000

        /** A flood of pushes should not queue minutes of speech. */
        const val MAX_PENDING: Int = 4
    }
}
