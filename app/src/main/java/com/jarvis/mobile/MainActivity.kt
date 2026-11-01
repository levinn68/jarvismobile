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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
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

private val Background = Color(0xFF01060C)
private val Panel = Color(0xFF07131C)
private val Cyan = Color(0xFF00E5FF)
private val TextMuted = Color(0xFF7C98A6)
private val Ice = Color(0xFFE7F6FF)
private val WarnAmber = Color(0xFFFFB14A)
private val AlertRed = Color(0xFFFF5D6C)
private val OkGreen = Color(0xFF3EE0A2)
private val HudShape = RoundedCornerShape(2.dp)

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
    private var voiceModeEnabled = false
    private val prefs by lazy { getSharedPreferences("jarvis_secure_settings", MODE_PRIVATE) }
    private val geminiKey get() = prefs.getString("gemini_api_key", "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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


    private fun askGemini(prompt: String, callback: (String) -> Unit): () -> Unit {
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
                val body = JSONObject().put("message", prompt)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                if (stream == null) throw IllegalStateException("Server tidak mengirim response body.")
                // Bound response memory to protect lower-memory Android devices from oversized AI payloads.
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
                val raw = String(rawBytes, Charsets.UTF_8)
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
                val message = e.message.orEmpty()
                if (message.contains("Jawaban terlalu besar")) message
                else "Gagal menghubungi JARVIS Worker: periksa koneksi internet dan status backend."
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
                if (voiceModeEnabled) speak("Good day. JARVIS is online. I am listening.")
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

private sealed class MarkdownBlock {
    data class Heading(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Code(val language: String, val text: String) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data class ImageBlock(val alt: String, val url: String) : MarkdownBlock()
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
        if (line.isBlank()) { i++; continue }
        val imageMatch = Regex("^\\s*!\\[([^]]*)]\\((https?://[^ )]+)\\)\\s*$").matchEntire(line)
        if (imageMatch != null) { blocks.add(MarkdownBlock.ImageBlock(imageMatch.groupValues[1], imageMatch.groupValues[2])); i++; continue }
        val heading = Regex("^\\s{0,3}(#{1,3})\\s+(.+)$").matchEntire(line)
            ?: Regex("^\\s*\\*\\*(.{3,72})\\*\\*\\s*$").matchEntire(line)
        if (heading != null) {
            blocks.add(MarkdownBlock.Heading(heading.groupValues[2].ifBlank { heading.groupValues[1] }))
            i++
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
        blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
    }
    return blocks
}

@Composable
private fun InlineMarkdownText(markdown: String, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
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
                val label = if (kind == "link") match.groupValues[1] else runCatching { java.net.URI(target).host?.removePrefix("www.") ?: "Buka tautan" }.getOrDefault("Buka tautan")
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
private fun RemoteMarkdownImage(alt: String, url: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Color(0xFF07131C), HudShape).border(1.dp, Color(0x7752DFF2), HudShape).padding(7.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url)
                .memoryCacheKey(url).diskCacheKey(url).build(),
            contentDescription = alt.ifBlank { "Hasil gambar JARVIS" },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        if (alt.isNotBlank()) Text(alt, color=TextMuted, fontSize=11.sp, modifier=Modifier.padding(top=7.dp))
    }
}
@Composable
private fun MarkdownCell(value: String, fontSize: androidx.compose.ui.unit.TextUnit = 10.sp) {
    val image = Regex("!\\[([^]]*)]\\((https?://[^ )]+)\\)").find(value)
    if (image != null) RemoteMarkdownImage(image.groupValues[1], image.groupValues[2])
    else InlineMarkdownText(value, fontSize)
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val headers = rows.first()
    val dataRows = rows.drop(1)
    val productLike = headers.size >= 4
    if (productLike) {
        Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("PRODUCT PICKS", color=Color(0xFF79DCEC), fontSize=8.sp, letterSpacing=1.6.sp, fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${dataRows.size} ITEMS  ·  SWIPE", color=TextMuted, fontSize=7.sp, letterSpacing=.8.sp)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                dataRows.forEachIndexed { index, row ->
                    val title=row.getOrNull(0).orEmpty().replace(Regex("\\*\\*"), "")
                    val cells=row.drop(1)
                    val imageCell=cells.firstOrNull { Regex("!\\[[^]]*]\\(https?://").containsMatchIn(it) }
                    val priceIndex=headers.indexOfFirst { it.contains("harga",true) || it.contains("price",true) }
                    val detailIndex=headers.indexOfFirst { it.contains("detail",true) || it.contains("deskripsi",true) }
                    val linkIndex=headers.indexOfFirst { it.contains("link",true) || it.contains("url",true) }
                    val price=if(priceIndex>0) row.getOrNull(priceIndex).orEmpty() else ""
                    val detail=if(detailIndex>0) row.getOrNull(detailIndex).orEmpty() else ""
                    val link=if(linkIndex>0) row.getOrNull(linkIndex).orEmpty() else ""
                    Column(Modifier.width(258.dp).background(Color(0xFF07141C),HudShape)
                        .border(1.dp,Color(0x6648BFD4),HudShape).padding(10.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("0${index+1}  /  ${dataRows.size.toString().padStart(2,'0')}",color=Color(0xFF62C8DA),
                            fontSize=7.sp,letterSpacing=1.1.sp)
                        Text(title,color=Color(0xFFEAFBFF),fontSize=12.sp,lineHeight=17.sp,
                            fontWeight=FontWeight.SemiBold,minLines=2,maxLines=2)
                        if(imageCell!=null) {
                            val match=Regex("!\\[([^]]*)]\\((https?://[^ )]+)\\)").find(imageCell)
                            if(match!=null) {
                                val context=LocalContext.current
                                AsyncImage(model=ImageRequest.Builder(context).data(match.groupValues[2])
                                    .memoryCacheKey(match.groupValues[2]).diskCacheKey(match.groupValues[2]).build(),
                                    contentDescription=match.groupValues[1],
                                    modifier=Modifier.fillMaxWidth().height(148.dp).background(Color(0xFF041018),HudShape),
                                    contentScale=androidx.compose.ui.layout.ContentScale.Fit)
                            }
                        }
                        if(price.isNotBlank()) Text(price.replace(Regex("\\*\\*"), ""),color=Color(0xFF8DEBFA),
                            fontSize=16.sp,fontWeight=FontWeight.Bold)
                        if(detail.isNotBlank()) Text(detail.replace(Regex("\\*\\*"), "").take(105),
                            color=Color(0xFFAAC5CE),fontSize=10.sp,lineHeight=14.sp,maxLines=3)
                        Spacer(Modifier.weight(1f))
                        if(link.isNotBlank()) MarkdownCell(link,10.sp)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(Color(0xFF07131C), HudShape)
            .border(1.dp, Color(0x6652DFF2), HudShape).padding(5.dp)) {
            rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { cell ->
                        Box(Modifier.weight(1f).heightIn(min=31.dp)
                            .background(if(ri==0) Color(0xFF104052) else Color.Transparent)
                            .border(.4.dp,Color(0x3349B8CB)).padding(5.dp)) {
                            MarkdownCell(cell, 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMessageText(markdown: String) {
    val blocks = parseMarkdownBlocks(markdown)
    var index = 0
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        while (index < blocks.size) {
            val block = blocks[index]
            if (block is MarkdownBlock.Heading) {
                Column(Modifier.fillMaxWidth()
                    .background(Color(0xE6061018), HudShape)
                    .border(1.dp, Color(0x8852DFF2), HudShape)
                    .padding(11.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(Color(0xFF0A2430), HudShape), contentAlignment=Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, null, tint=Cyan, modifier=Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(block.text, color=Color(0xFFE9FAFF), fontSize=13.sp, fontWeight=FontWeight.SemiBold)
                    }
                    index++
                    while (index < blocks.size && blocks[index] !is MarkdownBlock.Heading) {
                        when (val part=blocks[index]) {
                            is MarkdownBlock.Paragraph -> InlineMarkdownText(part.text, 12.sp)
                            is MarkdownBlock.ImageBlock -> RemoteMarkdownImage(part.alt, part.url)
                            is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                Text(if(part.ordered) "•" else "◆", color=Cyan, fontSize=9.sp, modifier=Modifier.padding(top=4.dp))
                                InlineMarkdownText(part.text, 11.sp)
                            }
                            is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(Color(0xFF040C12), HudShape)
                                .border(1.dp, Color(0x7752DFF2), HudShape).padding(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                    Text(part.language.ifBlank { "CODE" }.uppercase(), color=Cyan, fontSize=8.sp, letterSpacing=1.sp)
                                    Text("CODE BLOCK", color=TextMuted, fontSize=7.sp)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    SelectionContainer { Text(part.text, color=Color(0xFFD2E8EF), fontSize=10.sp, lineHeight=15.sp, fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace) }
                                }
                            }
                            is MarkdownBlock.Table -> MarkdownTable(part.rows)
                            is MarkdownBlock.Heading -> Unit
                        }
                        index++
                    }
                }
            } else {
                when (block) {
                    is MarkdownBlock.Heading -> Unit
                    is MarkdownBlock.Paragraph -> Column(Modifier.fillMaxWidth()
                        .background(Color(0xE6061018), HudShape)
                        .border(1.dp, Color(0x5549B8CB), HudShape).padding(11.dp)) {
                        InlineMarkdownText(block.text, 12.sp)
                    }
                    is MarkdownBlock.ImageBlock -> RemoteMarkdownImage(block.alt, block.url)
                    is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(horizontal=5.dp), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(if(block.ordered) "•" else "◆", color=Cyan, fontSize=9.sp, modifier=Modifier.padding(top=4.dp))
                        InlineMarkdownText(block.text, 11.sp)
                    }
                    is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(Color(0xFF040C12), HudShape)
                        .border(1.dp, Color(0x7752DFF2), HudShape).padding(10.dp)) {
                        Text(block.language.ifBlank { "CODE" }.uppercase(), color=Cyan, fontSize=8.sp, letterSpacing=1.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            SelectionContainer { Text(block.text, color=Color(0xFFD2E8EF), fontSize=10.sp, lineHeight=15.sp, fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace) }
                        }
                    }
                    is MarkdownBlock.Table -> MarkdownTable(block.rows)
                }
                index++
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
private fun HudTicks(modifier: Modifier = Modifier, color: Color = Cyan.copy(alpha = .72f)) {
    Canvas(modifier) {
        val arm = 9.dp.toPx()
        val stroke = 1.15.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(0f, arm), Offset(0f, 0f), stroke)
        drawLine(color, Offset(0f, 0f), Offset(arm, 0f), stroke)
        drawLine(color, Offset(w - arm, 0f), Offset(w, 0f), stroke)
        drawLine(color, Offset(w, 0f), Offset(w, arm), stroke)
        drawLine(color, Offset(0f, h - arm), Offset(0f, h), stroke)
        drawLine(color, Offset(0f, h), Offset(arm, h), stroke)
        drawLine(color, Offset(w - arm, h), Offset(w, h), stroke)
        drawLine(color, Offset(w, h - arm), Offset(w, h), stroke)
    }
}


@Composable
private fun JarvisScreen(
    recognizedText: String, status: String, onConsumed: () -> Unit, onSpeak: (String) -> Unit,
    onStartListening: () -> Unit, onModeChanged: (Boolean) -> Unit, onSearch: (String) -> Unit,
    geminiConfigured: () -> Boolean, onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, (String) -> Unit) -> (() -> Unit), onOpenApp: (String) -> Boolean,
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
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceAnswer by remember { mutableStateOf("") }
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
    var keyDraft by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Line>() }
    val revealScope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var followLatest by remember { mutableStateOf(true) }
    val transition = rememberInfiniteTransition(label = "reactor")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(16000), RepeatMode.Restart), label = "spin")
    val breathe by transition.animateFloat(.94f, 1.06f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "breathe")
    val wavePhase by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "wavePhase")
    val scan by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(6400, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "scan")

    fun send(raw: String, voice: Boolean = false) {
        val q = raw.trim()
        val files = pendingAttachments.toList()
        if ((q.isEmpty() && files.isEmpty()) || busy) return
        command = ""
        pendingAttachments.clear()
        val attachmentNote = if (files.isEmpty()) "" else "\n\nLampiran: " + files.joinToString("; ") { it.name + " (" + it.mime + ")" }
        val requestText = q + attachmentNote
        if (q.isNotBlank()) lastPrompt = q
        if (voice) { voiceTranscript = q; voiceAnswer = "" } else lines.add(Line(true, q.ifBlank { "Tolong periksa lampiran ini." }, attachments = files))
        val norm = q.lowercase(Locale.ROOT)
        if (norm in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")) {
            val greeting = "I'm online. How may I assist?"
            if (voice) voiceAnswer = greeting else lines.add(Line(false, greeting))
            statusText = "JARVIS ONLINE"
            if (voice) onSpeak(greeting)
            return
        }
        val openIntent = Regex("\\b(buka|bukakan|jalankan|open|launch|run|start)\\b").containsMatchIn(norm)
        val questionIntent = Regex("\\b(apa|siapa|kapan|kenapa|mengapa|bagaimana|jelaskan|cari|harga|what|who|when|why|how|search|find|explain|price)\\b").containsMatchIn(norm)
        val simpleApp = norm.split(" ").size <= 2 && !questionIntent && !openIntent && norm !in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis")
        if (files.isEmpty() && (openIntent || simpleApp) && onOpenApp(q)) {
            val answer = "Baik. Perintah dijalankan."
            if (voice) voiceAnswer = answer else lines.add(Line(false, answer)); statusText = "COMMAND EXECUTED"
            if (voice) onSpeak(answer)
            return
        }
        if (files.isEmpty() && openIntent) {
            val answer = "Aplikasi tersebut tidak ditemukan di perangkat ini."
            if (voice) voiceAnswer = answer else lines.add(Line(false, answer)); statusText = "APP NOT FOUND"
            if (voice) onSpeak(answer)
            return
        }
        busy = true
        statusText = "ANALYSING REQUEST"
        onSearch(q)
        cancelCurrentRequest = onAskGemini(requestText) { response ->
            val answer = response.substringAfter("KESIMPULAN JARVIS", response)
                .substringAfter("JAWABAN AKHIR", response).trim().ifBlank { response.trim() }
            if (voice) { voiceAnswer = answer } else {
                val responseLine = Line(false, answer)
                responseLine.displayText.value = answer
                responseLine.revealed.value = false
                lines.add(responseLine)
            }
            busy = false
            cancelCurrentRequest = null
            statusText = "RESPONSE READY"
            if (!voice) revealScope.launch {
                kotlinx.coroutines.delay(24)
                lines.lastOrNull()?.revealed?.value = true
            }
            if (voice) onSpeak(answer)
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
                    statusText = "JARVIS ACTIVATED"
                    onSpeak("Yes. I am listening. How may I assist?")
                } else send(request, true)
            } else {
                statusText = "WAKE WORD REQUIRED"
                onStartListening()
            }
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
    val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .background(Background)) {
            val compact = maxHeight < 700.dp
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(Brush.verticalGradient(listOf(Color(0xFF01060C), Color(0xFF041018), Color(0xFF01060C))))
                val core = Offset(w * .5f, h * .34f)
                drawCircle(Brush.radialGradient(listOf(Color(0x2200E5FF), Color.Transparent), core, w * .72f), w * .72f, core)
                val unit = 28.dp.toPx()
                for (x in 0..(w / unit).toInt()) drawLine(Cyan.copy(alpha = .035f), Offset(x * unit, 0f), Offset(x * unit, h), .6f)
                for (y in 0..(h / unit).toInt()) drawLine(Cyan.copy(alpha = .028f), Offset(0f, y * unit), Offset(w, y * unit), .6f)
                val scanLine = h * scan
                drawLine(Cyan.copy(alpha = .16f), Offset(0f, scanLine), Offset(w, scanLine), 1.1f)
                val arm = 18.dp.toPx()
                val inset = 7.dp.toPx()
                val stroke = 1.1.dp.toPx()
                val frame = Cyan.copy(alpha = .45f)
                drawLine(frame, Offset(inset, inset + arm), Offset(inset, inset), stroke)
                drawLine(frame, Offset(inset, inset), Offset(inset + arm, inset), stroke)
                drawLine(frame, Offset(w - inset - arm, inset), Offset(w - inset, inset), stroke)
                drawLine(frame, Offset(w - inset, inset), Offset(w - inset, inset + arm), stroke)
                drawLine(frame, Offset(inset, h - inset - arm), Offset(inset, h - inset), stroke)
                drawLine(frame, Offset(inset, h - inset), Offset(inset + arm, h - inset), stroke)
                drawLine(frame, Offset(w - inset - arm, h - inset), Offset(w - inset, h - inset), stroke)
                drawLine(frame, Offset(w - inset, h - inset - arm), Offset(w - inset, h - inset), stroke)
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().rotate(spin * .15f)) {
                            drawCircle(Cyan.copy(alpha = .85f), size.minDimension * .46f, style = Stroke(1.dp.toPx()))
                            drawCircle(Cyan.copy(alpha = .35f), size.minDimension * .30f, style = Stroke(.7.dp.toPx()))
                            drawArc(Cyan, -20f + spin * .2f, 70f, false, style = Stroke(1.6.dp.toPx()))
                        }
                        Text("J", color = Ice, fontSize = 13.sp, fontWeight = FontWeight.Light)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("J.A.R.V.I.S.", color = Ice, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.4.sp)
                        Text("PERSONAL INTELLIGENCE  //  MOBILE", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clock, color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(5.dp).background(if (busy) WarnAmber else OkGreen, CircleShape))
                            Text(if (busy) "THINKING" else "ONLINE", color = if (busy) WarnAmber else OkGreen,
                                fontSize = 8.sp, letterSpacing = 1.2.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                    IconButton(onClick = { keyDraft = ""; settings = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SYS", color = Cyan.copy(alpha = .7f), fontSize = 8.sp, letterSpacing = 1.4.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) processStage.uppercase(Locale.ROOT) else statusText, color = TextMuted, fontSize = 8.sp,
                        letterSpacing = .6.sp, maxLines = 1, modifier = Modifier.weight(1f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text(if (page == "VOICE") "MODE  VOICE" else "MODE  CHAT", color = Cyan.copy(alpha = .8f),
                        fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Cyan.copy(alpha = .22f)))
                if (page == "VOICE") {
                    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            val reactorSize = if (compact) 248.dp else 300.dp
                            Box(Modifier.size(reactorSize).scale(if (voiceListening || busy) breathe else 1f), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize().rotate(spin)) {
                                    val c = Cyan
                                    val r = size.minDimension * .42f
                                    drawCircle(Brush.radialGradient(listOf(Color(0x5500E5FF), Color(0x1400A8C4), Color.Transparent), center, r * 1.35f), r * 1.35f, center)
                                    drawCircle(c.copy(alpha = .22f), r * 1.02f, center, style = Stroke(.8.dp.toPx()))
                                    drawCircle(c.copy(alpha = .55f), r * .84f, center, style = Stroke(1.2.dp.toPx()))
                                    drawCircle(c.copy(alpha = .18f), r * .62f, center, style = Stroke(.7.dp.toPx()))
                                    for (i in 0 until 72) {
                                        val angle = i * Math.PI * 2 / 72
                                        val major = i % 6 == 0
                                        val inner = r * (if (major) .90f else .96f)
                                        val outer = r * (if (major) 1.08f else 1.0f)
                                        drawLine(c.copy(alpha = if (major) .75f else .22f),
                                            Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                                            Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                                            if (major) 1.3.dp.toPx() else .5.dp.toPx())
                                    }
                                    drawArc(c.copy(alpha = .9f), 200f, 80f, false, style = Stroke(2.dp.toPx()))
                                    drawCircle(Brush.radialGradient(listOf(Color(0xFF123F4C), Color(0xFF061018)), center, r * .48f), r * .48f, center)
                                    drawCircle(Color.White.copy(alpha = .9f), r * .07f, center)
                                }
                                Text(if (voiceListening) "LIVE" else "IDLE", color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            Column(Modifier.align(Alignment.TopStart).padding(top = 4.dp)) {
                                Text("REACTOR", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.4.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text(if (voiceListening) "AUDIO LOCK" else "STANDBY", color = Cyan, fontSize = 10.sp, letterSpacing = 1.2.sp)
                            }
                        }
                        Box(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().border(1.dp, Cyan.copy(alpha = .35f), HudShape)
                                .background(Color(0xCC041018), HudShape).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("COMMAND LOG  //  SECURE", color = Cyan, fontSize = 8.sp, letterSpacing = 1.3.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        repeat(9) { n ->
                                            val h = 3 + kotlin.math.abs(kotlin.math.sin(wavePhase + n * .55f)) * (if (voiceListening) 12f else 4f)
                                            Box(Modifier.width(2.dp).height(h.dp).background(Cyan.copy(alpha = if (voiceListening) .85f else .3f)))
                                        }
                                    }
                                }
                                Text(voiceTranscript.ifBlank { "Awaiting wake word. Say “JARVIS”, then the request." },
                                    color = Ice, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
                                if (voiceAnswer.isNotBlank()) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(Cyan.copy(alpha = .2f)))
                                    Text("J.A.R.V.I.S.", color = Cyan, fontSize = 8.sp, letterSpacing = 1.4.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    Text(voiceAnswer, color = Color(0xFFC5E4EE), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4)
                                }
                            }
                            HudTicks(Modifier.matchParentSize(), Cyan.copy(alpha = .8f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(52.dp).border(1.dp, if (voiceListening) TextMuted else AlertRed, CircleShape)
                                    .clickable {
                                        voiceListening = false
                                        statusText = "MICROPHONE OFF"
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MicOff, "Matikan mikrofon", tint = if (voiceListening) TextMuted else AlertRed, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("MIC OFF", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.1.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(if (compact) 78.dp else 88.dp).scale(if (voiceListening) breathe else 1f)
                                    .border(1.4.dp, Cyan, CircleShape)
                                    .clickable {
                                        voiceListening = !voiceListening
                                        if (voiceListening) onStartListening()
                                        statusText = if (voiceListening) "VOICE MODE · LISTENING" else "MICROPHONE OFF"
                                    }, contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawCircle(Cyan.copy(alpha = .18f), size.minDimension * .38f, style = Stroke(1.dp.toPx()))
                                    }
                                    Icon(if (voiceListening) Icons.Default.Mic else Icons.Default.MicOff, "Toggle microphone", tint = Cyan, modifier = Modifier.size(32.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(if (voiceListening) "LISTENING" else "TAP MIC", color = Cyan, fontSize = 8.sp, letterSpacing = 1.4.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(52.dp).border(1.dp, Cyan.copy(alpha = .55f), CircleShape)
                                    .clickable { voiceListening = false; page = "CHAT" }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Language, "Buka chat", tint = Cyan, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("COMMS", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.1.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("SECURE CHANNEL", color = Cyan, fontSize = 8.sp, letterSpacing = 1.6.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(lines.size.toString().padStart(2, '0') + "  ENTRIES", color = TextMuted, fontSize = 8.sp, letterSpacing = .8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (lines.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().fillParentMaxHeight(.92f),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Box(Modifier.size(if (compact) 132.dp else 150.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val r = size.minDimension * .42f
                                        drawCircle(Brush.radialGradient(listOf(Color(0x3300E5FF), Color.Transparent), center, r * 1.25f), r * 1.25f, center)
                                        drawCircle(Cyan.copy(alpha = .55f), r, center, style = Stroke(1.dp.toPx()))
                                        drawCircle(Cyan.copy(alpha = .22f), r * .72f, center, style = Stroke(.7.dp.toPx()))
                                        drawArc(Cyan, 210f + spin * .05f, 90f, false, style = Stroke(1.8.dp.toPx()))
                                        drawCircle(Color.White.copy(alpha = .85f), r * .08f, center)
                                    }
                                    Text("REACTOR\nONLINE", color = Cyan.copy(alpha = .8f), fontSize = 7.sp, letterSpacing = 1.2.sp,
                                        textAlign = TextAlign.Center, lineHeight = 10.sp,
                                        modifier = Modifier.padding(top = 36.dp),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                Text("At your service.", color = TextMuted, fontSize = 12.sp, letterSpacing = .4.sp)
                                Spacer(Modifier.height(2.dp))
                                Text("How may I assist?", color = Ice, fontSize = 26.sp, fontWeight = FontWeight.Light)
                                Spacer(Modifier.height(6.dp))
                                Text("Voice, search, or a direct command.", color = TextMuted, fontSize = 11.sp)
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("MISSION DECK", color = Cyan, fontSize = 8.sp, letterSpacing = 1.5.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    Spacer(Modifier.weight(1f))
                                    Text("04 TASKS", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        Triple("01", "Explore GitHub", "Repositories & code"),
                                        Triple("02", "Build something", "Code & debugging"),
                                        Triple("03", "Plan a project", "Ideas & workflow"),
                                        Triple("04", "Discover tools", "AI & automation")
                                    ).forEachIndexed { index, item ->
                                        Box(Modifier.width(148.dp).height(86.dp).clickable {
                                            command = when (index) {
                                                0 -> "Help me explore my GitHub repositories"
                                                1 -> "Help me build something or debug code"
                                                2 -> "Help me plan a project"
                                                else -> "Show me the available JARVIS tools"
                                            }
                                            inputFocus.requestFocus()
                                        }) {
                                            Column(Modifier.fillMaxSize().background(Color(0xCC061018), HudShape)
                                                .border(1.dp, Cyan.copy(alpha = .28f), HudShape).padding(10.dp),
                                                verticalArrangement = Arrangement.SpaceBetween) {
                                                Text(item.first, color = Cyan, fontSize = 9.sp, letterSpacing = 1.2.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                                Column {
                                                    Text(item.second, color = Ice, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                    Text(item.third, color = TextMuted, fontSize = 9.sp)
                                                }
                                            }
                                            HudTicks(Modifier.matchParentSize())
                                        }
                                    }
                                }
                            }
                        }
                        items(lines.size) { i ->
                            val line = lines[i]
                            AnimatedVisibility(visible = true, enter = fadeIn(tween(180)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(520))) {
                                if (line.user) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                                        Text("OPERATOR   " + line.time, color = TextMuted, fontSize = 7.sp, letterSpacing = 1.1.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            modifier = Modifier.padding(bottom = 4.dp, end = 2.dp))
                                        Column(Modifier.widthIn(max = 300.dp).background(Color(0x3300E5FF), HudShape)
                                            .border(1.dp, Cyan.copy(alpha = .55f), HudShape).padding(horizontal = 12.dp, vertical = 9.dp)) {
                                            Text(line.text, color = Ice, fontSize = 13.sp, lineHeight = 18.sp)
                                            line.attachments.forEach { file ->
                                                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.AttachFile, null, tint = Cyan, modifier = Modifier.size(12.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(file.name, color = Color(0xFFB7DCE8), fontSize = 9.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("J.A.R.V.I.S.  //  RESPONSE", color = Cyan, fontSize = 7.sp, letterSpacing = 1.2.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                                            Text(line.time, color = TextMuted, fontSize = 7.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                        }
                                        Box(Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth().background(Color(0xE6061018), HudShape)
                                                .border(1.dp, Cyan.copy(alpha = .28f), HudShape).padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AnimatedVisibility(visible = line.revealed.value, enter = fadeIn(tween(180)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(420))) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        RichMessageText(line.displayText.value)
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            TextButton(onClick = { clipboard.setText(AnnotatedString(line.text)) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                                                Icon(Icons.Default.ContentCopy, null, tint = TextMuted, modifier = Modifier.size(12.dp))
                                                                Spacer(Modifier.width(3.dp)); Text("SALIN", color = TextMuted, fontSize = 8.sp, letterSpacing = .8.sp)
                                                            }
                                                            TextButton(onClick = {
                                                                val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, line.text) }
                                                                runCatching { context.startActivity(Intent.createChooser(shareIntent, "Bagikan jawaban JARVIS")) }
                                                            }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                                                Icon(Icons.Default.Share, null, tint = TextMuted, modifier = Modifier.size(12.dp))
                                                                Spacer(Modifier.width(3.dp)); Text("BAGIKAN", color = TextMuted, fontSize = 8.sp, letterSpacing = .8.sp)
                                                            }
                                                        }
                                                        SourceStrip(line.text)
                                                    }
                                                }
                                            }
                                            HudTicks(Modifier.matchParentSize())
                                        }
                                    }
                                }
                            }
                        }
                        if (busy) item {
                            val pulse by rememberInfiniteTransition(label = "thinkingPulse").animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "thinkingAlpha")
                            Column(Modifier.fillMaxWidth().border(1.dp, Cyan.copy(alpha = .25f), HudShape).padding(10.dp)) {
                                Text("NEURAL LINK  //  ANALYSING", color = Cyan, fontSize = 8.sp, letterSpacing = 1.3.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF0C2430))) {
                                    Box(Modifier.fillMaxWidth(pulse).height(2.dp).background(Cyan))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(processStage, color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    if (pendingAttachments.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pendingAttachments.toList().forEach { file ->
                                Row(Modifier.background(Color(0xFF07141C), HudShape).border(1.dp, Cyan.copy(alpha = .3f), HudShape)
                                    .padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(file.name, color = Ice, fontSize = 9.sp, maxLines = 1, modifier = Modifier.widthIn(max = 120.dp))
                                    IconButton(onClick = { pendingAttachments.remove(file) }, modifier = Modifier.size(22.dp)) {
                                        Icon(Icons.Default.Close, "Hapus lampiran", tint = TextMuted, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF061018), HudShape)
                        .border(1.dp, Cyan.copy(alpha = if (command.isNotEmpty()) .7f else .32f), HudShape)
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Box(Modifier.size(36.dp).clickable(enabled = !busy) { attachmentMenu = !attachmentMenu }, contentAlignment = Alignment.Center) {
                                Icon(if (attachmentMenu) Icons.Default.Close else Icons.Default.Add,
                                    if (attachmentMenu) "Tutup menu lampiran" else "Tambah lampiran", tint = Cyan, modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false },
                                properties = PopupProperties(focusable = false), modifier = Modifier.background(Color(0xFF071018))) {
                                DropdownMenuItem(text = { Text("File / Dokumen", color = Ice, fontSize = 13.sp) },
                                    onClick = { attachmentMenu = false; attachmentPicker.launch(arrayOf("*/*")) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null, tint = Cyan) })
                                DropdownMenuItem(text = { Text("Kamera", color = Ice, fontSize = 13.sp) },
                                    onClick = { attachmentMenu = false; cameraPicker.launch(null) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null, tint = Cyan) })
                                DropdownMenuItem(text = { Text("Foto dari galeri", color = Ice, fontSize = 13.sp) },
                                    onClick = { attachmentMenu = false; photoPicker.launch("image/*") },
                                    leadingIcon = { Icon(Icons.Default.Image, null, tint = Cyan) })
                            }
                        }
                        Text(">", color = Cyan, fontSize = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(end = 4.dp))
                        BasicTextField(value = command, onValueChange = { command = it },
                            modifier = Modifier.weight(1f).focusRequester(inputFocus).padding(vertical = 10.dp),
                            singleLine = false, minLines = 1, maxLines = 4,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Ice, fontSize = 15.sp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                                if (!busy) { keyboard?.hide(); focusManager.clearFocus(); send(command) }
                            }),
                            decorationBox = { inner ->
                                if (command.isEmpty()) Text("State a command…", color = TextMuted, fontSize = 13.sp)
                                inner()
                            })
                        if (busy) {
                            Box(Modifier.size(36.dp).border(1.dp, AlertRed.copy(alpha = .8f), HudShape).clickable {
                                cancelCurrentRequest?.invoke()
                                cancelCurrentRequest = null
                                busy = false
                                statusText = "REQUEST STOPPED"
                                lines.add(Line(false, "Permintaan dihentikan."))
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Stop, "Stop response", tint = AlertRed, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            val armed = command.isNotBlank() || pendingAttachments.isNotEmpty()
                            Box(Modifier.size(36.dp).background(if (armed) Cyan else Color.Transparent, HudShape)
                                .border(1.dp, Cyan, HudShape).clickable {
                                    keyboard?.hide(); focusManager.clearFocus(); send(command)
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Send, "Send command", tint = if (armed) Color(0xFF041018) else Cyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf("CHAT" to "01   COMMS", "VOICE" to "02   VOICE").forEach { (tab, label) ->
                        val active = page == tab
                        Column(Modifier.weight(1f).clickable {
                            if (tab == "CHAT") voiceListening = false
                            page = tab
                        }.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, color = if (active) Cyan else TextMuted, fontSize = 9.sp, letterSpacing = 1.5.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(if (active) 2.dp else 1.dp).background(if (active) Cyan else Cyan.copy(alpha = .18f)))
                        }
                    }
                }
            }
            if (settings) AlertDialog(
                onDismissRequest = { settings = false },
                containerColor = Color(0xFF071018),
                title = {
                    Column {
                        Text("SYSTEM CONFIG", color = Ice, letterSpacing = 1.4.sp, fontSize = 14.sp)
                        Text("GEMINI VOICE KEY", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                },
                text = {
                    Column {
                        Text("Stored only on this device.", color = TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            placeholder = { Text("API key", color = TextMuted, fontSize = 12.sp) })
                    }
                },
                confirmButton = {
                    TextButton(onClick = { if (keyDraft.isNotBlank()) onSaveGeminiKey(keyDraft.trim()); settings = false; keyDraft = "" }) {
                        Text("COMMIT", color = Cyan, letterSpacing = 1.sp)
                    }
                },
                dismissButton = { TextButton(onClick = { settings = false }) { Text("ABORT", color = TextMuted, letterSpacing = 1.sp) } }
            )
        }
    }
}
