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
                        }.joinToString("\n")
                    } else ""
                    json.optString("error").ifBlank { "JARVIS API error ($code)." } +
                        if (diagnosticText.isNotBlank()) "\n\nDIAGNOSTIK PROVIDER\n$diagnosticText" else ""
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
                val persona = JSONObject().put("text", "Speak the supplied text exactly in natural Bahasa Indonesia. Voice persona: mature male AI assistant, low and resonant register, calm, precise, composed, intelligent, subtly authoritative, measured pace, clean Indonesian diction, restrained cinematic delivery. Never imitate a specific actor or copyrighted character voice.")
                val systemInstruction = JSONObject().put("parts", JSONArray().put(persona))
                val prebuiltVoice = JSONObject().put("voiceName", geminiVoice)
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
    var searchLoading by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("SYSTEM STANDBY") }
    var settingsOpen by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var geminiBusy by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(ChatLine(false, "Good day. JARVIS systems are online. Say Jarvis, open when you need me."))
    }
    val pulse = rememberInfiniteTransition(label = "jarvisCore")
    val orbPulse by pulse.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "orbPulse")
    val ringRotation by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(14000), RepeatMode.Restart), label = "ringRotation")
    val ringReverse by pulse.animateFloat(360f, 0f, infiniteRepeatable(tween(19000), RepeatMode.Restart), label = "ringReverse")
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun execute(raw: String, speakResponse: Boolean = true) {
        val q = raw.trim()
        if (q.isBlank()) return
        typedCommand = ""
        messages.add(ChatLine(true, q))
        val normalized = q.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val hasOpenVerb = Regex("\\b(buka|bukakan|jalankan|mainkan|main|open|launch|run|start)\\b").containsMatchIn(normalized)
        val explicitSearch = Regex("\\b(cari|search|google|harga|bandingkan|compare|find|berapa|what|why|how|where|siapa|kapan|kenapa|mengapa|apa)\\b").containsMatchIn(normalized)
        val standaloneAppLike = normalized.split(" ").size <= 3 && !explicitSearch &&
            !Regex("\\b(halo|hai|hey|hi|pagi|siang|malam|tes|test)\\b").containsMatchIn(normalized)
        val wantsAppList = Regex("\\b(daftar|list|lihat|tampilkan)\\b").containsMatchIn(normalized) &&
            Regex("\\b(aplikasi|app|apps)\\b").containsMatchIn(normalized)
        if (wantsAppList) {
            val apps = onInstalledApps()
            val response = if (apps.isEmpty()) "I cannot read the installed app list on this device." else
                "Applications detected (" + apps.size + "):\n" + apps.take(70).joinToString(", ") +
                    if (apps.size > 70) "\nAnd " + (apps.size - 70) + " more." else ""
            messages.add(ChatLine(false, response)); statusLabel = "DEVICE SCAN COMPLETE"
            if (speakResponse) onSpeak(response)
            return
        }
        val opened = (hasOpenVerb || standaloneAppLike) && onOpenApp(q)
        if (opened) {
            val appName = normalized.replace(Regex("^(tolong |please |buka(kan)? |jalankan |mainkan |main |open |launch |run |start |aplikasi |app |the )+"), "").trim()
            val response = "Certainly. Opening " + appName + "."
            messages.add(ChatLine(false, response)); searchLoading = false; statusLabel = "COMMAND EXECUTED"
            if (speakResponse) onSpeak(response)
        } else if (hasOpenVerb) {
            val apps = onInstalledApps()
            val lastWord = normalized.substringAfterLast(" ").trim()
            val suggestion = apps.filter { lastWord.isNotBlank() && it.contains(lastWord, true) }.take(8)
            val response = if (suggestion.isNotEmpty()) "Closest matches: " + suggestion.joinToString(", ") + "." else
                "That application was not found among the installed apps."
            messages.add(ChatLine(false, response)); statusLabel = "APP NOT FOUND"
            if (speakResponse) onSpeak(response)
        } else {
            searchQuery = q; searchLoading = true; statusLabel = "ANALYSING REQUEST"
            onSearch(q); geminiBusy = true
            onAskGemini(q) { answer ->
                messages.add(ChatLine(false, answer))
                geminiBusy = false; searchLoading = false; statusLabel = "RESPONSE READY"
                if (speakResponse) onSpeak(answer)
            }
        }
    }

    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank() && voiceMode) {
            val heard = recognizedText.trim()
            val wake = Regex("(?i)^jarvis[,.! ]*")
            if (wake.containsMatchIn(heard)) {
                val command = heard.replaceFirst(wake, "").trim()
                onConsumed()
                if (command.isBlank() || command.equals("open", true)) {
                    statusLabel = "JARVIS ACTIVATED"
                    onSpeak("Yes. I am listening. How may I assist?")
                } else execute(command, true)
            } else {
                statusLabel = "AWAITING WAKE WORD"
                onConsumed()
                onStartListening()
            }
        }
    }
    LaunchedEffect(Unit) { onStartListening() }
    LaunchedEffect(messages.size, searchLoading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF02080D), Color(0xFF06131C), Color(0xFF02070C))))
        ) {
            val compact = maxHeight < 700.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = if (compact) 8.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("J.A.R.V.I.S.", color = Color(0xFFE4FAFF), fontSize = 21.sp,
                            fontWeight = FontWeight.Light, letterSpacing = 4.sp)
                        Text("JUST A RATHER VERY INTELLIGENT SYSTEM", color = Color(0xFF5E9BA9),
                            fontSize = 7.sp, letterSpacing = 1.25.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (geminiBusy) Color(0xFFFFB74D) else Color(0xFF53E6C1), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if (geminiBusy) "PROCESSING" else "SYSTEM ONLINE", color = Cyan, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                        Text("CORE 01  /  " + if (voiceMode) "VOICE" else "TEXT", color = TextMuted, fontSize = 7.sp)
                    }
                    IconButton(onClick = { apiKeyDraft = ""; settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Cyan)
                    }
                }
                Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0x9954DDF4), Color(0x2254DDF4), Color.Transparent))
                ))
                Box(
                    Modifier.fillMaxWidth().weight(if (compact) 0.42f else 0.48f).padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val coreSize = if (compact) 174.dp else 218.dp
                    Box(Modifier.size(coreSize + 62.dp).rotate(ringRotation)
                        .border(1.dp, Color(0x334DEBFF), CircleShape).padding(12.dp)
                        .border(1.dp, Color(0x224DEBFF), CircleShape))
                    Box(Modifier.size(coreSize + 30.dp).rotate(ringReverse)
                        .border(1.dp, Color(0x6652DFF2), CircleShape).padding(8.dp)
                        .border(1.dp, Color(0x224DEBFF), CircleShape))
                    Box(Modifier.size(coreSize).scale(orbPulse)
                        .background(Brush.radialGradient(listOf(Color(0x3349E8FF), Color(0x1022B8D5), Color.Transparent)), CircleShape)
                        .border(1.dp, Color(0x885DEBFF), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(coreSize * 0.72f).border(1.dp, Color(0x665DEBFF), CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0x2039D9F0), Color(0x080D6D8A), Color.Transparent)), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(coreSize * 0.48f).border(1.dp, Color(0xAA70EFFF), CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF17485A), Color(0xFF071923), Color(0xFF02080D)), CircleShape), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text("J", color = Color(0xFFD8FAFF), fontSize = if (compact) 43.sp else 54.sp,
                                    fontWeight = FontWeight.ExtraLight, letterSpacing = 2.sp)
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                geminiBusy -> "PROCESSING REQUEST"
                                status.contains("SPEAK", true) -> "VOICE OUTPUT"
                                status.contains("LISTEN", true) || status.contains("HEARING", true) -> "AUDIO INPUT"
                                else -> statusLabel
                            }, color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(if (voiceMode) "SAY  JARVIS, OPEN  TO ACTIVATE" else "TEXT INTERFACE ACTIVE",
                            color = Color(0xFF638C9A), fontSize = 7.sp, letterSpacing = 1.1.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF07151E), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x224DEBFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("NEURAL LINK", color = TextMuted, fontSize = 7.sp); Text("STABLE", color = Color(0xFF68E6CA), fontSize = 9.sp) }
                    Column { Text("AI COUNCIL", color = TextMuted, fontSize = 7.sp); Text(if (geminiBusy) "ACTIVE" else "STANDBY", color = Cyan, fontSize = 9.sp) }
                    Column { Text("AUDIO CORE", color = TextMuted, fontSize = 7.sp); Text(if (voiceMode) "ARMED" else "MUTED", color = Cyan, fontSize = 9.sp) }
                    Column(horizontalAlignment = Alignment.End) { Text("SECURE LINK", color = TextMuted, fontSize = 7.sp); Text("CONNECTED", color = Color(0xFF68E6CA), fontSize = 9.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("COMMUNICATION LOG", color = Color(0xFF8EDDED), fontSize = 8.sp, letterSpacing = 1.8.sp,
                        modifier = Modifier.weight(1f))
                    Text(messages.size.toString() + " ENTRIES", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(5.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(if (compact) 0.48f else 0.52f),
                    state = listState, contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages.size) { index ->
                        val message = messages[index]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
                            Column(Modifier.fillMaxWidth(if (message.isUser) 0.86f else 0.96f)
                                .background(if (message.isUser) Color(0xFF0A2633) else Color(0xCC081923), RoundedCornerShape(10.dp))
                                .border(1.dp, if (message.isUser) Color(0x6655CFE8) else Color(0x4449B8CB), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(5.dp).background(if (message.isUser) Color(0xFF8BB4C1) else Cyan, CircleShape))
                                    Spacer(Modifier.width(7.dp))
                                    Text(if (message.isUser) "YOU  /  INPUT" else "J.A.R.V.I.S.  /  RESPONSE",
                                        color = if (message.isUser) Color(0xFF8BB4C1) else Cyan,
                                        fontSize = 7.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(message.text, color = Color(0xFFE2F5F8), fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                    if (geminiBusy) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Cyan, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("JARVIS IS ANALYSING  /  " + searchQuery, color = Cyan, fontSize = 8.sp, letterSpacing = 1.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!voiceMode) {
                        Row(Modifier.weight(1f).background(Color(0xFF081923), RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0x6652DFF2), RoundedCornerShape(24.dp))
                            .padding(start = 15.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(value = typedCommand, onValueChange = { typedCommand = it },
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                decorationBox = { inner -> if (typedCommand.isEmpty()) Text("Transmit command…", color = TextMuted, fontSize = 12.sp); inner() })
                            IconButton(onClick = { execute(typedCommand, false) }) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = Cyan)
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text("VOICE RECOGNITION", color = TextMuted, fontSize = 7.sp, letterSpacing = 1.2.sp)
                            Text("Wake phrase protected", color = Color(0xFF8CDDEB), fontSize = 10.sp)
                        }
                    }
                    Button(onClick = {
                        voiceMode = !voiceMode
                        onModeChanged(voiceMode)
                        statusLabel = if (voiceMode) "VOICE SYSTEM ARMED" else "TEXT INTERFACE ACTIVE"
                    }, shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B3443), contentColor = Cyan),
                        border = BorderStroke(1.dp, Color(0x8854DDF4))) {
                        Icon(if (voiceMode) Icons.Default.Mic else Icons.Default.SettingsVoice, contentDescription = "Toggle mode",
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp)); Text("VOICE", fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
                Text("J.A.R.V.I.S.  /  PERSONAL INTELLIGENCE SYSTEM", color = Color(0xFF355C68),
                    fontSize = 6.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            }
            if (settingsOpen) {
                AlertDialog(
                    onDismissRequest = { settingsOpen = false }, containerColor = Color(0xFF0A1922),
                    title = { Text("JARVIS SYSTEM SETTINGS", color = Color.White, fontWeight = FontWeight.Light) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("AI Council terhubung melalui Cloudflare Worker. Provider API keys disimpan di backend.", color = TextMuted, fontSize = 12.sp)
                            OutlinedTextField(value = apiKeyDraft, onValueChange = { apiKeyDraft = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Gemini API key") },
                                placeholder = { Text("Paste key baru di sini") }, singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White, focusedBorderColor = Cyan,
                                    unfocusedBorderColor = Color(0xFF365165), focusedLabelColor = Cyan,
                                    unfocusedLabelColor = TextMuted))
                            Text("Key ini hanya untuk fitur suara Gemini TTS.", color = TextMuted, fontSize = 10.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (apiKeyDraft.trim().isNotBlank()) onSaveGeminiKey(apiKeyDraft.trim())
                            settingsOpen = false; apiKeyDraft = ""
                        }) { Text("SAVE", color = Cyan) }
                    },
                    dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("CANCEL", color = TextMuted) } }
                )
            }
        }
    }
}
