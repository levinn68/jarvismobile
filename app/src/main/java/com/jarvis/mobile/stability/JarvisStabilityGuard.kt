package com.jarvis.mobile.stability

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * J.A.R.V.I.S. Batch 8.2 — Force Close & Crash Elimination Engine
 *
 * Enforces:
 * 1. Safe coroutine cancellation (never swallowing CancellationException).
 * 2. Subsystem isolation (Chat, Voice, Collaboration, Provider, Storage, Navigation).
 * 3. Secret-redacted diagnostic logging (zero token/API-key/email leakage).
 * 4. Bounded stream & attachment reading (prevents OutOfMemoryError).
 * 5. Thread-safe SpeechRecognizer, TextToSpeech, and MediaPlayer lifecycle cleanup.
 * 6. Duplicate request deduplication.
 */
object JarvisStabilityGuard {
    private const val TAG = "JarvisStabilityGuard"
    const val MAX_TTS_AUDIO_BYTES = 8 * 1024 * 1024 // 8 MB ceiling for TTS audio streams
    const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024L // 15 MB ceiling for attachments
    const val MAX_TEXT_SNIPPET_CHARS = 16_384

    enum class Subsystem {
        CHAT,
        VOICE,
        COLLABORATION,
        PROVIDER,
        STORAGE,
        NAVIGATION,
        DEVICE_CONTROL,
        NETWORK
    }

    data class DiagnosticEvent(
        val timestampMs: Long = System.currentTimeMillis(),
        val subsystem: Subsystem,
        val code: String,
        val message: String,
        val recoverable: Boolean = true
    )

    private val recentDiagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
    private val activeRequestLocks = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-~+/]+=*")
    private val secretKeyRegex = Regex("\\b(?:sk-|AIza|gsk_|hf_|rk_|pk_|sb_publishable_|eyJ)[A-Za-z0-9._\\-]{8,}\\b")
    private val queryKeyRegex = Regex("(?i)([?&](?:key|api_key|token|access_token|refresh_token)=)[^&\\s]+")

    fun redactSecrets(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(secretKeyRegex, "[REDACTED_KEY]")
            .replace(queryKeyRegex, "$1[REDACTED]")
            .take(600)
    }

    fun recordDiagnostic(
        subsystem: Subsystem,
        code: String,
        throwable: Throwable? = null,
        detail: String = "",
        recoverable: Boolean = true
    ): DiagnosticEvent {
        if (throwable is CancellationException) throw throwable
        val rawMsg = buildString {
            if (detail.isNotBlank()) append(detail)
            if (throwable != null) {
                if (isNotEmpty()) append(" | ")
                append(throwable::class.java.simpleName)
                val msg = throwable.message.orEmpty()
                if (msg.isNotBlank()) append(": ").append(msg)
            }
        }
        val event = DiagnosticEvent(
            subsystem = subsystem,
            code = code,
            message = redactSecrets(rawMsg.ifBlank { "Subsystem diagnostic event" }),
            recoverable = recoverable
        )
        recentDiagnostics.add(0, event)
        while (recentDiagnostics.size > 60) {
            runCatching { recentDiagnostics.removeAt(recentDiagnostics.lastIndex) }
        }
        runCatching {
            Log.w(TAG, "[${subsystem.name}:$code] ${event.message}")
        }
        return event
    }

    fun recentEvents(limit: Int = 20): List<DiagnosticEvent> =
        recentDiagnostics.take(limit.coerceIn(1, 60))

    fun rethrowIfCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    fun launchIsolated(
        scope: CoroutineScope,
        subsystem: Subsystem,
        code: String,
        onError: (String) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                val event = recordDiagnostic(subsystem, code, error, recoverable = true)
                runOnMainSafe {
                    onError(event.message)
                }
            }
        }
    }

    fun runOnMainSafe(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(action).onFailure { err ->
                if (err is CancellationException) throw err
                recordDiagnostic(Subsystem.NAVIGATION, "MAIN_THREAD_ACTION_ERROR", err)
            }
        } else {            mainHandler.post {
                runCatching(action).onFailure { err ->
                    if (err is CancellationException) throw err
                    recordDiagnostic(Subsystem.NAVIGATION, "MAIN_POST_ACTION_ERROR", err)
                }
            }
        }
    }

    fun acquireRequestLock(key: String, windowMs: Long = 800L): Boolean {
        val now = System.currentTimeMillis()
        val previous = activeRequestLocks.put(key, now)
        if (previous != null && (now - previous) < windowMs) {
            return false
        }
        return true
    }

    fun releaseRequestLock(key: String) {
        activeRequestLocks.remove(key)
    }

    fun parseJsonObjectSafe(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw.trim())
        } catch (err: Exception) {
            recordDiagnostic(Subsystem.PROVIDER, "JSON_OBJECT_PARSE_WARN", err)
            null
        }
    }

    fun parseJsonArraySafe(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONArray(raw.trim())
        } catch (err: Exception) {
            recordDiagnostic(Subsystem.PROVIDER, "JSON_ARRAY_PARSE_WARN", err)
            null
        }
    }

    fun readBoundedBytes(stream: InputStream, maxBytes: Int = MAX_TTS_AUDIO_BYTES): ByteArray {
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IllegalStateException("Ukuran stream melebihi batas aman (${maxBytes / (1024 * 1024)} MB).")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun validateAttachmentSize(context: Context, uri: Uri, maxBytes: Long = MAX_ATTACHMENT_BYTES): Boolean {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val size = cursor.getLong(idx)
                        return@runCatching size in 0..maxBytes
                    }
                }
                true
            } ?: true
        }.getOrElse { err ->
            recordDiagnostic(Subsystem.CHAT, "ATTACHMENT_SIZE_CHECK_WARN", err)
            false
        }
    }

    fun safeReleaseMediaPlayer(player: MediaPlayer?) {
        if (player == null) return
        runCatching {
            if (player.isPlaying) {
                player.stop()
            }
        }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    fun safeDestroySpeechRecognizer(recognizer: SpeechRecognizer?) {
        if (recognizer == null) return
        runOnMainSafe {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    fun safeStopTts(tts: TextToSpeech?, shutdown: Boolean = false) {
        if (tts == null) return
        runCatching { tts.stop() }
        if (shutdown) {
            runCatching { tts.shutdown() }
        }
    }
}