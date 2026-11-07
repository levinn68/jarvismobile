package com.jarvis.mobile.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * JARVIS design system — Holographic AI + Ambient Aurora + Futuristic Minimal.
 * Near-black / graphite base, electric cyan + violet accents, restrained glass.
 */
object JarvisColors {
    val Background = Color(0xFF040911)
    val BackgroundElevated = Color(0xFF071018)
    val Graphite = Color(0xFF0B1520)
    val Panel = Color(0xCC0A1624)
    val PanelSolid = Color(0xFF0C1826)
    val Glass = Color(0xE6081420)
    val GlassBorder = Color(0x33A8E6FF)

    val Cyan = Color(0xFF00E5FF)
    val CyanGlow = Color(0xFF26F0FF)
    val CyanDim = Color(0xFF0A8FA8)
    val CyanBorder = Color(0xFF145674)

    val Violet = Color(0xFF8B5CFF)
    val VioletGlow = Color(0xFFB794FF)
    val AuroraStart = Color(0xFF00E5FF)
    val AuroraMid = Color(0xFF5B8CFF)
    val AuroraEnd = Color(0xFFB26BFF)

    val Ice = Color(0xFFECF9FF)
    val TextPrimary = Color(0xFFE8F7FF)
    val TextMuted = Color(0xFF7B9EB2)
    val TextDim = Color(0xFF4A6578)

    val Ok = Color(0xFF00F59B)
    val Warn = Color(0xFFFFC56A)
    val Error = Color(0xFFFF5E6E)
    val Listening = Color(0xFF00E5FF)
    val Thinking = Color(0xFFB26BFF)
    val Responding = Color(0xFF38D4FF)
    val Speaking = Color(0xFF5BFFC8)
    val Muted = Color(0xFF4A6578)

    // B8 Semantic Color Aliases
    val BackgroundDeep = Background
    val SurfaceCard = PanelSolid
    val SurfaceElevated = BackgroundElevated
    val PanelBorder = CyanBorder
    val CyanPrimary = Cyan
    val TextSecondary = TextMuted
    val EmeraldSuccess = Ok
    val AmberWarning = Warn
    val CoralDanger = Error
}

object JarvisShapes {
    val Orb = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(16.dp)
    val CardSm = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(999.dp)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val Button = RoundedCornerShape(14.dp)
}

object JarvisSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * Voice orb visual state driven by real recognition / processing / response / TTS / mute / error flags.
 * Supports all 7 required states (Idle, Listening, Processing, Responding, Speaking, Muted, Error)
 * plus Standby alias for backward compatibility.
 */
enum class VoiceOrbState {
    Idle,
    Standby,
    Listening,
    Processing,
    Responding,
    Speaking,
    Muted,
    Error
}

fun auroraBrush(alpha: Float = 0.55f): Brush = Brush.linearGradient(
    colors = listOf(
        JarvisColors.AuroraStart.copy(alpha = alpha),
        JarvisColors.AuroraMid.copy(alpha = alpha * 0.9f),
        JarvisColors.AuroraEnd.copy(alpha = alpha * 0.85f)
    )
)

fun verticalAuroraBrush(alpha: Float = 0.35f): Brush = Brush.verticalGradient(
    colors = listOf(
        JarvisColors.Violet.copy(alpha = alpha * 0.4f),
        Color.Transparent,
        JarvisColors.Cyan.copy(alpha = alpha * 0.25f)
    )
)

@Composable
fun resolveVoiceOrbState(
    voiceEnabled: Boolean,
    recognitionActive: Boolean,
    processing: Boolean,
    speaking: Boolean,
    error: Boolean,
    responding: Boolean = false,
    muted: Boolean = !voiceEnabled
): VoiceOrbState = when {
    error -> VoiceOrbState.Error
    speaking -> VoiceOrbState.Speaking
    responding -> VoiceOrbState.Responding
    processing -> VoiceOrbState.Processing
    muted || !voiceEnabled -> VoiceOrbState.Muted
    recognitionActive -> VoiceOrbState.Listening
    else -> VoiceOrbState.Idle
}


/**
 * J.A.R.V.I.S. B8.1 Design System Extensions — Typography, Motion Tokens,
 * 5-Tab Bottom Navigation (`Beranda`, `Chat`, `Voice`, `Kolaborasi`, `Pengaturan`),
 * and Intelligence Tier Badges (`CORE`, `PULSE`, `SPARK`, `APEX`).
 */
object JarvisTypography {
    val HeroTitleSp = 22
    val ScreenTitleSp = 18
    val SectionTitleSp = 15
    val BodySp = 13
    val CaptionSp = 11
    val MonoBadgeSp = 10
}

object JarvisMotion {
    const val FastMs = 180
    const val StandardMs = 280
    const val EmphasisMs = 420
    const val InitStepMs = 650
    const val FAST_MS = FastMs
    const val STANDARD_MS = StandardMs
    const val EMPHASIS_MS = EmphasisMs
}

enum class JarvisB8NavTab(val route: String, val labelId: String) {
    BERANDA("BERANDA", "Beranda"),
    CHAT("CHAT", "Chat"),
    VOICE("VOICE", "Voice"),
    KOLABORASI("KOLABORASI", "Kolaborasi"),
    PENGATURAN("PENGATURAN", "Pengaturan")
}

enum class JarvisIntelligenceTier(
    val code: String,
    val displayTitle: String,
    val badgeSummary: String,
    val defaultModelName: String
) {
    CORE("CORE", "JARVIS 1 — CORE", "Cepat & Efisien", "Llama 3.3 70B"),
    PULSE("PULSE", "JARVIS 2 — PULSE", "Multimodal & Konteks Panjang", "Gemini 1.5 Pro"),
    SPARK("SPARK", "JARVIS 3 — SPARK", "Penalaran & Arsitektur Kode", "DeepSeek-V3"),
    APEX("APEX", "JARVIS 4 — APEX", "Flagship Multi-AI Orchestration", "GPT-4o")
}
