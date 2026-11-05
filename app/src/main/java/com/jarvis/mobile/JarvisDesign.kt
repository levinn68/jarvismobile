package com.jarvis.mobile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object JarvisPalette {
    val Background = Color(0xFF050A12)
    val Surface = Color(0xFF0B1722)
    val SurfaceRaised = Color(0xFF102331)
    val Cyan = Color(0xFF69E4F5)
    val CyanBright = Color(0xFFB9F7FF)
    val Muted = Color(0xFF7895A4)
    val Text = Color(0xFFEAF8FC)
    val Green = Color(0xFF62DDB1)
}

@Composable
internal fun JarvisBackdrop(ambience: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF03070D), Color(0xFF081722), Color(0xFF03080E))))
        val center = Offset(w * .5f, h * .37f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF1D8395).copy(alpha = .22f * ambience), Color.Transparent),
                center, w * .72f
            ), w * .72f, center
        )
        val step = 44.dp.toPx()
        val alpha = .045f * ambience
        for (x in 0..(w / step).toInt()) {
            drawLine(Color(0xFF6CEBFA).copy(alpha = alpha), Offset(x * step, 0f), Offset(x * step, h), .65f)
        }
        for (y in 0..(h / step).toInt()) {
            drawLine(Color(0xFF6CEBFA).copy(alpha = alpha * .8f), Offset(0f, y * step), Offset(w, y * step), .65f)
        }
        val horizon = h * .68f
        for (i in 0..9) {
            val t = i / 9f
            val y = horizon + (h - horizon) * t * t
            drawLine(
                Color(0xFF65DFF0).copy(alpha = .11f * ambience * (1f - t * .55f)),
                Offset(w * .06f, y), Offset(w * .94f, y), .75f
            )
        }
        for (i in -7..7) {
            drawLine(
                Color(0xFF65DFF0).copy(alpha = .075f * ambience),
                Offset(w * .5f, horizon), Offset(w * (.5f + i * .075f), h), .7f
            )
        }
    }
}

@Composable
internal fun JarvisTopBar(status: String, busy: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF1A5667), Color(0xFF0A202D))), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x8874E9F8), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("J", color = JarvisPalette.CyanBright, fontSize = 27.sp, fontWeight = FontWeight.ExtraLight)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("J.A.R.V.I.S.", color = JarvisPalette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp)
            Text("PERSONAL INTELLIGENCE", color = JarvisPalette.Muted, fontSize = 7.sp, letterSpacing = 1.7.sp)
        }
        Row(
            Modifier.background(Color(0xFF0D252B), CircleShape)
                .border(1.dp, Color(0x443FD7B0), CircleShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(6.dp).background(if (busy) Color(0xFFFFC56D) else JarvisPalette.Green, CircleShape))
            Text(if (busy) "WORKING" else status.uppercase().take(12), color = Color(0xFFB5D3D8), fontSize = 7.sp, letterSpacing = .8.sp)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "Pengaturan", tint = Color(0xFF9CBAC5), modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun JarvisModeSwitcher(selected: String, onSelected: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xB90A1721), RoundedCornerShape(17.dp))
            .border(1.dp, Color(0x334C9AA9), RoundedCornerShape(17.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("CHAT", "VOICE").forEach { mode ->
            val active = selected == mode
            Box(
                Modifier.weight(1f).height(39.dp)
                    .background(
                        if (active) Brush.horizontalGradient(listOf(Color(0xFF16495A), Color(0xFF10303F)))
                        else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
                        RoundedCornerShape(13.dp)
                    )
                    .border(if (active) 1.dp else .5.dp, if (active) Color(0x665CE1F2) else Color.Transparent, RoundedCornerShape(13.dp))
                    .clickable { onSelected(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (mode == "CHAT") "CHAT  /  TEXT" else "VOICE  /  LIVE",
                    color = if (active) JarvisPalette.CyanBright else JarvisPalette.Muted,
                    fontSize = 9.sp, letterSpacing = 1.15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun JarvisWelcomeHero(compact: Boolean, onAction: (String) -> Unit) {
    val motion = rememberInfiniteTransition(label = "welcomeHologram")
    val rotation by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(22000), RepeatMode.Restart), label = "heroRotation")
    val pulse by motion.animateFloat(.94f, 1.035f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "heroPulse")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = if (compact) 7.dp else 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.background(Color(0xAA0C2933), CircleShape)
                .border(1.dp, Color(0x5557D5E7), CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(6.dp).background(JarvisPalette.Green, CircleShape))
            Text("SYSTEM ONLINE  ·  READY", color = Color(0xFFA9DCE4), fontSize = 8.sp, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(if (compact) 205.dp else 245.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * .5f
                val cy = size.height * .46f
                val r = size.minDimension * .37f
                val c = JarvisPalette.Cyan
                drawCircle(Brush.radialGradient(listOf(Color(0x5531CDE7), Color(0x1421AFC8), Color.Transparent), Offset(cx, cy), r * 1.8f), r * 1.8f, Offset(cx, cy))
                drawCircle(c.copy(alpha = .35f), r * 1.14f, Offset(cx, cy), style = Stroke(1.dp.toPx()))
                drawCircle(c.copy(alpha = .18f), r * .96f, Offset(cx, cy), style = Stroke(.8.dp.toPx()))
                drawCircle(c.copy(alpha = .25f), r * .77f, Offset(cx, cy), style = Stroke(.8.dp.toPx()))
                for (i in 0 until 64) {
                    val angle = Math.PI * 2 * i / 64 + rotation * Math.PI / 180.0
                    val major = i % 4 == 0
                    val inner = r * if (major) 1.02f else 1.07f
                    val outer = r * if (major) 1.16f else 1.11f
                    drawLine(
                        c.copy(alpha = if (major) .85f else .26f),
                        Offset(cx + kotlin.math.cos(angle).toFloat() * inner, cy + kotlin.math.sin(angle).toFloat() * inner),
                        Offset(cx + kotlin.math.cos(angle).toFloat() * outer, cy + kotlin.math.sin(angle).toFloat() * outer),
                        if (major) 1.2.dp.toPx() else .65.dp.toPx()
                    )
                }
                drawCircle(Brush.radialGradient(listOf(Color(0xFF236D7D), Color(0xFF0B2936), Color(0xFF06121C)), Offset(cx, cy), r * .72f), r * .72f, Offset(cx, cy))
                drawCircle(Color(0xFF9BF2FF).copy(alpha = .58f), r * .72f, Offset(cx, cy), style = Stroke(1.dp.toPx()))
                // Thin holographic scan line.
                val scanY = cy + kotlin.math.sin(rotation * Math.PI / 180.0).toFloat() * r * .42f
                drawLine(Color(0xFFB6F8FF).copy(alpha = .42f), Offset(cx - r * .5f, scanY), Offset(cx + r * .5f, scanY), .8f)
            }
            Box(
                Modifier.size(if (compact) 105.dp else 122.dp).scale(pulse)
                    .background(Brush.radialGradient(listOf(Color(0xFF1B6072), Color(0xFF0A2B38), Color(0xFF061721))), CircleShape)
                    .border(1.dp, Color(0xAA9CF3FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("J", color = JarvisPalette.CyanBright, fontSize = if (compact) 69.sp else 78.sp, fontWeight = FontWeight.ExtraLight)
            }
            Text("CORE 01", color = Color(0xFF70BCCA), fontSize = 7.sp, letterSpacing = 1.6.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 13.dp))
            Text("NEURAL INTERFACE", color = Color(0xFF70BCCA), fontSize = 7.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 9.dp))
        }
        Text("Good to see you.", color = Color(0xFF91C9D3), fontSize = 13.sp, letterSpacing = .4.sp)
        Spacer(Modifier.height(3.dp))
        Text("What can I do for you?", color = JarvisPalette.Text, fontSize = if (compact) 25.sp else 29.sp,
            fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
        Spacer(Modifier.height(7.dp))
        Text("Ask a question, start a project, or give me a task.", color = JarvisPalette.Muted,
            fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val actions = listOf(
                Triple("⌘", "Explore", "Explore my GitHub repositories"),
                Triple("⌁", "Build", "Help me build or debug code"),
                Triple("◈", "Plan", "Help me plan a project"),
                Triple("✧", "Tools", "Show me available JARVIS tools")
            )
            actions.forEach { item ->
                Column(
                    Modifier.weight(1f).height(if (compact) 78.dp else 88.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xFF112D3A), Color(0xFF091923))), RoundedCornerShape(13.dp))
                        .border(1.dp, Color(0x4456C8DC), RoundedCornerShape(13.dp))
                        .clickable { onAction(item.third) }.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.first, color = JarvisPalette.Cyan, fontSize = 17.sp)
                    Text(item.second, color = JarvisPalette.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
