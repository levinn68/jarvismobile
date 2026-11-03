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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holographic orb bound to real [VoiceOrbState].
 * Standby: slow pulse. Listening: reactive rings + RMS. Processing: spin.
 * Speaking: outward energy. Error: red alert pulse.
 */
@Composable
fun HolographicOrb(
    state: VoiceOrbState,
    rmsLevel: Float = 0f,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val spin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (state == VoiceOrbState.Processing) 4200 else 14000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by infinite.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(when (state) {
            VoiceOrbState.Listening -> 700
            VoiceOrbState.Speaking -> 900
            VoiceOrbState.Error -> 500
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
        VoiceOrbState.Standby -> 0.35f
        VoiceOrbState.Listening -> (0.55f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.45f)
        VoiceOrbState.Processing -> 0.7f
        VoiceOrbState.Speaking -> 0.85f
        VoiceOrbState.Error -> 0.9f
    }
    val intensity by animateFloatAsState(targetIntensity, tween(220), label = "intensity")

    val coreColor = when (state) {
        VoiceOrbState.Standby -> JarvisColors.Cyan
        VoiceOrbState.Listening -> JarvisColors.CyanGlow
        VoiceOrbState.Processing -> JarvisColors.VioletGlow
        VoiceOrbState.Speaking -> JarvisColors.Speaking
        VoiceOrbState.Error -> JarvisColors.Error
    }
    val secondary = when (state) {
        VoiceOrbState.Processing -> JarvisColors.Violet
        VoiceOrbState.Speaking -> JarvisColors.Cyan
        VoiceOrbState.Error -> JarvisColors.Warn
        else -> JarvisColors.AuroraMid
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val baseR = this.size.minDimension * 0.28f * pulse
            val rmsBoost = if (state == VoiceOrbState.Listening) (rmsLevel / 16f).coerceIn(0f, 1f) * baseR * 0.35f else 0f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = 0.22f * intensity), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 2.8f
                ),
                radius = baseR * 2.8f,
                center = Offset(cx, cy)
            )

            for (i in 0 until 3) {
                val phase = (ringBreath + i * 0.33f) % 1f
                val ringR = baseR * (1.35f + phase * 1.1f) + rmsBoost * (1f - i * 0.2f)
                val alpha = (1f - phase) * 0.35f * intensity
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
                    color = coreColor.copy(alpha = 0.55f * intensity),
                    startAngle = 0f,
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.55f, cy - baseR * 1.55f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.1f, baseR * 3.1f),
                    style = arcStroke
                )
                drawArc(
                    color = secondary.copy(alpha = 0.4f * intensity),
                    startAngle = 140f,
                    sweepAngle = 50f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.7f, cy - baseR * 1.7f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.4f, baseR * 3.4f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )
            }
            rotate(-spin * 0.65f, Offset(cx, cy)) {
                drawArc(
                    color = JarvisColors.VioletGlow.copy(alpha = 0.3f * intensity),
                    startAngle = 200f,
                    sweepAngle = 40f,
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

            if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking) {
                val nodes = 8
                for (n in 0 until nodes) {
                    val ang = Math.toRadians((n * (360.0 / nodes) + spin * 0.3).toDouble())
                    val dist = baseR * (1.45f + (rmsLevel / 16f).coerceIn(0f, 1f) * 0.25f)
                    val nx = cx + cos(ang).toFloat() * dist
                    val ny = cy + sin(ang).toFloat() * dist
                    drawCircle(
                        color = coreColor.copy(alpha = 0.45f * intensity),
                        radius = 2.5f + intensity * 2f,
                        center = Offset(nx, ny)
                    )
                }
            }
        }
    }
}
