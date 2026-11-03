package com.jarvis.mobile.device

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional user-enabled bridge. It does not inspect screen events in the background.
 * Visible text is traversed only after an explicit screen.read command.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not collect or retain screen text from background events.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun readVisibleScreen(): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "Tidak ada jendela aktif yang dapat dibaca.")
        val text = StringBuilder()
        collectVisibleText(root, text, 0)
        return JSONObject().put("ok", true)
            .put("text", text.toString().take(MAX_CHARS))
            .put("characters", text.length.coerceAtMost(MAX_CHARS))
            .put("privacy", "Teks dibaca hanya untuk permintaan ini dan tidak disimpan sebagai riwayat layar.")
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_CHARS || !node.isVisibleToUser) return
        val value = sequenceOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (value != null && !out.contains(value)) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(value.take(500))
        }
        for (i in 0 until node.childCount) {
            if (out.length >= MAX_CHARS) break
            collectVisibleText(node.getChild(i), out, depth + 1)
        }
    }

    companion object {
        private const val MAX_CHARS = 8000
        private const val MAX_DEPTH = 24
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: JarvisAccessibilityService? = null

        fun isActive(): Boolean = instance != null

        fun snapshot(): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service di Pengaturan Aksesibilitas Android.")
            if (Looper.myLooper() == Looper.getMainLooper()) return service.readVisibleScreen()
            val latch = CountDownLatch(1)
            var result: JSONObject? = null
            mainHandler.post {
                result = service.readVisibleScreen()
                latch.countDown()
            }
            if (!latch.await(3, TimeUnit.SECONDS)) {
                return JSONObject().put("ok", false).put("error", "Pembacaan layar timeout. Coba lagi.")
            }
            return result ?: JSONObject().put("ok", false).put("error", "Gagal membaca layar.")
        }
    }
}
