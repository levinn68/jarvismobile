package com.jarvis.mobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
 * Cupertino Liquid Glass + Aurora Holographic Orb bound to real [VoiceOrbState].
 * Designed as the large centered centerpiece for J.A.R.V.I.S. Voice Mode.
 * Supports all 7 states:
 * - Idle / Standby: luminous cyan-violet aurora sphere with calm breathing rings.
 * - Listening: reactive multi-ring expansion + live microphone RMS wave energy.
 * - Processing: accelerated dual-axis neural violet-cyan orbital spin.
 * - Responding: synthesized response burst before/with visual card reveal.
 * - Speaking: outward harmonic aurora waves + orbital energy nodes.
 * - Muted: restrained dark-glass cyan-indigo core with subtle standby ring.
 * - Error: high-visibility coral-amber alert pulse.
 */
@Composable
fun HolographicOrb(
    state: VoiceOrbState,
    rmsLevel: Float = 0f,
    size: Dp = 248.dp,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "jarvis_liquid_orb")
    val spinDuration = when (state) {
        VoiceOrbState.Processing -> 3200
        VoiceOrbState.Responding -> 4200
        VoiceOrbState.Speaking -> 5200
        VoiceOrbState.Listening -> 6800
        VoiceOrbState.Muted -> 16000
        else -> 11000
    }
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(spinDuration, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_spin"
    )
    val counterSpin by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween((spinDuration * 1.35f).toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "orb_counter_spin"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(
                durationMillis = when (state) {
                    VoiceOrbState.Listening -> 620
                    VoiceOrbState.Processing -> 740
                    VoiceOrbState.Responding -> 700
                    VoiceOrbState.Speaking -> 780
                    VoiceOrbState.Error -> 460
                    VoiceOrbState.Muted -> 1900
                    else -> 1500
                },
                easing = FastOutSlowInEasing
            ),
            RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )
    val ringBreath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_ring_breath"
    )

    val normalizedRms = (rmsLevel / 16f).coerceIn(0f, 1f)
    val targetIntensity = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> 0.72f
        VoiceOrbState.Listening -> (0.78f + normalizedRms * 0.22f)
        VoiceOrbState.Processing -> 0.88f
        VoiceOrbState.Responding -> 0.92f
        VoiceOrbState.Speaking -> (0.86f + normalizedRms * 0.14f)
        VoiceOrbState.Muted -> 0.56f
        VoiceOrbState.Error -> 0.94f
    }
    val intensity by animateFloatAsState(
        targetValue = targetIntensity,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "orb_intensity"
    )

    val coreColor = when (state) {
        VoiceOrbState.Idle, VoiceOrbState.Standby -> JarvisColors.CyanGlow
        VoiceOrbState.Listening -> JarvisColors.CyanGlow
        VoiceOrbState.Processing -> JarvisColors.VioletGlow
        VoiceOrbState.Responding -> JarvisColors.Responding
        VoiceOrbState.Speaking -> JarvisColors.Speaking
        VoiceOrbState.Muted -> JarvisColors.CyanDim
        VoiceOrbState.Error -> JarvisColors.Error
    }
    val secondaryColor = when (state) {
        VoiceOrbState.Processing -> JarvisColors.Cyan
        VoiceOrbState.Responding -> JarvisColors.VioletGlow
        VoiceOrbState.Speaking -> JarvisColors.CyanGlow
        VoiceOrbState.Muted -> JarvisColors.AuroraMid
        VoiceOrbState.Error -> JarvisColors.Warn
        else -> JarvisColors.Violet
    }
    val tertiaryColor = when (state) {
        VoiceOrbState.Speaking -> JarvisColors.VioletGlow
        VoiceOrbState.Listening -> JarvisColors.AuroraMid
        else -> JarvisColors.AuroraEnd
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
            val effectivePulse = if (state == VoiceOrbState.Muted) (0.96f + (pulse - 0.94f) * 0.35f) else pulse
            val baseR = this.size.minDimension * 0.29f * effectivePulse
            val rmsBoost = if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking) {
                normalizedRms * baseR * 0.38f
            } else {
                baseR * 0.04f
            }

            // 1. Deep outer ambient aurora halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.32f * intensity),
                        secondaryColor.copy(alpha = 0.18f * intensity),
                        tertiaryColor.copy(alpha = 0.06f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = baseR * 3.1f + rmsBoost
                ),
                radius = baseR * 3.1f + rmsBoost,
                center = Offset(cx, cy)
            )

            // 2. Expanding harmonic liquid wave rings
            val ringCount = if (state == VoiceOrbState.Muted) 2 else 4
            for (i in 0 until ringCount) {
                val phase = (ringBreath + i * (1f / ringCount)) % 1f
                val ringR = baseR * (1.18f + phase * 1.25f) + rmsBoost * (1f - i * 0.18f)
                val alpha = (1f - phase) * 0.42f * intensity
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            coreColor.copy(alpha = alpha),
                            secondaryColor.copy(alpha = alpha * 0.85f),
                            tertiaryColor.copy(alpha = alpha * 0.65f),
                            coreColor.copy(alpha = alpha)
                        ),
                        center = Offset(cx, cy)
                    ),
                    radius = ringR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f + intensity * 1.5f)
                )
            }

            // 3. Outer precision holographic instrument ring
            drawCircle(
                color = Color.White.copy(alpha = 0.12f * intensity),
                radius = baseR * 1.92f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // 4. Primary rotating aurora arcs
            rotate(spin, Offset(cx, cy)) {
                val arcStroke = Stroke(width = 3.2f, cap = StrokeCap.Round)
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            coreColor.copy(alpha = 0.78f * intensity),
                            Color.White.copy(alpha = 0.85f * intensity),
                            secondaryColor.copy(alpha = 0.65f * intensity),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy)
                    ),
                    startAngle = 12f,
                    sweepAngle = if (state == VoiceOrbState.Responding || state == VoiceOrbState.Speaking) 135f else 96f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.48f, cy - baseR * 1.48f),
                    size = androidx.compose.ui.geometry.Size(baseR * 2.96f, baseR * 2.96f),
                    style = arcStroke
                )
                drawArc(
                    color = secondaryColor.copy(alpha = 0.62f * intensity),
                    startAngle = 192f,
                    sweepAngle = if (state == VoiceOrbState.Processing) 110f else 74f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.66f, cy - baseR * 1.66f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.32f, baseR * 3.32f),
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                )
            }

            // 5. Counter-rotating inner & outer plasma filaments
            rotate(counterSpin, Offset(cx, cy)) {
                drawArc(
                    color = tertiaryColor.copy(alpha = 0.52f * intensity),
                    startAngle = 45f,
                    sweepAngle = 82f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.82f, cy - baseR * 1.82f),
                    size = androidx.compose.ui.geometry.Size(baseR * 3.64f, baseR * 3.64f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = coreColor.copy(alpha = 0.46f * intensity),
                    startAngle = 225f,
                    sweepAngle = 68f,
                    useCenter = false,
                    topLeft = Offset(cx - baseR * 1.28f, cy - baseR * 1.28f),
                    size = androidx.compose.ui.geometry.Size(baseR * 2.56f, baseR * 2.56f),
                    style = Stroke(width = 2.0f, cap = StrokeCap.Round)
                )
            }

            // 6. Refractive Liquid Glass Core Sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.94f * intensity),
                        coreColor.copy(alpha = 0.92f * intensity),
                        secondaryColor.copy(alpha = 0.78f * intensity),
                        Color(0xFF091B32).copy(alpha = 0.92f)
                    ),
                    center = Offset(cx - baseR * 0.18f, cy - baseR * 0.22f),
                    radius = baseR * 1.28f
                ),
                radius = baseR + rmsBoost * 0.28f,
                center = Offset(cx, cy)
            )

            // 7. Inner plasma swirl / aurora lens
            rotate(spin * 0.5f, Offset(cx, cy)) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.36f * intensity),
                            coreColor.copy(alpha = 0.22f * intensity),
                            tertiaryColor.copy(alpha = 0.40f * intensity)
                        ),
                        start = Offset(cx - baseR, cy - baseR),
                        end = Offset(cx + baseR, cy + baseR)
                    ),
                    radius = baseR * 0.82f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.6f)
                )
            }

            // 8. Specular glass rim border + top-left lens reflection
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.78f * intensity),
                        coreColor.copy(alpha = 0.55f * intensity),
                        secondaryColor.copy(alpha = 0.35f * intensity)
                    ),
                    start = Offset(cx - baseR, cy - baseR),
                    end = Offset(cx + baseR, cy + baseR)
                ),
                radius = baseR + rmsBoost * 0.28f,
                center = Offset(cx, cy),
                style = Stroke(width = 2.2f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.58f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(cx - baseR * 0.30f, cy - baseR * 0.34f),
                    radius = baseR * 0.46f
                ),
                radius = baseR * 0.46f,
                center = Offset(cx - baseR * 0.30f, cy - baseR * 0.34f)
            )

            // 9. Orbital telemetry nodes (active in all states, expanded on Listening/Speaking)
            val nodes = if (state == VoiceOrbState.Listening || state == VoiceOrbState.Speaking || state == VoiceOrbState.Responding) 12 else 8
            for (n in 0 until nodes) {
                val ang = Math.toRadians((n * (360.0 / nodes) + spin * 0.45).toDouble())
                val waveOffset = sin(Math.toRadians((ringBreath * 360f + n * 45f).toDouble())).toFloat() * (baseR * 0.08f)
                val dist = baseR * (1.38f + normalizedRms * 0.28f) + waveOffset
                val nx = cx + cos(ang).toFloat() * dist
                val ny = cy + sin(ang).toFloat() * dist
                drawCircle(
                    color = if (n % 2 == 0) coreColor.copy(alpha = 0.72f * intensity) else secondaryColor.copy(alpha = 0.64f * intensity),
                    radius = if (n % 3 == 0) 3.4f + intensity * 1.8f else 2.2f + intensity * 1.2f,
                    center = Offset(nx, ny)
                )
            }
        }
    }
}