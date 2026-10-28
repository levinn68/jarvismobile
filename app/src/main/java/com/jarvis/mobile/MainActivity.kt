package com.jarvis.mobile

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Navy = Color(0xFF07111F)
private val Cyan = Color(0xFF49E8FF)
private val Muted = Color(0xFF9EB5C9)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.UK
        }
        setContent { JarvisScreen(
            onSpeak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response") },
            onSearch = { q -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))) },
            onOpenApp = { q ->
                val pkg = when {
                    q.contains("roblox", true) -> "com.roblox.client"
                    q.contains("youtube", true) -> "com.google.android.youtube"
                    q.contains("whatsapp", true) -> "com.whatsapp"
                    q.contains("chrome", true) || q.contains("browser", true) -> "com.android.chrome"
                    else -> ""
                }
                if (pkg.isNotEmpty()) {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) startActivity(launch)
                }
            }
        ) }
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun JarvisScreen(onSpeak: (String) -> Unit, onSearch: (String) -> Unit, onOpenApp: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("SYSTEM ONLINE") }
    var english by remember { mutableStateOf("Good day. JARVIS is ready. How may I assist you?") }
    var indo by remember { mutableStateOf("JARVIS siap. Apa yang bisa saya bantu?") }
    var busy by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) query = spoken
        status = if (spoken.isNullOrBlank()) "VOICE INPUT CANCELLED" else "VOICE INPUT RECEIVED"
    }
    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        when {
            q.startsWith("open ", true) || q.startsWith("buka ", true) -> {
                status = "OPENING APP"
                onOpenApp(q)
                english = "I attempted to open the requested app. Android may require the app to be installed."
                indo = "Saya mencoba membuka aplikasi yang diminta. Aplikasi tersebut harus sudah terpasang."
            }
            else -> {
                status = "SEARCH REQUEST SENT"
                english = "I have opened a web search for: $q. Search results are provided by your browser."
                indo = "Saya membuka pencarian web untuk: $q. Hasil pencarian ditampilkan melalui browser."
                onSearch(q)
            }
        }
        onSpeak(english)
        query = ""
        busy = false
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Color(0xFF101F31))) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10243A), Navy, Color(0xFF03070D))))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("J.A.R.V.I.S.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("PERSONAL INTELLIGENCE SYSTEM", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
                Surface(color = Color(0xFF143A49), shape = RoundedCornerShape(50.dp)) {
                    Text("● ONLINE", color = Cyan, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(36.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
                Surface(modifier = Modifier.size(214.dp), shape = CircleShape, color = Color(0x2225DFFF), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6649E8FF))) {}
                Surface(modifier = Modifier.size(174.dp), shape = CircleShape, color = Color(0x3325DFFF), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xAA49E8FF))) {}
                Box(modifier = Modifier.size(126.dp).background(Brush.radialGradient(listOf(Color(0xFFB8FAFF), Color(0xFF16A9D5), Color(0xFF063B65))), CircleShape), contentAlignment = Alignment.Center) {
                    Text("J", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Light)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(status, color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xDD102034)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("JARVIS", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(english, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0x3349E8FF))
                    Spacer(Modifier.height(10.dp))
                    Text("INDONESIAN TRANSLATION", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(indo, color = Color(0xFFD4E3F0), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask or say: Open Roblox…", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Cyan) },
                trailingIcon = { IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
                    }
                    try { speechLauncher.launch(intent) } catch (_: Exception) { status = "VOICE INPUT UNAVAILABLE" }
                }) { Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = Cyan) } },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Cyan, unfocusedBorderColor = Color(0x665A839C)),
                shape = RoundedCornerShape(16.dp), singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("EXECUTE COMMAND", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("QUICK COMMANDS", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Search web" to "latest technology news", "Open Roblox" to "open Roblox").forEach { (label, value) ->
                    OutlinedButton(onClick = { query = value; if (value.startsWith("open", true)) { onOpenApp(value); status = "OPENING APP" } else { onSearch(value); status = "SEARCH REQUEST SENT" } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Prototype • Web searches open in your browser • AI backend setup required", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}
