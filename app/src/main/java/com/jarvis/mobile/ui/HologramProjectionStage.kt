package com.jarvis.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Translucent glass HUD projection stage.
 * Uses layered gradients and restrained edge highlights so the panel reads as
 * glass without relying on expensive real-time blur on the mobile GPU.
 */
@Composable
fun HologramProjectionStage(
    visible: Boolean,
    title: String = "HOLOGRAPHIC PROJECTION",
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(420)) + slideInVertically(
            animationSpec = tween(520),
            initialOffsetY = { it / 4 }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xA82C6075),
                            Color(0x7A102C40),
                            Color(0xA8061725)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xB8B9F5FF),
                            Color(0x4D55CBEA),
                            Color(0x8064DDF4)
                        )
                    ),
                    shape = shape
                )
        ) {
            // Subtle glass sheen; intentionally static and GPU-light.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.075f),
                            0.16f to Color(0x2259DDF5),
                            0.48f to Color.Transparent,
                            1f to Color(0x1600BFEA)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        color = Color(0xFFC5F6FF),
                        fontSize = 9.sp,
                        letterSpacing = 1.65.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "PROJECTION ACTIVE",
                        color = Color(0xFF78DDF2),
                        fontSize = 8.sp,
                        letterSpacing = 0.9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}
