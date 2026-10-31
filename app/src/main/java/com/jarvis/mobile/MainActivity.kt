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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val voiceText = mutableStateOf("")
    private val voiceStatus = mutableStateOf("INITIALIZING VOICE CORE")
    private var permissionAsked = false
    @Volatile private var geminiSpeechInProgress = false
    private var speechPlayer: MediaPlayer? = null
    private var greetingSpoken = false
    private var voiceModeEnabled = true
    private val prefs by lazy { getSharedPreferences("jarvis_secure_settings", MODE_PRIVATE) }
    private val geminiKey get() = prefs.getString("gemini_api_key", "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val voices = tts?.voices.orEmpty()
                val englishVoices = voices.filter { it.locale.language == "en" }
                val maleVoice = englishVoices.firstOrNull {
                    val n = it.name.lowercase(Locale.ROOT)
                    n.contains("male") || n.contains("iom") || n.contains("rjs") || n.contains("davis")
                } ?: englishVoices.sortedByDescending { it.quality }.firstOrNull()
                if (maleVoice != null) tts?.voice = maleVoice else tts?.language = Locale.US
                tts?.setSpeechRate(0.88f)
                tts?.setPitch(0.82f)
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
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { voiceStatus.value = "PROCESSING VOICE" }
                override fun onError(error: Int) {
                    if (geminiSpeechInProgress) return
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
                    if (voiceMode) startListening() else recognizer?.cancel()
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
                    val diagnosticArray = json.optJSONArray("diagnostics")
                    val diagnosticText = if (diagnosticArray != null) {
                        (0 until diagnosticArray.length()).map { index ->
                            val item = diagnosticArray.optJSONObject(index) ?: JSONObject()
                            val provider = item.optString("provider", "AI").uppercase(Locale.ROOT)
                            val detail = item.optString("detail", "Tidak ada detail")
                            "$provider: $detail"
                        }.joinToString("\\n")
                    } else ""
                    json.optString("error").ifBlank { "JARVIS API error ($code)." } +
                        if (diagnosticText.isNotBlank()) "\\n\\nDIAGNOSTIK PROVIDER\\n$diagnosticText" else ""
                } else {
                    val participants = json.optJSONArray("participants") ?: JSONArray()
                    val names = (0 until participants.length()).map { participants.optString(it).uppercase(Locale.ROOT) }
                    val level = json.optString("level", "general").uppercase(Locale.ROOT)
                    val rounds = json.optInt("rounds", 1)
                    val answer = json.optString("answer", "JARVIS tidak menerima jawaban akhir.")
                    val discussion = json.optJSONArray("discussion") ?: JSONArray()
                    val debateText = (0 until discussion.length()).map { index ->
                        val item = discussion.optJSONObject(index) ?: JSONObject()
                        val agent = item.optString("agent", "AI").uppercase(Locale.ROOT)
                        val round = item.optInt("round", 1)
                        val message = item.optString("message", "").replace("\n", " ").take(420)
                        "PUTARAN $round · $agent\n$message"
                    }.joinToString("\n\n")
                    "AI COUNCIL · $level · ${names.size} PROVIDER · $rounds ROUND\n" +
                        "Peserta: " + names.joinToString(", ") + "\n\n" +
                        "RINGKASAN DISKUSI\n" + debateText + "\n\n" +
                        "KESIMPULAN JARVIS\n" + answer
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
        val key = geminiKey
        if (key.isBlank() || text.isBlank()) {
            speakWithAndroid(text)
            return
        }
        geminiSpeechInProgress = true
        tts?.stop()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=" + java.net.URLEncoder.encode(key, "UTF-8"))
                connection = endpoint.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val textPart = JSONObject().put("text", "Read the following response aloud exactly as written. Do not add or omit words. Text: " + text)
                val contentParts = JSONArray().put(textPart)
                val contents = JSONArray().put(JSONObject().put("parts", contentParts))
                val persona = JSONObject().put("text", "Voice persona: a sophisticated male AI assistant inspired by a cinematic British butler. Calm, composed, intelligent, subtly authoritative, measured pace, clear British English pronunciation when speaking English, warm low-register delivery. Never imitate a specific actor or copyrighted character voice. Preserve the language of the supplied text.")
                val systemInstruction = JSONObject().put("parts", JSONArray().put(persona))
                val prebuiltVoice = JSONObject().put("voiceName", "Alnilam")
                val voiceConfig = JSONObject().put("prebuiltVoiceConfig", prebuiltVoice)
                val speechConfig = JSONObject().put("voiceConfig", voiceConfig)
                val generationConfig = JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig)
                val body = JSONObject()
                    .put("contents", contents)
                    .put("systemInstruction", systemInstruction)
                    .put("generationConfig", generationConfig)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw IllegalStateException("Gemini TTS HTTP " + code)
                val json = JSONObject(response)
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                var audioData = ""
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                        if (data.isNotBlank()) { audioData = data; break }
                    }
                }
                if (audioData.isBlank()) throw IllegalStateException("Gemini TTS returned no audio")
                val pcm = Base64.decode(audioData, Base64.DEFAULT)
                val wav = pcmToWav(pcm, 24000, 1, 16)
                val audioFile = File(cacheDir, "jarvis_voice.wav")
                FileOutputStream(audioFile).use { it.write(wav) }
                handler.post {
                    try {
                        speechPlayer?.release()
                        speechPlayer = MediaPlayer().apply {
                            setDataSource(audioFile.absolutePath)
                            setOnPreparedListener {
                                voiceStatus.value = "JARVIS SPEAKING"
                                it.start()
                            }
                            setOnCompletionListener {
                                it.release()
                                speechPlayer = null
                                geminiSpeechInProgress = false
                                handler.postDelayed({ startListening() }, 350)
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                speechPlayer = null
                                geminiSpeechInProgress = false
                                speakWithAndroid(text)
                                true
                            }
                            prepareAsync()
                        }
                    } catch (_: Exception) {
                        speechPlayer?.release()
                        speechPlayer = null
                        geminiSpeechInProgress = false
                        speakWithAndroid(text)
                    }
                }
            } catch (_: Exception) {
                handler.post {
                    geminiSpeechInProgress = false
                    speakWithAndroid(text)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun speakWithAndroid(text: String) {
        geminiSpeechInProgress = false
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(pcm.size + 44)
        fun writeInt(value: Int) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
        fun writeShort(value: Int) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte())) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(pcm.size + 36)
        out.write("WAVE".toByteArray(Charsets.US_ASCII)); out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeInt(16); writeShort(1); writeShort(channels); writeInt(sampleRate)
        writeInt(sampleRate * channels * bitsPerSample / 8); writeShort(channels * bitsPerSample / 8); writeShort(bitsPerSample)
        out.write("data".toByteArray(Charsets.US_ASCII)); writeInt(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun startListening() {
        if (isFinishing || isDestroyed || !voiceModeEnabled || geminiSpeechInProgress) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 700)
                voiceStatus.value = "MICROPHONE PERMISSION REQUIRED"
            }
            return
        }
        if (!greetingSpoken) {
            greetingSpoken = true
            speak("Good day. JARVIS is online. I am listening.")
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
private fun JarvisScreen(
    recognizedText: String,
    status: String,
    onConsumed: () -> Unit,
    onSpeak: (String) -> Unit,
    onStartListening: () -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    geminiConfigured: () -> Boolean,
    onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, (String) -> Unit) -> Unit,
    onOpenApp: (String) -> Boolean,
    onInstalledApps: () -> List<String>
) {
    data class ChatLine(val isUser: Boolean, val text: String)
    var typedCommand by remember { mutableStateOf("") }
    var voiceMode by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("SYSTEM ONLINE") }
    var settingsOpen by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var geminiBusy by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            ChatLine(false, "JARVIS online. Ada yang bisa gue bantu?")
        )
    }
    val pulse = rememberInfiniteTransition(label = "orb")
    val orbScale by pulse.animateFloat(
        initialValue = 0.96f, targetValue = 1.045f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Reverse),
        label = "orbPulse"
    )
    val ringRotation by pulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Restart),
        label = "ringRotation"
    )
    val scanProgress by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "scanProgress"
    )
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun execute(raw: String, speakResponse: Boolean = true) {
        val q = raw.trim()
        if (q.isBlank()) return
        typedCommand = ""
        messages.add(ChatLine(true, q))
        val normalized = q.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val hasOpenVerb = Regex("\\b(buka|bukakan|jalankan|mainkan|main|open|launch|run|start)\\b")
            .containsMatchIn(normalized)
        val explicitSearch = Regex("\\b(cari|search|google|harga|bandingkan|compare|find|berapa|what|why|how|where|siapa|kapan|kenapa|mengapa|apa)\\b")
            .containsMatchIn(normalized)
        val standaloneAppLike = normalized.split(" ").size <= 3 && !explicitSearch &&
            !Regex("\\b(halo|hai|hey|hi|pagi|siang|malam|tes|test)\\b").containsMatchIn(normalized)
        val wantsAppList = Regex("\\b(daftar|list|lihat|tampilkan)\\b").containsMatchIn(normalized) &&
            Regex("\\b(aplikasi|app|apps)\\b").containsMatchIn(normalized)
        if (wantsAppList) {
            val apps = onInstalledApps()
            val response = if (apps.isEmpty()) {
                "Gue belum bisa membaca daftar aplikasi di perangkat ini. Periksa izin dan coba lagi."
            } else {
                "Ini aplikasi yang terdeteksi di perangkat lu (${apps.size}):\\n" +
                    apps.take(70).joinToString(", ") +
                    if (apps.size > 70) "\\nDan ${apps.size - 70} aplikasi lainnya." else ""
            }
            messages.add(ChatLine(false, response))
            statusLabel = "INSTALLED APPS DETECTED"
            if (speakResponse) onSpeak(response)
            return
        }
        val opened = (hasOpenVerb || standaloneAppLike) && onOpenApp(q)
        if (opened) {
            val appName = normalized.replace(
                Regex("^(tolong |please |buka(kan)? |jalankan |mainkan |main |open |launch |run |start |aplikasi |app |the )+"), ""
            ).trim()
            val response = "Siap. Gue buka $appName."
            messages.add(ChatLine(false, response))
            searchVisible = false
            searchLoading = false
            searchQuery = ""
            statusLabel = "COMMAND COMPLETE"
            if (speakResponse) onSpeak(response)
        } else if (hasOpenVerb) {
            val apps = onInstalledApps()
            val lastWord = normalized.substringAfterLast(" ").trim()
            val suggestion = apps.filter { lastWord.isNotBlank() && it.contains(lastWord, true) }.take(8)
            val response = if (suggestion.isNotEmpty()) {
                "Aplikasi yang paling mendekati: " + suggestion.joinToString(", ") + ". Sebutkan nama aplikasinya dengan lebih jelas."
            } else {
                "Aplikasi itu tidak ditemukan di daftar aplikasi yang terpasang. Coba perintah 'daftar aplikasi' untuk melihat aplikasi yang terdeteksi."
            }
            messages.add(ChatLine(false, response))
            searchVisible = false
            searchLoading = false
            searchQuery = ""
            statusLabel = "APP NOT FOUND"
            if (speakResponse) onSpeak(response)
        } else {
            searchQuery = q
            searchVisible = true
            searchLoading = true
            statusLabel = "SEARCHING NETWORK"
            messages.add(ChatLine(false, "Gue cari informasi tentang “$q”. Tunggu sebentar, gue susun hasilnya."))
            onSearch(q)
            geminiBusy = true
            onAskGemini(q) { answer ->
                messages.add(ChatLine(false, answer))
                geminiBusy = false
                searchLoading = false
                statusLabel = "AI COUNCIL RESPONSE READY"
                if (speakResponse) onSpeak(answer)
            }
        }
    }

    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank() && voiceMode) {
            execute(recognizedText, true)
            onConsumed()
        }
    }
    LaunchedEffect(Unit) { onStartListening() }
    LaunchedEffect(messages.size, searchLoading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0B192A), Color(0xFF050A13), Color(0xFF02050A))))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("J.A.R.V.I.S.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("PERSONAL AI INTERFACE", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.7.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (searchLoading) Color(0xFFFFC857) else Color(0xFF63D6A3), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (geminiBusy) "THINKING" else if (geminiConfigured()) "CLOUD AI READY" else "SETUP AI", color = Cyan, fontSize = 10.sp, letterSpacing = 1.1.sp)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { apiKeyDraft = ""; settingsOpen = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "AI settings", tint = Cyan)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0A1422), RoundedCornerShape(14.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { voiceMode = true; onModeChanged(true); statusLabel = "VOICE MODE" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (voiceMode) Color(0xFF10415A) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("VOICE", fontSize = 11.sp)
                }
                Button(
                    onClick = { voiceMode = false; onModeChanged(false); statusLabel = "CHAT MODE" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (!voiceMode) Color(0xFF10415A) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("CHAT", fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(60.dp).scale(orbScale).border(1.dp, Color(0x7739DFF9), CircleShape).background(Color(0x1028DFFF), CircleShape))
                    Box(Modifier.size(48.dp).border(1.dp, Color(0xBB64E8FF), CircleShape).background(Brush.radialGradient(listOf(Color(0xFF42DDF7), Color(0xFF087DAA), Color(0xFF07182E))), CircleShape), contentAlignment = Alignment.Center) {
                        Text("J", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            searchLoading -> "SEARCHING"
                            status.contains("LISTEN", true) || status.contains("HEARING", true) -> "LISTENING"
                            status.contains("SPEAK", true) -> "SPEAKING"
                            else -> statusLabel
                        },
                        color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(if (voiceMode) "Gue siap dengerin perintah lu." else "Tulis pesan buat JARVIS.", color = TextMuted, fontSize = 11.sp)
                }
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(24.dp).border(1.dp, Color(0x7747DDF5), CircleShape).scale(1f + scanProgress * 0.1f))
                    Box(Modifier.size(4.dp).background(Cyan, CircleShape))
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(1.dp).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0xAA2EDCF8), Color.Transparent))
                )
            )

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages.size) { index ->
                    val message = messages[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Column(
                            Modifier.widthIn(max = 340.dp)
                                .background(
                                    if (message.isUser) Color(0xFF10384C) else Color(0xCC0C1828),
                                    RoundedCornerShape(
                                        topStart = 17.dp, topEnd = 17.dp,
                                        bottomStart = if (message.isUser) 17.dp else 5.dp,
                                        bottomEnd = if (message.isUser) 5.dp else 17.dp
                                    )
                                )
                                .border(1.dp, if (message.isUser) Color(0x6644CDE8) else Color(0x3337CDE8), RoundedCornerShape(17.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Text(if (message.isUser) "YOU" else "J.A.R.V.I.S.", color = if (message.isUser) Color(0xFF9CB9CA) else Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(message.text, color = Color(0xFFEAF7FF), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }

                if (geminiBusy) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.widthIn(max = 310.dp)
                                    .background(Color(0xCC0C1828), RoundedCornerShape(17.dp))
                                    .border(1.dp, Color(0x5539DFF9), RoundedCornerShape(17.dp))
                                    .padding(horizontal = 14.dp, vertical = 13.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(34.dp).rotate(ringRotation).border(1.dp, Cyan.copy(alpha = 0.85f), CircleShape))
                                        Box(Modifier.size(25.dp).rotate(-ringRotation * 1.3f).border(1.dp, Color(0x6685F1FF), RoundedCornerShape(8.dp)))
                                        Box(Modifier.size(8.dp).background(Cyan, CircleShape))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("J.A.R.V.I.S. IS THINKING", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                        Text("AI Council is discussing your request…", color = TextMuted, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(24) { i ->
                                        val barHeight by rememberInfiniteTransition(label = "wave-$i").animateFloat(
                                            initialValue = 4f,
                                            targetValue = (7 + (i * 11 % 15)).toFloat(),
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(360 + i * 27, delayMillis = i * 24),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "bar-$i"
                                        )
                                        Box(
                                            Modifier.weight(1f).height(barHeight.dp)
                                                .background(Cyan.copy(alpha = 0.35f + (i % 4) * 0.15f), RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                                Spacer(Modifier.height(7.dp))
                                Text("AI COUNCIL · $searchQuery", color = TextMuted, fontSize = 8.sp, maxLines = 2, letterSpacing = 0.6.sp)
                            }
                        }
                    }
                }
            }

            if (!voiceMode) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0B1421), RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0x6640DDF5), RoundedCornerShape(20.dp))
                        .padding(start = 15.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = typedCommand, onValueChange = { typedCommand = it },
                        modifier = Modifier.weight(1f).padding(vertical = 11.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                        decorationBox = { inner ->
                            if (typedCommand.isEmpty()) Text("Message JARVIS…", color = TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    )
                    IconButton(onClick = { execute(typedCommand, false) }) {
                        Icon(Icons.Default.Send, contentDescription = "Send message", tint = Cyan)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Cyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("VOICE ACTIVE · KEEP JARVIS IN FOREGROUND", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }

        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                containerColor = Color(0xFF0C1726),
                title = { Text("JARVIS AI SETTINGS", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("AI Council terhubung ke Cloudflare Worker. API key provider disimpan sebagai Secret di backend, bukan di APK.", color = TextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Gemini API key") },
                            placeholder = { Text("Paste key baru di sini") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF365165),
                                focusedLabelColor = Cyan, unfocusedLabelColor = TextMuted
                            )
                        )
                        Text("Kolom ini hanya digunakan untuk fitur suara Gemini TTS. AI Council tidak menggunakan key ini.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (apiKeyDraft.trim().isNotBlank()) onSaveGeminiKey(apiKeyDraft.trim())
                        settingsOpen = false
                        apiKeyDraft = ""
                    }) { Text("SIMPAN", color = Cyan) }
                },
                dismissButton = {
                    TextButton(onClick = { settingsOpen = false }) { Text("BATAL", color = TextMuted) }
                }
            )
        }
        }
    }
}
