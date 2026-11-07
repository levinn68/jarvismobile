package com.jarvis.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Premium chat bubbles, error cards, conversation chips, and composer for JARVIS Chat Mode.
 * Graphite base, cyan/violet accents — no large white panels.
 */

@Composable
fun JarvisUserBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            JarvisColors.Cyan.copy(alpha = 0.22f),
                            JarvisColors.AuroraMid.copy(alpha = 0.18f)
                        )
                    )
                )
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                color = JarvisColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun JarvisAssistantBubble(
    text: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(JarvisColors.CyanGlow.copy(alpha = 0.55f), JarvisColors.Graphite)
                    )
                )
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("J", color = JarvisColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(JarvisColors.PanelSolid.copy(alpha = 0.92f))
                .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .animateContentSize()
        ) {
            Column {
                Text(
                    text = text.ifBlank { if (isStreaming) "…" else "" },
                    color = JarvisColors.Ice,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (isStreaming && text.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = JarvisColors.Cyan
                    )
                }
            }
        }
    }
}

@Composable
fun JarvisChatErrorCard(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(JarvisShapes.CardSm)
            .background(JarvisColors.Error.copy(alpha = 0.12f))
            .border(1.dp, JarvisColors.Error.copy(alpha = 0.45f), JarvisShapes.CardSm)
            .padding(12.dp)
            .semantics { contentDescription = "Peringatan kesalahan sistem JARVIS" }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Error",
                tint = JarvisColors.Error,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "DIAGNOSTIK KONEKSI JARVIS",
                color = JarvisColors.Error,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            color = JarvisColors.Ice,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        if (onRetry != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Coba lagi",
                    tint = JarvisColors.Cyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("COBA LAGI", color = JarvisColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun JarvisChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: (() -> Unit)?,
    busy: Boolean,
    onCancel: (() -> Unit)?,
    placeholder: String = "Tulis perintah untuk JARVIS…",
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(JarvisColors.BackgroundElevated)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        JarvisColors.Cyan.copy(alpha = 0.55f),
                        JarvisColors.Violet.copy(alpha = 0.35f)
                    )
                ),
                RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 120.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = JarvisColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            cursorBrush = SolidColor(JarvisColors.Cyan),
            enabled = !busy,
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = JarvisColors.TextDim, fontSize = 14.sp)
                }
                inner()
            }
        )
        if (busy && onCancel != null) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Hentikan pemrosesan", tint = JarvisColors.Warn)
            }
        } else {
            if (onMic != null) {
                IconButton(
                    onClick = onMic,
                    enabled = !busy,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Mode Suara", tint = JarvisColors.CyanDim)
                }
            }
            IconButton(
                onClick = onSend,
                enabled = !busy && value.isNotBlank(),
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Kirim pesan",
                    tint = if (!busy && value.isNotBlank()) JarvisColors.Cyan else JarvisColors.TextDim
                )
            }
        }
    }
}

@Composable
fun JarvisConversationChip(
    title: String,
    updatedAt: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(JarvisShapes.CardSm)
            .background(if (selected) JarvisColors.Cyan.copy(alpha = 0.12f) else JarvisColors.PanelSolid)
            .border(
                1.dp,
                if (selected) JarvisColors.Cyan.copy(alpha = 0.5f) else JarvisColors.GlassBorder,
                JarvisShapes.CardSm
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = title.ifBlank { "Percakapan" },
            color = JarvisColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = updatedAt,
            color = JarvisColors.TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun JarvisThinkingBar(
    stage: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(JarvisShapes.Chip)
            .background(JarvisColors.Violet.copy(alpha = 0.12f))
            .border(1.dp, JarvisColors.Violet.copy(alpha = 0.35f), JarvisShapes.Chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = JarvisColors.VioletGlow
        )
        Text(
            text = stage.uppercase(),
            color = JarvisColors.VioletGlow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
