package com.jarvis.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Cupertino Liquid Glass chat bubbles, error cards, conversation chips,
 * unified attachment menu, and compact pill composer for J.A.R.V.I.S. Chat Mode.
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
                .shadow(8.dp, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF0E3958),
                            Color(0xFF1A2E5A)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            JarvisColors.GlassSpecularTop,
                            JarvisColors.Cyan.copy(alpha = 0.55f),
                            JarvisColors.Violet.copy(alpha = 0.40f)
                        )
                    ),
                    RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                color = JarvisColors.Ice,
                fontSize = 13.5.sp,
                lineHeight = 19.sp
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
                        listOf(JarvisColors.CyanGlow.copy(alpha = 0.65f), JarvisColors.Graphite)
                    )
                )
                .border(1.dp, JarvisColors.CyanGlow.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("J", color = JarvisColors.Ice, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .shadow(10.dp, RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                .clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                .background(liquidGlassCardBrush(highlighted = false))
                .border(1.dp, liquidGlassBorderBrush(highlighted = false), RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .animateContentSize()
        ) {
            Column {
                Text(
                    text = text.ifBlank { if (isStreaming) "…" else "" },
                    color = JarvisColors.Ice,
                    fontSize = 13.5.sp,
                    lineHeight = 19.5.sp
                )
                if (isStreaming && text.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = JarvisColors.CyanGlow
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
            .clip(JarvisShapes.SquircleMd)
            .background(
                Brush.verticalGradient(
                    listOf(
                        JarvisColors.Error.copy(alpha = 0.18f),
                        Color(0xE6140A12)
                    )
                )
            )
            .border(1.dp, JarvisColors.Error.copy(alpha = 0.55f), JarvisShapes.SquircleMd)
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
                    tint = JarvisColors.CyanGlow,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("COBA LAGI", color = JarvisColors.CyanGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Unified Liquid Glass Attachment Menu Popover combining File, Foto, Dokumen, and Suara.
 */
@Composable
fun JarvisUnifiedAttachmentMenu(
    visible: Boolean,
    onPickFile: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit,
    onActivateVoice: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(JarvisMotion.springSmooth()) + expandVertically(JarvisMotion.springSmooth()),
        exit = fadeOut(JarvisMotion.springSmooth()) + shrinkVertically(JarvisMotion.springSmooth()),
        modifier = modifier
    ) {
        JarvisLiquidGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            highlighted = true,
            shape = JarvisShapes.SquircleLg,
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lampiran & Input Multimodal",
                    color = JarvisColors.Ice,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tutup ✕",
                    color = JarvisColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val items = listOf(
                    Triple("File", Icons.Default.AttachFile, onPickFile),
                    Triple("Foto", Icons.Default.Image, onPickPhoto),
                    Triple("Dokumen", Icons.Default.Description, onPickDocument),
                    Triple("Suara", Icons.Default.Mic, onActivateVoice)
                )
                items.forEach { (label, icon, action) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(JarvisShapes.SquircleSm)
                            .background(Color(0x66122840))
                            .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.SquircleSm)
                            .clickable {
                                onDismiss()
                                action()
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            JarvisColors.Cyan.copy(alpha = 0.22f),
                                            JarvisColors.Violet.copy(alpha = 0.18f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = JarvisColors.CyanGlow,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Text(
                            text = label,
                            color = JarvisColors.Ice,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact, ergonomic Cupertino Liquid Glass Pill Composer (`minHeight = 48.dp`).
 */
@Composable
fun JarvisChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: (() -> Unit)?,
    busy: Boolean,
    onCancel: (() -> Unit)?,
    placeholder: String = "Tulis pesan ke J.A.R.V.I.S...",
    onToggleAttachmentMenu: (() -> Unit)? = null,
    attachmentMenuOpen: Boolean = false,
    attachmentCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 112.dp)
            .shadow(14.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xF012253A),
                        Color(0xEB0A1624)
                    )
                )
            )
            .border(
                1.2.dp,
                Brush.linearGradient(
                    listOf(
                        JarvisColors.GlassSpecularTop,
                        JarvisColors.Cyan.copy(alpha = 0.52f),
                        JarvisColors.Violet.copy(alpha = 0.38f)
                    )
                ),
                RoundedCornerShape(26.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onToggleAttachmentMenu != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (attachmentMenuOpen || attachmentCount > 0) {
                            Brush.linearGradient(listOf(JarvisColors.Cyan.copy(alpha = 0.28f), JarvisColors.Violet.copy(alpha = 0.24f)))
                        } else {
                            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f)))
                        }
                    )
                    .border(
                        1.dp,
                        if (attachmentMenuOpen || attachmentCount > 0) JarvisColors.CyanGlow else Color.White.copy(alpha = 0.18f),
                        CircleShape
                    )
                    .clickable(enabled = !busy) { onToggleAttachmentMenu() },
                contentAlignment = Alignment.Center
            ) {
                if (attachmentCount > 0 && !attachmentMenuOpen) {
                    Text(
                        text = "+$attachmentCount",
                        color = JarvisColors.CyanGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = if (attachmentMenuOpen) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Lampiran",
                        tint = if (attachmentMenuOpen) JarvisColors.CyanGlow else JarvisColors.Ice,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 38.dp, max = 96.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = JarvisColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp
            ),
            cursorBrush = SolidColor(JarvisColors.CyanGlow),
            enabled = !busy,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = JarvisColors.TextMuted,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )

        if (busy && onCancel != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(JarvisColors.Error.copy(alpha = 0.22f))
                    .border(1.dp, JarvisColors.Error, CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Hentikan pemrosesan",
                    tint = JarvisColors.Error,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            if (onMic != null && value.isBlank() && attachmentCount == 0) {
                IconButton(
                    onClick = onMic,
                    enabled = !busy,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Mode Suara",
                        tint = JarvisColors.CyanGlow,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            val canSend = !busy && (value.isNotBlank() || attachmentCount > 0)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) {
                            Brush.linearGradient(
                                listOf(JarvisColors.CyanGlow, JarvisColors.Cyan, JarvisColors.AuroraMid)
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0xFF16293D), Color(0xFF101F30))
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (canSend) Color(0xFFE0FCFF) else Color.White.copy(alpha = 0.14f),
                        CircleShape
                    )
                    .clickable(enabled = canSend) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Kirim pesan",
                    tint = if (canSend) Color(0xFF031926) else JarvisColors.TextDim,
                    modifier = Modifier.size(17.dp)
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
            .clip(JarvisShapes.SquircleMd)
            .background(
                if (selected) {
                    Brush.horizontalGradient(
                        listOf(JarvisColors.Cyan.copy(alpha = 0.18f), JarvisColors.Violet.copy(alpha = 0.14f))
                    )
                } else {
                    liquidGlassCardBrush(false)
                }
            )
            .border(
                1.dp,
                if (selected) JarvisColors.CyanGlow.copy(alpha = 0.65f) else JarvisColors.GlassBorder,
                JarvisShapes.SquircleMd
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
            .background(
                Brush.horizontalGradient(
                    listOf(
                        JarvisColors.Cyan.copy(alpha = 0.14f),
                        JarvisColors.Violet.copy(alpha = 0.16f)
                    )
                )
            )
            .border(1.dp, JarvisColors.VioletGlow.copy(alpha = 0.45f), JarvisShapes.Chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = JarvisColors.CyanGlow
        )
        Text(
            text = stage.uppercase(),
            color = JarvisColors.Ice,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}