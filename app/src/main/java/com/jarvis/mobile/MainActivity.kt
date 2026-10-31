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
                    json.optString("error").ifBlank { "JARVIS API error ($code)." } + " Periksa provider AI dan koneksi backend."
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
        val openIntent = Regex("\\b(buka|bukakan|jalankan|open|launch|run|start)\\b").containsMatchIn(norm)
        val questionIntent = Regex("\\b(apa|siapa|kapan|kenapa|mengapa|bagaimana|jelaskan|cari|harga|what|who|when|why|how|search|find|explain|price)\\b").containsMatchIn(norm)
        val simpleApp = norm.split(" ").size <= 2 && !questionIntent && !openIntent
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
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF02080D), Color(0xFF06131C), Color(0xFF02070C))))) {
            val compact = maxHeight < 700.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
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
                        Box(Modifier.size(size+65.dp).rotate(spin).border(1.dp, Color(0x335DEBFF), CircleShape).padding(12.dp).border(1.dp, Color(0x445DEBFF), CircleShape))
                        Box(Modifier.size(size+28.dp).rotate(-spin*.6f).border(1.dp, Color(0x6652DFF2), CircleShape))
                        Box(Modifier.size(size).scale(breathe).background(Brush.radialGradient(listOf(Color(0x5549E8FF), Color(0x1822B8D5), Color.Transparent)), CircleShape).border(1.dp, Color(0x885DEBFF), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(size*.53f).background(Brush.radialGradient(listOf(Color(0xFF17485A), Color(0xFF071923), Color(0xFF02080D))), CircleShape).border(1.dp, Color(0xAA70EFFF), CircleShape), contentAlignment = Alignment.Center) {
                                Text("J", color = Color(0xFFD8FAFF), fontSize = 58.sp, fontWeight = FontWeight.ExtraLight)
                            }
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
                            Text("SAY  “JARVIS, OPEN”  TO ACTIVATE", color = Color(0xFF638C9A), fontSize = 8.sp, letterSpacing = 1.sp)
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
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), state=listState, contentPadding=PaddingValues(bottom=8.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        items(lines.size) { i ->
                            val line=lines[i]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=if(line.user) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.fillMaxWidth(.88f).background(if(line.user) Color(0xFF0A2633) else Color(0xCC081923), RoundedCornerShape(12.dp))
                                    .border(1.dp, if(line.user) Color(0x7755CFE8) else Color(0x5549B8CB), RoundedCornerShape(12.dp)).padding(13.dp)) {
                                    Text(if(line.user) "YOU / COMMAND" else "J.A.R.V.I.S. / FINAL RESPONSE", color=if(line.user) Color(0xFF8BB4C1) else Cyan, fontSize=8.sp, letterSpacing=1.3.sp, fontWeight=FontWeight.Bold)
                                    Spacer(Modifier.height(7.dp))
                                    Text(line.text, color=Color(0xFFE2F5F8), fontSize=14.sp, lineHeight=21.sp)
                                }
                            }
                        }
                        if(busy) item {
                            Column(Modifier.fillMaxWidth().background(Color(0xFF071923), RoundedCornerShape(10.dp)).border(1.dp, Color(0x6652DFF2), RoundedCornerShape(10.dp)).padding(14.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color=Cyan, strokeWidth=2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("J.A.R.V.I.S. IS THINKING", color=Cyan, fontSize=9.sp, letterSpacing=1.4.sp)
                                }
                                Spacer(Modifier.height(9.dp))
                                Text("Understanding request  ·  Consulting AI Council  ·  Preparing response", color=Color(0xFFB5DCE4), fontSize=10.sp)
                                Spacer(Modifier.height(9.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color=Cyan, trackColor=Color(0xFF19313C))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical=8.dp).background(Color(0xFF081923), RoundedCornerShape(28.dp)).border(1.dp, Color(0x8852DFF2), RoundedCornerShape(28.dp)).padding(start=15.dp,end=5.dp,top=4.dp,bottom=4.dp), verticalAlignment=Alignment.CenterVertically) {
                        BasicTextField(value=command,onValueChange={command=it},modifier=Modifier.weight(1f).padding(vertical=11.dp),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=14.sp),decorationBox={ inner -> if(command.isEmpty()) Text("Enter a command…",color=TextMuted,fontSize=13.sp); inner() })
                        IconButton(onClick={send(command)},enabled=!busy) { Icon(Icons.Default.Send,"Send command",tint=if(busy) TextMuted else Cyan) }
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
