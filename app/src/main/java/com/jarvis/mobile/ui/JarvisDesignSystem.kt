package com.jarvis.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * J.A.R.V.I.S. Unified Cupertino Liquid Glass + Smooth Motion Design System
 * Dark graphite base, cyan/violet aurora refractive glass, specular bevel highlights,
 * continuous squircle radii, and spring physics tuned for ASUS ROG Phone 6 (Android 14).
 */
object JarvisColors {
    val Background = Color(0xFF040911)
    val BackgroundElevated = Color(0xFF07111E)
    val Graphite = Color(0xFF0A1626)
    val Panel = Color(0xD90B1829)
    val PanelSolid = Color(0xFF0C1A2B)
    val Glass = Color(0xE60A1726)
    val GlassElevated = Color(0xEB102238)
    val GlassBorder = Color(0x40A8ECFF)
    val GlassSpecularTop = Color(0x73E2FAFF)
    val GlassSpecularBottom = Color(0x1F5B8CFF)

    val Cyan = Color(0xFF00E5FF)
    val CyanGlow = Color(0xFF38F3FF)
    val CyanDim = Color(0xFF0C95B0)
    val CyanBorder = Color(0xFF195E80)

    val Violet = Color(0xFF8B5CFF)
    val VioletGlow = Color(0xFFB996FF)
    val AuroraStart = Color(0xFF00E5FF)
    val AuroraMid = Color(0xFF5B8CFF)
    val AuroraEnd = Color(0xFFB26BFF)

    val Ice = Color(0xFFF0FAFF)
    val TextPrimary = Color(0xFFECF8FF)
    val TextMuted = Color(0xFF89A9BE)
    val TextDim = Color(0xFF526F84)

    val Ok = Color(0xFF00F59B)
    val Warn = Color(0xFFFFC56A)
    val Error = Color(0xFFFF5E6E)
    val Danger = Error
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
    val SquircleLg = RoundedCornerShape(24.dp)
    val SquircleMd = RoundedCornerShape(18.dp)
    val SquircleSm = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(20.dp)
    val CardSm = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(999.dp)
    val Sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val Button = RoundedCornerShape(16.dp)
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

fun liquidGlassCardBrush(highlighted: Boolean = false): Brush = Brush.verticalGradient(
    colors = if (highlighted) {
        listOf(
            Color(0xEE142C44),
            Color(0xE00C1C2E),
            Color(0xD8081320)
        )
    } else {
        listOf(
            Color(0xE6102236),
            Color(0xD90A1624),
            Color(0xD107101B)
        )
    }
)

fun liquidGlassBorderBrush(highlighted: Boolean = false, accentColor: Color = JarvisColors.Cyan): Brush =
    Brush.verticalGradient(
        colors = if (highlighted) {
            listOf(
                JarvisColors.GlassSpecularTop,
                accentColor.copy(alpha = 0.62f),
                JarvisColors.Violet.copy(alpha = 0.38f)
            )
        } else {
            listOf(
                Color(0x52D8F5FF),
                accentColor.copy(alpha = 0.26f),
                Color(0x226888A8)
            )
        }
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
 * J.A.R.V.I.S. B8.1 Design System Extensions — Typography, Smooth Motion Tokens,
 * 3-Destination Bottom Navigation (`Chat`, `Voice`, `More`),
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
    const val StandardMs = 260
    const val EmphasisMs = 380
    const val InitStepMs = 650
    const val FAST_MS = FastMs
    const val STANDARD_MS = StandardMs
    const val EMPHASIS_MS = EmphasisMs

    fun <T> springBouncy() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    fun <T> springSmooth() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

enum class JarvisB8NavTab(val route: String, val labelId: String) {
    BERANDA("BERANDA", "Beranda"), // Legacy route; reused as the Models navigation selection while its selector is open.
    CHAT("CHAT", "Chat"),
    KOLABORASI("KOLABORASI", "Kolaborasi"),
    VOICE("VOICE", "Voice"),
    PENGATURAN("PENGATURAN", "More")
}

enum class JarvisIntelligenceTier(
    val code: String,
    val displayTitle: String,
    val badgeSummary: String,
    val defaultModelName: String,
    val defaultModelId: String = "gpt-4o"
) {
    CORE("CORE", "JARVIS 1 — CORE", "Cepat & Efisien", "Llama 3.3 70B", "llama-3.3-70b"),
    PULSE("PULSE", "JARVIS 2 — PULSE", "Multimodal & Konteks Panjang", "Gemini 1.5 Pro", "gemini-1.5-pro"),
    SPARK("SPARK", "JARVIS 3 — SPARK", "Penalaran & Arsitektur Kode", "DeepSeek-V3", "deepseek-v3"),
    APEX("APEX", "JARVIS 4 — APEX", "Flagship Multi-AI Orchestration", "GPT-4o", "gpt-4o")
}

@Composable
fun JarvisLiquidGlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    accentColor: Color = JarvisColors.Cyan,
    shape: Shape = JarvisShapes.Card,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val baseModifier = modifier
        .shadow(if (highlighted) 14.dp else 6.dp, shape)
        .clip(shape)
        .background(liquidGlassCardBrush(highlighted), shape)
        .border(
            width = if (highlighted) 1.2.dp else 1.dp,
            brush = liquidGlassBorderBrush(highlighted, accentColor),
            shape = shape
        )
        .let { mod ->
            if (onClick != null) mod.clickable(onClick = onClick) else mod
        }
        .padding(contentPadding)

    Column(modifier = baseModifier, content = content)
}

@Composable
fun JarvisLiquidGlassPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accentColor: Color = JarvisColors.CyanGlow,
    leadingBadge: String? = null,
    trailingBadge: String? = null,
    onClick: (() -> Unit)? = null
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1f,
        animationSpec = JarvisMotion.springSmooth(),
        label = "pill_scale"
    )
    Row(
        modifier = modifier
            .scale(scale)
            .clip(JarvisShapes.Chip)
            .background(
                if (selected) {
                    Brush.horizontalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.28f),
                            JarvisColors.Violet.copy(alpha = 0.22f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color(0xDD112338), Color(0xCC091422))
                    )
                }
            )
            .border(
                width = 1.dp,
                brush = if (selected) {
                    Brush.horizontalGradient(listOf(JarvisColors.GlassSpecularTop, accentColor))
                } else {
                    liquidGlassBorderBrush(false, accentColor)
                },
                shape = JarvisShapes.Chip
            )
            .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (!leadingBadge.isNullOrBlank()) {
            Text(
                text = leadingBadge,
                color = if (selected) accentColor else JarvisColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = if (selected) JarvisColors.Ice else JarvisColors.TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!trailingBadge.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = trailingBadge,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun JarvisSegmentedGlassTabs(
    tabs: List<Pair<Int, String>>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(JarvisShapes.Chip)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xE60B1828), Color(0xE607101C))
                )
            )
            .border(1.dp, liquidGlassBorderBrush(false), JarvisShapes.Chip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (idx, label) ->
            val selected = selectedTab == idx
            val textColor by animateColorAsState(
                targetValue = if (selected) JarvisColors.Ice else JarvisColors.TextMuted,
                animationSpec = tween(JarvisMotion.FastMs),
                label = "seg_tab_text_$idx"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(JarvisShapes.Chip)
                    .background(
                        if (selected) {
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF163854),
                                    Color(0xFF1E2D52)
                                )
                            )
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    )
                    .let { mod ->
                        if (selected) {
                            mod.border(
                                1.dp,
                                Brush.horizontalGradient(
                                    listOf(JarvisColors.CyanGlow.copy(alpha = 0.65f), JarvisColors.VioletGlow.copy(alpha = 0.45f))
                                ),
                                JarvisShapes.Chip
                            )
                        } else mod
                    }
                    .clickable { onSelectTab(idx) }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}