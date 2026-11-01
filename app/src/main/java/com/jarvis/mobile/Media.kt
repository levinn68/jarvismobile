package com.jarvis.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val MCyan = Color(0xFF64E8FF)
private val MMuted = Color(0xFF8FA6BD)

/** One photo or product returned by the JARVIS worker. */
internal data class MediaItem(
    val type: String,
    val title: String,
    val imageUrl: String,
    val thumbUrl: String,
    val source: String,
    val link: String,
    val price: String = "",
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val badge: String = ""
)

/** Result of one /v1/chat call: answer text plus the pictures that belong to it. */
internal data class AskResult(val text: String, val media: List<MediaItem> = emptyList(), val ok: Boolean = true)

internal fun parseMedia(obj: JSONObject?): List<MediaItem> {
    val arr = obj?.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<MediaItem>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val img = o.optString("imageUrl")
        val thumb = o.optString("thumbUrl")
        if (img.isBlank() && thumb.isBlank()) continue
        out.add(
            MediaItem(
                type = o.optString("type", "image"),
                title = o.optString("title"),
                imageUrl = img,
                thumbUrl = thumb,
                source = o.optString("source"),
                link = o.optString("link"),
                price = o.optString("price"),
                rating = o.optDouble("rating", 0.0),
                ratingCount = o.optInt("ratingCount", 0),
                badge = o.optString("badge")
            )
        )
    }
    return out
}

// ---------------------------------------------------------------- image loading (no extra dependency)

private object ImageMemory {
    val cache = object : LruCache<String, Bitmap>(maxOf(8 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
}

private sealed interface ImgState {
    object Loading : ImgState
    object Failed : ImgState
    class Ok(val bitmap: Bitmap) : ImgState
}

private fun loadBitmap(url: String, maxPx: Int): Bitmap? {
    var conn: HttpURLConnection? = null
    try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; JarvisMobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8")
        if (conn.responseCode !in 200..299) return null
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty() || bytes.size > 12_000_000) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) {
        return null
    } finally {
        conn?.disconnect()
    }
}

/** Loads the first URL that works (verified URL first, thumbnail as fallback) and caches it in memory. */
@Composable
internal fun NetImage(
    urls: List<String>,
    description: String?,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop
) {
    val state by produceState<ImgState>(ImgState.Loading, urls) {
        value = ImgState.Loading
        for (u in urls) {
            val cached = ImageMemory.cache.get(u)
            if (cached != null) {
                value = ImgState.Ok(cached)
                return@produceState
            }
            val bmp = withContext(Dispatchers.IO) { loadBitmap(u, 900) }
            if (bmp != null) {
                ImageMemory.cache.put(u, bmp)
                value = ImgState.Ok(bmp)
                return@produceState
            }
        }
        value = ImgState.Failed
    }
    Box(modifier.background(Color(0xFF0A1A24)), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is ImgState.Ok -> Image(s.bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = scale)
            is ImgState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = MCyan, strokeWidth = 2.dp)
            is ImgState.Failed -> Icon(Icons.Default.BrokenImage, null, tint = Color(0xFF3F6A78), modifier = Modifier.size(26.dp))
        }
    }
}

private fun MediaItem.urls(): List<String> = listOf(imageUrl, thumbUrl).filter { it.isNotBlank() }.distinct()

// ---------------------------------------------------------------- chat gallery

@Composable
internal fun MediaGallery(items: List<MediaItem>) {
    val shape = RoundedCornerShape(14.dp)
    val isProduct = items.any { it.type == "product" }
    Column(
        Modifier.fillMaxWidth().background(Color(0xE60A1823), shape).border(1.dp, MCyan.copy(alpha = .35f), shape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(MCyan.copy(alpha = .14f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Image, null, tint = MCyan, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                (if (isProduct) "Rekomendasi Produk (" else "Hasil Gambar (") + items.size + ")",
                color = Color(0xFFEAFBFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        if (isProduct) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { ResultCard(it, 150.dp) }
            }
        } else {
            items.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { ImageTile(it, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ImageTile(item: MediaItem, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.aspectRatio(1.45f).clip(shape).border(1.dp, MCyan.copy(alpha = .3f), shape)
            .clickable(enabled = item.link.isNotBlank()) { runCatching { uriHandler.openUri(item.link) } }
    ) {
        NetImage(item.urls(), item.title, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC02080D))))
                .padding(horizontal = 7.dp, vertical = 5.dp)
        ) {
            Text(item.source.ifBlank { item.title }, color = Color(0xFFD9EDF2), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).background(Color(0x99050912), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.OpenInNew, "Buka sumber", tint = MCyan, modifier = Modifier.size(12.dp))
        }
    }
}

// ---------------------------------------------------------------- product / result card (chat + voice)

private fun storeColor(name: String): Color {
    val n = name.lowercase(Locale.ROOT)
    return when {
        "tokopedia" in n -> Color(0xFF42B549)
        "shopee" in n -> Color(0xFFEE4D2D)
        "lazada" in n -> Color(0xFF6C5CFF)
        "blibli" in n -> Color(0xFF0095DA)
        "bukalapak" in n -> Color(0xFFE31E52)
        else -> MCyan
    }
}

private fun ratingText(rating: Double, count: Int): String {
    val r = String.format(Locale.US, "%.1f", rating)
    if (count <= 0) return r
    val c = if (count >= 1000) String.format(Locale.US, "%.1fRB", count / 1000.0).replace('.', ',').replace(",0RB", "RB") else count.toString()
    return "$r ($c)"
}

@Composable
internal fun ResultCard(item: MediaItem, width: Dp) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(14.dp)
    val product = item.type == "product"
    Column(
        Modifier.width(width).clip(shape).background(Color(0xCC0A1823)).border(1.dp, MCyan.copy(alpha = .4f), shape).padding(6.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(width * 0.72f).clip(RoundedCornerShape(10.dp))) {
            NetImage(item.urls(), item.title, Modifier.fillMaxSize())
            if (item.badge.isNotBlank()) {
                Text(
                    item.badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                        .background(Color(0xCC050912), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title, color = Color(0xFFEAFBFF), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 28.dp)
        )
        if (product && item.price.isNotBlank()) {
            Text(item.price, color = MCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
        }
        if (item.source.isNotBlank()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(storeColor(item.source), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
                Text(item.source, color = Color(0xFFD9EDF2), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (product && item.rating > 0) {
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFC857), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(ratingText(item.rating, item.ratingCount), color = MMuted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        val buttonShape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().clip(buttonShape).border(1.dp, MCyan.copy(alpha = .6f), buttonShape)
                .clickable(enabled = item.link.isNotBlank()) { runCatching { uriHandler.openUri(item.link) } }
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.OpenInNew, null, tint = MCyan, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (product) "Lihat Produk" else "Buka Sumber", color = Color(0xFFEAFBFF), fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------- voice mode widgets

@Composable
internal fun VoiceTranscriptPill(text: String) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0x66082030), shape)
            .border(1.dp, Color(0x6652DFF2), shape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Mic, null, tint = MCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFEAFBFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Voice mode shows the pictures only; the spoken part is limited to "searching" and "found it". */
@Composable
internal fun VoiceResults(items: List<MediaItem>, busy: Boolean, fallbackText: String) {
    val cardWidth = 150.dp
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(12.dp), color = MCyan, strokeWidth = 1.5.dp)
            else Box(Modifier.size(6.dp).background(MCyan, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                if (busy) "MENCARI..." else if (items.isNotEmpty()) "MENAMPILKAN HASIL · " + items.size else "JAWABAN SIAP",
                color = MMuted, fontSize = 8.sp, letterSpacing = 1.4.sp
            )
        }
        if (items.isNotEmpty()) {
            val scroll = rememberScrollState()
            val stepPx = with(LocalDensity.current) { (cardWidth + 10.dp).toPx() }
            val index = if (stepPx > 0f) (scroll.value / stepPx).roundToInt().coerceIn(0, items.lastIndex) else 0
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { ResultCard(it, cardWidth) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                items.forEachIndexed { i, _ ->
                    Box(
                        Modifier.padding(horizontal = 2.dp).width(if (i == index) 14.dp else 5.dp).height(3.dp)
                            .background(if (i == index) MCyan else MCyan.copy(alpha = .3f), CircleShape)
                    )
                }
            }
        } else if (!busy && fallbackText.isNotBlank()) {
            val shape = RoundedCornerShape(12.dp)
            Text(
                fallbackText.replace(Regex("[#*`]"), "").trim().take(160),
                color = Color(0xFFD9EDF2), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Color(0xCC0A1823), shape).border(1.dp, MCyan.copy(alpha = .3f), shape).padding(12.dp)
            )
        }
    }
}
