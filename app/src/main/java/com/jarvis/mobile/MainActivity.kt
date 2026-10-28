package com.jarvis.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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
    private var greetingSpoken = false
    private var voiceModeEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setSpeechRate(0.94f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { runOnUiThread { voiceStatus.value = "JARVIS SPEAKING" } }
                    override fun onDone(utteranceId: String?) { handler.postDelayed({ startListening() }, 550) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.postDelayed({ startListening() }, 700) }
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
                onSearch = { _ -> voiceStatus.value = "SEARCHING THE WEB" },
                onOpenApp = { q -> openRequestedApp(q) }
            )
        }
    }

    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response") }

    private fun startListening() {
        if (isFinishing || isDestroyed || !voiceModeEnabled) return
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
    onOpenApp: (String) -> Boolean
) {
    data class ChatLine(val isUser: Boolean, val text: String)
    var typedCommand by remember { mutableStateOf("") }
    var voiceMode by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("SYSTEM ONLINE") }
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
        } else {
            searchQuery = q
            searchVisible = true
            searchLoading = true
            statusLabel = "SEARCHING NETWORK"
            messages.add(ChatLine(false, "Gue cari informasi tentang “$q”. Tunggu sebentar, gue susun hasilnya."))
            onSearch(q)
            if (speakResponse) onSpeak("Oke, gue cari informasi tentang $q.")
        }
    }

    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank() && voiceMode) {
            execute(recognizedText, true)
            onConsumed()
        }
    }
    LaunchedEffect(Unit) { onStartListening() }
    LaunchedEffect(searchQuery, searchVisible) {
        if (searchVisible && searchQuery.isNotBlank()) {
            searchLoading = true
            kotlinx.coroutines.delay(1400)
            searchLoading = false
            statusLabel = "RESULTS READY"
        }
    }
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
                    Text(if (searchLoading) "WORKING" else "ONLINE", color = Cyan, fontSize = 10.sp, letterSpacing = 1.1.sp)
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
                            Modifier.widthIn(max = 310.dp)
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

                if (searchVisible && searchQuery.isNotBlank()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color(0xCC091523), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0x5539DFF9), RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(26.dp).border(1.5.dp, Cyan, CircleShape).scale(if (searchLoading) orbScale else 1f))
                                    Box(Modifier.size(6.dp).background(Cyan, CircleShape))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (searchLoading) "MENCARI DI WEB" else "PENCARIAN SIAP", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Text(searchQuery, color = TextMuted, fontSize = 11.sp, maxLines = 2)
                                }
                                if (searchLoading) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(19.dp), color = Cyan, strokeWidth = 2.dp
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                repeat(18) { i ->
                                    Box(
                                        Modifier.weight(1f).height(if (searchLoading) (5 + ((i * 7) % 12)).dp else 3.dp)
                                            .background(if (searchLoading && i % 3 != 0) Cyan.copy(alpha = 0.8f) else Color(0xFF1D4B5D), RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (searchLoading) {
                                Text("Menganalisis kata kunci dan menyiapkan hasil…", color = TextMuted, fontSize = 12.sp)
                            } else {
                                Text("Hasil web untuk pencarian ini tersedia di dalam tampilan JARVIS.", color = Color(0xFFD9ECF5), fontSize = 12.sp, lineHeight = 18.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onSearch(searchQuery) },
                                    border = BorderStroke(1.dp, Color(0x8843DDF7)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("MUAT HASIL WEB", fontSize = 10.sp, letterSpacing = 1.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Catatan: koneksi pencarian saat ini menggunakan halaman web; ringkasan AI dan perbandingan harga otomatis memerlukan sumber data/API tambahan.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
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
        }
    }
}
