package com.jarvis.mobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
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
 * Holographic orb for B8 Liquid Glass redesign.
 * Bound to VoiceOrbState with liquid glass aesthetics, soft glow, and reactive rings.
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
        VoiceOrbState.Processing -> 3200
        VoiceOrbState.Responding -> 4200
        VoiceOrbState.Speaking -> 5800
        VoiceOrbState.Muted -> 22000
        else -> 12000
    }
    val spin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(spinDuration, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by infinite.animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(when (state) {
            VoiceOrbState.Listening -> 620
            VoiceOrbState.Processing -> 780
            VoiceOrbState.Responding -> 700
            VoiceOrbState.Speaking -> 840
            VoiceOrbState.Error -> 420
            VoiceOrbState.Muted -> 2000
            else -> 1500
        }), RepeatMode.Reverse),
        label = "pulse"
    )
    val ringBreath by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    val targetIntensity = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> 0.40f
        VoiceOrbState.Listening -> (0.55f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.40f)
        VoiceOrbState.Processing -> 0.74f
        VoiceOrbState.Responding -> 0.82f
        VoiceOrbState.Speaking -> 0.88f
        VoiceOrbState.Muted -> 0.22f
        VoiceOrbState.Error -> 0.90f
    }
    val intensity by animateFloatAsState(targetIntensity, tween(200), label = "intensity")

    val coreColor = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> JarvisTokens.AccentCyan
        VoiceOrbState.Listening -> Color(0xFF00F0FF)
        VoiceOrbState.Processing -> JarvisTokens.AccentPurple
        VoiceOrbState.Responding -> Color(0xFF40C4FF)
        VoiceOrbState.Speaking -> JarvisTokens.AccentCyan
        VoiceOrbState.Muted -> Color(0xFF78909C)
        VoiceOrbState.Error -> JarvisTokens.Error
    }
    val secondary = when (state) {
        VoiceOrbState.Processing -> Color(0xFFB388FF)
        VoiceOrbState.Responding -> Color(0xFF80D8FF)
        VoiceOrbState.Speaking -> Color(0xFF18FFFF)
        VoiceOrbState.Muted -> Color(0xFF546E7A)
        VoiceOrbState.Error -> JarvisTokens.Warning
        else -> Color(0xFF82B1FF)
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
            val effectivePulse = if (state == VoiceOrbState.Muted) 0.96f else pulse
            val baseR = this.size.minDimension * 0.27f * effectivePulse
            val rmsBoost = if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking) {
                (rmsLevel / 16f).coerceIn(0f, 1f) * baseR * 0.32f
            } else 0f

            // Soft liquid glass outer glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = 0.22f * intensity), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 2.9f
                ),
                radius = baseR * 2.9f,
                center = Offset(cx, cy)
            )

            val ringCount = if (state == VoiceOrbState.Muted) 1 else 3
            for (i in 0 until ringCount) {
                val phase = (ringBreath + i * 0.33f) % 1f
                val ringR = baseR * (1.32f + phase * 1.05f) + rmsBoost * (1f - i * 0.18f)
                val alpha = (1f - phase) * 0.34f * intensity
                drawCircle(
                    color = coreColor.copy(alpha = alpha),
                    radius = ringR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f + intensity * 1.3f)
                )
            }

            // Precision instrument halo
            rotate(-spin * 0.20f, Offset(cx, cy)) {
                val haloRadius = baseR * 2.08f
                drawCircle(
                    color = coreColor.copy(alpha = 0.16f * intensity),
                    radius = haloRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 0.8.dp.toPx())
                )
                for (tick in 0 until 48) {
                    val angle = Math.toRadians((tick * 7.5).toDouble())
                    val major = tick % 4 == 0
                    val inner = haloRadius - if (major) 7.dp.toPx() else 2.8.dp.toPx()
                    val outer = haloRadius + if (major) 2.8.dp.toPx() else 1.dp.toPx()
                    drawLine(
                        color = coreColor.copy(alpha = (if (major) 0.50f else 0.18f) * intensity),
                        start = Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                        end = Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                        strokeWidth = if (major) 1.1.dp.toPx() else 0.65.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            rotate(spin, Offset(cx, cy)) {
                val arcStroke = Stroke(width = 2.2f, cap = StrokeCap.Round)
                drawArc(
                    color = coreColor.copy(alpha = 0.55f * intensity),
                    startAngle = 0f,
                    sweepAngle = if (state == VoiceOrbState.Responding) 105f else 68f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.52f, cy - baseR * 1.52f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.04f, baseR * 3.04f),
                    style = arcStroke
                )
                drawArc(
                    color = secondary.copy(alpha = 0.42f * intensity),
                    startAngle = 135f,
                    sweepAngle = if (state == VoiceOrbState.Processing) 80f else 48f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.68f, cy - baseR * 1.68f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.36f, baseR * 3.36f),
                    style = Stroke(width = 1.7f, cap = StrokeCap.Round)
                )
            }
            rotate(-spin * 0.62f, Offset(cx, cy)) {
                drawArc(
                    color = JarvisTokens.AccentPurple.copy(alpha = 0.30f * intensity),
                    startAngle = 195f,
                    sweepAngle = 42f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.88f, cy - baseR * 1.88f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.76f, baseR * 3.76f),
                    style = Stroke(width = 1.3f, cap = StrokeCap.Round)
                )
            }

            // Liquid glass core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.80f * intensity),
                        coreColor.copy(alpha = 0.92f),
                        secondary.copy(alpha = 0.70f),
                        coreColor.copy(alpha = 0.12f)
                    ),
                    center = Offset(cx - baseR * 0.14f, cy - baseR * 0.18f),
                    radius = baseR * 1.12f
                ),
                radius = baseR,
                center = Offset(cx, cy)
            )

            // Hologram mesh
            rotate(spin * 0.40f, Offset(cx, cy)) {
                for (latitude in -2..2) {
                    val yOffset = latitude * baseR * 0.26f
                    val widthFactor = kotlin.math.sqrt(
                        (1f - (yOffset * yOffset) / (baseR * baseR)).coerceAtLeast(0.12f)
                    )
                    drawOval(
                        color = Color(0xFFB8F7FF).copy(alpha = 0.18f * intensity),
                        topLeft = Offset(
                            cx - baseR * 0.90f * widthFactor,
                            cy + yOffset - baseR * 0.15f * widthFactor
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            baseR * 1.80f * widthFactor,
                            baseR * 0.30f * widthFactor
                        ),
                        style = Stroke(width = 0.75.dp.toPx())
                    )
                }
                for (longitude in -2..2) {
                    val xScale = 0.21f + kotlin.math.abs(longitude) * 0.17f
                    drawOval(
                        color = Color(0xFF8DEEFF).copy(alpha = 0.14f * intensity),
                        topLeft = Offset(cx - baseR * xScale, cy - baseR * 0.94f),
                        size = androidx.compose.ui.geometry.Size(
                            baseR * xScale * 2f,
                            baseR * 1.88f
                        ),
                        style = Stroke(width = 0.75.dp.toPx())
                    )
                }
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.32f * intensity),
                radius = baseR * 0.26f,
                center = Offset(cx - baseR * 0.20f, cy - baseR * 0.26f)
            )

            if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking || state == VoiceOrbState.Responding) {
                val nodes = 8
                for (n in 0 until nodes) {
                    val ang = Math.toRadians((n * (360.0 / nodes) + spin * 0.28).toDouble())
                    val dist = baseR * (1.42f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.22f)
                    val nx = cx + cos(ang).toFloat() * dist
                    val ny = cy + sin(ang).toFloat() * dist
                    drawCircle(
                        color = coreColor.copy(alpha = 0.45f * intensity),
                        radius = 2.3f + intensity * 1.8f,
                        center = Offset(nx, ny)
                    )
                }
            }

            if (state == VoiceOrbState.Muted) {
                val slashOffset = baseR * 0.62f
                drawLine(
                    color = JarvisTokens.Warning.copy(alpha = 0.62f),
                    start = Offset(cx - slashOffset, cy - slashOffset),
                    end = Offset(cx + slashOffset, cy + slashOffset),
                    strokeWidth = 2.0.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
