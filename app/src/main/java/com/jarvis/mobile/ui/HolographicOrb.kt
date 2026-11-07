package com.jarvis.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holographic orb bound to real [VoiceOrbState].
 * Supports all 7 states:
 * - Idle / Standby: calm ambient pulse.
 * - Listening: reactive rings + live microphone RMS expansion.
 * - Processing: accelerated dual-ring orbital spin.
 * - Responding: synthesized response burst before/with visual card reveal.
 * - Speaking: outward harmonic energy + orbital nodes.
 * - Muted: dimmed graphite-amber dormant ring with diagonal mute vector.
 * - Error: high-visibility alert pulse.
 */
@Composable
fun HolographicOrb(
    state: VoiceOrbState,
    rmsLevel: Float = 0f,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val spinDuration = when (state) {
        VoiceOrbState.Processing -> 3600
        VoiceOrbState.Responding -> 4800
        VoiceOrbState.Speaking -> 6400
        VoiceOrbState.Muted -> 24000
        else -> 14000
    }
    val spin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(spinDuration, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by infinite.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(when (state) {
            VoiceOrbState.Listening -> 680
            VoiceOrbState.Processing -> 820
            VoiceOrbState.Responding -> 760
            VoiceOrbState.Speaking -> 880
            VoiceOrbState.Error -> 480
            VoiceOrbState.Muted -> 2200
            else -> 1600
        }), RepeatMode.Reverse),
        label = "pulse"
    )
    val ringBreath by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    val targetIntensity = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> 0.42f
        VoiceOrbState.Listening -> (0.58f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.42f)
        VoiceOrbState.Processing -> 0.76f
        VoiceOrbState.Responding -> 0.84f
        VoiceOrbState.Speaking -> 0.90f
        VoiceOrbState.Muted -> 0.25f
        VoiceOrbState.Error -> 0.92f
    }
    val intensity by animateFloatAsState(targetIntensity, tween(220), label = "intensity")

    val coreColor = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> JarvisColors.Cyan
        VoiceOrbState.Listening -> JarvisColors.CyanGlow
        VoiceOrbState.Processing -> JarvisColors.VioletGlow
        VoiceOrbState.Responding -> JarvisColors.Responding
        VoiceOrbState.Speaking -> JarvisColors.Speaking
        VoiceOrbState.Muted -> JarvisColors.Muted
        VoiceOrbState.Error -> JarvisColors.Error
    }
    val secondary = when (state) {
        VoiceOrbState.Processing -> JarvisColors.Violet
        VoiceOrbState.Responding -> JarvisColors.CyanGlow
        VoiceOrbState.Speaking -> JarvisColors.Cyan
        VoiceOrbState.Muted -> JarvisColors.TextDim
        VoiceOrbState.Error -> JarvisColors.Warn
        else -> JarvisColors.AuroraMid
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "JARVIS Holographic Orb (${state.name})" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val effectivePulse = if (state == VoiceOrbState.Muted) 0.95f else pulse
            val baseR = this.size.minDimension * 0.28f * effectivePulse
            val rmsBoost = if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking) {
                (rmsLevel / 16f).coerceIn(0f, 1f) * baseR * 0.35f
            } else 0f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = 0.24f * intensity), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 2.8f
                ),
                radius = baseR * 2.8f,
                center = Offset(cx, cy)
            )

            val ringCount = if (state == VoiceOrbState.Muted) 1 else 3
            for (i in 0 until ringCount) {
                val phase = (ringBreath + i * 0.33f) % 1f
                val ringR = baseR * (1.35f + phase * 1.1f) + rmsBoost * (1f - i * 0.2f)
                val alpha = (1f - phase) * 0.36f * intensity
                drawCircle(
                    color = coreColor.copy(alpha = alpha),
                    radius = ringR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6f + intensity * 1.4f)
                )
            }

            rotate(spin, Offset(cx, cy)) {
                val arcStroke = Stroke(width = 2.4f, cap = StrokeCap.Round)
                drawArc(
                    color = coreColor.copy(alpha = 0.58f * intensity),
                    startAngle = 0f,
                    sweepAngle = if (state == VoiceOrbState.Responding) 110f else 72f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.55f, cy - baseR * 1.55f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.1f, baseR * 3.1f),
                    style = arcStroke
                )
                drawArc(
                    color = secondary.copy(alpha = 0.44f * intensity),
                    startAngle = 140f,
                    sweepAngle = if (state == VoiceOrbState.Processing) 85f else 50f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.7f, cy - baseR * 1.7f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.4f, baseR * 3.4f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )
            }
            rotate(-spin * 0.65f, Offset(cx, cy)) {
                drawArc(
                    color = JarvisColors.VioletGlow.copy(alpha = 0.32f * intensity),
                    startAngle = 200f,
                    sweepAngle = 44f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.9f, cy - baseR * 1.9f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.8f, baseR * 3.8f),
                    style = Stroke(width = 1.4f, cap = StrokeCap.Round)
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f * intensity),
                        coreColor.copy(alpha = 0.95f),
                        secondary.copy(alpha = 0.75f),
                        coreColor.copy(alpha = 0.15f)
                    ),
                    center = Offset(cx - baseR * 0.15f, cy - baseR * 0.2f),
                    radius = baseR * 1.15f
                ),
                radius = baseR,
                center = Offset(cx, cy)
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.35f * intensity),
                radius = baseR * 0.28f,
                center = Offset(cx - baseR * 0.22f, cy - baseR * 0.28f)
            )

            if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking || state == VoiceOrbState.Responding) {
                val nodes = 8
                for (n in 0 until nodes) {
                    val ang = Math.toRadians((n * (360.0 / nodes) + spin * 0.3).toDouble())
                    val dist = baseR * (1.45f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.25f)
                    val nx = cx + cos(ang).toFloat() * dist
                    val ny = cy + sin(ang).toFloat() * dist
                    drawCircle(
                        color = coreColor.copy(alpha = 0.48f * intensity),
                        radius = 2.5f + intensity * 2f,
                        center = Offset(nx, ny)
                    )
                }
            }

            if (state == VoiceOrbState.Muted) {
                val slashOffset = baseR * 0.65f
                drawLine(
                    color = JarvisColors.Warn.copy(alpha = 0.65f),
                    start = Offset(cx - slashOffset, cy - slashOffset),
                    end = Offset(cx + slashOffset, cy + slashOffset),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
