package com.jarvis.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

private fun spokenSummary(text: String): String {
    val clean = text
        .replace(Regex("!\\[[^\\]]*\\]\\(https?://[^ )]+\\)"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\(https?://[^ )]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?m)^\\s*[-*•#>]+\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (clean.length <= 190) return clean
    val firstSentence = clean.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
    return if (firstSentence.length in 35..190) firstSentence else clean.take(187).trimEnd() + "..."
}

private val Background = Color(0xFF050912)
private val Panel = Color(0xFF0D1726)
private val Cyan = Color(0xFF64E8FF)
private val TextMuted = Color(0xFF8FA6BD)

/**
 * Lightweight clap-pattern detector fed by SpeechRecognizer's live RMS callback.
 * A single clap fires after the double-clap window; two distinct spikes fire immediately.
 * Threshold follows the room's quiet baseline to reduce triggers from steady background noise.
 */
private class ClapPatternDetector(
    private val handler: Handler,
    private val onDetected: (Int) -> Unit
) {
    private var baseline = 0f
    private var previous = 0f
    private var initialized = false
    private var firstClapAt = 0L
    private var lastSpikeAt = 0L
    private var lastTriggeredAt = 0L
    private var pendingCount = 0
    private var pendingSingle: Runnable? = null

    fun onRms(rmsDb: Float) {
        if (!rmsDb.isFinite()) return
        val now = SystemClock.elapsedRealtime()
        val level = rmsDb.coerceIn(0f, 20f)
        if (!initialized) {
            baseline = level
            previous = level
            initialized = true
            return
        }

        val jump = level - previous
        if (pendingCount == 0 && level < baseline + 4f) {
            baseline = baseline * 0.96f + level * 0.04f
        }
        previous = level

        val threshold = maxOf(7.5f, baseline + 6f)
        val isSharpTransient = level >= threshold && jump >= 3.5f
        if (!isSharpTransient || now - lastSpikeAt < 180L || now - lastTriggeredAt < 1100L) return
        lastSpikeAt = now

        if (pendingCount == 1 && now - firstClapAt in 140L..650L) {
            pendingSingle?.let(handler::removeCallbacks)
            pendingSingle = null
            pendingCount = 0
            lastTriggeredAt = now
            onDetected(2)
            return
        }

        pendingSingle?.let(handler::removeCallbacks)
        pendingCount = 1
        firstClapAt = now
        val task = Runnable {
            if (pendingCount == 1) {
                pendingCount = 0
                pendingSingle = null
                lastTriggeredAt = SystemClock.elapsedRealtime()
                onDetected(1)
            }
        }
        pendingSingle = task
        handler.postDelayed(task, 650L)
    }

    fun reset() {
        pendingSingle?.let(handler::removeCallbacks)
        pendingSingle = null
        pendingCount = 0
        initialized = false
        previous = 0f
        baseline = 0f
        lastSpikeAt = 0L
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val voiceText = mutableStateOf("")
    private val voiceStatus = mutableStateOf("INITIALIZING VOICE CORE")
    private var permissionAsked = false
    @Volatile private var clapActivationInProgress = false
    private val clapDetector by lazy {
        ClapPatternDetector(handler) { count ->
            if (voiceModeEnabled) handler.post {
                if (voiceModeEnabled && voiceText.value.isBlank()) {
                    clapActivationInProgress = true
                    recognizer?.cancel()
                    voiceStatus.value = if (count == 2) "DOUBLE CLAP · JARVIS ACTIVATED" else "SINGLE CLAP · JARVIS ACTIVATED"
                    voiceText.value = "JARVIS, OPEN"
                }
            }
        }
    }
    @Volatile private var geminiSpeechInProgress = false
    private var speechPlayer: MediaPlayer? = null
    private val geminiVoice get() = prefs.getString("gemini_voice", "Charon").orEmpty()
    private var voiceModeEnabled = true
    private val prefs by lazy { getSharedPreferences("jarvis_secure_settings", MODE_PRIVATE) }
    private val geminiKey get() = prefs.getString("gemini_api_key", "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                // Select Android fallback voice per response language.
                tts?.language = Locale("id", "ID")
                tts?.setSpeechRate(0.86f)
                tts?.setPitch(0.78f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { runOnUiThread { voiceStatus.value = "JARVIS SPEAKING" } }
                    override fun onDone(utteranceId: String?) {
                        if (!geminiSpeechInProgress) handler.postDelayed({ startListening() }, 550)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!geminiSpeechInProgress) handler.postDelayed({ startListening() }, 700)
                    }
                })
                if (voiceStatus.value == "INITIALIZING VOICE CORE") voiceStatus.value = "VOICE CORE READY"
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceStatus.value = "LISTENING · SPEAK NOW" }
                override fun onBeginningOfSpeech() { voiceStatus.value = "HEARING YOUR VOICE" }
                override fun onRmsChanged(rmsdB: Float) { if (voiceModeEnabled) clapDetector.onRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { voiceStatus.value = "PROCESSING VOICE" }
                override fun onError(error: Int) {
                    if (geminiSpeechInProgress || clapActivationInProgress) return
                    voiceStatus.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "DIDN'T CATCH THAT · TRY AGAIN"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MICROPHONE PERMISSION REQUIRED"
                        else -> "RECONNECTING VOICE CORE"
                    }
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) handler.postDelayed({ startListening() }, 900)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        voiceText.value = text
                        voiceStatus.value = "COMMAND RECEIVED"
                    } else handler.postDelayed({ startListening() }, 600)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else voiceStatus.value = "VOICE RECOGNITION UNAVAILABLE"

        setContent {
            JarvisScreen(
                recognizedText = voiceText.value,
                status = voiceStatus.value,
                onConsumed = { voiceText.value = "" },
                onSpeak = { speak(it) },
                onStartListening = { startListening() },
                onModeChanged = { voiceMode ->
                    voiceModeEnabled = voiceMode
                    // JarvisScreen owns the single startListening call when its Voice tab becomes active.
                    // Avoid starting SpeechRecognizer twice from both this callback and LaunchedEffect.
                    if (!voiceMode) { clapDetector.reset(); recognizer?.cancel() }
                    voiceStatus.value = if (voiceMode) "VOICE MODE · LISTENING" else "CHAT MODE · READY TO TYPE"
                },
                onSearch = { _ -> voiceStatus.value = "AI COUNCIL DISCUSSION" },
                geminiConfigured = { true },
                onSaveGeminiKey = { key -> prefs.edit().putString("gemini_api_key", key.trim()).apply() },
                onAskGemini = { prompt, language, callback -> askGemini(prompt, language, callback) },
                initialResponseLanguage = prefs.getString("response_language", "auto").orEmpty(),
                onSaveResponseLanguage = { language -> prefs.edit().putString("response_language", language).apply() },
                onOpenApp = { q -> openRequestedApp(q) },
                onInstalledApps = { installedAppNames() }
            )
        }
    }


    private fun askGemini(prompt: String, responseLanguage: String, callback: (String) -> Unit): () -> Unit {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        Thread {
            var connection: HttpURLConnection? = null
            val result = try {
                val endpoint = URL("https://jarvis-api.alvinorasya1304.workers.dev/v1/chat")
                connection = endpoint.openConnection() as HttpURLConnection
                activeConnection.set(connection)
                if (cancelled.get()) {
                    connection.disconnect()
                    return@Thread
                }
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 180000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val body = JSONObject().put("message", prompt).put("responseLanguage", responseLanguage)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(raw)
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    val diagnostics = json.optJSONArray("diagnostics")
                    val detail = if (diagnostics != null) (0 until diagnostics.length()).mapNotNull { i ->
                        val item = diagnostics.optJSONObject(i) ?: return@mapNotNull null
                        item.optString("provider") + ": " + item.optString("detail", item.optString("status"))
                    }.joinToString("\n") else ""
                    json.optString("error").ifBlank { "JARVIS API error ($code)." } +
                        if (detail.isNotBlank()) "\n\nDetail provider:\n$detail" else " Periksa provider AI dan koneksi backend."
                } else {
                    json.optString("answer", "JARVIS tidak menerima jawaban akhir.").trim()
                }
            } catch (e: Exception) {
                "Gagal menghubungi JARVIS Worker: periksa koneksi internet dan status backend."
            } finally {
                connection?.disconnect()
            }
            if (!cancelled.get()) handler.post { if (!cancelled.get()) callback(result) }
        }.start()
        return {
            cancelled.set(true)
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        geminiSpeechInProgress = true
        clapDetector.reset()
        tts?.stop()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = URL("https://jarvis-api.alvinorasya1304.workers.dev/v1/tts")
                connection = endpoint.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 90000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val body = JSONObject().put("text", text)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    }.orEmpty()
                    throw IllegalStateException("Fish Audio HTTP $code: $detail")
                }
                val audio = connection.inputStream.use { it.readBytes() }
                if (audio.isEmpty()) throw IllegalStateException("Fish Audio returned empty audio")
                val audioFile = File(cacheDir, "jarvis_mcu_voice.mp3")
                FileOutputStream(audioFile).use { it.write(audio) }
                handler.post {
                    try {
                        speechPlayer?.release()
                        speechPlayer = MediaPlayer().apply {
                            setDataSource(audioFile.absolutePath)
                            setOnPreparedListener {
                                voiceStatus.value = "JARVIS MCU VOICE · SPEAKING"
                                it.start()
                            }
                            setOnCompletionListener {
                                it.release()
                                speechPlayer = null
                                geminiSpeechInProgress = false
                                handler.postDelayed({ startListening() }, 450)
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                speechPlayer = null
                                geminiSpeechInProgress = false
                                voiceStatus.value = "FISH AUDIO PLAYBACK ERROR"
                                handler.postDelayed({ startListening() }, 900)
                                true
                            }
                            prepareAsync()
                        }
                    } catch (_: Exception) {
                        speechPlayer?.release()
                        speechPlayer = null
                        geminiSpeechInProgress = false
                        voiceStatus.value = "VOICE PLAYBACK ERROR"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    geminiSpeechInProgress = false
                    voiceStatus.value = "FISH AUDIO ERROR · CHECK SERVER KEY"
                    speakWithAndroid(text)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun isLikelyEnglish(text: String): Boolean {
        val words = Regex("[\\p{L}']+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        val english = setOf("the", "is", "are", "was", "were", "what", "where", "when", "why", "how",
            "please", "open", "search", "find", "show", "tell", "your", "you", "i", "we", "can",
            "could", "would", "should", "hello", "thanks", "thank", "good", "morning", "today",
            "tomorrow", "price", "best", "compare", "explain", "about")
        val indonesian = setOf("yang", "dan", "di", "ke", "dari", "ini", "itu", "apa", "siapa",
            "kapan", "kenapa", "bagaimana", "tolong", "buka", "cari", "carikan", "tampilkan",
            "jelaskan", "saya", "aku", "kamu", "anda", "untuk", "dengan", "tidak", "bisa",
            "berapa", "harga", "terbaik", "bandingkan", "terima", "kasih", "selamat", "pagi")
        return words.count { it in english } > words.count { it in indonesian }
    }

    private fun speakWithAndroid(text: String) {
        val locale = if (isLikelyEnglish(text)) Locale.US else Locale("id", "ID")
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.getDefault()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun startListening() {
        clapActivationInProgress = false
        if (isFinishing || isDestroyed || !voiceModeEnabled || geminiSpeechInProgress) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 700)
                voiceStatus.value = "MICROPHONE PERMISSION REQUIRED"
            }
            return
        }
        val speech = recognizer ?: run { voiceStatus.value = "VOICE RECOGNITION UNAVAILABLE"; return }
        try {
            speech.cancel()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Android 14+ recognition services can detect either language.
                // Indonesian remains the fallback for older recognition services.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES",
                    arrayListOf("id-ID", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speech.startListening(intent)
        } catch (_: Exception) {
            voiceStatus.value = "VOICE CORE RESTARTING"
            handler.postDelayed({ startListening() }, 1200)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                voiceStatus.value = "MICROPHONE READY"
                speak("JARVIS sudah online. Saya siap mendengarkan.")
            } else voiceStatus.value = "MICROPHONE PERMISSION DENIED"
        }
    }

    private fun installedAppNames(): List<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.loadLabel(packageManager)?.toString()?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase(Locale.ROOT) }
        } catch (_: Exception) { emptyList() }
    }

    private fun openRequestedApp(command: String): Boolean {
        val normalized = command.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val cleaned = normalized.replace(Regex("^(tolong |please |buka(kan)? |jalankan |mainkan |main |open |launch |run |start |aplikasi )+"), "").replace(Regex("^(aplikasi |app |the )+"), "").trim()
        if (cleaned.isBlank()) return false
        voiceStatus.value = "SEARCHING INSTALLED APPS"
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = try {
            packageManager.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val pkg = info.activityInfo?.packageName.orEmpty()
                val activity = info.activityInfo?.name.orEmpty()
                if (label.isNotBlank() && pkg.isNotBlank() && activity.isNotBlank()) Triple(label, pkg, activity) else null
            }
        } catch (_: Exception) { emptyList() }
        val aliases = mapOf(
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "roblox" to "com.roblox.client"
        )
        val directPackage = aliases[cleaned]
        val directLaunch = directPackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (directLaunch != null) {
            return try {
                directLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(directLaunch)
                voiceStatus.value = "OPENING " + cleaned.uppercase(Locale.ROOT)
                true
            } catch (_: Exception) { voiceStatus.value = "COULD NOT OPEN APP"; false }
        }
        val found = candidates.firstOrNull { it.first.equals(cleaned, true) }
            ?: candidates.firstOrNull { it.first.startsWith(cleaned, true) }
            ?: candidates.firstOrNull { it.first.contains(cleaned, true) }
            ?: candidates.firstOrNull { item -> cleaned.split(" ").all { token -> token.length > 1 && item.first.contains(token, true) } }
        if (found == null) { voiceStatus.value = "APP NOT FOUND · SEARCHING WEB"; return false }
        return try {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(found.second, found.third)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            voiceStatus.value = "OPENING " + found.first.uppercase(Locale.ROOT)
            true
        } catch (_: Exception) { voiceStatus.value = "COULD NOT OPEN APP"; false }
    }

    override fun onPause() { recognizer?.cancel(); super.onPause() }
    override fun onResume() { super.onResume(); if (recognizer != null) handler.postDelayed({ startListening() }, 500) }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        speechPlayer?.release()
        speechPlayer = null
        tts?.shutdown()
        super.onDestroy()
    }
}

private sealed class MarkdownBlock {
    data class Image(val alt: String, val url: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Code(val language: String, val text: String) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data class Bullet(val text: String, val ordered: Boolean) : MarkdownBlock()
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\\\\n", "\n").lines()
    val blocks = mutableListOf<MarkdownBlock>()
    fun isTableSeparator(line: String): Boolean {
        val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        return cells.isNotEmpty() && cells.all { it.matches(Regex(":?-{3,}:?")) }
    }
    fun tableCells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val imageMatch = Regex("^!\\[([^\\]]*)\\]\\((https?://[^ )]+)\\)$").matchEntire(line.trim())
        if (imageMatch != null) {
            blocks.add(MarkdownBlock.Image(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
            continue
        }
        if (line.isBlank()) { i++; continue }
        if (line.trimStart().startsWith("```")) {
            val language = line.trim().removePrefix("```").trim()
            i++
            val code = mutableListOf<String>()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.add(lines[i]); i++ }
            if (i < lines.size) i++
            blocks.add(MarkdownBlock.Code(language, code.joinToString("\n")))
            continue
        }
        if (line.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val rows = mutableListOf<List<String>>()
            rows.add(tableCells(line))
            i += 2
            while (i < lines.size) {
                if (lines[i].isBlank()) {
                    var next = i
                    while (next < lines.size && lines[next].isBlank()) next++
                    if (next < lines.size && lines[next].contains("|") && !isTableSeparator(lines[next])) {
                        i = next
                        continue
                    }
                    i = next
                    break
                }
                if (!lines[i].contains("|")) break
                rows.add(tableCells(lines[i]))
                i++
            }
            val columns = rows.maxOfOrNull { it.size } ?: 0
            blocks.add(MarkdownBlock.Table(rows.map { row -> row + List((columns - row.size).coerceAtLeast(0)) { "" } }))
            continue
        }
        val bullet = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)(.*)$").matchEntire(line)
        if (bullet != null) {
            blocks.add(MarkdownBlock.Bullet(bullet.groupValues[1], Regex("^\\s*\\d").containsMatchIn(line)))
            i++
            continue
        }
        val paragraph = mutableListOf(line)
        i++
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !(lines[i].contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) &&
            !Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)").containsMatchIn(lines[i])) {
            paragraph.add(lines[i]); i++
        }
        val paragraphText = paragraph.joinToString("\n")
        val inlineImages = Regex("!\\[([^\\]]*)\\]\\((https?://[^ )]+)\\)")
        val imageMatches = inlineImages.findAll(paragraphText).toList()
        if (imageMatches.isEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphText))
        } else {
            var cursor = 0
            imageMatches.forEach { match ->
                val before = paragraphText.substring(cursor, match.range.first).trim()
                if (before.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(before))
                blocks.add(MarkdownBlock.Image(match.groupValues[1], match.groupValues[2]))
                cursor = match.range.last + 1
            }
            val after = paragraphText.substring(cursor).trim()
            if (after.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(after))
        }
    }
    return blocks
}

@Composable
private fun InlineMarkdownText(markdown: String, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    val uriHandler = LocalUriHandler.current
    val linkPattern = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)")
    val rawUrlPattern = Regex("https?://[^\\s<>]+(?<![.,!?;:)\\]])")
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    val clean = markdown.replace(Regex("(?m)^#{1,6}\\s*"), "")
    val annotated = buildAnnotatedString {
        var cursor = 0
        val matches = (linkPattern.findAll(clean).map { it to "link" } +
            rawUrlPattern.findAll(clean).map { it to "url" } +
            boldPattern.findAll(clean).map { it to "bold" }).sortedBy { it.first.range.first }
        for ((match, kind) in matches) {
            if (match.range.first < cursor) continue
            append(clean.substring(cursor, match.range.first))
            if (kind == "link" || kind == "url") {
                val startOffset = length
                val label = if (kind == "link") match.groupValues[1] else match.value
                val target = if (kind == "link") match.groupValues[2] else match.value
                withStyle(SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) { append(label) }
                addStringAnnotation("URL", target, startOffset, length)
            } else withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF1FCFF))) { append(match.groupValues[1]) }
            cursor = match.range.last + 1
        }
        if (cursor < clean.length) append(clean.substring(cursor))
    }
    ClickableText(text = annotated, style = androidx.compose.ui.text.TextStyle(color = Color(0xFFE2F5F8), fontSize = fontSize, lineHeight = 22.sp), onClick = { offset ->
        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
    })
}

@Composable
private fun RemoteMessageImage(url: String, alt: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 9000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "JARVIS-Android")
                try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
                finally { connection.disconnect() }
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = alt.ifBlank { "Visual result" },
        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp))
}

@Composable
private fun RichMessageText(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseMarkdownBlocks(markdown).forEach { block ->
            when (block) {
                is MarkdownBlock.Image -> RemoteMessageImage(block.url, block.alt)
                is MarkdownBlock.Paragraph -> InlineMarkdownText(block.text)
                is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (block.ordered) "1." else "•", color = Cyan, fontSize = 14.sp)
                    InlineMarkdownText(block.text)
                }
                is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(Color(0xFF06111A), RoundedCornerShape(9.dp))
                    .border(1.dp, Color(0x5549B8CB), RoundedCornerShape(9.dp)).padding(10.dp)) {
                    if (block.language.isNotBlank()) Text(block.language.uppercase(), color = Cyan, fontSize = 8.sp, letterSpacing = 1.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        SelectionContainer { Text(block.text, color = Color(0xFFD2E8EF), fontSize = 12.sp, lineHeight = 18.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }
                is MarkdownBlock.Table -> {
                    val columnCount = block.rows.maxOfOrNull { it.size } ?: 0
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Column(Modifier.border(1.dp, Color(0x5549B8CB), RoundedCornerShape(7.dp))) {
                            block.rows.forEachIndexed { rowIndex, row ->
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    repeat(columnCount) { columnIndex ->
                                        val longest = block.rows.maxOfOrNull { it.getOrNull(columnIndex)?.length ?: 0 } ?: 8
                                        val cellWidth = (longest * 7 + 22).coerceIn(88, 180).dp
                                        Box(Modifier.width(cellWidth).heightIn(min = 36.dp)
                                            .background(if (rowIndex == 0) Color(0xFF123443) else Color(0xFF091923))
                                            .border(.5.dp, Color(0x5549B8CB)).padding(horizontal = 8.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.CenterStart) {
                                            Text(row.getOrNull(columnIndex).orEmpty(), color = if (rowIndex == 0) Cyan else Color(0xFFE2F5F8),
                                                fontSize = 11.sp, lineHeight = 15.sp, fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLogo(url: String) {
    val uriHandler = LocalUriHandler.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(url, "UTF-8")
                URL("https://www.google.com/s2/favicons?domain_url=" + encoded + "&sz=64").openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    Surface(onClick = { runCatching { uriHandler.openUri(url) } }, color = Color(0xFF102A36), shape = CircleShape, border = BorderStroke(1.dp, Color(0x6652DFF2))) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Open source", modifier = Modifier.size(18.dp))
            else Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun SourceStrip(markdown: String) {
    val links = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)").findAll(markdown.replace("\\\\n", "\n")).map { it.groupValues[2] }.distinct().take(6).toList()
    if (links.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("SOURCES", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { links.forEach { SourceLogo(it) } }
    }
}
@Composable
private fun JarvisScreen(
    recognizedText: String, status: String, onConsumed: () -> Unit, onSpeak: (String) -> Unit,
    onStartListening: () -> Unit, onModeChanged: (Boolean) -> Unit, onSearch: (String) -> Unit,
    geminiConfigured: () -> Boolean, onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, String, (String) -> Unit) -> (() -> Unit), onOpenApp: (String) -> Boolean,
    initialResponseLanguage: String, onSaveResponseLanguage: (String) -> Unit,
    onInstalledApps: () -> List<String>
) {
    data class Attachment(val uri: Uri, val name: String, val mime: String)
    data class Line(
        val user: Boolean,
        val text: String,
        val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
        val attachments: List<Attachment> = emptyList(),
        val displayText: MutableState<String> = mutableStateOf(if (user) text else ""),
        val revealed: MutableState<Boolean> = mutableStateOf(true)
    )
    var page by remember { mutableStateOf("CHAT") }
    var voiceListening by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var lastPrompt by remember { mutableStateOf("") }
    val context = LocalContext.current
    val pendingAttachments = remember { mutableStateListOf<Attachment>() }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "Lampiran"
            if (pendingAttachments.none { it.uri == uri } && pendingAttachments.size < 5) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                pendingAttachments.add(Attachment(uri, name, mime))
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            if (pendingAttachments.size < 5 && pendingAttachments.none { it.uri == uri }) {
                pendingAttachments.add(Attachment(uri, "Foto", context.contentResolver.getType(uri) ?: "image/*"))
            }
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null && pendingAttachments.size < 5) {
            runCatching {
                val file = File(context.cacheDir, "jarvis_camera_photo.jpg")
                FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                pendingAttachments.add(Attachment(Uri.fromFile(file), "Foto kamera", "image/jpeg"))
            }
        }
    }
    var attachmentMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cancelCurrentRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var statusText by remember { mutableStateOf("SYSTEM STANDBY") }
    var processStage by remember { mutableStateOf("Menganalisis permintaan") }
    var settings by remember { mutableStateOf(false) }
    var voiceArmed by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var responseLanguage by remember { mutableStateOf(initialResponseLanguage) }
    val lines = remember { mutableStateListOf<Line>() }
    val revealScope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var followLatest by remember { mutableStateOf(true) }
    val transition = rememberInfiniteTransition(label = "reactor")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(16000), RepeatMode.Restart), label = "spin")
    val breathe by transition.animateFloat(.94f, 1.06f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "breathe")
    val wavePhase by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "wavePhase")

    fun send(raw: String, voice: Boolean = false) {
        val q = raw.trim()
        val files = pendingAttachments.toList()
        if ((q.isEmpty() && files.isEmpty()) || busy) return
        command = ""
        pendingAttachments.clear()
        val attachmentNote = if (files.isEmpty()) "" else "\n\nLampiran: " + files.joinToString("; ") { it.name + " (" + it.mime + ")" }
        val requestText = q + attachmentNote
        if (q.isNotBlank()) lastPrompt = q
        lines.add(Line(true, q.ifBlank { "Tolong periksa lampiran ini." }, attachments = files))
        val norm = q.lowercase(Locale.ROOT)
        if (norm in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")) {
            val greeting = "Saya online. Ada yang bisa saya bantu?"
            lines.add(Line(false, greeting))
            statusText = "JARVIS ONLINE"
            if (voice) onSpeak(greeting)
            return
        }
        val openIntent = Regex("\\b(buka|bukakan|jalankan|open|launch|run|start)\\b").containsMatchIn(norm)
        val questionIntent = Regex("\\b(apa|siapa|kapan|kenapa|mengapa|bagaimana|jelaskan|cari|harga|what|who|when|why|how|search|find|explain|price)\\b").containsMatchIn(norm)
        val simpleApp = norm.split(" ").size <= 2 && !questionIntent && !openIntent && norm !in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")
        if (files.isEmpty() && (openIntent || simpleApp) && onOpenApp(q)) {
            val answer = "Baik, aplikasi dibuka."
            lines.add(Line(false, answer)); statusText = "COMMAND EXECUTED"
            if (voice) onSpeak(spokenSummary(answer))
            return
        }
        if (files.isEmpty() && openIntent) {
            val answer = "Aplikasi tersebut tidak ditemukan di perangkat ini."
            lines.add(Line(false, answer)); statusText = "APP NOT FOUND"
            if (voice) onSpeak(spokenSummary(answer))
            return
        }
        busy = true
        statusText = "ANALYSING REQUEST"
        onSearch(q)
        cancelCurrentRequest = onAskGemini(requestText, responseLanguage) { response ->
            val answer = response.substringAfter("KESIMPULAN JARVIS", response)
                .substringAfter("JAWABAN AKHIR", response).trim().ifBlank { response.trim() }
            val responseLine = Line(false, answer)
            responseLine.displayText.value = answer
            responseLine.revealed.value = false
            lines.add(responseLine)
            busy = false
            cancelCurrentRequest = null
            statusText = "RESPONSE READY"
            revealScope.launch {
                kotlinx.coroutines.delay(24)
                responseLine.revealed.value = true
            }
            if (voice) onSpeak(spokenSummary(answer))
        }
    }

    LaunchedEffect(recognizedText, page, voiceListening) {
        if (page == "VOICE" && voiceListening && recognizedText.isNotBlank()) {
            val heard = recognizedText.trim()
            onConsumed()
            val wake = Regex("(?i)^jarvis[,.! ]*")
            if (wake.containsMatchIn(heard)) {
                val request = heard.replaceFirst(wake, "").trim()
                if (request.isBlank() || request.equals("open", true)) {
                    // A bare wake word gets a short acknowledgement. The next
                    // utterance is always accepted as a command, without another wake word.
                    voiceArmed = true
                    statusText = "JARVIS ACTIVATED · READY FOR COMMAND"
                    onSpeak("Ya, Sir. Saya siap.")
                } else {
                    voiceArmed = false
                    send(request, true)
                }
            } else {
                // Voice mode is conversational: accept a direct Indonesian command
                // without requiring "Jarvis" at the beginning of every sentence.
                voiceArmed = false
                send(heard, true)
            }
        } else if (page != "VOICE" || !voiceListening) {
            voiceArmed = false
        }
    }
    LaunchedEffect(page, voiceListening) {
        val voiceOn = page == "VOICE" && voiceListening
        onModeChanged(voiceOn)
        if (voiceOn) onStartListening()
    }
    LaunchedEffect(listState) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }.collect { (lastVisible, total) -> followLatest = total == 0 || lastVisible == null || lastVisible >= total - 2 } }
    LaunchedEffect(lines.size, busy) { if (followLatest && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
    LaunchedEffect(lines) {
        snapshotFlow { lines.lastOrNull()?.revealed?.value }.collect {
            if (followLatest && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
    }
    LaunchedEffect(busy) { if (busy) { val stages = listOf("Memahami konteks", "Menyusun strategi pencarian", "Memeriksa informasi", "Merangkum hasil"); var i = 0; while (busy) { processStage = stages[i++ % stages.size]; kotlinx.coroutines.delay(1450) } } else processStage = "Siap membantu" }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .background(Brush.verticalGradient(listOf(Color(0xFF02080D), Color(0xFF06131C), Color(0xFF02070C))))) {
            val compact = maxHeight < 700.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp).animateContentSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).padding(end = 5.dp)) {
                        Text("J.A.R.V.I.S.", color = Color(0xFFE4FAFF), fontSize = 23.sp, fontWeight = FontWeight.Light, letterSpacing = 4.2.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("JUST A RATHER VERY INTELLIGENT SYSTEM", color = Color(0xFF5E9BA9), fontSize = 8.sp, letterSpacing = 1.35.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (busy) "● PROCESSING" else "● SYSTEM ONLINE", color = Cyan, fontSize = 8.sp)
                        Text("CORE 01 / " + page, color = TextMuted, fontSize = 7.sp)
                    }
                    Spacer(Modifier.width(2.dp))
                }
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha=.6f), Color.Transparent))))
                if (page == "VOICE") {
                    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("JARVIS", color = Color(0xFF8DEEFF), fontSize = 11.sp, letterSpacing = 4.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        Text("PERSONAL INTELLIGENCE INTERFACE", color = Color(0xFF397F8D), fontSize = 7.sp, letterSpacing = 1.6.sp)
                        Box(Modifier.fillMaxWidth().weight(if (lines.isEmpty()) 1f else .82f), contentAlignment = Alignment.Center) {
                            val reactorSize = if (compact) 220.dp else 246.dp
                            Canvas(Modifier.size(reactorSize)
                                .scale(if (voiceListening) breathe else 1f)
                                .rotate(if (voiceListening) spin * .12f else spin * .025f)
                                .clickable(enabled = voiceListening) { onStartListening() }) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val r = size.minDimension * .43f
                                val cyan = Color(0xFF36DDF5)
                                val pulse = .5f + .5f * kotlin.math.sin(wavePhase.toDouble()).toFloat()
                                drawCircle(Brush.radialGradient(listOf(Color(0x5534D9F3), Color(0x1A21AFCB), Color.Transparent), Offset(cx, cy), r * 1.52f), r * 1.52f, Offset(cx, cy))
                                // Organic, translucent reactor membrane from the reference video.
                                for (layer in 0..3) {
                                    val contour = androidx.compose.ui.graphics.Path()
                                    for (i in 0..180) {
                                        val angle = Math.PI * 2.0 * i / 180.0
                                        val wobble = 1f + .025f * kotlin.math.sin(angle * 7.0 + wavePhase * .22).toFloat() +
                                            .018f * kotlin.math.cos(angle * 11.0 - wavePhase * .16).toFloat() +
                                            .009f * kotlin.math.sin(angle * 17.0 + layer).toFloat()
                                        val radius = r * (.91f + layer * .045f) * wobble
                                        val point = Offset(cx + kotlin.math.cos(angle).toFloat() * radius,
                                            cy + kotlin.math.sin(angle).toFloat() * radius)
                                        if (i == 0) contour.moveTo(point.x, point.y) else contour.lineTo(point.x, point.y)
                                    }
                                    contour.close()
                                    drawPath(contour, cyan.copy(alpha = .13f + pulse * .035f - layer * .018f),
                                        style = Stroke(if (layer == 0) 1.5f else .75f))
                                }
                                drawCircle(cyan.copy(alpha = .07f + pulse * .025f), r * .78f, Offset(cx, cy), style = Stroke(.9f))
                                drawCircle(cyan.copy(alpha = .09f), r * .58f, Offset(cx, cy), style = Stroke(.8f))
                                // Layered translucent holographic disc, matching the reference interface.
                                for (ring in 0..9) {
                                    val rr = r * (.72f - ring * .061f)
                                    drawCircle(cyan.copy(alpha = .075f + pulse * .025f), rr, Offset(cx, cy), style = Stroke(if (ring == 0) 1.2f else .55f))
                                }
                                // Fine segmented perimeter and scanning arcs.
                                for (i in 0 until 144) {
                                    val a = Math.PI * 2.0 * i / 144.0 + spin * Math.PI / 180.0
                                    val major = i % 12 == 0
                                    val r1 = r * if (major) .91f else .96f
                                    val r2 = r * if (major) 1.04f else 1.005f
                                    drawLine(cyan.copy(alpha = if (major) .62f else .19f),
                                        Offset(cx + kotlin.math.cos(a).toFloat() * r1, cy + kotlin.math.sin(a).toFloat() * r1),
                                        Offset(cx + kotlin.math.cos(a).toFloat() * r2, cy + kotlin.math.sin(a).toFloat() * r2),
                                        if (major) 1.6f else .65f)
                                }
                                for (i in 0..3) {
                                    val rr = r * (.62f + i * .12f)
                                    drawArc(cyan.copy(alpha = .22f + pulse * .18f), spin * (if (i % 2 == 0) 1f else -1f) + i * 70f,
                                        48f + i * 13f, false, Offset(cx - rr, cy - rr), androidx.compose.ui.geometry.Size(rr * 2f, rr * 2f), style = Stroke(1.2f))
                                }
                                // Central wireframe globe, dotted matrix and moving scan beam.
                                val core = r * .43f
                                val scanY = cy + kotlin.math.sin(wavePhase.toDouble() * .55).toFloat() * core * .72f
                                drawLine(Color(0xFF8CF3FF).copy(alpha = .62f), Offset(cx - core * .88f, scanY), Offset(cx + core * .88f, scanY), 1.25f)
                                drawCircle(Color(0xFF9CF5FF).copy(alpha = .18f), core * .95f, Offset(cx, cy), style = Stroke(.8f))
                                for (lat in -5..5) {
                                    val yy = lat / 5f
                                    val half = core * kotlin.math.sqrt((1f - yy * yy).coerceAtLeast(.02f))
                                    drawOval(cyan.copy(alpha = .22f), Offset(cx - half, cy + yy * core - core * .055f),
                                        androidx.compose.ui.geometry.Size(half * 2f, core * .11f), style = Stroke(.8f))
                                }
                                for (lon in 0..12) {
                                    val w = core * kotlin.math.abs(kotlin.math.cos(lon * Math.PI / 12.0)).toFloat().coerceAtLeast(.06f)
                                    drawOval(cyan.copy(alpha = .2f), Offset(cx - w, cy - core),
                                        androidx.compose.ui.geometry.Size(w * 2f, core * 2f), style = Stroke(.8f))
                                }
                                for (y in -5..5) for (x in -5..5) {
                                    val distance = kotlin.math.sqrt((x*x + y*y).toFloat())
                                    if (distance <= 5.5f) {
                                        val alpha = (.18f + pulse * .34f) * (1f - distance / 7f)
                                        drawCircle(Color(0xFF9CF5FF).copy(alpha = alpha.coerceAtLeast(.05f)),
                                            if (x == 0 && y == 0) 2.2f else 1.05f,
                                            Offset(cx + x * core * .14f, cy + y * core * .14f))
                                    }
                                }
                                // Three linked holographic nodes phase in and out around the core.
                                val nodeAlpha = (.25f + pulse * .55f)
                                val points = (0..2).map { index ->
                                    val a = (spin * .35f + index * 120f - 90f) * Math.PI / 180.0
                                    Offset(cx + kotlin.math.cos(a).toFloat() * core * .9f, cy + kotlin.math.sin(a).toFloat() * core * .9f)
                                }
                                drawLine(cyan.copy(alpha = nodeAlpha), points[0], points[1], 1.4f)
                                drawLine(cyan.copy(alpha = nodeAlpha), points[1], points[2], 1.4f)
                                drawLine(cyan.copy(alpha = nodeAlpha), points[2], points[0], 1.4f)
                                points.forEach { point ->
                                    drawCircle(cyan.copy(alpha = .18f), 13f, point)
                                    drawCircle(Color(0xFF8CF3FF).copy(alpha = .9f), 5f, point)
                                    drawCircle(cyan.copy(alpha = .75f), 9f, point, style = Stroke(1.2f))
                                }
                                drawCircle(Color(0xFFBDFBFF).copy(alpha = .85f), 2.5f, Offset(cx, cy))
                            }
                            Text("J.A.R.V.I.S.", color = Color(0xFFBDFBFF), fontSize = 15.sp, letterSpacing = 2.6.sp,
                                fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))
                            Column(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp), horizontalAlignment = Alignment.End) {
                                Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                                    color = Color(0xFF6FCFE0), fontSize = 10.sp, letterSpacing = 1.4.sp)
                                Text(if (voiceListening) "AUDIO INPUT ACTIVE" else "AUDIO INPUT STANDBY",
                                    color = if (voiceListening) Cyan else TextMuted, fontSize = 6.sp, letterSpacing = 1.sp)
                            }
                            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (busy) "NEURAL PROCESSING" else statusText, color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(7.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    repeat(31) { bar ->
                                        val level = kotlin.math.abs(kotlin.math.sin(wavePhase + bar * .42f))
                                        Box(Modifier.width(2.dp).height((4 + level * (if (busy) 18 else 11)).dp)
                                            .background(Cyan.copy(alpha = .35f + level * .55f), RoundedCornerShape(2.dp)))
                                    }
                                }
                            }
                        }
                        if (lines.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                Modifier.fillMaxWidth().weight(.62f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(lines.takeLast(3)) { line ->
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
                                        Text(if (line.user) "YOU / VOICE" else "J.A.R.V.I.S. / RESPONSE",
                                            color = if (line.user) TextMuted else Cyan, fontSize = 8.sp, letterSpacing = 1.4.sp)
                                        Spacer(Modifier.height(4.dp))
                                        if (line.revealed.value) RichMessageText(line.displayText.value)
                                        else Text(processStage, color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(50.dp).background(Color(0x220D2833), CircleShape)
                                    .border(1.dp, Color(0x6652DFF2), CircleShape)
                                    .clickable { voiceListening = !voiceListening }, contentAlignment = Alignment.Center) {
                                    Icon(if (voiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (voiceListening) "Matikan mikrofon" else "Aktifkan mikrofon",
                                        tint = if (voiceListening) Color(0xFFFF8A8A) else Cyan, modifier = Modifier.size(22.dp))
                                }
                                Text(if (voiceListening) "MIC OFF" else "MIC ON", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(82.dp).background(Color(0x3320BFD8), CircleShape)
                                    .border(2.dp, Cyan.copy(alpha = .85f), CircleShape)
                                    .clickable {
                                        if (busy) {
                                            cancelCurrentRequest?.invoke()
                                            cancelCurrentRequest = null
                                            busy = false
                                            statusText = "REQUEST STOPPED"
                                            lines.add(Line(false, "Permintaan dihentikan."))
                                        } else if (!voiceListening) voiceListening = true else onStartListening()
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(if (busy) Icons.Default.Stop else Icons.Default.SettingsVoice,
                                        contentDescription = if (busy) "Stop" else "Listen",
                                        tint = if (busy) Color(0xFFFF8A8A) else Cyan, modifier = Modifier.size(32.dp))
                                }
                                Text(if (busy) "STOP" else if (voiceListening) "LISTENING" else "LISTEN",
                                    color = Cyan, fontSize = 8.sp, letterSpacing = 1.5.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(50.dp).background(Color(0x220D2833), CircleShape)
                                    .border(1.dp, Color(0x6652DFF2), CircleShape)
                                    .clickable { voiceListening = false; page = "CHAT" }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Language, contentDescription = "Chat mode", tint = Cyan, modifier = Modifier.size(22.dp))
                                }
                                Text("CHAT", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.sp)
                            }
                        }
                        Text(if (voiceListening) "BICARA LANGSUNG · JARVIS SIAP MENDENGARKAN" else "ENABLE MIC TO START VOICE CONTROL",
                            color = Color(0xFF397F8D), fontSize = 7.sp, letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(top = 7.dp, bottom = 4.dp))
                    }
               } else {
                    Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CHAT CONSOLE", color = Color(0xFF8EDDED), fontSize = 9.sp, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
                        Text(lines.size.toString()+" ENTRIES", color = TextMuted, fontSize = 7.sp)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), state=listState, contentPadding=PaddingValues(bottom=12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if (lines.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(top=34.dp, bottom=22.dp),
                                horizontalAlignment=Alignment.CenterHorizontally) {
                                Box(Modifier.size(104.dp).background(Color(0xFF0B2430), CircleShape)
                                    .border(1.dp, Color(0x6652DFF2), CircleShape), contentAlignment=Alignment.Center) {
                                    Text("J", color=Cyan, fontSize=48.sp, fontWeight=FontWeight.Light)
                                }
                                Spacer(Modifier.height(18.dp))
                                Text("GOOD DAY. I'M JARVIS.", color=Color(0xFFBDEFFA), fontSize=13.sp,
                                    letterSpacing=2.sp, fontWeight=FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Text("Your personal intelligence system is ready.",
                                    color=TextMuted, fontSize=12.sp, textAlign=TextAlign.Center)
                                Spacer(Modifier.height(18.dp))
                                Column(Modifier.fillMaxWidth().background(Color(0x99081721), RoundedCornerShape(14.dp))
                                    .border(1.dp, Color(0x4449B8CB), RoundedCornerShape(14.dp)).padding(15.dp)) {
                                    Text("TRY A COMMAND", color=Cyan, fontSize=8.sp, letterSpacing=1.8.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Text("“Carikan rekomendasi HP terbaik.”", color=Color(0xFFD9EDF2), fontSize=12.sp)
                                    Spacer(Modifier.height(7.dp))
                                    Text("“Bandingkan dua pilihan laptop.”", color=Color(0xFFD9EDF2), fontSize=12.sp)
                                    Spacer(Modifier.height(7.dp))
                                    Text("“Buka YouTube.”", color=Color(0xFFD9EDF2), fontSize=12.sp)
                                }
                            }
                        }
                        items(lines.size) { i ->
                            val line=lines[i]
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(180)) +
                                    expandVertically(
                                        expandFrom = Alignment.Top,
                                        animationSpec = tween(durationMillis = 720, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    )
                            ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=if(line.user) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max = if (line.user) 292.dp else 326.dp)
                                    .wrapContentWidth(Alignment.Start)
                                    .background(if(line.user) Color(0xFF0A2633) else Color(0xCC081923), RoundedCornerShape(12.dp))
                                    .border(1.dp, if(line.user) Color(0x7755CFE8) else Color(0x5549B8CB), RoundedCornerShape(12.dp)).padding(11.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Text(if(line.user) "YOU / CHAT" else "J.A.R.V.I.S. / RESPONSE", color=if(line.user) Color(0xFF8BB4C1) else Cyan, fontSize=8.sp, letterSpacing=1.1.sp, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                                        Text(line.time, color=TextMuted, fontSize=8.sp)
                                        
                                    }
                                    Spacer(Modifier.height(7.dp))
                                    AnimatedVisibility(
                                        visible = line.revealed.value,
                                        enter = fadeIn(animationSpec = tween(180)) + expandVertically(
                                            expandFrom = Alignment.Top,
                                            animationSpec = tween(durationMillis = 720, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                        )
                                    ) {
                                        Column {
                                            RichMessageText(line.displayText.value)
                                            if (!line.user) {
                                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { clipboard.setText(AnnotatedString(line.text)) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                                                Icon(Icons.Default.ContentCopy, null, tint = TextMuted, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Salin", color = TextMuted, fontSize = 9.sp)
                                            }
                                            TextButton(onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, line.text)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(shareIntent, "Bagikan jawaban JARVIS")) }
                                            }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                                                Icon(Icons.Default.Language, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Bagikan", color = TextMuted, fontSize = 9.sp)
                                            }
                                                }
                                            }
                                            if (!line.user) SourceStrip(line.text)
                                        }
                                    }
                                    line.attachments.forEach { file ->
                                        Row(Modifier.fillMaxWidth().padding(top=5.dp), verticalAlignment=Alignment.CenterVertically) {
                                            Icon(Icons.Default.AttachFile, null, tint=Cyan, modifier=Modifier.size(13.dp))
                                            Text(file.name, color=TextMuted, fontSize=9.sp, maxLines=1)
                                        }
                                    }
                                }
                            }
                            }
                        }
                        if(busy) item {
                            val pulse by rememberInfiniteTransition(label="thinkingPulse").animateFloat(.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label="thinkingAlpha")
                            Row(Modifier.wrapContentWidth().padding(start = 5.dp, top = 2.dp, bottom = 4.dp),
                                verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(6.dp).background(Cyan.copy(alpha=pulse), CircleShape))
                                Text(processStage, color=TextMuted, fontSize=10.sp)
                                repeat(3) { index -> Box(Modifier.size(4.dp).background(Cyan.copy(alpha=if (index == 1) pulse else .45f), CircleShape)) }
                            }
                        }
                    }
                    if (pendingAttachments.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top=5.dp), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            pendingAttachments.toList().forEach { file ->
                                Row(Modifier.weight(1f, fill=false).background(Color(0xFF102B38), RoundedCornerShape(16.dp)).padding(start=8.dp, end=2.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Text(file.name, color=Color(0xFFD7F3F8), fontSize=9.sp, maxLines=1, modifier=Modifier.widthIn(max=115.dp))
                                    IconButton(onClick={ pendingAttachments.remove(file) }, modifier=Modifier.size(22.dp)) { Icon(Icons.Default.Close, "Hapus lampiran", tint=TextMuted, modifier=Modifier.size(12.dp)) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top=5.dp,bottom=2.dp)
                        .background(Color(0xFF081923), RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0x8852DFF2), RoundedCornerShape(28.dp))
                        .padding(start=7.dp,end=5.dp,top=3.dp,bottom=3.dp), verticalAlignment=Alignment.CenterVertically) {
                        Box {
                            Box(
                                Modifier.size(36.dp)
                                    .background(Color(0xFF103442), CircleShape)
                                    .border(1.dp, Color(0x8852DFF2), CircleShape)
                                    .clickable(enabled = !busy) { attachmentMenu = !attachmentMenu },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (attachmentMenu) Icons.Default.Close else Icons.Default.Add,
                                    if (attachmentMenu) "Tutup menu lampiran" else "Tambah lampiran",
                                    tint = Cyan, modifier = Modifier.size(21.dp))
                            }
                            DropdownMenu(expanded=attachmentMenu, onDismissRequest={ attachmentMenu=false },
                                properties = PopupProperties(focusable = true),
                                modifier=Modifier.background(Color(0xFF102632))) {
                                DropdownMenuItem(text={ Text("Pengaturan AI", color=Color.White) },
                                    onClick={ attachmentMenu=false; keyDraft=""; settings=true },
                                    leadingIcon={ Icon(Icons.Default.Settings, null, tint=Cyan) })
                                DropdownMenuItem(text={ Text("File / Dokumen", color=Color.White) },
                                    onClick={ attachmentMenu=false; attachmentPicker.launch(arrayOf("*/*")) },
                                    leadingIcon={ Icon(Icons.Default.AttachFile, null, tint=Cyan) })
                                DropdownMenuItem(text={ Text("Kamera", color=Color.White) },
                                    onClick={ attachmentMenu=false; cameraPicker.launch(null) },
                                    leadingIcon={ Icon(Icons.Default.CameraAlt, null, tint=Cyan) })
                                DropdownMenuItem(text={ Text("Foto dari galeri", color=Color.White) },
                                    onClick={ attachmentMenu=false; photoPicker.launch("image/*") },
                                    leadingIcon={ Icon(Icons.Default.Image, null, tint=Cyan) })
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        BasicTextField(value=command,onValueChange={command=it},
                            modifier=Modifier.weight(1f).focusRequester(inputFocus).padding(vertical=8.dp),
                            singleLine=false, minLines=1, maxLines=4, textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=15.sp),
                            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),
                            keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={ if (!busy) { keyboard?.hide(); focusManager.clearFocus(); send(command) } }),
                            decorationBox={ inner -> if(command.isEmpty()) Text("Message JARVIS…",color=TextMuted,fontSize=13.sp); inner() })
                        if (busy) {
                            Box(Modifier.padding(start = 5.dp).size(40.dp)
                                .background(Color(0x33250E16), CircleShape)
                                .border(1.dp, Color(0xCCFF7777), CircleShape)
                                .clickable {
                                    cancelCurrentRequest?.invoke()
                                    cancelCurrentRequest = null
                                    busy = false
                                    statusText = "REQUEST STOPPED"
                                    lines.add(Line(false, "Permintaan dihentikan."))
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Stop, "Stop response", tint = Color(0xFFFF8A8A), modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick={ keyboard?.hide(); focusManager.clearFocus(); send(command) }) {
                                Icon(Icons.Default.Send,"Send command",tint=Cyan)
                            }
                        }
                    }
                }
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp)
                        .background(Color(0xFF071923), RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0x3354DDF4), RoundedCornerShape(24.dp)).padding(3.dp)) {
                        listOf("CHAT", "VOICE").forEach { tab ->
                            TextButton(onClick = { if (tab == "CHAT") voiceListening = false; page = tab }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(contentColor = if (page == tab) Cyan else TextMuted)) {
                                Text(if (tab == "VOICE") "◉  VOICE" else "◉  CHAT", fontSize = 9.sp, letterSpacing = 1.1.sp)
                            }
                        }
                    }
                if (page == "VOICE") Text("J.A.R.V.I.S. / PERSONAL INTELLIGENCE SYSTEM", color=Color(0xFF355C68), fontSize=6.sp, letterSpacing=1.3.sp, modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=3.dp))
            }
            if(settings) AlertDialog(onDismissRequest={settings=false},containerColor=Color(0xFF0A1922),
                title={Text("J.A.R.V.I.S. SETTINGS",color=Color.White)},
                text={Column {
                    Text("Bahasa respons JARVIS",color=TextMuted,fontSize=11.sp)
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth().background(Color(0xFF071923), RoundedCornerShape(12.dp)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("auto" to "AUTO", "id" to "INDONESIA", "en" to "ENGLISH").forEach { (value, label) ->
                            TextButton(onClick = { responseLanguage = value }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(contentColor = if (responseLanguage == value) Cyan else TextMuted)) {
                                Text(label, fontSize = 8.sp, letterSpacing = .5.sp)
                            }
                        }
                    }
                    Text(
                        when (responseLanguage) {
                            "en" -> "Kamu tetap bicara Bahasa Indonesia. JARVIS akan memahami lalu menjawab dan berbicara dalam Bahasa Inggris."
                            "id" -> "JARVIS selalu menjawab dan berbicara dalam Bahasa Indonesia."
                            else -> "JARVIS otomatis mengikuti bahasa yang kamu gunakan."
                        }, color = TextMuted, fontSize = 9.sp, lineHeight = 14.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Gemini TTS API key",color=TextMuted,fontSize=11.sp)
                    OutlinedTextField(value=keyDraft,onValueChange={keyDraft=it},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Spacer(Modifier.height(14.dp))

                }},
                confirmButton={TextButton(onClick={
                    onSaveResponseLanguage(responseLanguage)
                    if(keyDraft.isNotBlank()) onSaveGeminiKey(keyDraft.trim())
                    settings=false;keyDraft=""
                }){Text("SAVE",color=Cyan)}},
                dismissButton={TextButton(onClick={settings=false}){Text("CANCEL",color=TextMuted)}})
        }
    }
}
