package com.jarvis.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.media.MediaPlayer
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
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

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
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val voices = tts?.voices.orEmpty()
                val indoVoice = voices.firstOrNull { it.locale.toLanguageTag().equals("id-ID", true) }
                if (indoVoice != null) tts?.voice = indoVoice else tts?.language = Locale("id", "ID")
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
                    if (voiceMode) startListening() else { clapDetector.reset(); recognizer?.cancel() }
                    voiceStatus.value = if (voiceMode) "VOICE MODE · LISTENING" else "CHAT MODE · READY TO TYPE"
                },
                onSearch = { _ -> voiceStatus.value = "AI COUNCIL DISCUSSION" },
                geminiConfigured = { true },
                onSaveGeminiKey = { key -> prefs.edit().putString("gemini_api_key", key.trim()).apply() },
                onAskGemini = { prompt, callback -> askGemini(prompt, callback) },
                onOpenApp = { q -> openRequestedApp(q) },
                onInstalledApps = { installedAppNames() }
            )
        }
    }


    private fun askGemini(prompt: String, callback: (String) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            val result = try {
                val endpoint = URL("https://jarvis-api.alvinorasya1304.workers.dev/v1/chat")
                connection = endpoint.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 180000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val body = JSONObject().put("message", prompt)
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
            handler.post { callback(result) }
        }.start()
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

    private fun speakWithAndroid(text: String) {
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
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
                speak("Good day. JARVIS is online. I am listening.")
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

@Composable
private fun RichMessageText(markdown: String) {
    val uriHandler = LocalUriHandler.current
    val linkPattern = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)")
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    val clean = markdown.replace("\\\\n", "\n").replace(Regex("(?m)^#{1,3}\\s*"), "")
    val annotated = buildAnnotatedString {
        var cursor = 0
        val matches = (linkPattern.findAll(clean).map { it to "link" } + boldPattern.findAll(clean).map { it to "bold" }).sortedBy { it.first.range.first }
        for ((match, kind) in matches) {
            if (match.range.first < cursor) continue
            append(clean.substring(cursor, match.range.first))
            if (kind == "link") {
                val start = length
                withStyle(SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) { append(match.groupValues[1]) }
                addStringAnnotation("URL", match.groupValues[2], start, length)
            } else withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF1FCFF))) { append(match.groupValues[1]) }
            cursor = match.range.last + 1
        }
        if (cursor < clean.length) append(clean.substring(cursor))
    }
    ClickableText(text = annotated, style = androidx.compose.ui.text.TextStyle(color = Color(0xFFE2F5F8), fontSize = 14.sp, lineHeight = 22.sp), onClick = { offset ->
        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
    })
}

@Composable
private fun SourceStrip(markdown: String) {
    val uriHandler = LocalUriHandler.current
    val links = Regex("\\[([^\\]]+)\\]\\((https?://[^ )]+)\\)").findAll(markdown.replace("\\\\n", "\n")).map { it.groupValues[2] }.distinct().take(5).toList()
    if (links.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("REFERENSI", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            links.forEach { url ->
                val host = runCatching { Uri.parse(url).host?.removePrefix("www.") ?: "WEB" }.getOrDefault("WEB")
                Surface(onClick = { runCatching { uriHandler.openUri(url) } }, color = Color(0xFF102A36), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0x6652DFF2))) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(host, color = Color(0xFFB9EAF2), fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
private fun JarvisScreen(
    recognizedText: String, status: String, onConsumed: () -> Unit, onSpeak: (String) -> Unit,
    onStartListening: () -> Unit, onModeChanged: (Boolean) -> Unit, onSearch: (String) -> Unit,
    geminiConfigured: () -> Boolean, onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, (String) -> Unit) -> Unit, onOpenApp: (String) -> Boolean,
    onInstalledApps: () -> List<String>
) {
    data class Line(val user: Boolean, val text: String)
    var page by remember { mutableStateOf("VOICE") }
    var command by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("SYSTEM STANDBY") }
    var processStage by remember { mutableStateOf("Menganalisis permintaan") }
    var settings by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Line>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val transition = rememberInfiniteTransition(label = "reactor")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(16000), RepeatMode.Restart), label = "spin")
    val breathe by transition.animateFloat(.94f, 1.06f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "breathe")
    val wavePhase by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "wavePhase")

    fun send(raw: String, voice: Boolean = false) {
        val q = raw.trim()
        if (q.isEmpty() || busy) return
        command = ""
        lines.add(Line(true, q))
        val norm = q.lowercase(Locale.ROOT)
        if (norm in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")) {
            val greeting = "I'm online. How may I assist?"
            lines.add(Line(false, greeting))
            statusText = "JARVIS ONLINE"
            if (voice) onSpeak(greeting)
            return
        }
        val openIntent = Regex("\\b(buka|bukakan|jalankan|open|launch|run|start)\\b").containsMatchIn(norm)
        val questionIntent = Regex("\\b(apa|siapa|kapan|kenapa|mengapa|bagaimana|jelaskan|cari|harga|what|who|when|why|how|search|find|explain|price)\\b").containsMatchIn(norm)
        val simpleApp = norm.split(" ").size <= 2 && !questionIntent && !openIntent && norm !in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")
        if ((openIntent || simpleApp) && onOpenApp(q)) {
            val answer = "Baik. Perintah dijalankan."
            lines.add(Line(false, answer)); statusText = "COMMAND EXECUTED"
            if (voice) onSpeak(answer)
            return
        }
        if (openIntent) {
            val answer = "Aplikasi tersebut tidak ditemukan di perangkat ini."
            lines.add(Line(false, answer)); statusText = "APP NOT FOUND"
            if (voice) onSpeak(answer)
            return
        }
        busy = true
        statusText = "ANALYSING REQUEST"
        onSearch(q)
        onAskGemini(q) { response ->
            val answer = response.substringAfter("KESIMPULAN JARVIS", response)
                .substringAfter("JAWABAN AKHIR", response).trim().ifBlank { response.trim() }
            lines.add(Line(false, answer))
            busy = false
            statusText = "RESPONSE READY"
            if (voice) onSpeak(answer)
        }
    }

    LaunchedEffect(recognizedText, page) {
        if (page == "VOICE" && recognizedText.isNotBlank()) {
            val heard = recognizedText.trim()
            onConsumed()
            val wake = Regex("(?i)^jarvis[,.! ]*")
            if (wake.containsMatchIn(heard)) {
                val request = heard.replaceFirst(wake, "").trim()
                if (request.isBlank() || request.equals("open", true)) {
                    statusText = "JARVIS ACTIVATED"
                    onSpeak("Yes. I am listening. How may I assist?")
                } else send(request, true)
            } else {
                statusText = "WAKE WORD REQUIRED"
                onStartListening()
            }
        }
    }
    LaunchedEffect(page) {
        val voiceOn = page == "VOICE"
        onModeChanged(voiceOn)
        if (voiceOn) onStartListening()
    }
    LaunchedEffect(lines.size, busy) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
    LaunchedEffect(busy) { if (busy) { val stages = listOf("Memahami konteks", "Menyusun strategi pencarian", "Memeriksa informasi", "Merangkum hasil"); var i = 0; while (busy) { processStage = stages[i++ % stages.size]; kotlinx.coroutines.delay(1450) } } else processStage = "Siap membantu" }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().navigationBarsPadding()
            .background(Brush.verticalGradient(listOf(Color(0xFF02080D), Color(0xFF06131C), Color(0xFF02070C))))) {
            val compact = maxHeight < 700.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp).animateContentSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("J.A.R.V.I.S.", color = Color(0xFFE4FAFF), fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
                        Text("JUST A RATHER VERY INTELLIGENT SYSTEM", color = Color(0xFF5E9BA9), fontSize = 7.sp, letterSpacing = 1.2.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (busy) "● PROCESSING" else "● SYSTEM ONLINE", color = Cyan, fontSize = 8.sp)
                        Text("CORE 01 / " + page, color = TextMuted, fontSize = 7.sp)
                    }
                    IconButton(onClick = { keyDraft = ""; settings = true }) { Icon(Icons.Default.Settings, null, tint = Cyan) }
                }
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha=.6f), Color.Transparent))))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).background(Color(0xFF071923), RoundedCornerShape(30.dp)).border(1.dp, Color(0x4454DDF4), RoundedCornerShape(30.dp)).padding(4.dp)) {
                    listOf("COMMAND", "VOICE").forEach { tab ->
                        Button(onClick = { page = tab }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (page == tab) Color(0xFF124253) else Color.Transparent, contentColor = if (page == tab) Color(0xFFBDF7FF) else TextMuted),
                            elevation = ButtonDefaults.buttonElevation(0.dp)) {
                            Text(if (tab == "VOICE") "◉  VOICE" else "⌘  COMMAND", fontSize = 10.sp, letterSpacing = 1.3.sp)
                        }
                    }
                }
                if (page == "VOICE") {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val size = if (compact) 190.dp else 230.dp
                        Canvas(Modifier.size(size + 74.dp).scale(breathe).rotate(spin * .35f)) {
                            val cx = this.size.width / 2f
                            val cy = this.size.height / 2f
                            val r = this.size.minDimension * .43f
                            val cyan = Color(0xFF56DDF4)
                            val faint = Color(0x3356DDF4)
                            // Diffuse reactor glow and layered energy shell.
                            drawCircle(Brush.radialGradient(listOf(Color(0x5538D8F4), Color(0x1838D8F4), Color.Transparent), Offset(cx, cy), r * 1.2f), r * 1.2f, Offset(cx, cy))
                            drawCircle(faint, r, Offset(cx, cy), style = Stroke(1.2f))
                            drawCircle(cyan.copy(alpha = .32f), r * .91f, Offset(cx, cy), style = Stroke(2.2f))
                            // Uneven energy perimeter, like the particle shell in the reference.
                            val shell = androidx.compose.ui.graphics.Path()
                            for (i in 0..180) {
                                val a = Math.PI * 2.0 * i / 180.0
                                val wobble = 1f + .018f * kotlin.math.sin(i * .71f) + .012f * kotlin.math.cos(i * .23f)
                                val rr = r * wobble
                                val x = cx + kotlin.math.cos(a).toFloat() * rr
                                val y = cy + kotlin.math.sin(a).toFloat() * rr
                                if (i == 0) shell.moveTo(x, y) else shell.lineTo(x, y)
                            }
                            shell.close()
                            drawPath(shell, cyan.copy(alpha = .42f), style = Stroke(1.6f))
                            // Instrument-like radial calibration marks.
                            for (i in 0 until 72) {
                                val a = Math.PI * 2.0 * i / 72.0 + spin * Math.PI / 180.0
                                val major = i % 6 == 0
                                val r1 = r * if (major) .94f else .97f
                                val r2 = r * if (major) 1.04f else 1.005f
                                drawLine(cyan.copy(alpha = if (major) .8f else .28f),
                                    Offset(cx + kotlin.math.cos(a).toFloat() * r1, cy + kotlin.math.sin(a).toFloat() * r1),
                                    Offset(cx + kotlin.math.cos(a).toFloat() * r2, cy + kotlin.math.sin(a).toFloat() * r2),
                                    if (major) 2f else 1f)
                            }
                            // Spherical wireframe core: latitude and longitude contours.
                            val sphere = r * .48f
                            for (i in -3..3) {
                                val y = i / 3f
                                val h = sphere * kotlin.math.sqrt((1f - y * y).coerceAtLeast(.02f))
                                drawOval(cyan.copy(alpha = .32f), Offset(cx - h, cy + y * sphere - sphere * .13f), androidx.compose.ui.geometry.Size(h * 2f, sphere * .26f), style = Stroke(1f))
                            }
                            for (i in 0..8) {
                                val w = sphere * kotlin.math.abs(kotlin.math.cos(i * Math.PI / 8.0)).toFloat().coerceAtLeast(.08f)
                                drawOval(cyan.copy(alpha = .24f), Offset(cx - w, cy - sphere), androidx.compose.ui.geometry.Size(w * 2f, sphere * 2f), style = Stroke(1f))
                            }
                            drawCircle(Brush.radialGradient(listOf(Color(0xCC62E9FF), Color(0x5552DDF4), Color.Transparent), Offset(cx, cy), sphere * .85f), sphere * .85f, Offset(cx, cy))
                            drawCircle(Color(0xFFB9F7FF).copy(alpha = .8f), 2.5f, Offset(cx, cy))
                        }
                        Row(Modifier.align(Alignment.Center).padding(top = size + 86.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            repeat(25) { bar ->
                                val level = kotlin.math.abs(kotlin.math.sin(wavePhase + bar * .48f))
                                Box(Modifier.width(3.dp).height((5 + level * (if (busy) 28 else 15)).dp)
                                    .background(Cyan.copy(alpha = .45f + level * .5f), RoundedCornerShape(3.dp)))
                            }
                        }
                        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (busy) "NEURAL PROCESSING" else statusText, color = Cyan, fontSize = 10.sp, letterSpacing = 2.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("CLAP ONCE / TWICE  ·  OR SAY “JARVIS, OPEN”", color = Color(0xFF638C9A), fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(Color(0xFF071923), RoundedCornerShape(12.dp)).border(1.dp, Color(0x445DEBFF), RoundedCornerShape(12.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VOICE ASSISTANT", color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (busy) "Neural core engaged · analysing" else "Awaiting activation phrase", color = Color(0xFFE2F5F8), fontSize = 14.sp)
                        Text("JARVIS speaks only after activation.", color = TextMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("COMMAND CONSOLE", color = Color(0xFF8EDDED), fontSize = 9.sp, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=if(line.user) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.fillMaxWidth(.88f).background(if(line.user) Color(0xFF0A2633) else Color(0xCC081923), RoundedCornerShape(12.dp))
                                    .border(1.dp, if(line.user) Color(0x7755CFE8) else Color(0x5549B8CB), RoundedCornerShape(12.dp)).padding(13.dp)) {
                                    Text(if(line.user) "YOU / COMMAND" else "J.A.R.V.I.S. / FINAL RESPONSE", color=if(line.user) Color(0xFF8BB4C1) else Cyan, fontSize=8.sp, letterSpacing=1.3.sp, fontWeight=FontWeight.Bold)
                                    Spacer(Modifier.height(7.dp))
                                    RichMessageText(line.text)
                                    if (!line.user) SourceStrip(line.text)
                                }
                            }
                        }
                        if(busy) item {
                            AnimatedVisibility(visible=true, enter=fadeIn(tween(350))+slideInVertically(initialOffsetY={ it / 5 }, animationSpec=tween(400))) {
                                val loadingTransition = rememberInfiniteTransition(label="chatLoading")
                                val pulse by loadingTransition.animateFloat(.72f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label="loadingPulse")
                                val orbit by loadingTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label="loadingOrbit")
                                Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF0A202C), Color(0xFF0B1725))), RoundedCornerShape(16.dp)).border(1.dp, Color(0x7752DFF2), RoundedCornerShape(16.dp)).padding(14.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Box(Modifier.size(38.dp), contentAlignment=Alignment.Center) {
                                            Canvas(Modifier.fillMaxSize().rotate(orbit)) { drawCircle(Cyan.copy(alpha=.25f), style=Stroke(1.2.dp.toPx())); drawCircle(Cyan.copy(alpha=pulse), radius=3.2.dp.toPx(), center=Offset(size.width*.5f,size.height*.08f)) }
                                            Text("J", color=Cyan.copy(alpha=pulse), fontSize=17.sp, fontWeight=FontWeight.Light)
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) { Text("J.A.R.V.I.S. / WORKING", color=Cyan, fontSize=9.sp, letterSpacing=1.4.sp); Spacer(Modifier.height(3.dp)); Text(processStage, color=Color(0xFFD6F4F8), fontSize=12.sp) }
                                        Text("LIVE", color=Color(0xFF8AEAFF), fontSize=8.sp, letterSpacing=1.sp)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(5.dp), verticalAlignment=Alignment.CenterVertically) { repeat(24) { index -> val level = kotlin.math.abs(kotlin.math.sin(wavePhase + index * .42f)); Box(Modifier.weight(1f).height((3 + level * 13).dp).background(Cyan.copy(alpha=.25f + level*.55f), RoundedCornerShape(4.dp))) } }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Menganalisis · memverifikasi · menyusun jawaban", color=TextMuted, fontSize=9.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp)
                        .background(Color(0xFF081923), RoundedCornerShape(28.dp))
                        .border(1.dp, Color(0x8852DFF2), RoundedCornerShape(28.dp))
                        .padding(start=16.dp,end=5.dp,top=4.dp,bottom=4.dp), verticalAlignment=Alignment.CenterVertically) {
                        BasicTextField(value=command,onValueChange={command=it},
                            modifier=Modifier.weight(1f).focusRequester(inputFocus).padding(vertical=12.dp),
                            singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=15.sp),
                            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),
                            keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={ if (!busy) { keyboard?.hide(); focusManager.clearFocus(); send(command) } }),
                            decorationBox={ inner -> if(command.isEmpty()) Text("Message JARVIS…",color=TextMuted,fontSize=13.sp); inner() })
                        IconButton(onClick={ if (!busy) { keyboard?.hide(); focusManager.clearFocus(); send(command) } },enabled=!busy) {
                            Icon(Icons.Default.Send,"Send command",tint=if(busy) TextMuted else Cyan)
                        }
                    }
                }
                Text("J.A.R.V.I.S. / PERSONAL INTELLIGENCE SYSTEM", color=Color(0xFF355C68), fontSize=6.sp, letterSpacing=1.3.sp, modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=3.dp))
            }
            if(settings) AlertDialog(onDismissRequest={settings=false},containerColor=Color(0xFF0A1922),
                title={Text("JARVIS SYSTEM SETTINGS",color=Color.White)},
                text={Column { Text("Gemini TTS API key",color=TextMuted,fontSize=11.sp); OutlinedTextField(value=keyDraft,onValueChange={keyDraft=it},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation()) }},
                confirmButton={TextButton(onClick={if(keyDraft.isNotBlank()) onSaveGeminiKey(keyDraft.trim());settings=false;keyDraft=""}){Text("SAVE",color=Cyan)}},
                dismissButton={TextButton(onClick={settings=false}){Text("CANCEL",color=TextMuted)}})
        }
    }
}
