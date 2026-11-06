package com.jarvis.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.mobile.account.SavedConversation
import com.jarvis.mobile.device.JarvisDeviceClient
import com.jarvis.mobile.stability.JarvisStabilityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

/**
 * J.A.R.V.I.S. Batch 8.5.1 — Hardened Multi-AI Collaboration, Chat, Voice & History Screens (Storyboard Screens 12–30)
 *
 * Production Hardening in Batch 8.5.1:
 * 1. Binds Collaboration UI to hardened B8.5.1 statuses (`SUCCESS`, `PARTIAL_SUCCESS`, `COLLABORATION_FAILED`, `SYNTHESIS_FAILED`)
 *    and exact provider failure error codes (`PROVIDER_CREDENTIAL_MISSING`, `PROVIDER_DISABLED`, `NETWORK_ERROR`,
 *    `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_AUTH_FAILED`, `MODEL_UNAVAILABLE`, `PROVIDER_ERROR`).
 * 2. Binds Screen 26 to real Claim-Based Consensus metrics (`consensusStatus`, `consensusScore`, `agreementScore`,
 *    `similarityScore`, `evidenceQualityScore`, `confidenceScore`).
 * 3. Binds Screen 28 (Knowledge Graph) to Graph View, Table View, Detail View, Node Provenance (`sessionId`, `agentId`,
 *    `modelId`, `messageId`, `timestamp`, `sourceReference`), Edge Explanation, and Agent Attribution.
 * 4. Binds Screen 29 (Final Synthesis) to all 11 required sections and supports retrying synthesis on `SYNTHESIS_FAILED`
 *    while preserving completed agent outputs.
 */

data class JarvisB8CollabAgentUi(
    val agentId: String,
    val modelId: String,
    val modelName: String,
    val provider: String,
    val role: String,
    val statusBadge: String,
    val speedLabel: String = "Fast",
    val capabilitySummary: String = "Reasoning • Code"
)

data class JarvisB8CollabMessageUi(
    val id: String,
    val round: Int,
    val agentId: String = "",
    val modelName: String,
    val provider: String = "",
    val role: String,
    val badge: String,
    val status: String = "completed",
    val content: String,
    val errorCode: String = "",
    val error: String = "",
    val keyPoints: List<String> = emptyList(),
    val confidence: Float = 0.92f,
    val timestamp: String = ""
)

data class JarvisB8AgentEventUi(
    val eventId: String,
    val sequence: Int,
    val sessionId: String,
    val agentId: String?,
    val eventType: String,
    val timestamp: String,
    val status: String,
    val summaryLabel: String
)

data class JarvisB8WorkflowStepUi(
    val stepNumber: Int,
    val title: String,
    val assignedModels: String,
    val status: String,
    val summary: String
)

data class JarvisB8KnowledgeNodeUi(
    val id: String,
    val label: String,
    val category: String,
    val ownerModel: String,
    val claimText: String,
    val nodeType: String = "CLAIM",
    val sessionId: String = "",
    val agentId: String = "",
    val modelId: String = "",
    val messageId: String = "",
    val timestamp: String = "",
    val sourceReference: String = ""
)

data class JarvisB8KnowledgeEdgeUi(
    val id: String,
    val source: String,
    val target: String,
    val relation: String,
    val edgeType: String = "SUPPORTS",
    val explanation: String = "",
    val verificationStatus: String = "VERIFIED",
    val agentId: String = "",
    val modelName: String = "",
    val messageId: String = "",
    val timestamp: String = "",
    val sourceReference: String = ""
)

data class JarvisB8SynthesisUi(
    val synthesizedBy: String,
    val mode: String,
    val consensusReached: Boolean,
    val consensusScore: Int,
    val summary: String,
    val conclusion: String,
    val evidence: List<String>,
    val agreements: List<String>,
    val disagreements: List<String>,
    val risks: List<String>,
    val limitations: List<String>,
    val recommendations: List<String>,
    val preReviewOutput: String? = null,
    val postReviewOutput: String? = null,
    val status: String = "SYNTHESIS_COMPLETED",
    val consensusStatus: String = "AGREED",
    val agreementScore: Int = 0,
    val similarityScore: Int = 0,
    val evidenceQualityScore: Int = 0,
    val confidenceScore: Int = 0,
    val executiveSummary: String = "",
    val mainFindings: List<String> = emptyList(),
    val supportingEvidence: List<String> = emptyList(),
    val conflictingEvidence: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList()
)

data class JarvisB8CollabSessionSummaryUi(
    val sessionId: String,
    val title: String,
    val mode: String,
    val modeName: String,
    val status: String,
    val modelsCount: Int,
    val totalRounds: Int,
    val consensusScore: Int,
    val createdAt: String
)

data class JarvisB8HistoryItemUi(
    val id: String,
    val title: String,
    val preview: String,
    val category: String,
    val modelBadge: String,
    val timeLabel: String,
    val isPinned: Boolean = false,
    val isDemo: Boolean = false
)

// ============================================================================
// AGENT NETWORK SMOOTH ANIMATION (BOUND TO REAL BACKEND AGENT EVENTS & LIFECYCLE)
// ============================================================================
@Composable
fun JarvisAgentNetworkAnimation(
    agents: List<JarvisB8CollabAgentUi>,
    activeAgentIndex: Int,
    isRunning: Boolean,
    latestEventType: String = "SESSION_CREATED",
    modifier: Modifier = Modifier
) {
    val pulseProgress = if (isRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "agent_network")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_progress"
        )
        animated
    } else {
        1f
    }

    val hubScale by animateFloatAsState(
        targetValue = if (isRunning) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = JarvisMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "hub_scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF071326))
            .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.36f
            val count = agents.size.coerceAtLeast(1)

            for (i in 0 until count) {
                val angle = (2.0 * Math.PI * i / count) - (Math.PI / 2.0)
                val nodePos = Offset(
                    x = center.x + (radius * cos(angle)).toFloat(),
                    y = center.y + (radius * sin(angle)).toFloat()
                )
                val agent = agents[i]
                val isSpeaking = isRunning && i == (activeAgentIndex % count)
                val isFailed = agent.statusBadge.equals("Error", ignoreCase = true) ||
                    agent.statusBadge.equals("failed", ignoreCase = true)

                val lineColor = when {
                    isFailed -> JarvisColors.CoralDanger
                    isSpeaking -> JarvisColors.CyanPrimary
                    else -> JarvisColors.PanelBorder
                }
                drawLine(
                    color = lineColor,
                    start = center,
                    end = nodePos,
                    strokeWidth = if (isSpeaking) 3.2f else 1.6f,
                    cap = StrokeCap.Round
                )

                if (isRunning && !isFailed) {
                    val packetPos = Offset(
                        x = nodePos.x + (center.x - nodePos.x) * pulseProgress,
                        y = nodePos.y + (center.y - nodePos.y) * pulseProgress
                    )
                    drawCircle(
                        color = if (isSpeaking) JarvisColors.CyanGlow else JarvisColors.EmeraldSuccess,                        radius = if (isSpeaking) 5.5f else 3.5f,
                        center = packetPos
                    )
                }

                drawCircle(
                    color = when {
                        isFailed -> JarvisColors.CoralDanger
                        isSpeaking -> JarvisColors.CyanPrimary
                        else -> Color(0xFF132A4A)
                    },
                    radius = if (isSpeaking) 14f else 11f,
                    center = nodePos
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(JarvisColors.CyanPrimary.copy(alpha = 0.45f), Color.Transparent),
                    center = center,
                    radius = 36f * hubScale
                ),
                radius = 36f * hubScale,
                center = center
            )
            drawCircle(
                color = JarvisColors.CyanPrimary,
                radius = 16f * hubScale,
                center = center,
                style = Stroke(width = 3f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isRunning) "EVENT: $latestEventType" else "STATUS: $latestEventType",
                color = JarvisColors.CyanGlow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${agents.size} Agent Aktif",
                color = JarvisColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

// ============================================================================
// SCREENS 12, 13, 14 — UPGRADED CHAT MODE CONNECTED TO REAL WORKER API
// ============================================================================
@Composable
fun JarvisB8ChatExperienceScreen(
    activeModelLabel: String,
    onOpenModelSelector: () -> Unit,
    onOpenCollaborationWithTopic: (String) -> Unit,
    onSwitchToVoice: () -> Unit,
    onSendLiveChatPrompt: ((String, (String, Boolean) -> Unit) -> (() -> Unit))? = null,
    modifier: Modifier = Modifier
) {
    var stage by remember { mutableIntStateOf(0) }
    var inputPrompt by remember { mutableStateOf("") }
    var lastSubmittedPrompt by remember { mutableStateOf("") }
    var liveResponseText by remember { mutableStateOf("") }
    var isStreamingOrLoading by remember { mutableStateOf(false) }
    var isErrorResponse by remember { mutableStateOf(false) }
    var cancelActiveCall by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun submitChatPrompt(promptToUse: String) {
        val clean = promptToUse.trim()
        if (clean.isBlank() || isStreamingOrLoading) return
        if (!JarvisStabilityGuard.acquireRequestLock("b8_chat_send", 600L)) return
        lastSubmittedPrompt = clean
        inputPrompt = ""
        stage = 1
        if (onSendLiveChatPrompt != null) {
            isStreamingOrLoading = true
            isErrorResponse = false
            cancelActiveCall = onSendLiveChatPrompt(clean) { answer, isErr ->
                liveResponseText = answer
                isErrorResponse = isErr
                isStreamingOrLoading = false
                cancelActiveCall = null
                JarvisStabilityGuard.releaseRequestLock("b8_chat_send")
            }
        } else {
            liveResponseText = "[UNAVAILABLE — Koneksi sesi live belum dikonfigurasi]\nPermintaan: $clean"
            isErrorResponse = true
            JarvisStabilityGuard.releaseRequestLock("b8_chat_send")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chat",
                color = JarvisColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(JarvisColors.SurfaceCard)
                    .border(1.dp, JarvisColors.CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable { onOpenModelSelector() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = activeModelLabel, color = JarvisColors.CyanGlow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "▾", color = JarvisColors.TextSecondary, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0 to "Mulai Chat",
                1 to "Percakapan",
                2 to "Detail & Struktur"
            ).forEach { (idx, label) ->
                val selected = stage == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) JarvisColors.CyanPrimary.copy(alpha = 0.2f) else JarvisColors.SurfaceCard)
                        .border(
                            1.dp,
                            if (selected) JarvisColors.CyanPrimary else JarvisColors.PanelBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { stage = idx }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) JarvisColors.CyanGlow else JarvisColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                fadeIn(tween(JarvisMotion.FAST_MS)) togetherWith fadeOut(tween(JarvisMotion.FAST_MS))
            },
            modifier = Modifier.weight(1f),
            label = "chat_stage_content"
        ) { currentStage ->
            when (currentStage) {
                0 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Apa yang ingin Anda kerjakan hari ini?",
                                color = JarvisColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Pilih template di bawah untuk mengirim request langsung ke model aktif ($activeModelLabel).",
                                color = JarvisColors.TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            val templates = listOf(
                                "Analisis" to "Analisis arsitektur backend multi-tenant dan rekomendasi RLS database.",
                                "Coding" to "Buatkan struktur modul API Gateway, Auth, dan Rate Limiter yang aman.",
                                "Riset" to "Bandingkan strategi caching Redis vs Read-Replica PostgreSQL untuk beban tinggi.",
                                "Ringkas" to "Susun checklist kesiapan rilis produksi dan mitigasi risiko operasional."
                            )
                            templates.chunked(2).forEach { rowItems ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { (title, promptTemplate) ->
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(JarvisColors.SurfaceCard)
                                                .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                                .clickable { submitChatPrompt(promptTemplate) }
                                                .padding(12.dp)
                                        ) {
                                            Text(text = title, color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(text = promptTemplate, color = JarvisColors.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("📎 File", "🖼️ Foto", "📄 Dokumen", "🎙️ Suara").forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (item.contains("Suara")) onSwitchToVoice() else stage = 1
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(text = item, color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                1 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (lastSubmittedPrompt.isNotBlank()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF102A4C))
                                        .padding(12.dp)
                                ) {
                                    Text(text = lastSubmittedPrompt, color = JarvisColors.TextPrimary, fontSize = 14.sp)
                                }
                            }
                        }

                        if (isStreamingOrLoading) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.CyanPrimary, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Menghubungi $activeModelLabel...",
                                        color = JarvisColors.CyanGlow,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    OutlinedButton(onClick = {
                                        cancelActiveCall?.invoke()
                                        cancelActiveCall = null
                                        isStreamingOrLoading = false
                                        liveResponseText = "Permintaan dibatalkan oleh pengguna."
                                    }) {
                                        Text("Batalkan", color = JarvisColors.CoralDanger, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        if (liveResponseText.isNotBlank()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(
                                            1.dp,
                                            if (isErrorResponse) JarvisColors.CoralDanger else JarvisColors.PanelBorder,
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isErrorResponse) "J.A.R.V.I.S. · PROVIDER ERROR" else "J.A.R.V.I.S. · $activeModelLabel",
                                            color = if (isErrorResponse) JarvisColors.CoralDanger else JarvisColors.CyanGlow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = if (onSendLiveChatPrompt != null) "LIVE" else "OFFLINE",
                                            color = if (onSendLiveChatPrompt != null) JarvisColors.EmeraldSuccess else JarvisColors.AmberWarning,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = liveResponseText,
                                        color = JarvisColors.TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (isErrorResponse && lastSubmittedPrompt.isNotBlank()) {
                                            Button(
                                                onClick = { submitChatPrompt(lastSubmittedPrompt) },
                                                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CoralDanger)
                                            ) {
                                                Text("Coba Lagi (Retry)", color = Color.White, fontSize = 11.sp)
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF0E223D))
                                                .clickable { stage = 2 }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("Lihat Struktur & Eskalasi", color = JarvisColors.CyanGlow, fontSize = 11.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF0E223D))
                                                .clickable {
                                                    onOpenCollaborationWithTopic(
                                                        lastSubmittedPrompt.ifBlank { liveResponseText.take(200) }
                                                    )
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("Buka di Kolaborasi", color = JarvisColors.CyanGlow, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    val extractedLines = remember(liveResponseText) {
                        liveResponseText.lines().filter { it.trim().isNotEmpty() }.take(8)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(14.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = if (liveResponseText.isNotBlank()) "Ekstraksi Poin Respons Aktual" else "Struktur Respons [UNAVAILABLE — Belum Ada Chat Aktif]",
                                    color = JarvisColors.CyanGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (extractedLines.isNotEmpty()) {
                                    extractedLines.forEach { line ->
                                        Text(
                                            text = "• ${line.take(140)}",
                                            color = JarvisColors.TextPrimary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Kirim pesan di tab Mulai Chat / Percakapan untuk mengekstrak struktur arsitektur dari respons model aktual.",
                                        color = JarvisColors.TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        onOpenCollaborationWithTopic(
                                            lastSubmittedPrompt.ifBlank { "Evaluasi arsitektur sistem dan skalabilitas" }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                                ) {
                                    Text("Diskusikan di Multi-AI Collaboration", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputPrompt,
                onValueChange = { inputPrompt = it },
                placeholder = { Text("Tulis pesan ke J.A.R.V.I.S...", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = { submitChatPrompt(inputPrompt) },
                enabled = !isStreamingOrLoading && inputPrompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
            ) {
                Text(if (isStreamingOrLoading) "..." else "Kirim", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================================
// SCREENS 15–19 — UPGRADED VOICE MODE SUITE BOUND TO REAL VOICE STATUS & CALLBACKS
// ============================================================================
@Composable
fun JarvisB8VoiceExperienceScreen(
    activeModelLabel: String,
    liveVoiceStatus: String = "MIC OFF",
    liveRecognizedText: String = "",
    liveRmsLevel: Float = 2f,
    onStartListening: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onSpeakText: (String) -> Unit = {},
    onSwitchToChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val derivedStage = when {
        liveVoiceStatus.contains("SPEAK", ignoreCase = true) ||
            liveVoiceStatus.contains("FALLBACK", ignoreCase = true) -> 3
        liveVoiceStatus.contains("PROCESSING", ignoreCase = true) ||
            liveVoiceStatus.contains("COUNCIL", ignoreCase = true) -> 2
        liveVoiceStatus.contains("LISTENING", ignoreCase = true) ||
            liveVoiceStatus.contains("HEARING", ignoreCase = true) ||
            liveVoiceStatus.contains("INPUT ACTIVE", ignoreCase = true) -> 1
        else -> 0
    }
    var manualStageOverride by remember { mutableIntStateOf(-1) }
    val voiceStage = if (manualStageOverride >= 0) manualStageOverride else derivedStage
    val isFishAudioFallback = liveVoiceStatus.contains("FISH AUDIO FAILED", ignoreCase = true) ||
        liveVoiceStatus.contains("ANDROID TTS", ignoreCase = true)

    val stageLabels = listOf("Siap", "Mendengar", "Proses", "Respons", "Hasil")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("J.A.R.V.I.S. VOICE", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(activeModelLabel, color = JarvisColors.TextSecondary, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFishAudioFallback) JarvisColors.AmberWarning.copy(alpha = 0.16f) else JarvisColors.SurfaceCard)
                .border(
                    1.dp,
                    if (isFishAudioFallback) JarvisColors.AmberWarning else JarvisColors.PanelBorder,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "STATUS AUDIO: $liveVoiceStatus",
                color = if (isFishAudioFallback) JarvisColors.AmberWarning else JarvisColors.CyanGlow,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            stageLabels.forEachIndexed { idx, label ->
                val selected = voiceStage == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) JarvisColors.CyanPrimary.copy(alpha = 0.22f) else JarvisColors.SurfaceCard)
                        .border(1.dp, if (selected) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(8.dp))
                        .clickable { manualStageOverride = if (manualStageOverride == idx) -1 else idx }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (selected) JarvisColors.CyanGlow else JarvisColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val orbState = resolveVoiceOrbState(
            voiceEnabled = voiceStage != 0,
            recognitionActive = voiceStage == 1,
            processing = voiceStage == 2,
            speaking = voiceStage == 3,
            error = liveVoiceStatus.contains("ERROR", ignoreCase = true) || liveVoiceStatus.contains("DENIED", ignoreCase = true)
        )

        HolographicOrb(
            state = orbState,
            rmsLevel = liveRmsLevel,
            size = 156.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(
            targetState = voiceStage,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.weight(1f),
            label = "voice_stage_card"
        ) { st ->
            when (st) {
                0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Siap Mendengarkan (Mic Default: OFF)", color = JarvisColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Ketuk tombol Mulai Dengar di bawah untuk mengaktifkan SpeechRecognizer.", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                manualStageOverride = -1
                                onStartListening()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                        ) {
                            Text("🎙️ Mulai Dengar", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { onStopSpeaking() }) {
                            Text("Hentikan Suara", color = JarvisColors.CoralDanger)
                        }
                    }
                }

                1 -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, JarvisColors.CyanPrimary, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("Mendengarkan Input Suara Aktual...", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = liveRecognizedText.ifBlank { "[Menunggu ucapan Anda melalui mikrofon perangkat...]" },
                        color = JarvisColors.TextPrimary,
                        fontSize = 14.sp
                    )
                }

                2 -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("Memproses Permintaan Suara", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("✓ Transkripsi SpeechRecognizer selesai", color = JarvisColors.EmeraldSuccess, fontSize = 13.sp)
                    Text("✓ Mengirim ke Worker & AI Provider ($activeModelLabel)", color = JarvisColors.EmeraldSuccess, fontSize = 13.sp)
                    Text("◐ Menyiapkan sintesis suara (Fish Audio / Android TTS)...", color = JarvisColors.CyanGlow, fontSize = 13.sp)
                }

                3 -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, JarvisColors.CyanPrimary, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = if (isFishAudioFallback) "Respons Suara (Fallback Android TTS Aktif)" else "Respons Suara (Fish Audio Neural TTS)",
                        color = if (isFishAudioFallback) JarvisColors.AmberWarning else JarvisColors.CyanGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Status Engine: $liveVoiceStatus",
                        color = JarvisColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onStopSpeaking() },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CoralDanger)
                    ) {
                        Text("Stop Speaking", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("Hasil Transkrip & Tindak Lanjut", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = liveRecognizedText.ifBlank { "UNAVAILABLE — Belum ada transkrip suara aktif." },
                        color = JarvisColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSwitchToChat(liveRecognizedText.ifBlank { "Lanjutkan diskusi dari Voice Mode" }) },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                        ) {
                            Text("Lanjutkan di Chat", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = {
                            manualStageOverride = -1
                            onStartListening()
                        }) {
                            Text("Tanya Lagi", color = JarvisColors.CyanGlow, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// SCREEN 20 — RIWAYAT PERCAKAPAN (CONNECTED TO REAL SUPABASE SAVED CONVERSATIONS)
// ============================================================================
@Composable
fun JarvisB8ConversationHistoryScreen(
    savedConversations: List<SavedConversation> = emptyList(),
    onRefreshConversations: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Semua") }

    val realItems = remember(savedConversations) {
        savedConversations.mapIndexed { idx, conv ->
            JarvisB8HistoryItemUi(
                id = conv.id,
                title = conv.title.ifBlank { "Percakapan #${idx + 1}" },
                preview = "Tersimpan di Supabase • ID: ${conv.id.take(8)}",
                category = if (conv.title.contains("voice", ignoreCase = true) || conv.title.contains("suara", ignoreCase = true)) "Voice" else "Chat",
                modelBadge = "LIVE DB",
                timeLabel = conv.updatedAt.take(16).replace("T", " "),
                isPinned = idx == 0,
                isDemo = false
            )
        }
    }

    val filtered = realItems.filter { item ->
        val matchesTab = selectedFilter == "Semua" || item.category.equals(selectedFilter, ignoreCase = true)
        val matchesQuery = searchQuery.isBlank() ||
            item.title.contains(searchQuery, ignoreCase = true) ||
            item.preview.contains(searchQuery, ignoreCase = true)
        matchesTab && matchesQuery
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Riwayat Percakapan", color = JarvisColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (realItems.isNotEmpty()) "↻ Refresh (${realItems.size} Live)" else "↻ Muat dari Supabase",
                color = JarvisColors.CyanGlow,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onRefreshConversations() }
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari percakapan...", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Semua", "Chat", "Voice").forEach { tab ->
                val active = selectedFilter == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                        .clickable { selectedFilter = tab }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tab,
                        color = if (active) Color.Black else JarvisColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(JarvisColors.SurfaceCard)
                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Belum ada riwayat percakapan tersimpan dari akun Supabase aktif.",
                    color = JarvisColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(JarvisColors.SurfaceCard)
                            .border(1.dp, if (item.isPinned) JarvisColors.CyanPrimary.copy(alpha = 0.6f) else JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                            .clickable { onSelectConversation(item.id) }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = (if (item.isPinned) "📌 " else "") + item.title,
                                color = JarvisColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(item.timeLabel, color = JarvisColors.TextMuted, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.preview, color = JarvisColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.category.uppercase(), color = JarvisColors.CyanGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "• ${item.modelBadge}",
                                color = JarvisColors.EmeraldSuccess,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// SCREENS 21–30 — REAL MULTI-AI COLLABORATION SUITE CONNECTED TO WORKER BACKEND
// ============================================================================
@Composable
fun JarvisB8CollaborationSuiteScreen(
    initialTopic: String = "Merancang arsitektur aplikasi SaaS scalable untuk 100.000 pengguna aktif.",
    accessTokenProvider: () -> String = { "" },
    onSendSynthesisToChat: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screenIndex by remember { mutableIntStateOf(21) }
    var selectionMode by remember { mutableStateOf("manual") }
    var topic by remember(initialTopic) { mutableStateOf(initialTopic) }
    var selectedModeId by remember { mutableStateOf("parallel") }
    var selectedRounds by remember { mutableIntStateOf(2) }
    var assignRoles by remember { mutableStateOf(true) }
    var enableKnowledgeGraph by remember { mutableStateOf(true) }
    var debateTab by remember { mutableIntStateOf(1) }
    var graphTab by remember { mutableIntStateOf(0) }

    var isExecutingSession by remember { mutableStateOf(false) }
    var activeSessionId by remember { mutableStateOf("") }
    var activeCollaborationStatus by remember { mutableStateOf("") }
    var executionError by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val liveEvents = remember { mutableStateListOf<JarvisB8AgentEventUi>() }
    val liveMessages = remember { mutableStateListOf<JarvisB8CollabMessageUi>() }
    val liveWorkflowSteps = remember { mutableStateListOf<JarvisB8WorkflowStepUi>() }
    val liveGraphNodes = remember { mutableStateListOf<JarvisB8KnowledgeNodeUi>() }
    val liveGraphEdges = remember { mutableStateListOf<JarvisB8KnowledgeEdgeUi>() }
    val savedSessions = remember { mutableStateListOf<JarvisB8CollabSessionSummaryUi>() }
    var liveSynthesis by remember { mutableStateOf<JarvisB8SynthesisUi?>(null) }

    val selectedModels = remember {
        mutableStateListOf("gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro", "deepseek-v3")
    }

    val catalogModels = remember {
        listOf(
            JarvisB8CollabAgentUi("a1", "gpt-4o", "GPT-4o", "OpenAI", "Analis", "Ready", "Fast", "Reasoning • Code"),
            JarvisB8CollabAgentUi("a2", "claude-3-5-sonnet", "Claude 3.5 Sonnet", "Anthropic", "Kreatif", "Ready", "Fast", "Analysis • Writing"),
            JarvisB8CollabAgentUi("a3", "gemini-1.5-pro", "Gemini 1.5 Pro", "Google", "Evaluator", "Ready", "Medium", "Multimodal • Long Context"),
            JarvisB8CollabAgentUi("a4", "deepseek-v3", "DeepSeek V3", "DeepSeek", "Teknis", "Ready", "Fast", "Math • Logic • Code"),
            JarvisB8CollabAgentUi("a5", "llama-3.3-70b", "Llama 3.3 70B", "Groq", "Perencana", "Ready", "Ultra-Fast", "Fast Synthesis"),
            JarvisB8CollabAgentUi("a6", "qwen-2.5-72b", "Qwen 2.5 72B", "Alibaba", "Moderator", "Ready", "Fast", "Multilingual • Code")
        )
    }

    val sevenRealModes = remember {
        listOf(
            "parallel" to "Parallel — Eksekusi Independen Paralel",
            "sequential" to "Sequential — Pipeline Berantai Antar-Agent",
            "debate" to "Debate — Adu Argumen & Kontraargumen Multi-Ronde",
            "consensus" to "Consensus — Analisis Kesepakatan & Perbedaan Nyata",
            "specialist" to "Specialist — Dekomposisi Subtugas Sesuai Peran",
            "reviewer" to "Reviewer — Draft Utama + Audit Reviewer + Revisi",
            "ensemble" to "Ensemble — Evaluasi Kualitas & Bukti Lintas Model"
        )
    }

    fun parseStringOrUnavailableList(anyVal: Any?): List<String> {
        return when (anyVal) {
            is JSONArray -> (0 until anyVal.length()).mapNotNull { anyVal.optString(it).takeIf { s -> s.isNotBlank() } }
            is String -> if (anyVal.isNotBlank()) listOf(anyVal) else listOf("UNAVAILABLE")
            else -> listOf("UNAVAILABLE")
        }
    }

    fun parseSessionJsonIntoState(sessionObj: JSONObject) {
        activeSessionId = sessionObj.optString("sessionId", "")
        activeCollaborationStatus = sessionObj.optString("collaborationStatus", sessionObj.optString("status", ""))
        liveEvents.clear()
        val evArr = sessionObj.optJSONArray("events") ?: JSONArray()
        for (i in 0 until evArr.length()) {
            val ev = evArr.optJSONObject(i) ?: continue
            liveEvents.add(
                JarvisB8AgentEventUi(
                    eventId = ev.optString("eventId", "ev_$i"),
                    sequence = ev.optInt("sequence", i + 1),
                    sessionId = ev.optString("sessionId", activeSessionId),
                    agentId = ev.optString("agentId", "").takeIf { it.isNotBlank() },
                    eventType = ev.optString("eventType", "EVENT"),
                    timestamp = ev.optString("timestamp", ""),
                    status = ev.optString("status", "ok"),
                    summaryLabel = "${ev.optString("eventType")} (${ev.optString("status")})"
                )
            )
        }

        liveMessages.clear()
        val msgArr = sessionObj.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgArr.length()) {
            val m = msgArr.optJSONObject(i) ?: continue
            val kpArr = m.optJSONArray("keyPoints") ?: JSONArray()
            val kps = (0 until kpArr.length()).mapNotNull { kpArr.optString(it).takeIf { s -> s.isNotBlank() } }
            liveMessages.add(
                JarvisB8CollabMessageUi(
                    id = m.optString("id", "m_$i"),
                    round = m.optInt("round", 1),
                    agentId = m.optString("agentId", ""),
                    modelName = m.optString("modelName", "AI"),
                    provider = m.optString("provider", ""),
                    role = m.optString("role", "Agent"),
                    badge = m.optString("badge", "Speaking"),
                    status = m.optString("status", "completed"),
                    content = m.optString("content", ""),
                    errorCode = m.optString("errorCode", ""),
                    error = m.optString("error", ""),
                    keyPoints = kps,
                    confidence = m.optDouble("confidence", 0.9).toFloat(),
                    timestamp = m.optString("createdAt", "")
                )
            )
        }

        liveWorkflowSteps.clear()
        val wfArr = sessionObj.optJSONArray("workflowSteps") ?: JSONArray()
        for (i in 0 until wfArr.length()) {
            val w = wfArr.optJSONObject(i) ?: continue
            val modelsArr = w.optJSONArray("assignedModels") ?: JSONArray()
            val modelsStr = (0 until modelsArr.length()).mapNotNull { modelsArr.optString(it) }.joinToString(", ")
            liveWorkflowSteps.add(
                JarvisB8WorkflowStepUi(
                    stepNumber = w.optInt("stepNumber", i + 1),
                    title = w.optString("title", "Step ${i + 1}"),
                    assignedModels = modelsStr,
                    status = w.optString("status", "completed").uppercase(),
                    summary = w.optString("summary", "")
                )
            )
        }

        liveGraphNodes.clear()
        liveGraphEdges.clear()
        val kgObj = sessionObj.optJSONObject("knowledgeGraph")
        if (kgObj != null) {
            val nArr = kgObj.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nArr.length()) {
                val n = nArr.optJSONObject(i) ?: continue
                val prov = n.optJSONObject("provenance")
                liveGraphNodes.add(
                    JarvisB8KnowledgeNodeUi(
                        id = n.optString("id", "n_$i"),
                        label = n.optString("label", "Node"),
                        category = n.optString("category", "claim"),
                        ownerModel = n.optString("ownerModel", prov?.optString("modelName", "AI") ?: "AI"),
                        claimText = n.optString("claimText", n.optString("label", "")),
                        nodeType = n.optString("nodeType", "CLAIM"),
                        sessionId = prov?.optString("sessionId", activeSessionId).orEmpty(),
                        agentId = prov?.optString("agentId", "").orEmpty(),                        modelId = prov?.optString("modelId", "").orEmpty(),
                        messageId = prov?.optString("messageId", "").orEmpty(),
                        timestamp = prov?.optString("timestamp", "").orEmpty(),
                        sourceReference = prov?.optString("sourceReference", "").orEmpty()
                    )
                )
            }
            val eArr = kgObj.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until eArr.length()) {
                val e = eArr.optJSONObject(i) ?: continue
                val prov = e.optJSONObject("provenance")
                liveGraphEdges.add(
                    JarvisB8KnowledgeEdgeUi(
                        id = e.optString("id", "e_$i"),
                        source = e.optString("source", ""),
                        target = e.optString("target", ""),
                        relation = e.optString("relation", "SUPPORTS"),
                        edgeType = e.optString("edgeType", e.optString("relation", "SUPPORTS")),
                        explanation = e.optString("explanation", ""),
                        verificationStatus = e.optString("verificationStatus", "VERIFIED"),
                        agentId = prov?.optString("agentId", "").orEmpty(),
                        modelName = prov?.optString("modelName", "").orEmpty(),
                        messageId = prov?.optString("messageId", "").orEmpty(),
                        timestamp = prov?.optString("timestamp", "").orEmpty(),
                        sourceReference = prov?.optString("sourceReference", "").orEmpty()
                    )
                )
            }
        }

        val synObj = sessionObj.optJSONObject("synthesis") ?: sessionObj.optJSONObject("failedSynthesisDiagnostic")
        val debObj = sessionObj.optJSONObject("debate")
        if (synObj != null || debObj != null) {
            val sectionsObj = synObj?.optJSONObject("sections")
            liveSynthesis = JarvisB8SynthesisUi(
                synthesizedBy = synObj?.optString("synthesizedBy", "J.A.R.V.I.S. CORE").orEmpty(),
                mode = synObj?.optString("mode", selectedModeId).orEmpty(),
                consensusReached = debObj?.optBoolean("consensusReached", false) ?: false,
                consensusScore = debObj?.optInt("consensusScore", 0) ?: 0,
                summary = synObj?.optString("summary", debObj?.optString("summary", "").orEmpty()).orEmpty(),
                conclusion = synObj?.optString("conclusion", "UNAVAILABLE").orEmpty(),
                evidence = parseStringOrUnavailableList(synObj?.opt("supportingEvidence") ?: synObj?.opt("evidence")),
                agreements = parseStringOrUnavailableList(synObj?.opt("agreement") ?: debObj?.opt("agreements")),
                disagreements = parseStringOrUnavailableList(synObj?.opt("disagreement") ?: debObj?.opt("disagreements")),
                risks = parseStringOrUnavailableList(synObj?.opt("risks")),
                limitations = parseStringOrUnavailableList(synObj?.opt("limitations")),
                recommendations = parseStringOrUnavailableList(synObj?.opt("recommendations")),
                preReviewOutput = synObj?.optString("preReviewOutput", "")?.takeIf { it.isNotBlank() },
                postReviewOutput = synObj?.optString("postReviewOutput", "")?.takeIf { it.isNotBlank() },
                status = synObj?.optString("status", sessionObj.optString("synthesisStatus", "SYNTHESIS_FAILED")).orEmpty(),
                consensusStatus = debObj?.optString("consensusStatus", "CONSENSUS_NOT_ESTABLISHED").orEmpty(),
                agreementScore = debObj?.optInt("agreementScore", 0) ?: 0,
                similarityScore = debObj?.optInt("similarityScore", 0) ?: 0,
                evidenceQualityScore = debObj?.optInt("evidenceQualityScore", 0) ?: 0,
                confidenceScore = debObj?.optInt("confidenceScore", 0) ?: 0,
                executiveSummary = sectionsObj?.optString("executiveSummary", synObj?.optString("executiveSummary", "UNAVAILABLE").orEmpty()).orEmpty(),
                mainFindings = parseStringOrUnavailableList(sectionsObj?.opt("mainFindings") ?: synObj?.opt("mainFindings")),
                supportingEvidence = parseStringOrUnavailableList(sectionsObj?.opt("supportingEvidence") ?: synObj?.opt("supportingEvidence")),
                conflictingEvidence = parseStringOrUnavailableList(sectionsObj?.opt("conflictingEvidence") ?: synObj?.opt("conflictingEvidence")),
                unresolvedQuestions = parseStringOrUnavailableList(sectionsObj?.opt("unresolvedQuestions") ?: synObj?.opt("unresolvedQuestions"))
            )
        }
    }

    fun fetchSavedCollaborationSessions() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val conn = (URL("$workerBaseUrl/v1/collaboration/sessions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 15000
                        val token = accessTokenProvider()
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) return@withContext emptyList<JarvisB8CollabSessionSummaryUi>()
                        val raw = String(JarvisStabilityGuard.readBoundedBytes(conn.inputStream, 2 * 1024 * 1024), Charsets.UTF_8)
                        val json = JarvisStabilityGuard.parseJsonObjectSafe(raw) ?: return@withContext emptyList()
                        val arr = json.optJSONArray("sessions") ?: JSONArray()
                        (0 until arr.length()).mapNotNull { idx ->
                            val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                            JarvisB8CollabSessionSummaryUi(
                                sessionId = item.optString("sessionId", ""),
                                title = item.optString("title", "Sesi Kolaborasi"),
                                mode = item.optString("mode", "parallel"),
                                modeName = item.optString("modeName", "Parallel"),
                                status = item.optString("collaborationStatus", item.optString("status", "SUCCESS")),
                                modelsCount = item.optInt("modelsCount", 4),
                                totalRounds = item.optInt("totalRounds", 1),
                                consensusScore = item.optInt("consensusScore", 0),
                                createdAt = item.optString("createdAt", "").take(16).replace("T", " ")
                            )
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                savedSessions.clear()
                savedSessions.addAll(list)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                JarvisStabilityGuard.recordDiagnostic(JarvisStabilityGuard.Subsystem.COLLABORATION, "COLLAB_HISTORY_FETCH_ERROR", err)
            }
        }
    }

    fun executeLiveCollaborationSession(actionOverride: String = "start") {
        if (isExecutingSession) return
        if (!JarvisStabilityGuard.acquireRequestLock("b8_collab_start", 1000L)) return
        isExecutingSession = true
        executionError = ""
        if (actionOverride == "start") {
            screenIndex = 25
        }

        activeJob = scope.launch {
            try {
                val responseJson = withContext(Dispatchers.IO) {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val conn = (URL("$workerBaseUrl/v1/collaboration/sessions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 90000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        val token = accessTokenProvider()
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        val modelsArr = JSONArray()
                        selectedModels.forEach { modelsArr.put(it) }
                        val payload = JSONObject()
                            .put("action", actionOverride)
                            .put("sessionId", activeSessionId.takeIf { actionOverride != "start" })
                            .put("topic", topic)
                            .put("selectionMode", selectionMode)
                            .put("selectedModelIds", modelsArr)
                            .put("mode", selectedModeId)
                            .put("rounds", selectedRounds)
                            .put("assignRoles", assignRoles)
                            .put("enableKnowledgeGraph", enableKnowledgeGraph)
                            .put("clientRequestId", "collab_ui_${System.currentTimeMillis()}")
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val raw = if (stream != null) {
                            String(JarvisStabilityGuard.readBoundedBytes(stream, 4 * 1024 * 1024), Charsets.UTF_8)
                        } else ""
                        val parsed = JarvisStabilityGuard.parseJsonObjectSafe(raw)
                        if (code !in 200..299 || parsed == null || !parsed.optBoolean("ok", false)) {
                            val errMsg = parsed?.optString("error").orEmpty().ifBlank { "HTTP $code dari Worker /v1/collaboration/sessions" }
                            throw IllegalStateException(errMsg)
                        }
                        parsed.optJSONObject("session") ?: throw IllegalStateException("Session object missing in Worker response")
                    } finally {
                        conn.disconnect()
                    }
                }
                parseSessionJsonIntoState(responseJson)
                fetchSavedCollaborationSessions()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                val diag = JarvisStabilityGuard.recordDiagnostic(
                    JarvisStabilityGuard.Subsystem.COLLABORATION,
                    "COLLAB_SESSION_EXEC_ERROR",
                    err
                )
                executionError = diag.message
            } finally {
                isExecutingSession = false
                JarvisStabilityGuard.releaseRequestLock("b8_collab_start")
            }
        }
    }

    fun cancelActiveCollaborationSession() {
        activeJob?.cancel()
        activeJob = null
        isExecutingSession = false
        val targetId = activeSessionId
        if (targetId.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val conn = (URL("$workerBaseUrl/v1/collaboration/sessions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        val token = accessTokenProvider()
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    conn.outputStream.use {
                        it.write(JSONObject().put("action", "cancel").put("sessionId", targetId).toString().toByteArray(Charsets.UTF_8))
                    }
                    conn.responseCode
                    conn.disconnect()
                }.onFailure { err ->
                    JarvisStabilityGuard.recordDiagnostic(JarvisStabilityGuard.Subsystem.COLLABORATION, "COLLAB_CANCEL_WARN", err)
                }
            }
        }
    }

    LaunchedEffect(screenIndex) {
        if (screenIndex == 30) {
            fetchSavedCollaborationSessions()
        }
    }

    val activeTeam = catalogModels.filter { selectedModels.contains(it.modelId) }.ifEmpty { catalogModels.take(4) }
    val latestEventType = liveEvents.lastOrNull()?.eventType ?: if (isExecutingSession) "AGENT_STARTED" else "READY"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Kolaborasi Multi-AI",
                color = JarvisColors.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (activeCollaborationStatus.isNotBlank()) {
                    "STATUS: $activeCollaborationStatus"
                } else {
                    "Layar #$screenIndex/30"
                },
                color = when (activeCollaborationStatus) {
                    "SUCCESS" -> JarvisColors.EmeraldSuccess
                    "PARTIAL_SUCCESS" -> JarvisColors.AmberWarning
                    "COLLABORATION_FAILED", "SYNTHESIS_FAILED" -> JarvisColors.CoralDanger
                    else -> JarvisColors.CyanGlow
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val screens = listOf(
                21 to "21. Akses",
                22 to "22. Buat Sesi",
                23 to "23. Pilih Tim",
                24 to "24. Atur Sesi",
                25 to "25. Diskusi Live",
                26 to "26. Debat & Eval",
                27 to "27. Workflow",
                28 to "28. Knowledge Graph",
                29 to "29. Synthesis",
                30 to "30. Riwayat"
            )
            screens.forEach { (num, label) ->
                val active = screenIndex == num
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                        .border(1.dp, if (active) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(10.dp))
                        .clickable { screenIndex = num }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        color = if (active) Color.Black else JarvisColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedContent(
            targetState = screenIndex,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.weight(1f),
            label = "collab_screen_transition"
        ) { currentScreen ->
            when (currentScreen) {
                21 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pusat Kolaborasi Multi-AI (7 Mode Nyata)", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        listOf(
                            "1. Parallel & Sequential Execution" to "Jalankan beberapa model secara paralel berbatas konkurensi atau berantai (output Agent 1 -> Agent 2).",
                            "2. Debate & Claim-Based Consensus" to "Adu argumen multi-ronde dan evaluasi konsensus berbasis klaim, bukti pendukung, dan kontradiksi.",
                            "3. Specialist, Reviewer & Ensemble" to "Dekomposisi subtugas sesuai keahlian, audit draf oleh reviewer independen, atau evaluasi kualitas ensemble."
                        ).forEach { (title, desc) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(title, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(desc, color = JarvisColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                        Button(
                            onClick = { screenIndex = 22 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Mulai Buat Sesi Kolaborasi", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                22 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Buat Sesi Kolaborasi", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        listOf(
                            "auto" to ("Mode Otomatis (Rekomendasi)" to "J.A.R.V.I.S. memilih tim AI terbaik dari Model Registry Batch 8.1."),
                            "manual" to ("Pilih Model Manual" to "Pilih sendiri model AI, peran, dan salah satu dari 7 mode eksekusi (maksimal 6 model).")
                        ).forEach { (modeKey, pair) ->
                            val isSelected = selectionMode == modeKey
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.5.dp, if (isSelected) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                    .clickable { selectionMode = modeKey }
                                    .padding(14.dp)
                            ) {
                                Text(pair.first, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(pair.second, color = JarvisColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                        Button(
                            onClick = { screenIndex = if (selectionMode == "manual") 23 else 24 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lanjutkan", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                23 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Pilih Tim AI (${selectedModels.size}/6 Model Dipilih)",
                            color = JarvisColors.CyanGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(catalogModels, key = { it.modelId }) { m ->
                                val checked = selectedModels.contains(m.modelId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, if (checked) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (checked && selectedModels.size > 2) {
                                                selectedModels.remove(m.modelId)
                                            } else if (!checked && selectedModels.size < 6) {
                                                selectedModels.add(m.modelId)
                                            }
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("${m.modelName} (${m.provider})", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("${m.capabilitySummary} • Peran: ${m.role}", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                    }
                                    Text(if (checked) "✓ Dipilih" else "+ Pilih", color = if (checked) JarvisColors.CyanGlow else JarvisColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Button(
                            onClick = { screenIndex = 24 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Konfirmasi ${selectedModels.size} Model", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                24 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Atur Sesi Kolaborasi (7 Mode Eksekusi Aktual)", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        item {
                            OutlinedTextField(
                                value = topic,
                                onValueChange = { topic = it },
                                label = { Text("Topik / Masalah") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text("Pilih Mode Kolaborasi (7 Real Modes)", color = JarvisColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            sevenRealModes.forEach { (modeId, label) ->
                                val active = selectedModeId == modeId
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, if (active) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(10.dp))
                                        .clickable { selectedModeId = modeId }
                                        .padding(10.dp)
                                ) {
                                    Text(label, color = if (active) JarvisColors.CyanGlow else JarvisColors.TextPrimary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Peran Spesialis (Analis, Kreatif, Teknis, Evaluator)", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                Switch(checked = assignRoles, onCheckedChange = { assignRoles = it })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Bangun Knowledge Graph Semantik + Provenance", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                Switch(checked = enableKnowledgeGraph, onCheckedChange = { enableKnowledgeGraph = it })
                            }
                        }
                        item {
                            Button(
                                onClick = { executeLiveCollaborationSession("start") },
                                enabled = !isExecutingSession && topic.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isExecutingSession) "Menjalankan Kolaborasi..." else "Mulai Kolaborasi Live (${selectedModeId.uppercase()})",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                25 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JarvisAgentNetworkAnimation(
                            agents = activeTeam,
                            activeAgentIndex = liveEvents.size,
                            isRunning = isExecutingSession,
                            latestEventType = latestEventType
                        )

                        if (executionError.isNotBlank() || activeCollaborationStatus == "COLLABORATION_FAILED") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.CoralDanger, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = if (activeCollaborationStatus == "COLLABORATION_FAILED") "COLLABORATION_FAILED — Seluruh Provider Gagal" else "Kegagalan Eksekusi Sesi Kolaborasi",
                                    color = JarvisColors.CoralDanger,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = executionError.ifBlank { "Tidak ada agent yang berhasil merespons. Periksa kredensial API dan status provider." },
                                    color = JarvisColors.TextPrimary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { executeLiveCollaborationSession("start") },
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                                    ) {
                                        Text("Coba Lagi", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(onClick = { screenIndex = 24 }) {
                                        Text("Ubah Konfigurasi", color = JarvisColors.CyanGlow, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (liveMessages.isNotEmpty()) {
                                    "Arena Diskusi Aktual (${liveMessages.size} Turn • ${liveEvents.size} Events)"
                                } else {
                                    "Arena Diskusi [Belum Ada Sesi Live — Klik Mulai di Layar 24]"
                                },
                                color = JarvisColors.CyanGlow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            if (isExecutingSession) {
                                OutlinedButton(onClick = { cancelActiveCollaborationSession() }) {
                                    Text("Batalkan Sesi", color = JarvisColors.CoralDanger, fontSize = 11.sp)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (liveMessages.isEmpty() && !isExecutingSession) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                            .padding(14.dp)
                                    ) {
                                        Text(
                                            text = "Belum ada sesi kolaborasi yang dijalankan.",
                                            color = JarvisColors.TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Ketuk tombol di bawah untuk mengirim request aktual ke provider AI yang dikonfigurasi.",
                                            color = JarvisColors.TextSecondary,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { executeLiveCollaborationSession("start") },
                                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                                        ) {
                                            Text("Jalankan Sesi Live Sekarang", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            items(liveMessages, key = { it.id }) { msg ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(
                                            1.dp,
                                            if (msg.status == "failed") JarvisColors.CoralDanger else JarvisColors.PanelBorder,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${msg.modelName} (${msg.role}) • R${msg.round}",
                                            color = JarvisColors.CyanGlow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = if (msg.status == "failed") msg.errorCode.ifBlank { "PROVIDER_ERROR" } else msg.badge,
                                            color = if (msg.status == "failed") JarvisColors.CoralDanger else JarvisColors.EmeraldSuccess,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (msg.status == "failed") "[FAILED: ${msg.errorCode}] ${msg.error}" else msg.content,
                                        color = if (msg.status == "failed") JarvisColors.CoralDanger else JarvisColors.TextPrimary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { screenIndex = 26 },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Debat & Evaluasi", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { screenIndex = 29 }, modifier = Modifier.weight(1f)) {
                                Text("Lihat Sintesis", color = JarvisColors.CyanGlow, fontSize = 12.sp)
                            }
                        }
                    }
                }

                26 -> {
                    val syn = liveSynthesis
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Metrik Klaim", 1 to "Kesepakatan & Konflik", 2 to "Bukti & Risiko").forEach { (idx, label) ->
                                val selected = debateTab == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                                        .clickable { debateTab = idx }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (selected) Color.Black else JarvisColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (syn == null) {
                            Text(
                                text = "UNAVAILABLE — Jalankan sesi di Layar 24/25 untuk menghasilkan evaluasi klaim & skor konsensus aktual.",
                                color = JarvisColors.AmberWarning,
                                fontSize = 12.sp
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.CyanPrimary, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Status Konsensus: ${syn.consensusStatus} (Consensus: ${syn.consensusScore}%)",
                                    color = JarvisColors.CyanGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Agreement: ${syn.agreementScore}% • Similarity: ${syn.similarityScore}% • Evidence Quality: ${syn.evidenceQualityScore}% • Confidence: ${syn.confidenceScore}%",
                                    color = JarvisColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (syn.consensusScore / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = JarvisColors.CyanPrimary
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.EmeraldSuccess.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("KESEPAKATAN KLAIM (${syn.agreements.size})", color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                syn.agreements.take(4).forEach { item ->
                                    Text("• $item", color = JarvisColors.TextPrimary, fontSize = 12.sp)
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.CoralDanger.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("KONTRADIKSI & PERBEDAAN PENDAPAT (${syn.disagreements.size})", color = JarvisColors.CoralDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                syn.disagreements.take(4).forEach { item ->
                                    Text("• $item", color = JarvisColors.TextPrimary, fontSize = 12.sp)
                                }
                            }
                        }

                        Button(
                            onClick = { screenIndex = 27 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lanjut ke Workflow Kolaborasi", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                27 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (liveWorkflowSteps.isNotEmpty()) "Workflow Kolaborasi (Task Planner Aktual)" else "Workflow Kolaborasi [Menunggu Eksekusi Sesi]",
                            color = JarvisColors.CyanGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        val stepsToRender = if (liveWorkflowSteps.isNotEmpty()) {
                            liveWorkflowSteps
                        } else {
                            listOf(
                                JarvisB8WorkflowStepUi(1, "Step 1 — Problem Breakdown", activeTeam.take(2).joinToString { it.modelName }, "READY", "Siap mendekomposisi topik kolaborasi."),
                                JarvisB8WorkflowStepUi(2, "Step 2 — Multi-Agent Execution", activeTeam.joinToString { it.modelName }, "READY", "Menunggu eksekusi mode $selectedModeId."),
                                JarvisB8WorkflowStepUi(3, "Step 3 — Claim Consensus & Risk Audit", activeTeam.takeLast(2).joinToString { it.modelName }, "READY", "Menunggu ekstraksi klaim, bukti, dan kontradiksi."),
                                JarvisB8WorkflowStepUi(4, "Step 4 — 10-Stage Final Synthesis", "J.A.R.V.I.S. CORE", "READY", "Menunggu hasil aktual agent.")
                            )
                        }
                        stepsToRender.forEach { st ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(st.title, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = st.status,
                                        color = when (st.status) {
                                            "COMPLETED" -> JarvisColors.EmeraldSuccess
                                            "FAILED" -> JarvisColors.CoralDanger
                                            else -> JarvisColors.CyanGlow
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text("Model: ${st.assignedModels}", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                if (st.summary.isNotBlank()) {
                                    Text(st.summary, color = JarvisColors.TextPrimary, fontSize = 11.sp)
                                }
                            }
                        }
                        Button(
                            onClick = { screenIndex = 28 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lihat Knowledge Graph", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                28 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                0 to "Graph & Attribution",
                                1 to "Tabel Relasi",
                                2 to "Detail & Provenance"
                            ).forEach { (idx, label) ->
                                val active = graphTab == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                                        .clickable { graphTab = idx }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (active) Color.Black else JarvisColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF071427))
                                .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (liveGraphNodes.isEmpty()) {
                                item {
                                    Text(
                                        text = "UNAVAILABLE — Knowledge Graph hanya dibangun dari klaim, bukti, dan relasi semantik aktual setelah sesi dijalankan.",
                                        color = JarvisColors.AmberWarning,
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (graphTab == 0) {
                                items(liveGraphNodes, key = { it.id }) { node ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "[${node.nodeType}] ${node.label} • Agent: ${node.ownerModel}",
                                            color = JarvisColors.CyanGlow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(node.claimText, color = JarvisColors.TextPrimary, fontSize = 11.sp)
                                    }
                                }
                            } else if (graphTab == 1) {
                                items(liveGraphEdges, key = { it.id }) { edge ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "${edge.source} —[${edge.edgeType}]→ ${edge.target} (${edge.verificationStatus})",
                                            color = if (edge.verificationStatus == "VERIFIED") JarvisColors.EmeraldSuccess else JarvisColors.AmberWarning,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = edge.explanation.ifBlank { edge.sourceReference },
                                            color = JarvisColors.TextPrimary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                items(liveGraphNodes, key = { "prov_${it.id}" }) { node ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "PROVENANCE NODE: ${node.id} (${node.nodeType})",
                                            color = JarvisColors.CyanGlow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "sessionId=${node.sessionId} • agentId=${node.agentId} • modelId=${node.modelId} • msgId=${node.messageId}",
                                            color = JarvisColors.TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "timestamp=${node.timestamp} • ref=${node.sourceReference}",
                                            color = JarvisColors.TextPrimary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { screenIndex = 29 },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lihat Synthesis & Hasil Akhir (11 Bagian)", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                29 -> {
                    val syn = liveSynthesis
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text(
                                text = if (syn != null) "Synthesis 10-Tahap — ${syn.synthesizedBy} (${syn.status})" else "Synthesis & Hasil Akhir [UNAVAILABLE]",
                                color = if (syn?.status == "SYNTHESIS_FAILED") JarvisColors.CoralDanger else JarvisColors.CyanGlow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        if (syn?.status == "SYNTHESIS_FAILED") {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.CoralDanger, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("SYNTHESIS_FAILED — Hasil Agent Tetap Tersimpan", color = JarvisColors.CoralDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(syn.summary, color = JarvisColors.TextPrimary, fontSize = 12.sp)
                                    Button(
                                        onClick = { executeLiveCollaborationSession("retry_synthesis") },
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary)
                                    ) {
                                        Text("Ulangi Sintesis (Retry Synthesis)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (syn != null) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.CyanPrimary, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("1. Executive Summary", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(syn.executiveSummary.ifBlank { syn.conclusion }, color = JarvisColors.TextPrimary, fontSize = 12.sp)
                                    Text("2. Main Findings", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    syn.mainFindings.take(4).forEach { item -> Text("• $item", color = JarvisColors.TextPrimary, fontSize = 12.sp) }
                                }
                            }
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("3. Supporting Evidence", color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    syn.supportingEvidence.take(3).forEach { ev -> Text("• $ev", color = JarvisColors.TextPrimary, fontSize = 12.sp) }
                                    Text("4. Conflicting Evidence", color = JarvisColors.CoralDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    syn.conflictingEvidence.take(3).forEach { ce -> Text("• $ce", color = JarvisColors.TextPrimary, fontSize = 12.sp) }
                                    Text("5. Agreement & 6. Disagreement", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    (syn.agreements.take(2) + syn.disagreements.take(2)).forEach { ad -> Text("• $ad", color = JarvisColors.TextSecondary, fontSize = 12.sp) }
                                    Text("7. Recommendations", color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    syn.recommendations.take(3).forEach { rec -> Text("• $rec", color = JarvisColors.TextPrimary, fontSize = 12.sp) }
                                    Text("8. Risks & 9. Limitations", color = JarvisColors.AmberWarning, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    (syn.risks.take(2) + syn.limitations.take(2)).forEach { rk -> Text("• $rk", color = JarvisColors.TextSecondary, fontSize = 12.sp) }
                                    Text("10. Confidence & 11. Unresolved Questions", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "Status: ${syn.consensusStatus} • Consensus: ${syn.consensusScore}% • Agreement: ${syn.agreementScore}% • Evidence: ${syn.evidenceQualityScore}% • Confidence: ${syn.confidenceScore}%",
                                        color = JarvisColors.TextPrimary,
                                        fontSize = 11.sp
                                    )
                                    syn.unresolvedQuestions.take(3).forEach { uq -> Text("? $uq", color = JarvisColors.TextSecondary, fontSize = 11.sp) }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onSendSynthesisToChat(syn?.executiveSummary?.takeIf { it != "UNAVAILABLE" } ?: topic) },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Kirim ke Chat", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)                                }
                                OutlinedButton(onClick = { screenIndex = 30 }, modifier = Modifier.weight(1f)) {
                                    Text("Riwayat Sesi", color = JarvisColors.CyanGlow, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Riwayat Kolaborasi (Database / KV)", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = "↻ Muat Ulang",
                                color = JarvisColors.CyanGlow,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { fetchSavedCollaborationSessions() }
                            )
                        }
                        if (savedSessions.isEmpty()) {
                            Text(
                                text = "Belum ada sesi tersimpan dari server atau sedang offline. Jalankan sesi di Layar 24 untuk menyimpan ke database.",
                                color = JarvisColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(savedSessions, key = { it.sessionId }) { s ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                            .clickable { screenIndex = 29 }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(s.title, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text(
                                                text = "${s.modeName} • ${s.modelsCount} Model • ${s.consensusScore}% Consensus",
                                                color = JarvisColors.TextSecondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Text(
                                            text = s.status.uppercase(),
                                            color = when (s.status.uppercase()) {
                                                "SUCCESS", "COMPLETED" -> JarvisColors.EmeraldSuccess
                                                "PARTIAL_SUCCESS" -> JarvisColors.AmberWarning
                                                else -> JarvisColors.CoralDanger
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}