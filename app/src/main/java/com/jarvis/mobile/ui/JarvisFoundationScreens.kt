package com.jarvis.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Storyboard Sheet 1 (Screens 1–10):
 * 1. Splash Screen (v8.0.0)
 * 2. Welcome / Onboarding
 * 3. Login ke J.A.R.V.I.S. (Google, GitHub, Email, Guest)
 * 4. Login Sukses
 * 5. System Initialization (42%)
 * 6. Core Formation (68%)
 * 7. Core Ready (Core Online)
 * 8. Fitur: Chat (55%)
 * 9. Fitur: Voice & Kolaborasi (78%)
 * 10. Fitur Lengkap (Beranda Dashboard + 5-Tab Bottom Bar)
 */
enum class JarvisOnboardingStage {
    SPLASH,
    WELCOME,
    LOGIN,
    LOGIN_SUCCESS,
    INIT_SYSTEM,
    INIT_CORE_FORMATION,
    INIT_CORE_READY,
    INIT_FEATURE_CHAT,
    INIT_FEATURE_VOICE_COLLAB,
    COMPLETED
}

@Composable
fun JarvisB8OnboardingFlow(
    initialStage: JarvisOnboardingStage = JarvisOnboardingStage.SPLASH,
    isAuthenticated: Boolean = false,
    onGoogleLoginClick: () -> Unit,
    onGitHubLoginClick: () -> Unit = {},
    onEmailLoginClick: () -> Unit = {},
    onGuestContinueClick: () -> Unit,
    onFlowCompleted: () -> Unit
) {
    var stage by remember { mutableStateOf(initialStage) }

    LaunchedEffect(stage, isAuthenticated) {
        when (stage) {
            JarvisOnboardingStage.SPLASH -> {
                delay(900)
                stage = if (isAuthenticated) JarvisOnboardingStage.INIT_SYSTEM else JarvisOnboardingStage.WELCOME
            }
            JarvisOnboardingStage.LOGIN_SUCCESS -> {
                delay(750)
                stage = JarvisOnboardingStage.INIT_SYSTEM
            }
            JarvisOnboardingStage.INIT_SYSTEM -> {
                delay(650)
                stage = JarvisOnboardingStage.INIT_CORE_FORMATION
            }
            JarvisOnboardingStage.INIT_CORE_FORMATION -> {
                delay(650)
                stage = JarvisOnboardingStage.INIT_CORE_READY
            }
            JarvisOnboardingStage.INIT_CORE_READY -> {
                delay(650)
                stage = JarvisOnboardingStage.INIT_FEATURE_CHAT
            }
            JarvisOnboardingStage.INIT_FEATURE_CHAT -> {
                delay(650)
                stage = JarvisOnboardingStage.INIT_FEATURE_VOICE_COLLAB
            }
            JarvisOnboardingStage.INIT_FEATURE_VOICE_COLLAB -> {
                delay(700)
                stage = JarvisOnboardingStage.COMPLETED
                onFlowCompleted()
            }
            else -> Unit
        }
    }

    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            fadeIn(tween(JarvisMotion.StandardMs, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(tween(JarvisMotion.FastMs))
        },
        label = "b8_onboarding_flow"
    ) { currentStage ->
        when (currentStage) {
            JarvisOnboardingStage.SPLASH -> JarvisSplashScreen()
            JarvisOnboardingStage.WELCOME -> JarvisWelcomeOnboardingScreen(
                onStartClick = { stage = JarvisOnboardingStage.LOGIN }
            )
            JarvisOnboardingStage.LOGIN -> JarvisLoginScreen(
                onGoogleLogin = {
                    onGoogleLoginClick()
                    stage = JarvisOnboardingStage.LOGIN_SUCCESS
                },
                onGitHubLogin = {
                    onGitHubLoginClick()
                    stage = JarvisOnboardingStage.LOGIN_SUCCESS
                },
                onEmailLogin = {
                    onEmailLoginClick()
                    stage = JarvisOnboardingStage.LOGIN_SUCCESS
                },
                onGuestLogin = {
                    onGuestContinueClick()
                    stage = JarvisOnboardingStage.LOGIN_SUCCESS
                },
                onBack = { stage = JarvisOnboardingStage.WELCOME }
            )
            JarvisOnboardingStage.LOGIN_SUCCESS -> JarvisLoginSuccessScreen()
            JarvisOnboardingStage.INIT_SYSTEM -> JarvisSystemInitStageScreen(
                stepNumber = 5,
                title = "INITIALIZING J.A.R.V.I.S.",
                progress = 0.42f,
                items = listOf(
                    "Memuat konfigurasi akun ..." to true,
                    "Menghubungkan layanan ..." to false,
                    "Menyiapkan modul AI ..." to false,
                    "Membangun antarmuka ..." to false
                )
            )
            JarvisOnboardingStage.INIT_CORE_FORMATION -> JarvisSystemInitStageScreen(
                stepNumber = 6,
                title = "Membangun Core AI ...",
                progress = 0.68f,
                items = listOf(
                    "Core Engine" to true,
                    "AI Orchestration" to true,
                    "Service Layer" to false
                )
            )
            JarvisOnboardingStage.INIT_CORE_READY -> JarvisSystemInitStageScreen(
                stepNumber = 7,
                title = "J.A.R.V.I.S. — Core Online",
                progress = 1.0f,
                items = listOf(
                    "Core Engine" to true,
                    "AI Orchestration" to true,
                    "Service Layer" to true
                )
            )
            JarvisOnboardingStage.INIT_FEATURE_CHAT -> JarvisFeatureActivationScreen(
                stepNumber = 8,
                activeFeature = "Chat",
                statusLabel = "Mengaktifkan Chat Mode ...",
                progress = 0.55f
            )
            JarvisOnboardingStage.INIT_FEATURE_VOICE_COLLAB -> JarvisFeatureActivationScreen(
                stepNumber = 9,
                activeFeature = "Voice & Kolaborasi",
                statusLabel = "Menambahkan Voice & Kolaborasi ...",
                progress = 0.78f
            )
            JarvisOnboardingStage.COMPLETED -> Box(Modifier.fillMaxSize().background(JarvisColors.Background))
        }
    }
}

@Composable
fun JarvisSplashScreen() {
    val infinite = rememberInfiniteTransition(label = "splash_ring")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "splash_spin"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HolographicOrb(state = VoiceOrbState.Idle, size = 180.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = "J.A.R.V.I.S.",
            color = JarvisColors.Ice,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Multi-AI Intelligence Platform",
            color = JarvisColors.TextMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(36.dp))
        Text(
            text = "Initializing...",
            color = JarvisColors.Cyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .width(200.dp)
                .height(6.dp)
                .clip(JarvisShapes.Chip),
            color = JarvisColors.Cyan,
            trackColor = JarvisColors.PanelSolid
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "v8.0.0",
            color = JarvisColors.TextDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun JarvisWelcomeOnboardingScreen(onStartClick: () -> Unit) {
    val features = remember {
        listOf(
            Icons.Default.Hub to "Multi-AI & Multi-Model",
            Icons.Default.Groups to "Kolaborasi Antar-AI",
            Icons.Default.Extension to "MCP & Skills Ecosystem",
            Icons.Default.PhoneAndroid to "Kontrol Perangkat Android"
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(22.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Welcome to\nJ.A.R.V.I.S.",
            color = JarvisColors.Ice,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Satu platform untuk Multi-AI, kolaborasi cerdas, MCP, Skills, dan kontrol perangkat Android.",
            color = JarvisColors.TextMuted,
            fontSize = 13.5.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(22.dp))
        features.forEach { (icon, title) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(JarvisShapes.CardSm)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.CyanBorder, JarvisShapes.CardSm)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = title, tint = JarvisColors.Cyan, modifier = Modifier.size(22.dp))
                Text(title, color = JarvisColors.Ice, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(4) { idx ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (idx == 0) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (idx == 0) JarvisColors.Cyan else JarvisColors.TextDim)
                )
            }
        }
        Button(
            onClick = onStartClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = JarvisShapes.Chip,
            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Cyan)
        ) {
            Text("Mulai  →", color = Color(0xFF03101C), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun JarvisLoginScreen(
    onGoogleLogin: () -> Unit,
    onGitHubLogin: () -> Unit,
    onEmailLogin: () -> Unit,
    onGuestLogin: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(22.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = JarvisColors.Ice)
            }
        }
        HolographicOrb(state = VoiceOrbState.Idle, size = 96.dp)
        Spacer(Modifier.height(14.dp))
        Text("Login ke J.A.R.V.I.S.", color = JarvisColors.Ice, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Masuk dengan akun Anda untuk menyinkronkan data dan konfigurasi.",
            color = JarvisColors.TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))

        @Composable
        fun LoginOptionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = JarvisShapes.Button,
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.CyanBorder)
            ) {
                Icon(icon, contentDescription = label, tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = JarvisColors.Ice, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }

        LoginOptionButton(Icons.Default.AccountCircle, "Lanjut dengan Google", onGoogleLogin)
        LoginOptionButton(Icons.Default.Code, "Lanjut dengan GitHub", onGitHubLogin)
        LoginOptionButton(Icons.Default.Email, "Lanjut dengan Email", onEmailLogin)

        Text("atau", color = JarvisColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

        OutlinedButton(
            onClick = onGuestLogin,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = JarvisShapes.Button,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.GlassBorder)
        ) {
            Text("Gunakan sebagai Guest", color = JarvisColors.TextMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Dengan masuk, Anda menyetujui Syarat Penggunaan & Kebijakan Privasi.",
            color = JarvisColors.TextDim,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun JarvisLoginSuccessScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(JarvisColors.PanelSolid)
                .border(2.5.dp, JarvisColors.Cyan, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = "Login Berhasil", tint = JarvisColors.Cyan, modifier = Modifier.size(54.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Login Berhasil!", color = JarvisColors.Ice, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Menyiapkan lingkungan J.A.R.V.I.S. untuk Anda...",
            color = JarvisColors.TextMuted,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun JarvisSystemInitStageScreen(
    stepNumber: Int,
    title: String,
    progress: Float,
    items: List<Pair<String, Boolean>>
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(JarvisMotion.EmphasisMs),
        label = "init_progress_$stepNumber"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HolographicOrb(state = VoiceOrbState.Processing, size = 148.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, color = JarvisColors.Ice, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(JarvisShapes.Card)
                .background(JarvisColors.PanelSolid)
                .border(1.dp, JarvisColors.CyanBorder, JarvisShapes.Card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { (label, done) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = if (done) JarvisColors.Ice else JarvisColors.TextMuted, fontSize = 13.sp)
                    Icon(
                        imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = label,
                        tint = if (done) JarvisColors.Ok else JarvisColors.Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.weight(1f).height(8.dp).clip(JarvisShapes.Chip),
                color = JarvisColors.Cyan,
                trackColor = JarvisColors.PanelSolid
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                color = JarvisColors.Ice,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun JarvisFeatureActivationScreen(
    stepNumber: Int,
    activeFeature: String,
    statusLabel: String,
    progress: Float
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(JarvisMotion.EmphasisMs),
        label = "feature_activation_$stepNumber"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HolographicOrb(state = VoiceOrbState.Responding, size = 156.dp)
        Spacer(Modifier.height(18.dp))
        Text(activeFeature, color = JarvisColors.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(statusLabel, color = JarvisColors.Ice, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.weight(1f).height(8.dp).clip(JarvisShapes.Chip),
                color = JarvisColors.Cyan,
                trackColor = JarvisColors.PanelSolid
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                color = JarvisColors.Ice,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Storyboard Screens 10 & 11 (2.1): Beranda (Dashboard) + Ringkasan Sistem + 5-Tab Bottom Bar.
 */
@Composable
fun JarvisDashboardScreen(
    userName: String?,
    activeTier: JarvisIntelligenceTier,
    activeProviderCount: Int = 4,
    activeAccountCount: Int = 7,
    activeMcpCount: Int = 3,
    installedSkillCount: Int = 29,
    onOpenChat: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenCollaboration: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSystemStatus: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Greeting + Mini Orb Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Halo, ${userName?.takeIf { it.isNotBlank() } ?: "User"} 👋",
                    color = JarvisColors.Ice,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Siap menjelajahi ide, menyelesaikan tugas, atau berkolaborasi dengan banyak AI?",
                    color = JarvisColors.TextMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }
            HolographicOrb(state = VoiceOrbState.Idle, size = 64.dp)
        }

        // Active Model Banner (Screen 2.1: JARVIS 4 — APEX · Model Aktif)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(JarvisShapes.Card)
                .background(Brush.horizontalGradient(listOf(Color(0xFF0C2338), Color(0xFF071524))))
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.55f), JarvisShapes.Card)
                .clickable { onOpenModels() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = JarvisColors.Cyan, modifier = Modifier.size(22.dp))
                Column {
                    Text(activeTier.displayTitle, color = JarvisColors.Ice, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Model Aktif · ${activeTier.defaultModelName}", color = JarvisColors.TextMuted, fontSize = 11.5.sp)
                }
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Pilih Model", tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
        }

        // 2x3 Primary Module Cards (Chat, Voice, Kolaborasi, Models, Skills, MCP)
        val modules = listOf(
            Triple("Chat", "Percakapan dengan AI", Icons.Default.Chat) to onOpenChat,
            Triple("Voice", "Asisten suara JARVIS", Icons.Default.GraphicEq) to onOpenVoice,
            Triple("Kolaborasi", "Multi-AI Discussion", Icons.Default.Hub) to onOpenCollaboration,
            Triple("Models", "CORE · PULSE · SPARK · APEX", Icons.Default.Layers) to onOpenModels,
            Triple("Skills", "Kelola & instal skill", Icons.Default.Extension) to onOpenSkills,
            Triple("MCP", "Hubungkan tools & server", Icons.Default.Dns) to onOpenMcp
        )

        for (rowIdx in 0 until 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (colIdx in 0 until 2) {
                    val (info, action) = modules[rowIdx * 2 + colIdx]
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(JarvisShapes.Card)
                            .background(JarvisColors.PanelSolid)
                            .border(1.dp, JarvisColors.CyanBorder, JarvisShapes.Card)
                            .clickable { action() }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(info.third, contentDescription = info.first, tint = JarvisColors.Cyan, modifier = Modifier.size(22.dp))
                        Text(info.first, color = JarvisColors.Ice, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            info.second,
                            color = JarvisColors.TextMuted,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Ringkasan Sistem Card (Screen 2.1)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(JarvisShapes.Card)
                .background(JarvisColors.PanelSolid)
                .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.Card)
                .clickable { onOpenSystemStatus() }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = JarvisColors.Cyan, modifier = Modifier.size(16.dp))
                Text("Ringkasan Sistem", color = JarvisColors.Ice, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Provider", color = JarvisColors.TextMuted, fontSize = 11.sp)
                    Text("$activeProviderCount aktif", color = JarvisColors.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Akun", color = JarvisColors.TextMuted, fontSize = 11.sp)
                    Text("$activeAccountCount akun", color = JarvisColors.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MCP Server", color = JarvisColors.TextMuted, fontSize = 11.sp)
                    Text("$activeMcpCount server", color = JarvisColors.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Skills", color = JarvisColors.TextMuted, fontSize = 11.sp)
                    Text("$installedSkillCount skill", color = JarvisColors.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun JarvisB8BottomNavigationBar(
    selectedTab: JarvisB8NavTab,
    onSelectTab: (JarvisB8NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    // Three primary destinations only: Chat, central Voice, and More.
// Team remains reachable from Chat; Models remains an in-context selector.
    val tabs = listOf(
        Triple(JarvisB8NavTab.CHAT, Icons.Default.Chat, "Chat"),
        Triple(JarvisB8NavTab.VOICE, Icons.Default.Mic, "Voice"),
        Triple(JarvisB8NavTab.PENGATURAN, Icons.Default.MoreHoriz, "More")
    )
    val voiceSelected = selectedTab == JarvisB8NavTab.VOICE
    val voiceScale by animateFloatAsState(
        targetValue = if (voiceSelected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "jarvis_voice_nav_scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 350.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE62B4052), Color(0xE6101D2B), Color(0xE60A1420))
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0x88D8F5FF), Color(0x5549DDF5), Color(0x336D8DAD))
                    ),
                    CircleShape
                )
                .padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEach { (tab, icon, label) ->
                val selected = selectedTab == tab
                if (tab == JarvisB8NavTab.VOICE) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .scale(voiceScale)
                                .shadow(12.dp, CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        if (selected)
                                            listOf(Color(0xFFB7F7FF), Color(0xFF39DDF5), Color(0xFF0785B5))
                                        else
                                            listOf(Color(0xFF426D82), Color(0xFF193C52), Color(0xFF102638))
                                    ),
                                    CircleShape
                                )
                                .border(
                                    1.2.dp,
                                    Color(0xCCDAFAFF),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "Voice",
                                    tint = if (selected) Color(0xFF042330) else Color(0xFFD7F6FF),
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                        Text(
                            text = label,
                            color = if (selected) JarvisColors.CyanGlow else JarvisColors.TextMuted,
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                CircleShape
                            )
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) JarvisColors.CyanGlow else Color(0xFF9CB4C5),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = label,
                            color = if (selected) JarvisColors.Ice else Color(0xFF9CB4C5),
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
