package com.jarvis.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jarvis B8 Liquid Glass Design System
 * Central tokens + primitives for the redesign.
 */
object JarvisTokens {
    // Core palette - liquid glass / holographic
    val BackgroundDeep = Color(0xFF05070D)
    val BackgroundElevated = Color(0xFF0C101C)
    val SurfaceGlass = Color(0x14FFFFFF)
    val SurfaceGlassStrong = Color(0x22FFFFFF)
    val BorderGlass = Color(0x33FFFFFF)
    val BorderGlassStrong = Color(0x55FFFFFF)

    val AccentCyan = Color(0xFF00E5FF)
    val AccentBlue = Color(0xFF2979FF)
    val AccentPurple = Color(0xFF7C4DFF)
    val AccentPink = Color(0xFFFF4081)
    val AccentGold = Color(0xFFFFD740)

    val TextPrimary = Color(0xFFF5F7FF)
    val TextSecondary = Color(0xFFA8B0C8)
    val TextMuted = Color(0xFF6B738C)

    val Success = Color(0xFF00E676)
    val Warning = Color(0xFFFFAB00)
    val Error = Color(0xFFFF5252)

    val RadiusSm = 10.dp
    val RadiusMd = 16.dp
    val RadiusLg = 24.dp
    val RadiusXl = 32.dp

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val Space2Xl = 32.dp
}

@Composable
fun liquidGlassBrush(
    accent: Color = JarvisTokens.AccentCyan,
    intensity: Float = 0.35f
): Brush {
    return Brush.linearGradient(
        colors = listOf(
            accent.copy(alpha = intensity * 0.55f),
            Color.White.copy(alpha = intensity * 0.12f),
            accent.copy(alpha = intensity * 0.25f),
            Color.Transparent
        )
    )
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = JarvisTokens.RadiusMd,
    borderAlpha: Float = 0.35f,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(JarvisTokens.SurfaceGlass)
            .border(
                width = 1.dp,
                color = JarvisTokens.BorderGlass.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(radius)
            )
            .padding(JarvisTokens.SpaceMd),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = JarvisTokens.AccentCyan,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "btnScale"
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) accent.copy(alpha = 0.28f) else accent.copy(alpha = 0.16f),
        label = "btnBg"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(JarvisTokens.RadiusMd))
            .background(bg)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(JarvisTokens.RadiusMd))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = JarvisTokens.SpaceLg, vertical = JarvisTokens.SpaceMd),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) JarvisTokens.TextPrimary else JarvisTokens.TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = JarvisTokens.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp
    )
}
