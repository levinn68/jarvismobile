package com.jarvis.mobile.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional user-enabled bridge.
 *
 * - screen.read: traverses visible text only after an explicit command.
 * - screen.tap / screen.type: run only after ScreenActionApprovalActivity
 *   records a real on-device user approval. Background event collection is
 *   intentionally disabled.
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

    /**
     * Performs a single tap at normalized coordinates (0.0–1.0).
     * Must be invoked only after explicit on-device user approval.
     */
    private fun performTapNormalized(xNorm: Double, yNorm: Double): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return JSONObject().put("ok", false).put("error", "Gesture tap membutuhkan Android 7.0 atau lebih baru.")
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val x = (xNorm.coerceIn(0.0, 1.0) * (width - 1)).toFloat()
        val y = (yNorm.coerceIn(0.0, 1.0) * (height - 1)).toFloat()

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val latch = CountDownLatch(1)
        val outcome = AtomicReference("pending")
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                outcome.set("completed")
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                outcome.set("cancelled")
                latch.countDown()
            }
        }, null)

        if (!dispatched) {
            return JSONObject().put("ok", false).put("error", "Sistem menolak mengirim gesture tap.")
        }
        if (!latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return JSONObject().put("ok", false).put("error", "Timeout saat mengetuk layar.")
        }
        return if (outcome.get() == "completed") {
            JSONObject()
                .put("ok", true)
                .put("executed", true)
                .put("action", "screen.tap")
                .put("x", xNorm)
                .put("y", yNorm)
                .put("pixelX", x.toInt())
                .put("pixelY", y.toInt())
        } else {
            JSONObject().put("ok", false).put("error", "Gesture tap dibatalkan oleh sistem.")
        }
    }

    /**
     * Types text into the currently focused editable field, or the first
     * editable node on screen. Must run only after on-device approval.
     */
    private fun performTypeText(text: String): JSONObject {
        val value = text.take(MAX_TYPE_CHARS)
        if (value.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Teks kosong tidak dapat diketik.")
        }

        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "Tidak ada jendela aktif untuk mengetik.")

        val focused = findFocusedEditable(root)
            ?: findFirstEditable(root)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Tidak ditemukan kolom teks yang dapat diedit di layar aktif.")

        if (!focused.isEnabled) {
            return JSONObject().put("ok", false).put("error", "Kolom teks target tidak aktif.")
        }

        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val setOk = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!setOk) {
            // Fallback: try clicking the field then set text again.
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val retry = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!retry) {
                return JSONObject().put("ok", false).put("error", "Gagal menuliskan teks ke kolom target.")
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("executed", true)
            .put("action", "screen.type")
            .put("characters", value.length)
            .put("preview", value.take(80))
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFocusedEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val MAX_CHARS = 8000
        private const val MAX_DEPTH = 24
        private const val MAX_TYPE_CHARS = 500
        private const val GESTURE_TIMEOUT_MS = 3000L
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: JarvisAccessibilityService? = null

        fun isActive(): Boolean = instance != null

        fun snapshot(): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service di Pengaturan Aksesibilitas Android.")
            return runOnServiceThread(service) { it.readVisibleScreen() }
        }

        fun tap(xNorm: Double, yNorm: Double): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service untuk mengetuk layar.")
            if (xNorm !in 0.0..1.0 || yNorm !in 0.0..1.0) {
                return JSONObject().put("ok", false).put("error", "Koordinat harus dalam rentang 0.0 sampai 1.0.")
            }
            return runOnServiceThread(service) { it.performTapNormalized(xNorm, yNorm) }
        }

        fun typeText(text: String): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service untuk mengetik teks.")
            return runOnServiceThread(service) { it.performTypeText(text) }
        }

        private fun runOnServiceThread(service: JarvisAccessibilityService, block: (JarvisAccessibilityService) -> JSONObject): JSONObject {
            if (Looper.myLooper() == Looper.getMainLooper()) return block(service)
            val latch = CountDownLatch(1)
            var result: JSONObject? = null
            mainHandler.post {
                result = try {
                    block(service)
                } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", error.message ?: "Accessibility action failed")
                }
                latch.countDown()
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return JSONObject().put("ok", false).put("error", "Timeout menunggu Accessibility Service.")
            }
            return result ?: JSONObject().put("ok", false).put("error", "Hasil Accessibility Service kosong.")
        }
    }
}
