package com.jarvis.mobile

import com.jarvis.mobile.device.JarvisDeviceClient
import com.jarvis.mobile.device.JarvisDeviceSyncService
import com.jarvis.mobile.device.DeviceSetupActivity
import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
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
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.jarvis.mobile.account.SupabaseJarvisClient
import com.jarvis.mobile.ui.VoiceOrbState
import com.jarvis.mobile.ui.HolographicOrb
import com.jarvis.mobile.ui.JarvisColors
import com.jarvis.mobile.ui.JarvisUserBubble
import com.jarvis.mobile.ui.JarvisAssistantBubble
import com.jarvis.mobile.ui.JarvisChatComposer
import com.jarvis.mobile.ui.JarvisChatErrorCard
import com.jarvis.mobile.ui.JarvisThinkingBar
import com.jarvis.mobile.ui.JarvisConversationChip
import com.jarvis.mobile.ui.resolveVoiceOrbState
import com.jarvis.mobile.account.SavedConversation
import com.jarvis.mobile.account.SavedMessage
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
    val images: List<Pair<String, String>> = emptyList(),
    val voiceSummary: String = ""
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

private fun detectEnglishContent(text: String): Boolean {
    val q = text.lowercase(Locale.ROOT)
    val en = listOf("the ", " and ", " what ", " how ", " why ", " please ", " find ", " search ", " explain ", " calculate ", " is ", " are ", " can ").count { q.contains(it) }
    val id = listOf("yang ", " dan ", " untuk ", " saya ", " kamu ", " tolong ", " carikan ", " jelaskan ", " berapa ", " bagaimana ", " dengan ").count { q.contains(it) }
    return en > id
}

class MainActivity : ComponentActivity() {
    private lateinit var supabaseClient: SupabaseJarvisClient
    private val accountEmail = mutableStateOf<String?>(null)
    private val accountName = mutableStateOf<String?>(null)
    private val accountBusy = mutableStateOf(false)
    private val accountStatus = mutableStateOf("")
    private val conversationsState = mutableStateOf<List<SavedConversation>>(emptyList())
    private val historyBusy = mutableStateOf(false)
    private val restoreConversation = mutableStateOf<Pair<String?, List<SavedMessage>>?>(null)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeningRestart = Runnable { startListening() }
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val voiceModeState = mutableStateOf(false)
    private val voiceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == JarvisVoiceService.ACTION_STOP) {
                stopVoiceMode()
            }
        }
    }

    private fun requestMicFocus(): Boolean {
        val manager = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
            ?: return false
        if (hasAudioFocus) return true
        val granted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        hasAudioFocus = false
                    }
                }
                .build()
                .also { audioFocusRequest = it }
            manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonMicFocus() {
        val manager = audioManager ?: return
        if (!hasAudioFocus) return
        hasAudioFocus = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(null) }
        }
    }

    private fun stopVoiceMode() {
        voiceModeEnabled = false
        voiceModeState.value = false
        handler.removeCallbacks(listeningRestart)
        recognitionActive = false
        recognitionStartPending = false
        clapDetector.reset()
        recognizer?.cancel()
        abandonMicFocus()
        stopVoiceForegroundService()
        voiceStatus.value = "MIC OFF"
    }

    private fun registerConnectivityFlush() {
        val cm = (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return
        connectivityManager = cm
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!supabaseClient.hasSession() || supabaseClient.pendingTurnCount() <= 0) return
                lifecycleScope.launch {
                    runCatching {
                        supabaseClient.flushPendingTurns()
                        conversationsState.value = supabaseClient.listConversations()
                        accountStatus.value = "ANTREAN OFFLINE TERSINKRON"
                    }
                }
            }
        }
        networkCallback = callback
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
    }

    private fun unregisterConnectivityFlush() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun startVoiceForegroundService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val intent = Intent(this, JarvisVoiceService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure { voiceStatus.value = "BACKGROUND VOICE SERVICE UNAVAILABLE" }
    }

    private fun stopVoiceForegroundService() {
        runCatching { stopService(Intent(this, JarvisVoiceService::class.java)) }
    }

    private fun signInGoogle() {
        if (accountBusy.value) return
        accountBusy.value = true
        accountStatus.value = "MENGHUBUNGKAN GOOGLE..."
        lifecycleScope.launch {
            try {
                val session = supabaseClient.signInWithGoogle(this@MainActivity)
                accountEmail.value = session.email
                accountName.value = session.displayName
                accountStatus.value = "AKUN GOOGLE TERSAMBUNG"
                refreshHistory()
            } catch (error: Exception) {
                accountStatus.value = error.message ?: "GOOGLE SIGN-IN GAGAL"
            } finally { accountBusy.value = false }
        }
    }

    private fun openDeviceSetup() {
        if (!supabaseClient.hasSession()) {
            accountStatus.value = "MASUK DENGAN GOOGLE SEBELUM MENGHUBUNGKAN PERANGKAT"
            return
        }
        if (accountBusy.value) return
        accountBusy.value = true
        lifecycleScope.launch {
            try {
                val accessToken = withContext(Dispatchers.IO) { supabaseClient.currentAccessToken() }
                startActivity(Intent(this@MainActivity, DeviceSetupActivity::class.java)
                    .putExtra(DeviceSetupActivity.EXTRA_SUPABASE_ACCESS_TOKEN, accessToken)
                    .putExtra(DeviceSetupActivity.EXTRA_SUPABASE_USER_ID, supabaseClient.currentUserId()))
            } catch (error: Exception) {
                accountStatus.value = "GAGAL MENYIAPKAN AKSES PERANGKAT: " + (error.message ?: "error")
            } finally { accountBusy.value = false }
        }
    }

    private fun signOutGoogle() {
        if (accountBusy.value) return
        accountBusy.value = true
        lifecycleScope.launch {
            try {
                supabaseClient.signOut()
                JarvisDeviceSyncService.stop(this@MainActivity)
                JarvisDeviceClient(this@MainActivity).clearAccountToken()
                accountEmail.value = null
                accountName.value = null
                conversationsState.value = emptyList()
                restoreConversation.value = null
                accountStatus.value = "KELUAR DARI AKUN"
            } catch (error: Exception) { accountStatus.value = error.message ?: "GAGAL KELUAR" }
            finally { accountBusy.value = false }
        }
    }

    private fun refreshHistory() {
        if (historyBusy.value) return
        if (!supabaseClient.hasSession()) { conversationsState.value = emptyList(); return }
        historyBusy.value = true
        lifecycleScope.launch {
            try {
                conversationsState.value = supabaseClient.listConversations()
                accountStatus.value = "RIWAYAT CHAT DIPERBARUI"
            } catch (error: Exception) { accountStatus.value = "GAGAL MEMUAT RIWAYAT: " + (error.message ?: "error") }
            finally { historyBusy.value = false }
        }
    }

    private fun openConversation(id: String) {
        if (!supabaseClient.hasSession() || historyBusy.value) return
        historyBusy.value = true
        lifecycleScope.launch {
            try {
                val messages = supabaseClient.listMessages(id)
                supabaseClient.setActiveConversation(id)
                restoreConversation.value = id to messages
                accountStatus.value = "PERCAKAPAN DIMUAT"
            } catch (error: Exception) { accountStatus.value = "GAGAL MEMBUKA CHAT: " + (error.message ?: "error") }
            finally { historyBusy.value = false }
        }
    }

    private fun newConversation() {
        lifecycleScope.launch {
            try { supabaseClient.startNewConversation(); restoreConversation.value = null to emptyList() }
            catch (error: Exception) { accountStatus.value = error.message ?: "GAGAL MEMBUAT CHAT BARU" }
        }
    }

    private fun deleteConversation(id: String) {
        if (historyBusy.value) return
        historyBusy.value = true
        lifecycleScope.launch {
            try {
                supabaseClient.deleteConversation(id)
                conversationsState.value = conversationsState.value.filterNot { it.id == id }
                accountStatus.value = "PERCAKAPAN DIHAPUS"
            } catch (error: Exception) { accountStatus.value = "GAGAL MENGHAPUS CHAT: " + (error.message ?: "error") }
            finally { historyBusy.value = false }
        }
    }

    private fun renameConversation(id: String, newTitle: String) {
        val cleaned = newTitle.trim()
        if (cleaned.isBlank() || historyBusy.value || !supabaseClient.hasSession()) return
        historyBusy.value = true
        lifecycleScope.launch {
            try {
                supabaseClient.renameConversation(id, cleaned)
                conversationsState.value = supabaseClient.listConversations()
                accountStatus.value = "JUDUL PERCAKAPAN DIPERBARUI"
            } catch (error: Exception) {
                accountStatus.value = "GAGAL MENGUBAH JUDUL: " + (error.message ?: "error")
            } finally {
                historyBusy.value = false
            }
        }
    }

    private fun saveChatTurn(userText: String, assistantText: String) {
        if (!supabaseClient.hasSession()) return
        lifecycleScope.launch {
            try {
                supabaseClient.saveTurn(userText, assistantText)
                accountStatus.value = "CHAT TERSIMPAN"
                // Refresh list only after the successful write; no polling in the background.
                conversationsState.value = supabaseClient.listConversations()
            } catch (error: Exception) { accountStatus.value = "CHAT BELUM TERSINKRON: " + (error.message ?: "error") }
        }
    }
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
    @Volatile private var suppressRecognizerCallbacks = false
    private var speechPlayer: MediaPlayer? = null
    private var voiceModeEnabled = false
    private var recognitionLanguage = "id-ID"
    private val prefs by lazy { getSharedPreferences("jarvis_secure_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supabaseClient = SupabaseJarvisClient(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ContextCompat.registerReceiver(
            this,
            voiceStopReceiver,
            IntentFilter(JarvisVoiceService.ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registerConnectivityFlush()
        accountEmail.value = supabaseClient.currentEmail()
        accountName.value = supabaseClient.currentName()
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
                        if (!geminiSpeechInProgress && voiceModeEnabled) handler.post { restartListeningSession() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!geminiSpeechInProgress && voiceModeEnabled) handler.post { restartListeningSession() }
                    }
                })
                if (voiceStatus.value == "INITIALIZING VOICE CORE") voiceStatus.value = "VOICE CORE READY"
            }
        }
        createSpeechRecognizer()
        if (supabaseClient.hasSession()) {
            lifecycleScope.launch {
                if (supabaseClient.restoreSession()) {
                    accountEmail.value = supabaseClient.currentEmail()
                    accountName.value = supabaseClient.currentName()
                    try {
                        conversationsState.value = supabaseClient.listConversations()
                        accountStatus.value = "SESI GOOGLE DIPULIHKAN"
                    } catch (error: Exception) {
                        accountStatus.value = "RIWAYAT BELUM TERMUAT: " + (error.message ?: "error")
                    }
                } else {
                    accountEmail.value = null
                    accountName.value = null
                    accountStatus.value = "SILAKAN MASUK KEMBALI DENGAN GOOGLE"
                }
            }
        }

        setContent {
            JarvisScreen(
                recognizedText = voiceText.value,
                status = voiceStatus.value,
                rmsLevel = liveRms.value,
                onConsumed = { voiceText.value = "" },
                onSpeak = { speak(it) },
                onStartListening = { startListening() },
                externalVoiceStop = !voiceModeState.value,
                onModeChanged = { voiceMode ->
                    voiceModeEnabled = voiceMode
                    voiceModeState.value = voiceMode
                    if (voiceMode) {
                        requestMicFocus()
                        startVoiceForegroundService()
                        startListening()
                        voiceStatus.value = "MICROPHONE STARTING"
                    } else {
                        stopVoiceMode()
                    }
                },
                onSearch = { _ -> voiceStatus.value = "AI COUNCIL DISCUSSION" },
                onSaveGeminiKey = { key -> prefs.edit().putString("gemini_api_key", key.trim()).apply() },
                onAskGemini = { prompt, history, callback -> askGemini(prompt, history, callback) },
                onOpenDeviceSetup = { openDeviceSetup() },
                onDeviceCommand = { action, params, callback ->
                    Thread {
                        val message = try {
                            val client = JarvisDeviceClient(this)
                            val localId = client.deviceId()
                                ?: throw IllegalStateException("Perangkat belum dipair. Buka JARVIS Device Setup terlebih dahulu.")
                            val deviceId = getSharedPreferences("jarvis_device_secure", MODE_PRIVATE)
                                .getString("target_device_id", null)?.takeIf { it.isNotBlank() } ?: localId
                            val accessToken = supabaseClient.currentAccessToken() // Refresh/validate Google session.
                            client.createOrRestoreAccountWithSupabase(accessToken) // Rotate the one-hour device-control token.
                            val userId = supabaseClient.currentUserId() ?: throw IllegalStateException("Masuk dengan Google terlebih dahulu.")
                            val queued = client.queueCommand(deviceId, action, org.json.JSONObject(params), userId)
                            if (queued.optBoolean("ok")) {
                                if (deviceId == localId) JarvisDeviceSyncService.start(this)
                                val commandId = queued.optString("commandId")
                                var completed: org.json.JSONObject? = null
                                for (attempt in 0 until 20) {
                                    Thread.sleep(1500)
                                    val state = client.commandStatus(deviceId, commandId, userId)
                                    val record = state.optJSONObject("command")
                                    if (record?.optString("status") == "completed") {
                                        completed = record
                                        break
                                    }
                                }
                                val record = completed
                                if (record != null) {
                                    val result = record.optJSONObject("result")
                                    if (result != null) "Hasil perintah ${action}: ${result}" else "Perintah ${action} selesai pada perangkat."
                                } else {
                                    "Perintah ${action} sudah masuk antrean, tetapi belum ada acknowledgement. Pastikan sinkronisasi JARVIS aktif pada perangkat target."
                                }
                            } else {
                                "Perintah tidak dapat dijalankan: ${queued.optString("reason", queued.optString("error", "izin kemampuan belum aktif"))}"
                            }
                        } catch (error: Exception) {
                            "Kontrol perangkat gagal: ${error.message ?: "koneksi atau pairing belum siap"}"
                        }
                        runOnUiThread { callback(message) }
                    }.start()
                },
                onOpenApp = { q -> openRequestedApp(q) },
                accountEmail = accountEmail.value,
                accountName = accountName.value,
                accountBusy = accountBusy.value,
                accountStatus = accountStatus.value,
                conversations = conversationsState.value,
                historyBusy = historyBusy.value,
                restoredMessages = restoreConversation.value,
                onGoogleSignIn = { signInGoogle() },
                onGoogleSignOut = { signOutGoogle() },
                onRefreshHistory = { refreshHistory() },
                onOpenConversation = { id -> openConversation(id) },
                onNewConversation = { newConversation() },
                onDeleteConversation = { id -> deleteConversation(id) },
                onRenameConversation = { id, title -> renameConversation(id, title) },
                onSaveTurn = { user, assistant -> saveChatTurn(user, assistant) }
            )
        }
    }

    private fun askGemini(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        callback: (ChatResponsePayload) -> Unit
    ): () -> Unit {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val clientRequestId = java.util.UUID.randomUUID().toString()
        Thread {
            val accessToken = runCatching { supabaseClient.currentAccessToken() }.getOrDefault("")
            var payload: ChatResponsePayload? = null
            for (attempt in 1..2) {
                if (cancelled.get()) return@Thread
                var connection: HttpURLConnection? = null
                var shouldRetry = false
                payload = try {
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
                    connection.setRequestProperty("X-Client-Request-Id", clientRequestId)
                    if (accessToken.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    }
                    val historyJson = JSONArray()
                    history.takeLast(20).forEach { (role, text) ->
                        if (text.isNotBlank()) {
                            historyJson.put(JSONObject().put("role", role).put("content", text.take(4000)))
                        }
                    }
                    val body = JSONObject()
                        .put("message", prompt)
                        .put("clientRequestId", clientRequestId)
                        .put("history", historyJson)
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
                    ChatResponsePayload(answer, parsedProducts, parsedImages, json.optString("voice_summary").trim())
                }
                } catch (e: Exception) {
                    val message = e.message.orEmpty()
                    if (attempt == 1 && !cancelled.get() && !message.contains("Jawaban terlalu besar")) {
                        shouldRetry = true
                    }
                    val err = if (message.contains("Jawaban terlalu besar")) message
                    else "Gagal menghubungi JARVIS Worker: periksa koneksi internet dan status backend."
                    ChatResponsePayload(err)
                } finally {
                    connection?.disconnect()
                }
                if (!shouldRetry) break
                try { Thread.sleep(750L) } catch (_: InterruptedException) { break }
            }
            val finalPayload = payload ?: ChatResponsePayload("Gagal menghubungi JARVIS Worker.")
            if (!cancelled.get()) handler.post { if (!cancelled.get()) callback(finalPayload) }
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
        suppressRecognizerCallbacks = true
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
                                handler.post { restartListeningSession() }
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                speechPlayer = null
                                geminiSpeechInProgress = false
                                voiceStatus.value = "AUDIO PLAYBACK ERROR · USING ANDROID TTS"
                                geminiSpeechInProgress = false
                                speakWithAndroid(cleanSpeech)
                                true
                            }
                            prepareAsync()
                        }
                    } catch (_: Exception) {
                        speechPlayer?.release()
                        speechPlayer = null
                        geminiSpeechInProgress = false
                        voiceStatus.value = "VOICE PLAYBACK ERROR · USING ANDROID TTS"
                        speakWithAndroid(cleanSpeech)
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

    private fun createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus.value = "VOICE RECOGNITION UNAVAILABLE"
            recognizer = null
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { recognitionStartPending = false; recognitionActive = true; if (voiceModeEnabled) voiceStatus.value = "LISTENING" }
                override fun onBeginningOfSpeech() { voiceStatus.value = "AUDIO INPUT ACTIVE" }
                override fun onRmsChanged(rmsdB: Float) {
                    liveRms.value = rmsdB.coerceIn(0f, 16f)
                    // RMS drives the orb only. Clap patterns are not an activation method; the exact wake phrase is mandatory.
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (voiceModeEnabled) voiceStatus.value = "PROCESSING VOICE" }
                override fun onError(error: Int) {
                    recognitionActive = false
                    recognitionStartPending = false
                    if (suppressRecognizerCallbacks || geminiSpeechInProgress || clapActivationInProgress) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        voiceStatus.value = "MICROPHONE PERMISSION REQUIRED"
                        return
                    }
                    // Android SpeechRecognizer is single-shot. Silence ends a capture session, not Voice mode.
                    voiceStatus.value = if (voiceModeEnabled) "LISTENING · RECONNECTING" else "MIC OFF"
                    if (voiceModeEnabled) {
                        handler.removeCallbacks(listeningRestart)
                        handler.postDelayed(listeningRestart, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 300L else 850L)
                    }
                }
                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    recognitionStartPending = false
                    if (suppressRecognizerCallbacks || geminiSpeechInProgress) return
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val bestIndex = if (scores != null && scores.size == candidates.size) scores.indices.maxByOrNull { scores[it] } ?: 0 else 0
                    val text = candidates.getOrNull(bestIndex).orEmpty()
                    if (text.isNotBlank()) {
                        recognitionLanguage = detectRecognitionLanguage(text)
                        voiceText.value = text
                        voiceStatus.value = "COMMAND RECEIVED"
                        // Do not reopen the microphone here. Compose first routes the transcript:
                        // wake-only -> speak confirmation; command -> execute; no wake -> resume listening.
                    } else if (voiceModeEnabled) {
                        handler.removeCallbacks(listeningRestart)
                        handler.postDelayed(listeningRestart, 300L)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) {
                        voiceStatus.value = "HEARING: ${partial.take(28)}"
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun restartListeningSession() {
        handler.removeCallbacks(listeningRestart)
        recognitionActive = false
        recognitionStartPending = false
        suppressRecognizerCallbacks = true
        if (!voiceModeEnabled || geminiSpeechInProgress || isFinishing || isDestroyed) return
        voiceStatus.value = "RECONNECTING VOICE CORE"
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            createSpeechRecognizer()
            suppressRecognizerCallbacks = false
        }.onFailure {
            voiceStatus.value = "VOICE CORE RESTARTING"
        }
        handler.postDelayed({
            if (voiceModeEnabled && !geminiSpeechInProgress) startListening()
        }, 650L)
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
            requestMicFocus()
            recognitionStartPending = true
            voiceStatus.value = "CONNECTING MICROPHONE"
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
            suppressRecognizerCallbacks = false
            recognitionStartPending = false
            recognitionActive = false
            voiceStatus.value = "VOICE CORE RESTARTING"
            if (voiceModeEnabled) {
                handler.removeCallbacks(listeningRestart)
                handler.postDelayed(listeningRestart, 1000L)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                voiceStatus.value = "MICROPHONE READY"
                if (voiceModeEnabled) handler.post { startVoiceForegroundService(); startListening() }
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
        val explicitPackage = cleaned.removePrefix("package ").takeIf {
            cleaned.startsWith("package ") && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
        }
        val directPackage = explicitPackage ?: aliases[cleaned]
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

    override fun onPause() {
        // Keep the user-enabled Voice session alive when the app is backgrounded.
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (recognizer != null && voiceModeEnabled && !recognitionActive && !recognitionStartPending && !geminiSpeechInProgress) {
            handler.postDelayed({ restartListeningSession() }, 500)
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(voiceStopReceiver) }
        unregisterConnectivityFlush()
        abandonMicFocus()
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
private fun ImageVisualStrip(images: List<Pair<String, String>>) {
    if (images.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(images.size) { index ->
            val (title, imageUrl) = images[index]
            Column(
                Modifier
                    .width(196.dp)
                    .clip(CardShape)
                    .background(Color(0xFF081725))
                    .border(1.dp, Cyan.copy(alpha = 0.32f), CardShape)
                    .clickable { runCatching { uriHandler.openUri(imageUrl) } }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(9.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Text(
                    title.ifBlank { "Referensi visual" },
                    color = Ice,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text("BUKA SUMBER", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
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
    externalVoiceStop: Boolean = false,
    onConsumed: () -> Unit,
    onSpeak: (String) -> Unit,
    onStartListening: () -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onSaveGeminiKey: (String) -> Unit,
    onAskGemini: (String, List<Pair<String, String>>, (ChatResponsePayload) -> Unit) -> (() -> Unit),
    onOpenDeviceSetup: () -> Unit,
    onDeviceCommand: (String, Map<String, String>, (String) -> Unit) -> Unit,
    onOpenApp: (String) -> Boolean,
    accountEmail: String?,
    accountName: String?,
    accountBusy: Boolean,
    accountStatus: String,
    conversations: List<SavedConversation>,
    historyBusy: Boolean,
    restoredMessages: Pair<String?, List<SavedMessage>>?,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onRefreshHistory: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onSaveTurn: (String, String) -> Unit
) {
    data class Attachment(val uri: Uri, val name: String, val mime: String)
    data class Line(
        val user: Boolean,
        val text: String,
        val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
        val attachments: List<Attachment> = emptyList(),
        val products: List<VisualProduct> = emptyList(),
        val images: List<Pair<String, String>> = emptyList(),
        val displayText: MutableState<String> = mutableStateOf(if (user) text else ""),
        val revealed: MutableState<Boolean> = mutableStateOf(true)
    )

    var page by remember { mutableStateOf("CHAT") }
    var voiceListening by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceAnswer by remember { mutableStateOf("") }
    var voiceStatusNote by remember { mutableStateOf("") }
    var wakeAcknowledged by remember { mutableStateOf(false) }
    var awaitingCommandAfterWake by remember { mutableStateOf(false) }
    val voiceProducts = remember { mutableStateListOf<VisualProduct>() }
    val voiceImages = remember { mutableStateListOf<Pair<String, String>>() }

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
    var accountPanel by remember { mutableStateOf(false) }
    var historyPanel by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Line>() }
    LaunchedEffect(restoredMessages?.first) {
        val restored = restoredMessages ?: return@LaunchedEffect
        lines.clear()
        restored.second.forEach { message ->
            val row = Line(user = message.role == "user", text = message.content)
            row.displayText.value = message.content
            row.revealed.value = true
            lines.add(row)
        }
        historyPanel = false
    }
    val revealScope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var followLatest by remember { mutableStateOf(true) }

    val transition = rememberInfiniteTransition(label = "hud_transition")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(15000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "spin")
    val breathe by transition.animateFloat(.95f, 1.05f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val wavePhase by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "wavePhase")

    fun showJarvis(
        answer: String,
        products: List<VisualProduct> = emptyList(),
        images: List<Pair<String, String>> = emptyList()
    ) {
        val responseLine = Line(user = false, text = answer, products = products, images = images)
        responseLine.displayText.value = answer
        responseLine.revealed.value = true
        lines.add(responseLine)
    }

    fun buildSpokenReply(request: String, answer: String, products: List<VisualProduct>): String {
        // The backend answer already carries JARVIS's personality and context.
        // Do not prepend repetitive canned acknowledgements to every spoken result.
        if (products.isEmpty()) return answer.trim().take(1800)
        val english = detectEnglishContent(request)
        val cheapest = products.minByOrNull { it.price.replace(Regex("[^0-9]"), "").toLongOrNull() ?: Long.MAX_VALUE }
        val lead = if (english) "Sir, I found ${products.size} relevant options." else "Sir, saya menemukan ${products.size} pilihan yang relevan."
        val detail = cheapest?.let {
            if (english) " The lowest listed price is ${it.price} for ${it.title}, from ${it.source}."
            else " Harga terendah yang tercantum adalah ${it.price} untuk ${it.title}, dari ${it.source}."
        }.orEmpty()
        val closing = if (english) " I have displayed the available options on your screen." else " Semua pilihannya sudah saya tampilkan di layar."
        return (lead + detail + closing).take(900)
    }
    fun parseDeviceCommand(raw: String): Pair<String, Map<String, String>>? {
        // Normalize punctuation from typed text and speech transcripts ("Jarvis, buka Roblox").
        // Keep dots intact so explicit Android package names remain parseable.
        val value = raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("[,!?;:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when (value) {
            "status perangkat", "cek status perangkat", "status hp", "cek baterai", "berapa baterai",
            "cek kondisi hp", "info perangkat" ->
                "system.status" to emptyMap()
            "daftar aplikasi", "list aplikasi", "aplikasi terpasang", "list apps", "lihat aplikasi" ->
                "apps.list" to emptyMap()
            "lihat notifikasi", "cek notifikasi", "notifikasi terbaru", "daftar notifikasi" ->
                "notifications.list" to emptyMap()
            "daftar file", "list file", "lihat file", "file saya" ->
                "files.list" to emptyMap()
            "baca layar", "baca isi layar", "apa yang tampil di layar", "ringkas layar", "lihat isi layar" ->
                "screen.read" to emptyMap()
            "daftar kontak", "lihat kontak", "cari kontak saya" ->
                "contacts.list" to emptyMap()
            "jadwal kalender", "agenda saya", "lihat kalender", "acara mendatang" ->
                "calendar.list" to emptyMap()
            else -> {
                val search = Regex("^(?:jarvis )?(?:cari|search) (?:file|berkas|dokumen) (.+)$").matchEntire(value)
                val packageLaunch = Regex("^(?:jarvis )?(?:buka|jalankan|open|launch) package ([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)$").matchEntire(value)
                val appLaunch = Regex("^(?:jarvis )?(?:tolong )?(?:buka|jalankan|open|launch) (?:(?:aplikasi|app) )?(.+)$").matchEntire(value)
                when {
                    search != null -> {
                        val query = search.groupValues[1].trim().take(200)
                        if (query.isBlank()) null else "files.search" to mapOf("query" to query)
                    }
                    packageLaunch != null ->
                        "apps.launch" to mapOf("packageName" to packageLaunch.groupValues[1])
                    appLaunch != null -> {
                        val appName = appLaunch.groupValues[1].trim().removeSuffix(" dong").trim()
                        if (appName.isBlank() || appName.length > 100) null
                        else {
                            val packageName = when (appName) {
                                "roblox" -> "com.roblox.client"
                                "instagram" -> null // Resolve by installed launcher label; package can vary by device/profile.
                                "whatsapp" -> "com.whatsapp"
                                "youtube" -> "com.google.android.youtube"
                                "chrome", "google chrome" -> "com.android.chrome"
                                else -> null
                            }
                            if (packageName != null) "apps.launch" to mapOf("packageName" to packageName)
                            else "apps.launch_label" to mapOf("appName" to appName)
                        }
                    }
                    else -> null
                }
            }
        }
    }

    fun send(raw: String, voice: Boolean = false) {
        val q = raw.trim()
        val files = pendingAttachments.toList()
        if ((q.isEmpty() && files.isEmpty()) || busy) return
        command = ""
        pendingAttachments.clear()
        val attachmentDetails = if (files.isEmpty()) "" else buildString {
            append("\n\nLampiran: ")
            append(files.joinToString("; ") { "${it.name} (${it.mime})" })
            files.forEach { file ->
                if (file.mime.startsWith("text/") || file.mime in setOf("application/json", "application/xml") ||
                    file.name.endsWith(".md", true) || file.name.endsWith(".txt", true) || file.name.endsWith(".csv", true) ||
                    file.name.endsWith(".kt", true) || file.name.endsWith(".js", true) || file.name.endsWith(".py", true)
                ) {
                    val snippet = runCatching {
                        context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val buf = CharArray(16384)
                            val read = reader.read(buf)
                            if (read > 0) String(buf, 0, read) else ""
                        }
                    }.getOrNull().orEmpty().trim()
                    if (snippet.isNotBlank()) {
                        append("\n\n[Isi Lampiran ${file.name}]:\n")
                        append(snippet)
                    }
                } else if (file.mime.startsWith("image/")) {
                    val dims = runCatching {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth}x${opts.outHeight}px" else ""
                    }.getOrNull().orEmpty()
                    if (dims.isNotBlank()) append(" [Resolusi gambar: $dims]")
                }
            }
        }
        val requestText = (q.ifBlank { "Tolong analisis lampiran ini dan jelaskan isinya." }) + attachmentDetails
        val priorHistory = lines.takeLast(16).map { (if (it.user) "user" else "assistant") to it.text }
        if (voice) {
            voiceTranscript = q
            voiceAnswer = ""
            voiceProducts.clear()
        } else {
            lines.add(Line(user = true, text = q.ifBlank { "Tolong analisis lampiran ini dan jelaskan isinya." }, attachments = files))
        }

        val norm = q.lowercase(Locale.ROOT)
        if (norm in setOf("jarvis", "hai jarvis", "hello jarvis", "halo jarvis", "hey jarvis", "open", "buka")) return
        val deviceCommand = parseDeviceCommand(q)
        if (deviceCommand != null) {
            val (action, params) = deviceCommand
            // Launching an app on this same phone is a local Android operation.
            // It must not require account pairing or a remote device-control token.
            if (action == "apps.launch" || action == "apps.launch_label") {
                val appTarget = params["appName"] ?: params["packageName"].orEmpty()
                val opened = onOpenApp(appTarget)
                val result = if (opened) "Aplikasi $appTarget berhasil dibuka di perangkat ini."
                    else "Aplikasi $appTarget tidak ditemukan atau tidak dapat dibuka di perangkat ini."
                if (voice) {
                    voiceAnswer = result
                    onSpeak(result)
                } else {
                    showJarvis(result)
                }
                return
            }
            busy = true
            processStage = "MENGIRIM PERINTAH KE PERANGKAT..."
            onDeviceCommand(action, params) { result ->
                if (voice) {
                    voiceAnswer = result
                    onSpeak(result)
                } else {
                    showJarvis(result)
                }
                busy = false
                processStage = "SIAP"
            }
            return
        }
        // Do not infer app-launch actions from ordinary words such as "open" or short utterances.
        // App launching is handled only by the explicit "Jarvis, open" voice-wake branch above.
        busy = true
        onSearch(q)
        cancelCurrentRequest = onAskGemini(requestText, priorHistory) { payload ->
            val answer = payload.answer.substringAfter("KESIMPULAN JARVIS", payload.answer)
                .substringAfter("JAWABAN AKHIR", payload.answer).trim().ifBlank { payload.answer.trim() }
            if (!voice && accountEmail != null) onSaveTurn(requestText, answer)
            if (voice) {
                voiceAnswer = answer
                voiceProducts.clear()
                voiceProducts.addAll(payload.products)
                voiceImages.clear()
                voiceImages.addAll(payload.images)
            } else {
                val responseLine = Line(user = false, text = answer, products = payload.products, images = payload.images)
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
            if (voice) onSpeak(payload.voiceSummary.ifBlank { buildSpokenReply(q, answer, payload.products) })
        }
    }

    // A bare wake word arms a short continuation window so the user can pause naturally
    // before speaking the actual command, without JARVIS talking over them.
    LaunchedEffect(awaitingCommandAfterWake, page, voiceListening) {
        if (awaitingCommandAfterWake && page == "VOICE" && voiceListening) {
            kotlinx.coroutines.delay(2500)
            awaitingCommandAfterWake = false
        }
    }

    LaunchedEffect(externalVoiceStop) {
        if (externalVoiceStop && voiceListening) {
            voiceListening = false
        }
    }

    // Voice remains silent until the user says JARVIS, then waits briefly for the command.
    // Only the exact wake phrase activates a session. Once active, each spoken command must address JARVIS by name.
    LaunchedEffect(recognizedText, page, voiceListening) {
        if (page == "VOICE" && voiceListening && recognizedText.isNotBlank()) {
            val heard = recognizedText.trim()
            onConsumed()
            val activation = Regex("(?i)^\\s*jarvis[, ]+open[.!?]*\\s*(.*)$").matchEntire(heard)
            val addressed = Regex("(?i)^\\s*jarvis[, ]+(.+)$").matchEntire(heard)

            if (!wakeAcknowledged) {
                if (activation == null) {
                    voiceStatusNote = "STANDBY · SAY JARVIS, OPEN"
                    kotlinx.coroutines.delay(180)
                    onStartListening()
                    return@LaunchedEffect
                }
                wakeAcknowledged = true
                voiceStatusNote = "JARVIS ACTIVE · LISTENING"
                val commandAfterActivation = activation.groupValues[1].trim()
                if (commandAfterActivation.isBlank()) {
                    val readyReply = if (detectEnglishContent(heard)) {
                        "At your service, Sir. JARVIS is active and listening."
                    } else {
                        "Siap, Sir. J.A.R.V.I.S. aktif dan siap menerima perintah."
                    }
                    onSpeak(readyReply)
                    return@LaunchedEffect
                }
                if (commandAfterActivation.isNotBlank()) send(commandAfterActivation, voice = true)
                return@LaunchedEffect
            }

            if (addressed == null) {
                voiceStatusNote = "ACTIVE · WAITING FOR JARVIS COMMAND"
                kotlinx.coroutines.delay(180)
                onStartListening()
                return@LaunchedEffect
            }

            val request = addressed.groupValues[1].trim()
            if (request.isBlank()) {
                kotlinx.coroutines.delay(180)
                onStartListening()
                return@LaunchedEffect
            }
            if (Regex("(?i)^(close|stop listening|matikan mic|matikan mikrofon|standby|tidur|sleep|istirahat)$").matches(request)) {
                wakeAcknowledged = false
                voiceStatusNote = "STANDBY · SAY JARVIS, OPEN"
                voiceListening = false
                return@LaunchedEffect
            }

            val appCommand = Regex("(?i)^(open|buka)(kan)?\\s+(.+)$").matchEntire(request)
            if (appCommand != null) {
                val appName = appCommand.groupValues[3].trim()
                val opened = onOpenApp(appName)
                val reply = if (detectEnglishContent(request)) {
                    if (opened) "Certainly, Sir. $appName is open." else "I could not find $appName on this device, Sir."
                } else {
                    if (opened) "Siap, Sir. $appName sudah dibuka." else "Maaf, Sir. $appName tidak ditemukan di perangkat ini."
                }
                onSpeak(reply)
            } else {
                send(request, voice = true)
            }
        }
    }
    LaunchedEffect(page, voiceListening) {
        val voiceOn = page == "VOICE" && voiceListening
        if (!voiceOn) wakeAcknowledged = false
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
            JarvisBackdrop(
                ambience = if (phase == "IDLE") 0.82f else 1f,
                modifier = Modifier.fillMaxSize()
            )

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
                    JarvisTopBar(
                        status = if (page == "VOICE" && voiceListening) voiceStatusNote else "SYSTEM ONLINE",
                        busy = busy,
                        onSettings = { keyDraft = ""; settings = true }
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (accountEmail != null) (accountName ?: accountEmail).take(20) else "ACCOUNT · SIGN IN",
                            color = if (accountEmail != null) Color(0xFF78DDB4) else JarvisPalette.Cyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFF0A2236))
                                .border(1.dp, Color(0x4456C8DC), RoundedCornerShape(9.dp))
                                .clickable { accountPanel = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                        Text(
                            "HISTORY",
                            color = JarvisPalette.Muted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0x660A1721))
                                .clickable { historyPanel = true; onRefreshHistory() }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        if (page == "CHAT" && lines.isNotEmpty()) {
                            Text(
                                "NEW CHAT",
                                color = JarvisPalette.Cyan,
                                fontSize = 9.sp,
                                modifier = Modifier.clickable { lines.clear(); onNewConversation() }
                                    .padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                    }

                    JarvisModeSwitcher(
                        selected = page,
                        onSelected = { selected ->
                            if (selected == "CHAT") {
                                voiceListening = false
                                page = "CHAT"
                            } else {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                voiceListening = true
                                page = "VOICE"
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, JarvisPalette.Cyan.copy(alpha = 0.55f), Color.Transparent)))
                    )

                    // ==========================================
                    // VOICE MODE UI (Exact Match to Reference 2)
                    // ==========================================
                    if (page == "VOICE") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
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
                                    .height(if (compact) 190.dp else 250.dp)
                                    .animateContentSize(animationSpec = tween(420)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.offset(y = hologramShift.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val orbState = resolveVoiceOrbState(
                                        voiceEnabled = voiceListening,
                                        recognitionActive = phase == "LISTEN",
                                        processing = phase == "THINK",
                                        speaking = phase == "SPEAK",
                                        error = status.contains("ERROR", true) || status.contains("DENIED", true)
                                    )
                                    Box(contentAlignment = Alignment.Center) {
                                        HolographicOrb(
                                            state = orbState,
                                            rmsLevel = rmsLevel,
                                            size = if (busy || voiceAnswer.isNotBlank() || voiceProducts.isNotEmpty()) 136.dp else if (compact) 154.dp else 194.dp
                                        )
                                        VoiceHudReactor(
                                            modifier = Modifier.size(if (busy || voiceAnswer.isNotBlank() || voiceProducts.isNotEmpty()) 132.dp else if (compact) 150.dp else 190.dp),
                                            phase = phase,
                                            spin = spin,
                                            breathe = breathe
                                        )
                                    }
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
                                        text = when {
                                            busy -> "PROCESSING"
                                            status.contains("SPEAK", true) -> "SPEAKING"
                                            page == "VOICE" && voiceListening && !wakeAcknowledged -> "STANDBY · SAY JARVIS, OPEN"
                                            page == "VOICE" && voiceListening && wakeAcknowledged -> "JARVIS ACTIVE · LISTENING"
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
                                    }
                                    if (voiceImages.isNotEmpty()) {
                                        ImageVisualStrip(voiceImages)
                                    }
                                    if (voiceProducts.isEmpty() && voiceImages.isEmpty() && voiceAnswer.isNotBlank()) {
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
                                                        showJarvis(voiceAnswer, voiceProducts.toList(), voiceImages.toList())
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
                                    JarvisWelcomeHero(
                                        compact = compact,
                                        onAction = { selectedPrompt -> send(selectedPrompt, voice = false) }
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
                                                        if (line.images.isNotEmpty()) {
                                                            ImageVisualStrip(line.images)
                                                        }
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

                }

                if (accountPanel) {
                    AlertDialog(
                        onDismissRequest = { accountPanel = false },
                        containerColor = Color(0xFF0B1826),
                        title = { Text("J.A.R.V.I.S. ACCOUNT", color = Ice, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(accountEmail ?: "Belum masuk dengan Google", color = Ice, fontSize = 13.sp)
                                if (accountStatus.isNotBlank()) Text(accountStatus, color = TextMuted, fontSize = 11.sp)
                                Text(if (accountEmail == null) "Masuk dengan Google untuk menyimpan dan menyinkronkan chat antarperangkat." else "Riwayat percakapan tersimpan di akun ini dan dibatasi oleh Row Level Security.", color = TextMuted, fontSize = 12.sp)
                            }
                        },
                        confirmButton = { TextButton(enabled = !accountBusy, onClick = { if (accountEmail == null) onGoogleSignIn() else onGoogleSignOut() }) { Text(if (accountEmail == null) "MASUK DENGAN GOOGLE" else "KELUAR", color = Cyan) } },
                        dismissButton = { TextButton(onClick = { accountPanel = false }) { Text("TUTUP", color = TextMuted) } }
                    )
                }

                var renameTarget by remember { mutableStateOf<SavedConversation?>(null) }
                var renameDraft by remember { mutableStateOf("") }

                if (renameTarget != null) {
                    val target = renameTarget!!
                    AlertDialog(
                        onDismissRequest = { renameTarget = null },
                        containerColor = Color(0xFF0B1826),
                        title = { Text("UBAH JUDUL PERCAKAPAN", color = Ice, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                        text = {
                            OutlinedTextField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                singleLine = true,
                                label = { Text("Judul Baru") }
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = renameDraft.trim().isNotBlank(),
                                onClick = {
                                    onRenameConversation(target.id, renameDraft.trim())
                                    renameTarget = null
                                }
                            ) { Text("SIMPAN", color = Cyan) }
                        },
                        dismissButton = {
                            TextButton(onClick = { renameTarget = null }) { Text("BATAL", color = TextMuted) }
                        }
                    )
                }

                if (historyPanel) {
                    AlertDialog(
                        onDismissRequest = { historyPanel = false },
                        containerColor = Color(0xFF0B1826),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("CHAT HISTORY", color = Ice, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = onRefreshHistory) { Text("REFRESH", color = Cyan, fontSize = 10.sp) }
                            }
                        },
                        text = {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                when {
                                    accountEmail == null -> Text("Masuk dengan Google untuk melihat riwayat chat.", color = TextMuted, fontSize = 12.sp)
                                    historyBusy -> JarvisThinkingBar(stage = "MEMUAT PERCAKAPAN...")
                                    conversations.isEmpty() -> Text("Belum ada percakapan tersimpan.", color = TextMuted, fontSize = 12.sp)
                                    else -> conversations.forEach { item ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF102536))
                                                .border(1.dp, Color(0x3358DCEC), RoundedCornerShape(10.dp))
                                                .clickable { onOpenConversation(item.id); historyPanel = false }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(Modifier.weight(1f)) {
                                                JarvisConversationChip(
                                                    title = item.title,
                                                    updatedAt = item.updatedAt.take(16).replace("T", " "),
                                                    selected = restoredMessages?.first == item.id,
                                                    onClick = { onOpenConversation(item.id); historyPanel = false }
                                                )
                                            }
                                            TextButton(onClick = { renameDraft = item.title; renameTarget = item }) {
                                                Text("UBAH", color = Cyan, fontSize = 9.sp)
                                            }
                                            TextButton(onClick = { onDeleteConversation(item.id) }) {
                                                Text("HAPUS", color = Color(0xFFFF8A8A), fontSize = 9.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { onNewConversation(); historyPanel = false }) { Text("CHAT BARU", color = Cyan) } },
                        dismissButton = { TextButton(onClick = { historyPanel = false }) { Text("TUTUP", color = TextMuted) } }
                    )
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
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = keyDraft,
                                    onValueChange = { keyDraft = it },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    placeholder = { Text("Kunci API Gemini", color = TextMuted) },
                                    label = { Text("API Key Override") }
                                )
                                OutlinedButton(
                                    onClick = { settings = false; onOpenDeviceSetup() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Devices, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("AKUN GOOGLE & AKSES PERANGKAT")
                                }
                            }
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