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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
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

// --- SCI-FI HUD PALETTE ---
private val Background = Color(0xFF040911)
private val DeepCard = Color(0xFF081421)
private val Panel = Color(0xFF0B1A2A)
private val Cyan = Color(0xFF00E5FF)
private val CyanGlow = Color(0xFF26F0FF)
private val CyanBorder = Color(0xFF145674)
private val TextMuted = Color(0xFF7B9EB2)
private val Ice = Color(0xFFECF9FF)
private val WarnAmber = Color(0xFFFFC56A)
private val AlertRed = Color(0xFFFF5E6E)
private val OkGreen = Color(0xFF00F59B)
private val Glass = Color(0xE6081420)
private val HudShape = RoundedCornerShape(16.dp)
private val CardShape = RoundedCornerShape(14.dp)

data class VisualProduct(
    val title: String,
    val image: String,
    val price: String,
    val source: String,
    val rating: String,
    val reviews: String,
    val link: String,
    val badge: String = "Rekomendasi"
)

data class ChatResponsePayload(
    val answer: String,
    val products: List<VisualProduct> = emptyList(),
    val images: List<Pair<String, String>> = emptyList()
)

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
    private val liveRms = mutableStateOf(2f)
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
    @Volatile private var recognitionActive = false
    @Volatile private var recognitionStartPending = false
    private var speechPlayer: MediaPlayer? = null
    private var voiceModeEnabled = false
    private var recognitionLanguage = "id-ID"
    private val prefs by lazy { getSharedPreferences("jarvis_secure_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val voices = tts?.voices.orEmpty()
                val indoVoice = voices.firstOrNull { it.locale.toLanguageTag().equals("id-ID", true) }
                if (indoVoice != null) tts?.voice = indoVoice else tts?.language = Locale("id", "ID")
                tts?.setSpeechRate(0.88f)
                tts?.setPitch(0.80f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { runOnUiThread { voiceStatus.value = "JARVIS SPEAKING" } }
                    override fun onDone(utteranceId: String?) {
                        if (!geminiSpeechInProgress && voiceModeEnabled) handler.postDelayed({ startListening() }, 550)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!geminiSpeechInProgress && voiceModeEnabled) handler.postDelayed({ startListening() }, 700)
                    }
                })
                if (voiceStatus.value == "INITIALIZING VOICE CORE") voiceStatus.value = "VOICE CORE READY"
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { recognitionStartPending = false; recognitionActive = true; if (voiceModeEnabled) voiceStatus.value = "LISTENING" }
                override fun onBeginningOfSpeech() { voiceStatus.value = "AUDIO INPUT ACTIVE" }
                override fun onRmsChanged(rmsdB: Float) {
                    liveRms.value = rmsdB.coerceIn(0f, 16f)
                    if (voiceModeEnabled) clapDetector.onRms(rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (voiceModeEnabled) voiceStatus.value = "PROCESSING VOICE" }
                override fun onError(error: Int) {
                    recognitionActive = false
                    recognitionStartPending = false
                    if (geminiSpeechInProgress || clapActivationInProgress) return
                    voiceStatus.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO SPEECH DETECTED · RETRYING"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MICROPHONE PERMISSION REQUIRED"
                        else -> "RECONNECTING VOICE CORE"
                    }
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && voiceModeEnabled) handler.postDelayed({ startListening() }, 900)
                }
                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    recognitionStartPending = false
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val bestIndex = if (scores != null && scores.size == candidates.size) scores.indices.maxByOrNull { scores[it] } ?: 0 else 0
                    val text = candidates.getOrNull(bestIndex).orEmpty()
                    if (text.isNotBlank()) {
                        recognitionLanguage = detectRecognitionLanguage(text)
                        voiceText.value = text
                        voiceStatus.value = "COMMAND RECEIVED"
                        if (voiceModeEnabled) handler.postDelayed({ startListening() }, 450)
                    } else if (voiceModeEnabled) handler.postDelayed({ startListening() }, 350)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) {
                        voiceStatus.value = "HEARING: ${partial.take(28)}"
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else voiceStatus.value = "VOICE RECOGNITION UNAVAILABLE"

        setContent {
            JarvisScreen(
                recognizedText = voiceText.value,
                status = voiceStatus.value,
                rmsLevel = liveRms.value,
                onConsumed = { voiceText.value = "" },
                onSpeak = { speak(it) },
                onStartListening = { startListening() },
                onModeChanged = { voiceMode ->
                    voiceModeEnabled = voiceMode
                    if (voiceMode) startListening() else { recognitionActive = false; recognitionStartPending = false; clapDetector.reset(); recognizer?.cancel(); voiceStatus.value = "MIC OFF" }
                    voiceStatus.value = if (voiceMode) "MICROPHONE STARTING" else "MIC OFF"
                },
                onSearch = { _ -> voiceStatus.value = "AI COUNCIL DISCUSSION" },
                onSaveGeminiKey = { key -> prefs.edit().putString("gemini_api_key", key.trim()).apply() },
                onAskGemini = { prompt, callback -> askGemini(prompt, callback) },
                onOpenApp = { q -> openRequestedApp(q) }
            )
        }
    }

    private fun askGemini(prompt: String, callback: (ChatResponsePayload) -> Unit): () -> Unit {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        Thread {
            var connection: HttpURLConnection? = null
            val payload = try {
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
                val body = JSONObject().put("message", prompt)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                if (stream == null) throw IllegalStateException("Server tidak mengirim response body.")
                val rawBytes = stream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 1_500_000) throw IllegalStateException("Jawaban terlalu besar untuk ditampilkan. Coba persempit permintaan.")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val raw = String(rawBytes, Charsets.UTF_8).trim()
                val json = try { JSONObject(raw) } catch (_: Exception) { null }
                if (json == null) {
                    if (code in 200..299 && raw.isNotBlank()) ChatResponsePayload(raw)
                    else ChatResponsePayload("JARVIS API mengirim respons yang tidak valid (HTTP $code).")
                } else if (code !in 200..299 || !json.optBoolean("ok", json.has("answer") || json.has("reply") || json.has("response"))) {
                    val diagnostics = json.optJSONArray("diagnostics")
                    val detail = if (diagnostics != null) (0 until diagnostics.length()).mapNotNull { i ->
                        val item = diagnostics.optJSONObject(i) ?: return@mapNotNull null
                        item.optString("provider") + ": " + item.optString("detail", item.optString("status"))
                    }.joinToString("\n") else ""
                    val errText = json.optString("error").ifBlank {
                        json.optString("detail").ifBlank { "JARVIS API error ($code)." }
                    } + if (detail.isNotBlank()) "\n\nDetail provider:\n$detail" else " Periksa provider AI dan koneksi backend."
                    ChatResponsePayload(errText)
                } else {
                    val answer = sequenceOf("answer", "reply", "response", "message", "text", "output")
                        .map { json.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                        ?: "JARVIS tidak menerima jawaban akhir."
                    val visualsObj = json.optJSONObject("visuals")
                    val kind = visualsObj?.optString("kind").orEmpty()
                    val resultsArr = visualsObj?.optJSONArray("results")
                    val parsedProducts = mutableListOf<VisualProduct>()
                    val parsedImages = mutableListOf<Pair<String, String>>()
                    if (resultsArr != null) {
                        val badges = listOf("Terlaris", "Rekomendasi", "Murah", "Pilihan", "Top")
                        for (i in 0 until resultsArr.length()) {
                            val item = resultsArr.optJSONObject(i) ?: continue
                            if (kind == "products" || item.has("price")) {
                                parsedProducts.add(
                                    VisualProduct(
                                        title = item.optString("title", "Produk"),
                                        image = item.optString("image", ""),
                                        price = item.optString("price", "Cek Harga"),
                                        source = item.optString("source", "Toko Online"),
                                        rating = item.optDouble("rating", 4.8).let { if (it.isNaN() || it <= 0.0) "4.8" else String.format(Locale.US, "%.1f", it) },
                                        reviews = item.optInt("reviews", 0).let { if (it > 0) "$it" else "100+" },
                                        link = item.optString("link", ""),
                                        badge = badges[i % badges.size]
                                    )
                                )
                            } else {
                                val img = item.optString("image", "")
                                val title = item.optString("title", "Referensi Visual")
                                if (img.isNotBlank()) parsedImages.add(title to img)
                            }
                        }
                    }
                    ChatResponsePayload(answer, parsedProducts, parsedImages)
                }
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val err = if (message.contains("Jawaban terlalu besar")) message
                else "Gagal menghubungi JARVIS Worker: periksa koneksi internet dan status backend."
                ChatResponsePayload(err)
            } finally {
                connection?.disconnect()
            }
            if (!cancelled.get()) handler.post { if (!cancelled.get()) callback(payload) }
        }.start()
        return {
            cancelled.set(true)
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        val cleanSpeech = text.replace(Regex("```[\\s\\S]*?```"), " blok kode telah ditampilkan di layar. ")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[#*|>`~_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1800)
        if (cleanSpeech.isBlank()) return
        geminiSpeechInProgress = true
        recognitionActive = false
        recognitionStartPending = false
        recognizer?.cancel()
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
                val body = JSONObject().put("text", cleanSpeech)
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
                            setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                            setVolume(1.0f, 1.0f)
                            setDataSource(audioFile.absolutePath)
                            setOnPreparedListener {
                                setVolume(1.0f, 1.0f)
                                voiceStatus.value = "JARVIS SPEAKING"
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
                                voiceStatus.value = "AUDIO PLAYBACK ERROR"
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
            } catch (_: Exception) {
                handler.post {
                    geminiSpeechInProgress = false
                    speakWithAndroid(cleanSpeech)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun detectRecognitionLanguage(text: String): String {
        val q = text.lowercase(Locale.ROOT)
        val englishCue = Regex("\\b(the|and|you|your|what|how|why|please|could|would|hello|thanks|with|from|about|create|build|make|explain)\\b")
        return if (englishCue.containsMatchIn(q)) "en-US" else "id-ID"
    }

    private fun isEnglishText(text: String): Boolean {
        val q = text.lowercase(Locale.ROOT)
        val english = Regex("\\b(the|and|you|your|what|how|why|please|could|would|hello|thanks|with|from|about|create|build|make|explain|find|search|show|tell|calculate|write|open|done|here|there|this|that|for|is|are|can|will)\\b").findAll(q).count()
        val indonesian = Regex("\\b(aku|saya|kamu|anda|yang|dan|dengan|untuk|tidak|nggak|enggak|gimana|kenapa|tolong|bisa|buat|jadi|adalah|sekarang|besok|kemarin|carikan|jelaskan|hitung|tampilkan|berapa|bagaimana)\\b").findAll(q).count()
        return english > indonesian
    }
    private fun speakWithAndroid(text: String) {
        tts?.language = Locale.forLanguageTag(if (isEnglishText(text)) "en-US" else "id-ID")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun startListening() {
        clapActivationInProgress = false
        if (isFinishing || isDestroyed || !voiceModeEnabled || geminiSpeechInProgress || recognitionActive || recognitionStartPending) return
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
            recognitionStartPending = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speech.startListening(intent)
        } catch (_: Exception) {
            recognitionStartPending = false
            recognitionActive = false
            voiceStatus.value = "VOICE CORE RESTARTING"
            if (voiceModeEnabled) handler.postDelayed({ startListening() }, 1200)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                voiceStatus.value = "MICROPHONE READY"
                if (voiceModeEnabled) handler.post { startListening() }
            } else voiceStatus.value = "MICROPHONE PERMISSION DENIED"
        }
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

    override fun onPause() { recognitionActive = false; recognitionStartPending = false; recognizer?.cancel(); super.onPause() }
    override fun onResume() { super.onResume(); if (recognizer != null && voiceModeEnabled) handler.postDelayed({ startListening() }, 500) }
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

// --- MARKDOWN & STRUCTURED HUD WIDGET PARSER ---
private sealed class MarkdownBlock {
    data class Heading(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Code(val language: String, val text: String) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data class ImageBlock(val alt: String, val url: String) : MarkdownBlock()
    data class Bullet(val text: String, val ordered: Boolean, val index: Int = 1) : MarkdownBlock()
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
    var orderCounter = 1
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) { i++; orderCounter = 1; continue }
        val imageMatch = Regex("^\\s*!\\[([^]]*)]\\((https?://[^ )]+)\\)\\s*$").matchEntire(line)
        if (imageMatch != null) { blocks.add(MarkdownBlock.ImageBlock(imageMatch.groupValues[1], imageMatch.groupValues[2])); i++; continue }
        val heading = Regex("^\\s{0,3}(#{1,3})\\s+(.+)$").matchEntire(line)
            ?: Regex("^\\s*\\*\\*(.{3,72})\\*\\*\\s*$").matchEntire(line)
        if (heading != null) {
            blocks.add(MarkdownBlock.Heading(heading.groupValues[2].ifBlank { heading.groupValues[1] }))
            i++
            orderCounter = 1
            continue
        }
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
        val bullet = Regex("^\\s*(?:[-*+]\\s+|(\\d+)[.)]\\s+)(.*)$").matchEntire(line)
        if (bullet != null) {
            val isOrd = bullet.groupValues[1].isNotEmpty()
            val idx = bullet.groupValues[1].toIntOrNull() ?: orderCounter++
            blocks.add(MarkdownBlock.Bullet(bullet.groupValues[2], isOrd, idx))
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
        blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
    }
    return blocks
}

@Composable
private fun InlineMarkdownText(markdown: String, fontSize: androidx.compose.ui.unit.TextUnit = 13.5.sp) {
    val uriHandler = LocalUriHandler.current
    val linkPattern = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)")
    val rawUrlPattern = Regex("https?://[^\\s<>]+(?<![.,!?;:)\\]])")
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    val clean = markdown.replace(Regex("<(https?://[^>]+)>"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "")
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
                val target = if (kind == "link") match.groupValues[2] else match.value
                val label = if (kind == "link") match.groupValues[1] else runCatching { java.net.URI(target).host?.removePrefix("www.") ?: "Lihat Produk" }.getOrDefault("Lihat Produk")
                withStyle(SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(label) }
                addStringAnnotation("URL", target, startOffset, length)
            } else withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF3FCFF))) { append(match.groupValues[1]) }
            cursor = match.range.last + 1
        }
        if (cursor < clean.length) append(clean.substring(cursor))
    }
    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(color = Ice, fontSize = fontSize, lineHeight = if (fontSize.value >= 13f) 20.sp else 16.sp),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
        }
    )
}

@Composable
private fun RemoteMarkdownImage(alt: String, url: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color(0xFF06101B))
            .border(1.dp, Cyan.copy(alpha = .32f), CardShape)
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url)
                    .memoryCacheKey(url).diskCacheKey(url).build(),
                contentDescription = alt.ifBlank { "Hasil gambar JARVIS" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 230.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            if (alt.isNotBlank()) {
                Text(alt, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xB3040B14))
                .border(1.dp, Cyan.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                .clickable { runCatching { uriHandler.openUri(url) } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.OpenInFull, contentDescription = "Buka gambar", tint = Cyan, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun MarkdownCell(value: String, fontSize: androidx.compose.ui.unit.TextUnit = 10.5.sp) {
    val image = Regex("!\\[([^]]*)]\\((https?://[^ )]+)\\)").find(value)
    if (image != null) RemoteMarkdownImage(image.groupValues[1], image.groupValues[2])
    else InlineMarkdownText(value, fontSize)
}

@Composable
private fun ProductCarouselCards(products: List<VisualProduct>) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(products) { item ->
            Column(
                modifier = Modifier
                    .width(172.dp)
                    .clip(CardShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A1B2E), Color(0xFF06111D))))
                    .border(1.dp, Cyan.copy(alpha = 0.35f), CardShape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(106.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF040B14))
                ) {
                    if (item.image.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(item.image)
                                .memoryCacheKey(item.image).diskCacheKey(item.image).build(),
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ShoppingBag, null, tint = Cyan.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xD9050E1A))
                            .border(0.7.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(item.badge, color = Ice, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = item.title,
                    color = Ice,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    minLines = 2,
                    lineHeight = 15.sp,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.price,
                    color = Cyan,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Storefront, null, tint = OkGreen, modifier = Modifier.size(12.dp))
                    Text(item.source.take(18), color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("★", color = WarnAmber, fontSize = 11.sp)
                    Text("${item.rating} (${item.reviews})", color = TextMuted, fontSize = 10.sp)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF0A2236))
                        .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .clickable { if (item.link.isNotBlank()) runCatching { uriHandler.openUri(item.link) } }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.OpenInNew, null, tint = Cyan, modifier = Modifier.size(12.dp))
                        Text("Lihat Produk", color = Ice, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val headers = rows.first()
    val dataRows = rows.drop(1)
    val productLike = headers.size >= 4 && headers.any { it.contains("harga", true) || it.contains("produk", true) || it.contains("toko", true) }
    val vsComparison = headers.size == 3 && !productLike

    if (productLike) {
        val extracted = dataRows.mapIndexed { idx, row ->
            val title = row.getOrNull(0).orEmpty().replace(Regex("\\*\\*"), "").trim()
            val imgMatch = row.firstNotNullOfOrNull { Regex("!\\[[^]]*]\\((https?://[^ )]+)\\)").find(it) }
            val priceIdx = headers.indexOfFirst { it.contains("harga", true) || it.contains("price", true) }
            val storeIdx = headers.indexOfFirst { it.contains("toko", true) || it.contains("rating", true) || it.contains("detail", true) }
            val linkMatch = row.firstNotNullOfOrNull { Regex("\\[[^]]+]\\((https?://[^ )]+)\\)").find(it) }
            val badges = listOf("Terlaris", "Rekomendasi", "Murah", "Pilihan")
            VisualProduct(
                title = title.ifBlank { "Produk ${idx + 1}" },
                image = imgMatch?.groupValues?.getOrNull(1).orEmpty(),
                price = if (priceIdx >= 0) row.getOrNull(priceIdx).orEmpty().replace("**", "") else "",
                source = if (storeIdx >= 0) row.getOrNull(storeIdx).orEmpty().substringBefore("·").trim() else "Marketplace",
                rating = "4.8",
                reviews = "1rb+",
                link = linkMatch?.groupValues?.getOrNull(1).orEmpty(),
                badge = badges[idx % badges.size]
            )
        }
        ProductCarouselCards(extracted)
    } else if (vsComparison) {
        // Spec VS Comparison Table like in reference image
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(Color(0xFF071422))
                .border(1.dp, Cyan.copy(alpha = 0.35f), CardShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0C2238))
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    headers.getOrNull(1).orEmpty().replace("**", ""),
                    color = Ice,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF071626))
                        .border(1.dp, Cyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("VS", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    headers.getOrNull(2).orEmpty().replace("**", ""),
                    color = Ice,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
            dataRows.forEachIndexed { idx, r ->
                val isPrice = r.getOrNull(0).orEmpty().contains("harga", true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (idx % 2 == 0) Color(0xFF081828) else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        r.getOrNull(0).orEmpty().replace("**", ""),
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(0.75f)
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (isPrice) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(OkGreen.copy(alpha = 0.16f))
                                    .border(1.dp, OkGreen.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(r.getOrNull(1).orEmpty().replace("**", ""), color = OkGreen, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            MarkdownCell(r.getOrNull(1).orEmpty(), 10.5.sp)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (isPrice) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(WarnAmber.copy(alpha = 0.16f))
                                    .border(1.dp, WarnAmber.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(r.getOrNull(2).orEmpty().replace("**", ""), color = WarnAmber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            MarkdownCell(r.getOrNull(2).orEmpty(), 10.5.sp)
                        }
                    }
                }
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(Color(0xFF07131E))
                .border(1.dp, Cyan.copy(alpha = 0.35f), CardShape)
                .padding(6.dp)
        ) {
            rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { cell ->
                        Box(
                            Modifier
                                .weight(1f)                                .heightIn(min = 32.dp)
                                .background(if (ri == 0) Color(0xFF0E2C42) else Color.Transparent)
                                .border(0.5.dp, Color(0x3349B8CB))
                                .padding(6.dp)
                        ) {
                            MarkdownCell(cell, if (ri == 0) 10.5.sp else 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockHudCard(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lines = code.lines()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color(0xFF060E18))
            .border(1.dp, Cyan.copy(alpha = .38f), CardShape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1C2E))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Code, null, tint = Cyan, modifier = Modifier.size(15.dp))
                Text(
                    text = if (language.isNotBlank()) "Kode (${language.lowercase()})" else "Kode Program",
                    color = Ice,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF102A42))
                    .border(1.dp, Cyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.ContentCopy, null, tint = Cyan, modifier = Modifier.size(12.dp))
                Text(if (copied) "Tersalin" else "Salin", color = Ice, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Column(Modifier.padding(end = 10.dp), horizontalAlignment = Alignment.End) {
                lines.forEachIndexed { idx, _ ->
                    Text(
                        text = "${idx + 1}",
                        color = TextMuted.copy(alpha = 0.5f),
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = code,
                    color = Color(0xFFD6F4FF),
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private data class StructuredSection(
    val title: String?,
    val blocks: List<MarkdownBlock>
)

private fun groupBlocksIntoSections(blocks: List<MarkdownBlock>): List<StructuredSection> {
    if (blocks.isEmpty()) return emptyList()
    val sections = mutableListOf<StructuredSection>()
    var currentTitle: String? = null
    var currentList = mutableListOf<MarkdownBlock>()

    for (b in blocks) {
        if (b is MarkdownBlock.Heading) {
            if (currentTitle != null || currentList.isNotEmpty()) {
                sections.add(StructuredSection(currentTitle, currentList.toList()))
                currentList = mutableListOf()
            }
            currentTitle = b.text
        } else {
            currentList.add(b)
        }
    }
    if (currentTitle != null || currentList.isNotEmpty()) {
        sections.add(StructuredSection(currentTitle, currentList.toList()))
    }
    return sections
}

@Composable
private fun SectionIconForTitle(title: String): Pair<ImageVector, Color> {
    val low = title.lowercase(Locale.ROOT)
    return when {
        low.contains("kesimpulan") || low.contains("ringkasan") || low.contains("rangkuman") -> Icons.Default.CheckCircle to OkGreen
        low.contains("tips") || low.contains("saran") || low.contains("catatan") -> Icons.Default.Lightbulb to WarnAmber
        low.contains("mind map") || low.contains("konsep") || low.contains("struktur") -> Icons.Default.AccountTree to Color(0xFFFF6B9D)
        low.contains("soal") || low.contains("latihan") || low.contains("kuis") -> Icons.Default.Quiz to Cyan
        low.contains("biaya") || low.contains("anggaran") || low.contains("harga") || low.contains("keuangan") -> Icons.Default.PieChart to WarnAmber
        low.contains("itinerary") || low.contains("hari") || low.contains("jadwal") || low.contains("travel") -> Icons.Default.FlightTakeoff to Cyan
        low.contains("kode") || low.contains("tampilan") || low.contains("preview") -> Icons.Default.Terminal to Cyan
        low.contains("analisis") || low.contains("gambar") || low.contains("produk") -> Icons.Default.AutoAwesome to Cyan
        else -> Icons.Default.DashboardCustomize to Cyan
    }
}

@Composable
private fun RichMessageText(
    markdown: String,
    products: List<VisualProduct> = emptyList(),
    onQuickFollowUp: ((String) -> Unit)? = null
) {
    val blocks = parseMarkdownBlocks(markdown)
    if (blocks.isEmpty() && markdown.isNotBlank()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(DeepCard)
                .border(1.dp, Cyan.copy(alpha = 0.3f), CardShape)
                .padding(12.dp)
        ) {
            InlineMarkdownText(markdown, 13.5.sp)
        }
        return
    }

    val sections = groupBlocksIntoSections(blocks)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sections.forEachIndexed { idx, section ->
            val staggerDelay = idx * 135
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> -fullHeight / 5 },
                    animationSpec = tween(durationMillis = 520, delayMillis = staggerDelay)
                ) + fadeIn(animationSpec = tween(durationMillis = 520, delayMillis = staggerDelay)),
                exit = fadeOut(animationSpec = tween(durationMillis = 180))
            ) {
            val isIntroOnly = idx == 0 && section.title == null && section.blocks.size == 1 && section.blocks.first() is MarkdownBlock.Paragraph
            if (isIntroOnly) {
                val p = (section.blocks.first() as MarkdownBlock.Paragraph).text
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A1B2A))
                        .border(1.dp, Cyan.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    InlineMarkdownText(p, 13.5.sp)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFF091726), Color(0xFF06101B))))
                        .border(1.dp, Cyan.copy(alpha = 0.34f), CardShape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (section.title != null) {
                        val (icon, tint) = SectionIconForTitle(section.title)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                            Text(
                                text = section.title,
                                color = Ice,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Cyan.copy(alpha = 0.18f)))
                    }
                    section.blocks.forEach { block ->
                        when (block) {
                            is MarkdownBlock.Heading -> Text(block.text, color = Ice, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            is MarkdownBlock.Paragraph -> InlineMarkdownText(block.text, 13.sp)
                            is MarkdownBlock.ImageBlock -> RemoteMarkdownImage(block.alt, block.url)
                            is MarkdownBlock.Bullet -> Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (block.ordered) {
                                    Text(
                                        "${block.index}.",
                                        color = Cyan,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .padding(top = 7.dp)
                                            .size(5.dp)
                                            .background(Cyan, CircleShape)
                                    )
                                }
                                Box(Modifier.weight(1f)) { InlineMarkdownText(block.text, 13.sp) }
                            }
                            is MarkdownBlock.Code -> CodeBlockHudCard(block.language, block.text)
                            is MarkdownBlock.Table -> MarkdownTable(block.rows)
                        }
                    }
                }
            }
            }
        }

        if (products.isNotEmpty() && blocks.none { it is MarkdownBlock.Table }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(Color(0xFF081523))
                    .border(1.dp, Cyan.copy(alpha = 0.34f), CardShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.ShoppingBag, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Text("Rekomendasi Produk Terkait", color = Ice, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                }
                ProductCarouselCards(products)
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
    Surface(
        onClick = { runCatching { uriHandler.openUri(url) } },
        color = Color(0xFF0D2233),
        shape = CircleShape,
        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f))
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Open source", modifier = Modifier.size(16.dp))
            else Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SourceStrip(markdown: String) {
    val links = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)").findAll(markdown.replace("\\\\n", "\n")).map { it.groupValues[2] }.distinct().take(6).toList()
    if (links.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SUMBER:", color = TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            links.forEach { SourceLogo(it) }
        }
    }
}

@Composable
private fun JarvisAvatarBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF081B2B))
            .border(1.2.dp, Cyan, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Cyan.copy(alpha = 0.25f), r, c)
            drawCircle(Cyan, r * 0.85f, c, style = Stroke(1.3.dp.toPx()))
            drawCircle(Color.White, r * 0.28f, c)
            for (i in 0 until 6) {
                val a = i * 60f * (Math.PI / 180.0).toFloat()
                drawLine(
                    Cyan,
                    Offset(c.x + kotlin.math.cos(a) * r * 0.45f, c.y + kotlin.math.sin(a) * r * 0.45f),
                    Offset(c.x + kotlin.math.cos(a) * r * 0.8f, c.y + kotlin.math.sin(a) * r * 0.8f),
                    1.dp.toPx()
                )
            }
        }
    }
}

@Composable
private fun UserAvatarBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF0D2235))
            .border(1.dp, Cyan.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = "User", tint = Ice, modifier = Modifier.size(16.dp))
    }
}

private data class CategoryTileData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val prompt: String
)

@Composable
private fun JarvisHomeDashboard(
    compact: Boolean,
    spin: Float,
    breathe: Float,
    onSelectPrompt: (String) -> Unit
) {
    val categories = remember {
        listOf(
            CategoryTileData("Teknologi", "(HP, Laptop, Gadget)", Icons.Default.Devices, "Bandingkan Samsung Galaxy A56 5G dengan Redmi Note 15 Pro 5G lengkap dengan tabel spesifikasi dan kesimpulan."),
            CategoryTileData("Belajar", "(Materi, Rangkuman)", Icons.Default.MenuBook, "Rangkum materi Sistem Tata Surya, buat poin ciri-ciri & anggota, dan berikan 3 soal latihan."),
            CategoryTileData("Produktivitas", "(To-Do, Rencana)", Icons.Default.TaskAlt, "Buatkan jadwal produktif harian untuk pelajar & developer dari pagi sampai malam beserta tips fokus."),
            CategoryTileData("Kreativitas", "(Gambar, Desain, AI)", Icons.Default.Palette, "Carikan referensi gambar wallpaper cyberpunk futuristik resolusi tinggi dengan nuansa neon cyan."),
            CategoryTileData("Coding", "(Kode, Debug, Project)", Icons.Default.Code, "Buatkan contoh kode HTML + CSS untuk halaman profil developer modern beserta penjelasan strukturnya."),
            CategoryTileData("Hiburan", "(Film, Game, Musik)", Icons.Default.SportsEsports, "Rekomendasikan 5 game PC ringan terbaik untuk spek menengah beserta genre dan kelebihannya."),
            CategoryTileData("Travel", "(Destinasi, Itinerary)", Icons.Default.Flight, "Buat itinerary 3 hari 2 malam di Bali untuk 2 orang, lengkap dengan tabel rincian estimasi biaya."),
            CategoryTileData("Keuangan", "(Anggaran, Investasi)", Icons.Default.Savings, "Bantu buatkan rencana keuangan bulanan dengan metode 50/30/20 untuk anggaran Rp7.000.000 lengkap dengan tabel rincian dan tips."),
            CategoryTileData("Lainnya", "(Tanya apa saja)", Icons.Default.AutoAwesome, "Jelaskan kemampuan utama sistem J.A.R.V.I.S. Mobile dan bagaimana cara memaksimalkan fitur suara serta chat.")
        )
    }

    val quickCommands = remember {
        listOf(
            Triple(Icons.Default.Smartphone, "Cari rekomendasi HP 3 jutaan terbaik", "Carikan rekomendasi HP harga 3 jutaan terbaik untuk gaming dan kamera saat ini."),
            Triple(Icons.Default.LaptopChromebook, "Bandingkan dua laptop coding", "Bandingkan dua laptop terbaik di kelas 8 jutaan untuk coding dan multitasking."),
            Triple(Icons.Default.Map, "Buat itinerary liburan hemat", "Buatkan itinerary liburan 3 hari 2 malam ke Yogyakarta beserta estimasi anggaran.")
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Holographic J Core Orb
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 142.dp else 172.dp),
            contentAlignment = Alignment.Center
        ) {
            HomeHeroOrb(
                modifier = Modifier.size(if (compact) 136.dp else 164.dp),
                spin = spin,
                breathe = breathe
            )
        }

        Text(
            text = "GOOD DAY. I'M JARVIS.",
            color = Ice,
            fontSize = if (compact) 17.sp else 19.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.2.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "Your personal intelligence system is ready.",
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(14.dp))

        // 3x3 Category Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (rowIndex in 0 until 3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (colIndex in 0 until 3) {
                        val item = categories[rowIndex * 3 + colIndex]
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.verticalGradient(listOf(Color(0xFF0B1E32), Color(0xFF071220))))
                                .border(1.dp, Cyan.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                                .clickable { onSelectPrompt(item.prompt) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color(0xFF0E2942))
                                    .border(0.8.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(item.icon, contentDescription = item.title, tint = Cyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = item.title,
                                color = Ice,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = item.subtitle,
                                color = TextMuted,
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // Indicator dots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Cyan))
            Box(Modifier.size(5.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.3f)))
        }

        Spacer(Modifier.height(12.dp))

        // Quick Commands Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "Quick Commands",
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            quickCommands.forEach { (icon, label, prompt) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF081523))
                        .border(1.dp, Cyan.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                        .clickable { onSelectPrompt(prompt) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = label,
                        color = Ice,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeHeroOrb(modifier: Modifier, spin: Float, breathe: Float) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val min = size.minDimension
            // Side equalizer wing ticks
            for (side in listOf(-1, 1)) {
                for (b in 1..6) {
                    val x = c.x + side * (min * 0.42f + b * 7.dp.toPx())
                    val barH = (if (b % 2 == 0) 14.dp else 8.dp).toPx() * breathe
                    drawLine(
                        Cyan.copy(alpha = (0.55f - b * 0.07f).coerceAtLeast(0.1f)),
                        Offset(x, c.y - barH),
                        Offset(x, c.y + barH),
                        1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            // Outer glow
            drawCircle(
                Brush.radialGradient(listOf(Cyan.copy(alpha = 0.32f), Color.Transparent), c, min * 0.52f),
                min * 0.5f * breathe,
                c
            )
            // Outer dashed ring
            drawCircle(Cyan.copy(alpha = 0.25f), min * 0.42f, c, style = Stroke(1.dp.toPx()))
            val inset1 = min * 0.12f
            drawArc(
                Cyan.copy(alpha = 0.85f),
                spin,
                75f,
                false,
                topLeft = Offset(inset1, inset1),
                size = Size(size.width - inset1 * 2, size.height - inset1 * 2),
                style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                Cyan.copy(alpha = 0.85f),
                spin + 180f,
                75f,
                false,
                topLeft = Offset(inset1, inset1),
                size = Size(size.width - inset1 * 2, size.height - inset1 * 2),
                style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            )
            // Inner glowing ring
            drawCircle(Color(0xFF071828), min * 0.27f, c)
            drawCircle(Cyan, min * 0.27f, c, style = Stroke(2.dp.toPx()))
            drawCircle(Cyan.copy(alpha = 0.4f), min * 0.23f, c, style = Stroke(1.dp.toPx()))
        }
        Text(
            text = "J",
            color = Ice,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif
        )
    }
}

@Composable
private fun VoiceHudReactor(
    modifier: Modifier,
    phase: String,
    spin: Float,
    breathe: Float
) {
    val accent = when (phase) {
        "THINK" -> WarnAmber
        "SPEAK" -> Color(0xFF66F7FF)
        else -> Cyan
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val min = size.minDimension
            val pulse = if (phase == "IDLE") 1f else breathe

            // Radial ambient glow
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.08f), Color.Transparent),
                    c,
                    min * 0.52f
                ),
                min * 0.50f * pulse,
                c
            )

            // Outer tech degree ticks
            val tickCount = 60
            for (i in 0 until tickCount) {
                val angle = (i * (360f / tickCount) + spin * 0.25f) * (Math.PI / 180.0).toFloat()
                val major = i % 5 == 0
                val rIn = min * (if (major) 0.44f else 0.455f)
                val rOut = min * 0.48f
                drawLine(
                    accent.copy(alpha = if (major) 0.65f else 0.22f),
                    Offset(c.x + kotlin.math.cos(angle) * rIn, c.y + kotlin.math.sin(angle) * rIn),
                    Offset(c.x + kotlin.math.cos(angle) * rOut, c.y + kotlin.math.sin(angle) * rOut),
                    strokeWidth = if (major) 1.6.dp.toPx() else 0.9.dp.toPx()
                )
            }

            // Ring 1 (Outer segmented arcs)
            val r1 = min * 0.08f
            for (k in 0 until 4) {
                drawArc(
                    accent.copy(alpha = 0.85f),
                    startAngle = spin + k * 90f,
                    sweepAngle = 52f,
                    useCenter = false,
                    topLeft = Offset(r1, r1),
                    size = Size(size.width - r1 * 2, size.height - r1 * 2),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Ring 2 (Counter-rotating HUD ring)
            val r2 = min * 0.16f
            drawCircle(accent.copy(alpha = 0.25f), min * 0.34f, c, style = Stroke(1.dp.toPx()))
            for (k in 0 until 3) {
                drawArc(
                    accent.copy(alpha = 0.65f),
                    startAngle = -spin * 1.2f + k * 120f,
                    sweepAngle = 68f,
                    useCenter = false,
                    topLeft = Offset(r2, r2),
                    size = Size(size.width - r2 * 2, size.height - r2 * 2),
                    style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Butt)
                )
            }

            // Ring 3 (Inner bright core ring)
            drawCircle(Color(0xFF040D18), min * 0.24f, c)
            drawCircle(accent.copy(alpha = 0.95f), min * 0.24f * pulse, c, style = Stroke(2.4.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.35f), min * 0.20f, c, style = Stroke(1.dp.toPx()))

            // Horizontal HUD crosshair lines
            drawLine(
                accent.copy(alpha = 0.35f),
                Offset(0f, c.y),
                Offset(min * 0.22f, c.y),
                1.dp.toPx()
            )
            drawLine(
                accent.copy(alpha = 0.35f),
                Offset(size.width - min * 0.22f, c.y),
                Offset(size.width, c.y),
                1.dp.toPx()
            )
        }

        // Center J.A.R.V.I.S. Typography
        Text(
            text = "J.A.R.V.I.S.",
            color = Ice,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun JarvisScreen(
    recognizedText: String,
    status: String,
    rmsLevel: Float,
    onConsumed: () -> Unit,
    onSpeak: (String) -> Unit,
    onStartListening: () -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, (ChatResponsePayload) -> Unit) -> (() -> Unit),
    onOpenApp: (String) -> Boolean
) {
    data class Attachment(val uri: Uri, val name: String, val mime: String)
    data class Line(
        val user: Boolean,
        val text: String,
        val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
        val attachments: List<Attachment> = emptyList(),
        val products: List<VisualProduct> = emptyList(),
        val displayText: MutableState<String> = mutableStateOf(if (user) text else ""),
        val revealed: MutableState<Boolean> = mutableStateOf(true)
    )

    var page by remember { mutableStateOf("CHAT") }
    var voiceListening by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceAnswer by remember { mutableStateOf("") }
    var voiceStatusNote by remember { mutableStateOf("") }
    val voiceProducts = remember { mutableStateListOf<VisualProduct>() }

    val context = LocalContext.current
    val pendingAttachments = remember { mutableStateListOf<Attachment>() }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "Lampiran"
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
    var processStage by remember { mutableStateOf("MENAMPILKAN HASIL...") }
    var settings by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Line>() }
    val revealScope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var followLatest by remember { mutableStateOf(true) }

    val transition = rememberInfiniteTransition(label = "hud_transition")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(15000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "spin")
    val breathe by transition.animateFloat(.95f, 1.05f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val wavePhase by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "wavePhase")

    fun showJarvis(answer: String, products: List<VisualProduct> = emptyList()) {
        val responseLine = Line(user = false, text = answer, products = products)
        responseLine.displayText.value = answer
        responseLine.revealed.value = true
        lines.add(responseLine)
    }

    fun buildSpokenReply(request: String, answer: String, products: List<VisualProduct>): String {
        val english = isEnglishText(request)
        if (products.isNotEmpty()) {
            val cheapest = products.minByOrNull { it.price.replace(Regex("[^0-9]"), "").toLongOrNull() ?: Long.MAX_VALUE }
            val lead = if (english) listOf("Certainly, Sir. I found a few options.", "I checked the available listings, Boss.", "Here are the closest matches I found.").random()
                else listOf("Baik, Sir. Saya sudah menemukan beberapa pilihan.", "Sudah saya periksa, Boss. Ini hasil terbaiknya.", "Saya menemukan beberapa opsi yang sesuai.").random()
            val detail = cheapest?.let { if (english) " The most affordable is ${it.title}, priced at ${it.price} from ${it.source}." else " Yang paling terjangkau adalah ${it.title}, seharga ${it.price} dari ${it.source}." }.orEmpty()
            return (lead + detail).take(900)
        }
        val q = request.lowercase(Locale.ROOT)
        val lead = if (english) when {
            Regex("\\b(open|launch|run|start)\\b").containsMatchIn(q) -> listOf("Right away, Sir.", "Certainly, Boss.").random()
            Regex("\\b(find|search|look for)\\b").containsMatchIn(q) -> listOf("Certainly, Sir. I have looked into it.", "I checked that for you, Boss.").random()
            else -> listOf("Certainly, Sir. Here is what I found.", "Of course, Boss.").random()
        } else when {
            Regex("\\b(buka|bukakan|jalankan)\\b").containsMatchIn(q) -> listOf("Baik, Sir. Saya jalankan.", "Siap, Boss.").random()
            Regex("\\b(cari|carikan|harga|rekomendasi)\\b").containsMatchIn(q) -> listOf("Baik, Sir. Saya sudah menelusurinya.", "Sudah saya periksa, Boss.").random()
            else -> listOf("Tentu, Sir. Berikut hasilnya.", "Baik, Boss.").random()
        }
        return if (answer.isBlank()) lead else "$lead $answer".take(1800)
    }
    fun send(raw: String, voice: Boolean = false) {
        val q = raw.trim()
        val files = pendingAttachments.toList()
        if ((q.isEmpty() && files.isEmpty()) || busy) return
        command = ""
        pendingAttachments.clear()
        val attachmentNote = if (files.isEmpty()) "" else "\n\nLampiran: " + files.joinToString("; ") { it.name + " (" + it.mime + ")" }
        val requestText = q + attachmentNote
        if (voice) {
            voiceTranscript = q
            voiceAnswer = ""
            voiceProducts.clear()
        } else {
            lines.add(Line(user = true, text = q.ifBlank { "Tolong analisis lampiran ini dan jelaskan isinya." }, attachments = files))
        }

        val norm = q.lowercase(Locale.ROOT)
        if (norm in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis", "open", "buka")) return
        // Do not infer app-launch actions from ordinary words such as "open" or short utterances.
        // App launching is handled only by the explicit "Jarvis, open" voice-wake branch above.
        busy = true
        onSearch(q)
        cancelCurrentRequest = onAskGemini(requestText) { payload ->
            val answer = payload.answer.substringAfter("KESIMPULAN JARVIS", payload.answer)
                .substringAfter("JAWABAN AKHIR", payload.answer).trim().ifBlank { payload.answer.trim() }
            if (voice) {
                voiceAnswer = answer
                voiceProducts.clear()
                voiceProducts.addAll(payload.products)
            } else {
                val responseLine = Line(user = false, text = answer, products = payload.products)
                responseLine.displayText.value = answer
                responseLine.revealed.value = false
                lines.add(responseLine)
            }
            busy = false
            cancelCurrentRequest = null
            if (!voice) revealScope.launch {
                kotlinx.coroutines.delay(24)
                lines.lastOrNull()?.revealed?.value = true
            }
            if (voice) onSpeak(buildSpokenReply(q, answer, payload.products))
        }
    }

    // Voice remains silent until the user says a command addressed to JARVIS.
    LaunchedEffect(recognizedText, page, voiceListening) {
        if (page == "VOICE" && voiceListening && recognizedText.isNotBlank()) {
            val heard = recognizedText.trim()
            onConsumed()
            val wake = Regex("(?i)\\bjarvis\\b[,.! ]*")
            if (!wake.containsMatchIn(heard)) return@LaunchedEffect
            val request = heard.replaceFirst(wake, "").trim()
            if (request.isBlank() || Regex("(?i)^(open|buka)(kan)?$").matches(request)) {
                voiceStatusNote = "READY FOR COMMAND"
                return@LaunchedEffect
            }
            if (Regex("(?i)^(close|stop listening|matikan mic|matikan mikrofon|standby)$").matches(request)) {
                voiceListening = false
                return@LaunchedEffect
            }
            val appCommand = Regex("(?i)^(open|buka)(kan)?\\s+(.+)$").matchEntire(request)
            if (appCommand != null) {
                val appName = appCommand.groupValues[3].trim()
                val opened = onOpenApp(appName)
                val reply = if (isEnglishText(request)) {
                    if (opened) "Certainly, Sir. $appName is open." else "I could not find $appName on this device, Sir."
                } else {
                    if (opened) "Siap, Sir. $appName sudah dibuka." else "Maaf, Sir. $appName tidak ditemukan di perangkat ini."
                }
                onSpeak(reply)
            } else send(request, voice = true)
        }
    }
    LaunchedEffect(page, voiceListening) {
        val voiceOn = page == "VOICE" && voiceListening
        onModeChanged(voiceOn)
        // onModeChanged owns the single recognizer start; avoid starting it twice.
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (lastVisible, total) ->
                followLatest = total == 0 || lastVisible == null || lastVisible >= total - 2
            }
    }
    LaunchedEffect(lines.size, busy) {
        if (followLatest && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LaunchedEffect(busy) {
        if (busy) {
            val stages = listOf("MENAMPILKAN HASIL...", "MENGANALISIS DATA...", "MENYUSUN RESPONS HUD...", "SINKRONISASI CORE 01...")
            var i = 0
            while (busy) {
                processStage = stages[i++ % stages.size]
                kotlinx.coroutines.delay(1300)
            }
        } else {
            processStage = "MENAMPILKAN HASIL..."
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val clock12 = SimpleDateFormat("h:mm a", Locale.US).format(Date(now))

    val hologramShift by animateFloatAsState(
        targetValue = if (busy || voiceAnswer.isNotBlank() || voiceProducts.isNotEmpty()) -68f else 0f,
        animationSpec = tween(520),
        label = "hologramPosition"
    )

    val phase = when {
        busy -> "THINK"
        status.contains("SPEAK", true) -> "SPEAK"
        page == "VOICE" && voiceListening -> "LISTEN"
        else -> "IDLE"
    }

    val coreModeLabel = when {
        page == "VOICE" -> "CORE 01 / VOICE"
        lines.isEmpty() -> "CORE 01 / HOME"
        else -> "CORE 01 / CHAT"
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Background)) {
            // Futuristic HUD Outer Frame & Ambient Glow
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(Brush.verticalGradient(listOf(Color(0xFF030811), Color(0xFF071525), Color(0xFF030811))))
                val glowCenter = Offset(w * 0.5f, h * (if (page == "VOICE") 0.34f else 0.22f))
                drawCircle(
                    Brush.radialGradient(listOf(Cyan.copy(alpha = 0.16f), Color.Transparent), glowCenter, w * 0.85f),
                    w * 0.85f,
                    glowCenter
                )
            }

            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val compact = maxHeight < 720.dp

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                        .background(Color(0x66040A12))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // --- TOP HUD HEADER ---
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "J . A . R . V . I . S .",
                                color = Ice,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.8.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "JUST A RATHER VERY INTELLIGENT SYSTEM",
                                color = TextMuted,
                                fontSize = 8.5.sp,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.clickable { keyDraft = ""; settings = true }
                            ) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Cyan))
                                Text(
                                    text = "SYSTEM ONLINE",
                                    color = Cyan,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (page == "CHAT" && lines.isNotEmpty()) {
                                    Text(
                                        text = "HOME",
                                        color = Cyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF0A2236))
                                            .border(0.6.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .clickable { lines.clear() }
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Text(
                                    text = coreModeLabel,
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()                            .height(1.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.55f), Color.Transparent)))
                    )

                    // ==========================================
                    // VOICE MODE UI (Exact Match to Reference 2)
                    // ==========================================
                    if (page == "VOICE") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "J A R V I S",
                                color = Cyan,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp
                            )
                            Text(
                                text = "PERSONAL INTELLIGENCE INTERFACE",
                                color = TextMuted,
                                fontSize = 9.sp,
                                letterSpacing = 1.6.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            // Right Telemetry Clock + Audio Input Active + Mini Equalizer
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = clock12,
                                        color = Ice,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = when {
                                            !voiceListening -> "AUDIO STANDBY"
                                            phase == "THINK" -> "VOICE PROCESSING"
                                            phase == "SPEAK" -> "AUDIO OUTPUT ACTIVE"
                                            else -> "AUDIO INPUT ACTIVE"
                                        },
                                        color = TextMuted,
                                        fontSize = 8.sp,
                                        letterSpacing = 1.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.Bottom,
                                        modifier = Modifier.height(12.dp)
                                    ) {
                                        repeat(8) { idx ->
                                            val hBar = 3f + kotlin.math.abs(kotlin.math.sin(wavePhase + idx * 0.6f)) * (if (voiceListening) 9f else 2f)
                                            Box(
                                                Modifier
                                                    .width(2.dp)
                                                    .height(hBar.dp)
                                                    .background(Cyan)
                                            )
                                        }
                                    }
                                }
                            }

                            // Center Multi-Ring Holographic Arc Reactor
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .animateContentSize(animationSpec = tween(420)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.offset(y = hologramShift.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    VoiceHudReactor(
                                        modifier = Modifier.size(if (busy || voiceAnswer.isNotBlank() || voiceProducts.isNotEmpty()) 150.dp else if (compact) 190.dp else 230.dp),
                                        phase = phase,
                                        spin = spin,
                                        breathe = breathe
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // Horizontal Waveform Visualizer
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        val barCount = 29
                                        repeat(barCount) { idx ->
                                            val centerDist = kotlin.math.abs(idx - barCount / 2).toFloat() / (barCount / 2f)
                                            val envelope = (1f - centerDist * 0.75f).coerceAtLeast(0.15f)
                                            val dynamicBoost = if (phase == "LISTEN" || phase == "SPEAK") (rmsLevel / 10f).coerceIn(0.35f, 1.3f) else 0.2f
                                            val hVal = 3f + kotlin.math.abs(kotlin.math.sin(wavePhase * 1.4f + idx * 0.45f)) * 22f * envelope * dynamicBoost
                                            Box(
                                                Modifier
                                                    .width(2.5.dp)
                                                    .height(hVal.dp)
                                                    .clip(RoundedCornerShape(99.dp))
                                                    .background(Cyan.copy(alpha = if (phase == "IDLE") 0.35f else 0.9f))
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = when (phase) {
                                            "THINK" -> "PROCESSING"
                                            "SPEAK" -> "SPEAKING"
                                            "LISTEN" -> "LISTENING"
                                            else -> "STANDBY"
                                        },
                                        color = Cyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Pill Voice Input Bar (Allows both Voice & Typing directly in Voice Mode!)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFF071524))
                                    .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.45f), Color(0xFF061626))))
                                        .border(1.dp, Cyan, CircleShape)
                                        .clickable {
                                            voiceListening = !voiceListening
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Ice, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                BasicTextField(
                                    value = command,
                                    onValueChange = { command = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Ice, fontSize = 13.5.sp),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                                        if (command.isNotBlank() && !busy) {
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                            send(command, voice = true)
                                        }
                                    }),
                                    decorationBox = { inner ->
                                        if (command.isEmpty()) {
                                            Text(
                                                text = voiceTranscript.ifBlank { "Ucapkan perintah atau ketik di sini..." },
                                                color = if (voiceTranscript.isBlank()) TextMuted else Ice,
                                                fontSize = 13.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        inner()
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                if (command.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                            send(command, voice = true)
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "Kirim", tint = Cyan, modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    // Mini waveform indicator inside right side of pill
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        repeat(7) { i ->
                                            val bh = 4f + kotlin.math.abs(kotlin.math.cos(wavePhase + i * 0.7f)) * (if (voiceListening) 10f else 3f)
                                            Box(Modifier.width(2.dp).height(bh.dp).background(Cyan, RoundedCornerShape(2.dp)))
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // MENAMPILKAN HASIL... + Horizontal Product Carousel or Voice Answer Panel
                            if (busy || voiceProducts.isNotEmpty() || voiceAnswer.isNotBlank()) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .border(1.5.dp, Cyan, CircleShape)
                                        )
                                        Text(
                                            text = if (busy) processStage else "MENAMPILKAN HASIL...",
                                            color = TextMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 1.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    if (voiceProducts.isNotEmpty()) {
                                        ProductCarouselCards(voiceProducts)
                                    } else if (voiceAnswer.isNotBlank()) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = if (compact) 120.dp else 155.dp)
                                                .clip(CardShape)
                                                .background(Color(0xFF081624))
                                                .border(1.dp, Cyan.copy(alpha = 0.35f), CardShape)
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = voiceAnswer,
                                                    color = Ice,
                                                    fontSize = 12.5.sp,
                                                    lineHeight = 18.sp,
                                                    maxLines = 5,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Buka detail lengkap di Chat Console →",
                                                    color = Cyan,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.clickable {
                                                        if (voiceTranscript.isNotBlank()) lines.add(Line(user = true, text = voiceTranscript))
                                                        showJarvis(voiceAnswer, voiceProducts.toList())
                                                        voiceListening = false
                                                        page = "CHAT"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Bottom 3-Button Voice Control Dock (MIC OFF | LISTENING | CHAT)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                // MIC OFF appears only while microphone is disabled.
                                if (!voiceListening) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF081523))
                                                .border(1.dp, AlertRed.copy(alpha = 0.7f), CircleShape)
                                                .clickable { voiceListening = false },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.MicOff, contentDescription = "Mic Off", tint = AlertRed, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("MIC OFF", color = TextMuted, fontSize = 9.sp, letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }

                                // CENTER GLOWING MIC (LISTENING)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .size(if (compact) 64.dp else 74.dp)
                                            .scale(if (phase == "LISTEN") breathe else 1f)
                                            .clip(CircleShape)
                                            .background(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.45f), Color(0xFF061320))))
                                            .border(2.dp, Cyan, CircleShape)
                                            .clickable {
                                                voiceListening = !voiceListening
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = if (voiceListening) "Microphone active" else "Turn microphone on", tint = Ice, modifier = Modifier.size(30.dp))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = when (phase) {
                                            "THINK" -> "PROCESSING"
                                            "SPEAK" -> "SPEAKING"
                                            "LISTEN" -> "LISTENING"
                                            else -> "MIC OFF"
                                        },
                                        color = Cyan,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                // CHAT SWITCH
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF081523))
                                            .border(1.dp, Cyan.copy(alpha = 0.3f), CircleShape)
                                            .clickable {
                                                voiceListening = false
                                                page = "CHAT"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Chat, contentDescription = "Chat", tint = Ice, modifier = Modifier.size(19.dp))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("CHAT", color = TextMuted, fontSize = 9.sp, letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    } else {
                        // ==========================================
                        // CHAT CONSOLE / HOME DASHBOARD MODE
                        // ==========================================
                        if (lines.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CHAT CONSOLE",
                                    color = Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${lines.size} ENTRIES",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        LazyColumn(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (lines.isEmpty()) {
                                item {
                                    JarvisHomeDashboard(
                                        compact = compact,
                                        spin = spin,
                                        breathe = breathe,
                                        onSelectPrompt = { selectedPrompt -> send(selectedPrompt, voice = false) }
                                    )
                                }
                            }

                            items(lines.size) { i ->
                                val line = lines[i]
                                AnimatedVisibility(visible = true, enter = fadeIn(tween(220)) + expandVertically()) {
                                    if (line.user) {
                                        Column(
                                            Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    Modifier
                                                        .widthIn(max = 290.dp)
                                                        .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                                                        .background(Brush.linearGradient(listOf(Color(0xFF103454), Color(0xFF0B233B))))
                                                        .border(1.dp, Cyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                                                        .padding(horizontal = 13.dp, vertical = 10.dp)
                                                ) {
                                                    Text(line.text, color = Ice, fontSize = 13.5.sp, lineHeight = 19.sp)
                                                }
                                                UserAvatarBadge(Modifier.padding(top = 2.dp))
                                            }
                                            // Display attached images or files right under user bubble
                                            line.attachments.forEach { file ->
                                                if (file.mime.startsWith("image/")) {
                                                    Box(
                                                        Modifier
                                                            .padding(end = 36.dp)
                                                            .widthIn(max = 260.dp)
                                                            .clip(CardShape)
                                                            .border(1.dp, Cyan.copy(alpha = 0.4f), CardShape)
                                                    ) {
                                                        AsyncImage(
                                                            model = file.uri,
                                                            contentDescription = file.name,
                                                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                        )
                                                    }
                                                } else {
                                                    Row(
                                                        Modifier
                                                            .padding(end = 36.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(Color(0xFF0A1B2C))
                                                            .border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Default.AttachFile, null, tint = Cyan, modifier = Modifier.size(14.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(file.name, color = Ice, fontSize = 11.5.sp, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            JarvisAvatarBadge(Modifier.padding(top = 2.dp))
                                            Column(Modifier.weight(1f)) {
                                                AnimatedVisibility(visible = line.revealed.value, enter = fadeIn(tween(200))) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        RichMessageText(
                                                            markdown = line.displayText.value.ifBlank { line.text },
                                                            products = line.products,
                                                            onQuickFollowUp = { followUp -> send(followUp, voice = false) }
                                                        )
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Row(
                                                                Modifier
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFF091929))
                                                                    .border(0.8.dp, Cyan.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                                                    .clickable { clipboard.setText(AnnotatedString(line.text)) }
                                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Icon(Icons.Default.ContentCopy, "Salin", tint = Cyan, modifier = Modifier.size(12.dp))
                                                                Text("Salin", color = TextMuted, fontSize = 10.sp)
                                                            }
                                                            Row(
                                                                Modifier
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFF091929))
                                                                    .border(0.8.dp, Cyan.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                                                    .clickable { onSpeak(line.text) }
                                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Icon(Icons.Default.VolumeUp, "Suara", tint = Cyan, modifier = Modifier.size(12.dp))
                                                                Text("Dengar", color = TextMuted, fontSize = 10.sp)
                                                            }
                                                            Row(
                                                                Modifier
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFF091929))
                                                                    .border(0.8.dp, Cyan.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                                                    .clickable {
                                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                                            type = "text/plain"
                                                                            putExtra(Intent.EXTRA_TEXT, line.text)
                                                                        }
                                                                        runCatching { context.startActivity(Intent.createChooser(shareIntent, "Bagikan jawaban JARVIS")) }
                                                                    }
                                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Icon(Icons.Default.Share, "Bagikan", tint = Cyan, modifier = Modifier.size(12.dp))
                                                                Text("Bagikan", color = TextMuted, fontSize = 10.sp)
                                                            }
                                                        }
                                                        SourceStrip(line.text)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (busy) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        JarvisAvatarBadge()
                                        Row(
                                            Modifier
                                                .clip(CardShape)
                                                .background(Color(0xFF091828))
                                                .border(1.dp, Cyan.copy(alpha = 0.35f), CardShape)
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(processStage, color = Ice, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                repeat(3) { n ->
                                                    val a = kotlin.math.sin(wavePhase + n * .9f) * .5f + .5f
                                                    Box(Modifier.size(6.dp).background(Cyan.copy(alpha = .25f + .75f * a), CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Pending attachments strip
                        if (pendingAttachments.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pendingAttachments.toList().forEach { file ->
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color(0xFF0B1C2C))
                                            .border(1.dp, Cyan.copy(alpha = .35f), RoundedCornerShape(999.dp))
                                            .padding(start = 10.dp, end = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(file.name, color = Ice, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.widthIn(max = 140.dp))
                                        IconButton(onClick = { pendingAttachments.remove(file) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, "Hapus lampiran", tint = TextMuted, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // --- MESSAGE JARVIS... INPUT BAR ---
                        val armed = command.isNotBlank() || pendingAttachments.isNotEmpty()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFF071422))
                                .border(1.dp, Cyan.copy(alpha = if (armed) 0.7f else 0.36f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0C2136))
                                        .border(1.dp, Cyan.copy(alpha = 0.5f), CircleShape)
                                        .clickable(enabled = !busy) { attachmentMenu = !attachmentMenu },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (attachmentMenu) Icons.Default.Close else Icons.Default.Add,
                                        contentDescription = "Lampirkan",
                                        tint = Cyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = attachmentMenu,
                                    onDismissRequest = { attachmentMenu = false },
                                    properties = PopupProperties(focusable = false),
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = Color(0xFF0B1B2B)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("File / Dokumen", color = Ice, fontSize = 13.sp) },
                                        onClick = { attachmentMenu = false; attachmentPicker.launch(arrayOf("*/*")) },
                                        leadingIcon = { Icon(Icons.Default.AttachFile, null, tint = Cyan) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Kamera", color = Ice, fontSize = 13.sp) },
                                        onClick = { attachmentMenu = false; cameraPicker.launch(null) },
                                        leadingIcon = { Icon(Icons.Default.CameraAlt, null, tint = Cyan) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Galeri Foto", color = Ice, fontSize = 13.sp) },
                                        onClick = { attachmentMenu = false; photoPicker.launch("image/*") },
                                        leadingIcon = { Icon(Icons.Default.Image, null, tint = Cyan) }
                                    )
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            BasicTextField(
                                value = command,
                                onValueChange = { command = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(inputFocus)
                                    .padding(vertical = 6.dp),
                                minLines = 1,
                                maxLines = 4,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Ice, fontSize = 14.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                                    if (!busy) {
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                        send(command, voice = false)
                                    }
                                }),
                                decorationBox = { inner ->
                                    if (command.isEmpty()) {
                                        Text("Message JARVIS...", color = TextMuted, fontSize = 13.5.sp)
                                    }
                                    inner()
                                }
                            )

                            if (busy) {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(AlertRed.copy(alpha = 0.2f))
                                        .border(1.dp, AlertRed, CircleShape)
                                        .clickable {
                                            cancelCurrentRequest?.invoke()
                                            cancelCurrentRequest = null
                                            busy = false
                                            showJarvis("Permintaan dihentikan.")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Stop, "Hentikan", tint = AlertRed, modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(if (armed) Cyan.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable {
                                            if (armed) {
                                                keyboard?.hide()
                                                focusManager.clearFocus()
                                                send(command, voice = false)
                                            } else {
                                                keyboard?.hide()
                                                focusManager.clearFocus()
                                                voiceListening = true
                                                page = "VOICE"
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = "Kirim",
                                        tint = Cyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // --- BOTTOM SEGMENTED MODE SWITCHER (Exact match to both references) ---
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF06111D))
                            .border(1.dp, Cyan.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isChat = page == "CHAT"
                        // Left Tab (CHAT / COMMAND)
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (isChat) Brush.horizontalGradient(listOf(Color(0xFF0E334E), Color(0xFF092236)))
                                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isChat) Cyan.copy(alpha = 0.65f) else Color.Transparent,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    voiceListening = false
                                    page = "CHAT"
                                }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isChat) Icons.Default.Home else Icons.Default.GridView,
                                contentDescription = null,
                                tint = if (isChat) Cyan else TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isChat) "CHAT" else "COMMAND",
                                color = if (isChat) Ice else TextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Right Tab (VOICE)
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (!isChat) Brush.horizontalGradient(listOf(Color(0xFF0E334E), Color(0xFF092236)))
                                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (!isChat) Cyan.copy(alpha = 0.65f) else Color.Transparent,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                    voiceListening = true
                                    page = "VOICE"
                                }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (!isChat) Cyan else TextMuted.copy(alpha = 0.6f))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "VOICE",
                                color = if (!isChat) Ice else TextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                if (settings) {
                    AlertDialog(
                        onDismissRequest = { settings = false },
                        containerColor = Color(0xFF0B1826),
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Column {
                                Text("Pengaturan Sistem J.A.R.V.I.S.", color = Ice, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text("Kunci API opsional tersimpan lokal di perangkatmu.", color = TextMuted, fontSize = 12.sp)
                            }
                        },
                        text = {
                            OutlinedTextField(
                                value = keyDraft,
                                onValueChange = { keyDraft = it },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                placeholder = { Text("Kunci API Gemini", color = TextMuted) },
                                label = { Text("API Key Override") }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (keyDraft.isNotBlank()) onSaveGeminiKey(keyDraft.trim())
                                settings = false
                                keyDraft = ""
                            }) { Text("Simpan", color = Cyan) }
                        },
                        dismissButton = {
                            TextButton(onClick = { settings = false }) { Text("Batal", color = TextMuted) }
                        }
                    )
                }
            }
        }
    }
}