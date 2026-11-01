package com.jarvis.mobile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val HoloTeal = Color(0xFF3FE9D6)
private val HoloCyan = Color(0xFF64E8FF)
private const val TAU = 6.2831855f

/**
 * Holographic reactor modelled on the reference video: a translucent, wobbling particle membrane,
 * concentric rings with rotating arc segments, a honeycomb core and a "JARVIS" title that
 * materialises (with glitch + scanlines) once per cycle.
 *
 * All motion uses integer harmonics of a looping phase, so the loop is seamless.
 */
@Composable
internal fun HologramCore(modifier: Modifier = Modifier, active: Boolean, busy: Boolean) {
    val transition = rememberInfiniteTransition(label = "holo")
    val t by transition.animateFloat(0f, TAU, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val fast by transition.animateFloat(0f, TAU, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "fast")
    val cycle by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "cycle")
    val energy by animateFloatAsState(if (busy) 1f else if (active) .62f else .3f, tween(700), label = "energy")
    val measurer = rememberTextMeasurer()
    val title = remember(measurer) {
        measurer.measure(
            "JARVIS",
            TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, letterSpacing = 3.sp)
        )
    }

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val c = Offset(cx, cy)
        val r = size.minDimension * .42f
        val hair = 1.dp.toPx()
        val pulse = .5f + .5f * sin(fast)
        val showTitle = cycle in .52f..0.70f
        val coreDim = if (showTitle) .35f else 1f

        // Soft teal glow behind everything.
        drawCircle(
            Brush.radialGradient(
                listOf(HoloTeal.copy(alpha = .20f + .10f * energy), HoloTeal.copy(alpha = .05f), Color.Transparent), c, r * 1.55f
            ),
            r * 1.55f, c
        )

        // Organic particle membrane (4 layers of dots; the first layer is also joined into a faint contour).
        val points = 220
        for (layer in 0..3) {
            val base = r * (.86f + layer * .042f)
            val contour = Path()
            for (i in 0 until points) {
                val a = TAU * i / points
                val wobble = (.020f + .030f * energy) * sin(3f * a + 2f * t + layer) +
                    (.014f + .020f * energy) * sin(5f * a - 3f * t + layer * 1.7f) +
                    .010f * sin(8f * a + 5f * t) +
                    .006f * sin(13f * a - 4f * t + layer)
                val jitter = sin(i * 12.9898f + layer * 78.233f) * .006f
                val rad = base * (1f + wobble + jitter)
                val p = Offset(cx + cos(a) * rad, cy + sin(a) * rad)
                if (layer == 0) {
                    if (i == 0) contour.moveTo(p.x, p.y) else contour.lineTo(p.x, p.y)
                }
                val sparkle = .5f + .5f * sin(2f * fast + i * .7f + layer)
                drawCircle(
                    lerp(HoloTeal, HoloCyan, layer / 3f).copy(alpha = (.22f + .55f * sparkle) * (1f - layer * .15f)),
                    hair * (.7f + 1.1f * sparkle), p
                )
            }
            if (layer == 0) {
                contour.close()
                drawPath(contour, HoloTeal.copy(alpha = .22f), style = Stroke(hair * .8f))
            }
        }

        // Concentric rings.
        val ringAlpha = .30f + .25f * energy
        drawCircle(HoloTeal.copy(alpha = ringAlpha), r * .74f, c, style = Stroke(hair * 1.4f))
        drawCircle(HoloTeal.copy(alpha = ringAlpha * .55f), r * .66f, c, style = Stroke(hair * .8f))
        drawCircle(HoloCyan.copy(alpha = ringAlpha * .8f), r * .52f, c, style = Stroke(hair * 2.2f))
        drawCircle(HoloTeal.copy(alpha = ringAlpha * .5f), r * .40f, c, style = Stroke(hair))

        // Rotating arc segments.
        val deg = t * 57.29578f
        for (i in 0..2) {
            val rr = r * (.62f + i * .06f)
            drawArc(
                HoloCyan.copy(alpha = .35f + .3f * energy),
                deg * (if (i % 2 == 0) 1f else -1.6f) + i * 120f, 38f + 12f * i, false,
                Offset(cx - rr, cy - rr), Size(rr * 2f, rr * 2f), style = Stroke(hair * 1.6f, cap = StrokeCap.Round)
            )
        }

        // Tick ring (one full turn per loop).
        for (i in 0 until 72) {
            val a = TAU * i / 72 + t
            val major = i % 6 == 0
            val r1 = r * .78f
            val r2 = r1 + hair * (if (major) 7f else 3.5f)
            drawLine(
                HoloTeal.copy(alpha = if (major) .6f else .25f),
                Offset(cx + cos(a) * r1, cy + sin(a) * r1), Offset(cx + cos(a) * r2, cy + sin(a) * r2), hair
            )
        }

        // Honeycomb core.
        val hexR = r * .058f
        val dx = hexR * 1.7320508f
        val dy = hexR * 1.5f
        val coreR = r * .34f
        for (row in -6..6) for (col in -6..6) {
            val hx = cx + col * dx + (if ((row and 1) != 0) dx / 2f else 0f)
            val hy = cy + row * dy
            val d = hypot(hx - cx, hy - cy)
            if (d <= coreR) {
                val fade = 1f - d / coreR
                val hp = Path()
                for (k in 0 until 6) {
                    val ang = TAU * k / 6 + TAU / 12
                    val px = hx + cos(ang) * hexR * .92f
                    val py = hy + sin(ang) * hexR * .92f
                    if (k == 0) hp.moveTo(px, py) else hp.lineTo(px, py)
                }
                hp.close()
                drawPath(hp, HoloTeal.copy(alpha = (.10f + .30f * fade) * (.6f + .4f * pulse) * coreDim), style = Stroke(hair * .8f))
            }
        }
        drawCircle(HoloCyan.copy(alpha = (.45f + .4f * pulse) * coreDim), r * .045f, c)
        val scanY = cy + sin(3f * t) * coreR * .9f
        drawLine(HoloCyan.copy(alpha = .35f * coreDim), Offset(cx - coreR, scanY), Offset(cx + coreR, scanY), hair)

        // Expanding ripple (stronger while listening / thinking).
        val p = fast / TAU
        drawCircle(HoloCyan.copy(alpha = (1f - p) * (.10f + .25f * energy)), r * (.3f + .75f * p), c, style = Stroke(hair * 1.2f))

        // "JARVIS" materialises once per cycle with chromatic glitch and scanlines.
        if (showTitle) {
            val local = (cycle - .52f) / .18f
            val envelope = sin(local * 3.1415927f).coerceIn(0f, 1f)
            val flick = if (((fast * 9f).toInt() % 5) == 0) .55f else 1f
            val a = (envelope * flick).coerceIn(0f, 1f)
            val tl = Offset(cx - title.size.width / 2f, cy - title.size.height / 2f)
            val shift = (1f - envelope) * 10f
            drawText(title, HoloCyan, tl + Offset(shift, 0f), alpha = a * .45f)
            drawText(title, HoloTeal, tl - Offset(shift * .6f, 0f), alpha = a * .6f)
            drawText(title, Color(0xFFD9FFFB), tl, alpha = a * .85f)
            var y = tl.y
            val bottom = tl.y + title.size.height
            while (y < bottom) {
                drawLine(Color(0xFF021014).copy(alpha = .55f), Offset(tl.x - 8f, y), Offset(tl.x + title.size.width + 8f, y), hair)
                y += hair * 3f
            }
        }
    }
}
