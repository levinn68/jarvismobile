package com.jarvis.mobile.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Optional, user-enabled accessibility bridge.
 * Screen text is kept in memory and returned only after an explicit JARVIS screen.read command.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: event?.source ?: return
        val text = StringBuilder()
        collectVisibleText(root, text, 0)
        synchronized(lock) {
            latestText = text.toString().take(MAX_CHARS)
            capturedAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {
        synchronized(lock) { latestText = "" }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
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
        private val lock = Any()
        @Volatile private var instance: JarvisAccessibilityService? = null
        private var latestText = ""
        private var capturedAt = 0L

        fun isActive(): Boolean = instance != null

        fun snapshot(): JSONObject = synchronized(lock) {
            val service = instance
            if (service == null) {
                JSONObject().put("ok", false).put("requiresUserPermission", true)
                    .put("error", "Aktifkan JARVIS Accessibility Service di Pengaturan Aksesibilitas Android.")
            } else if (latestText.isBlank()) {
                JSONObject().put("ok", true).put("text", "")
                    .put("note", "Belum ada teks layar yang terbaca. Buka layar aplikasi yang ingin dianalisis lalu coba lagi.")
            } else {
                JSONObject().put("ok", true).put("text", latestText)
                    .put("capturedAt", capturedAt).put("characters", latestText.length)
                    .put("privacy", "Teks layar hanya diambil saat diminta dan disimpan sementara di memori perangkat.")
            }
        }
    }
}
