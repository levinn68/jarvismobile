package com.jarvis.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
                onSearch = { q ->
                    voiceStatus.value = "OPENING WEB SEARCH"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                },
                onOpenApp = { q -> openRequestedApp(q) }
            )
        }
    }

    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response") }

    private fun startListening() {
        if (isFinishing || isDestroyed) return
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

    private fun openRequestedApp(command: String) {
        val pkg = when {
            command.contains("roblox", true) -> "com.roblox.client"
            command.contains("youtube", true) -> "com.google.android.youtube"
            command.contains("whatsapp", true) -> "com.whatsapp"
            command.contains("chrome", true) || command.contains("browser", true) -> "com.android.chrome"
            command.contains("settings", true) || command.contains("pengaturan", true) -> "com.android.settings"
            else -> ""
        }
        if (pkg.isBlank()) {
            speak("I do not have a direct app shortcut for that yet.")
            voiceStatus.value = "APP SHORTCUT NOT CONFIGURED"
            return
        }
        // First look up the installed app package, then launch its APK entry point.
        // The app label lookup also handles devices where the app's package name differs.
        val candidatePackages = when (pkg) {
            "com.roblox.client" -> listOf("com.roblox.client")
            "com.google.android.youtube" -> listOf("com.google.android.youtube", "com.google.android.youtube.tv")
            "com.whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "com.android.chrome" -> listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev")
            else -> listOf(pkg)
        }
        var launch: Intent? = null
        var installedPackage = ""
        for (candidate in candidatePackages) {
            try {
                val appInfo = packageManager.getApplicationInfo(candidate, 0)
                installedPackage = appInfo.packageName
                launch = packageManager.getLaunchIntentForPackage(candidate)
                if (launch != null) break
            } catch (_: PackageManager.NameNotFoundException) {
                // Try the next known package variant.
            }
        }

        try {
            voiceStatus.value = "SEARCHING INSTALLED APPS"
            val finalIntent = launch ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(installedPackage.ifBlank { pkg })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            voiceStatus.value = "LAUNCHING REQUESTED APP"
            startActivity(finalIntent)
        } catch (_: Exception) {
            voiceStatus.value = "APP NOT FOUND"
            speak("I could not find an installed launchable copy of that app. Please check whether it is installed.")
        }
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
    onSearch: (String) -> Unit,
    onOpenApp: (String) -> Unit
) {
    var typedCommand by remember { mutableStateOf("") }
    var lastCommand by remember { mutableStateOf("Say “open Roblox” or ask me to search the web.") }
    var reply by remember { mutableStateOf("Good day. JARVIS is online. I am listening.") }
    var translation by remember { mutableStateOf("JARVIS aktif dan siap mendengarkan.") }
    val pulse = rememberInfiniteTransition(label = "orb")
    val orbScale by pulse.animateFloat(initialValue = 0.96f, targetValue = 1.04f, animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Reverse), label = "orbPulse")

    fun execute(raw: String) {
        val q = raw.trim()
        if (q.isBlank()) return
        lastCommand = q
        typedCommand = ""
        val normalized = q.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Interpret the spoken phrase itself. App names can be spoken alone or with natural Indonesian/English commands.
        val appName = when {
            Regex("\\broblox\\b").containsMatchIn(normalized) -> "roblox"
            Regex("\\byoutube\\b").containsMatchIn(normalized) -> "youtube"
            Regex("\\bwhatsapp\\b").containsMatchIn(normalized) || Regex("\\bwa\\b").containsMatchIn(normalized) -> "whatsapp"
            Regex("\\bchrome\\b").containsMatchIn(normalized) || Regex("\\bbrowser\\b").containsMatchIn(normalized) || Regex("\\bperamban\\b").containsMatchIn(normalized) -> "chrome"
            Regex("\\b(settings|setting|pengaturan)\\b").containsMatchIn(normalized) -> "settings"
            else -> ""
        }
        val appAction = Regex("\\b(buka|bukakan|jalankan|mainkan|main|open|launch|run|tolong|please|start)\\b").containsMatchIn(normalized)
        val standaloneAppRequest = appName.isNotBlank() && normalized.split(" ").all { it in setOf(appName, "tolong", "please", "ya", "dong", "sekarang", "aplikasi", "app", "the", "please") }
        if (appName.isNotBlank() && (appAction || standaloneAppRequest)) {
            onOpenApp(appName)
            val spokenName = when (appName) {
                "roblox" -> "Roblox"
                "youtube" -> "YouTube"
                "whatsapp" -> "WhatsApp"
                "chrome" -> "Chrome"
                else -> "Settings"
            }
            reply = "Opening $spokenName now."
            translation = "Membuka $spokenName sekarang."
        } else {
            reply = "I heard: $q. I will search the web for that."
            translation = "Saya mendengar: $q. Saya akan mencari di web."
            onSearch(q)
        }
        onSpeak(reply)
    }

    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank()) {
            execute(recognizedText)
            onConsumed()
        }
    }
    LaunchedEffect(Unit) { onStartListening() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101E30), Background, Color(0xFF03060C)))).padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("J.A.R.V.I.S.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("PERSONAL AI · VOICE INTERFACE", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.8.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (status.contains("LISTEN", true) || status.contains("HEARING", true)) Cyan else Color(0xFF63D6A3), CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(if (status.contains("LISTEN", true) || status.contains("HEARING", true)) "LISTENING" else "ONLINE", color = Cyan, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                    Text("ROG MOBILE CORE", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(34.dp))
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(240.dp).scale(orbScale).border(1.dp, Color(0x5539DFF9), CircleShape).background(Color(0x0828DFFF), CircleShape))
                Box(Modifier.size(200.dp).border(1.dp, Color(0x8845DDF5), CircleShape).background(Color(0x1026C8F0), CircleShape))
                Box(Modifier.size(158.dp).border(1.dp, Color(0xAA64E8FF), CircleShape).background(Brush.radialGradient(listOf(Color(0xFF5AE9FF), Color(0xFF087DAA), Color(0xFF07182E))), CircleShape), contentAlignment = Alignment.Center) {
                    Text("J", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.ExtraLight)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(status.uppercase(), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Speak naturally. Commands execute automatically.", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))

            Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xCC0D1A2A), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0x222FE2FF))) {
                Column(Modifier.padding(18.dp)) {
                    Text("YOU SAID", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text(lastCommand, color = Color(0xFFE2F5FF), fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(17.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SettingsVoice, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("JARVIS RESPONSE", color = Cyan, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(reply, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(translation, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().background(Color(0xFF0B1421), RoundedCornerShape(18.dp)).border(1.dp, Color(0x5537CDE8), RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = typedCommand, onValueChange = { typedCommand = it },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    decorationBox = { inner -> if (typedCommand.isEmpty()) Text("Type only if you prefer…", color = TextMuted, fontSize = 13.sp); inner() }
                )
                IconButton(onClick = { execute(typedCommand) }) { Icon(Icons.Default.Send, contentDescription = "Send typed command", tint = Cyan) }
            }
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Cyan, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text("HANDS-FREE MODE · FOREGROUND LISTENING", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
