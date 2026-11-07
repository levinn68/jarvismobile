package com.jarvis.mobile.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional user-enabled bridge for non-root Android 14 (ASUS ROG Phone 6) screen automation.
 *
 * - screen.read: traverses visible text only after an explicit on-device user approval.
 * - screen.tap / TAP_COORDINATE, screen.type / TYPE_TEXT, screen.click_text / CLICK_TEXT,
 *   screen.scroll / SCROLL, screen.global_action / GLOBAL_ACTION: run ONLY after
 *   ScreenActionApprovalActivity records a real on-device user approval.
 *   Background event collection is intentionally disabled.
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
        synchronized(Companion) {
            gestureCallbackThread?.quitSafely()
            gestureCallbackThread = null
            gestureCallbackHandler = null
        }
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
        val callbackHandler = ensureGestureCallbackHandler()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                outcome.set("completed")
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                outcome.set("cancelled")
                latch.countDown()
            }
        }, callbackHandler)

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
     * Clicks a visible UI element matching the target text or contentDescription (`CLICK_TEXT` / `screen.click_text`).
     * Walks up the parent hierarchy if the text node itself is not directly clickable.
     */
    private fun performClickByText(targetText: String): JSONObject {
        val query = targetText.trim().take(200)
        if (query.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Teks target untuk CLICK_TEXT tidak boleh kosong.")
        }
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "Tidak ada jendela aktif untuk mencari teks.")
        val matchedNode = findNodeByText(root, query, exact = true, depth = 0)
            ?: findNodeByText(root, query, exact = false, depth = 0)
            ?: return JSONObject().put("ok", false).put("error", "Elemen dengan teks '$query' tidak ditemukan di layar aktif.")

        var clickableTarget: AccessibilityNodeInfo? = matchedNode        var hops = 0
        while (clickableTarget != null && !clickableTarget.isClickable && hops < 6) {
            clickableTarget = clickableTarget.parent
            hops++
        }
        val effectiveNode = clickableTarget ?: matchedNode
        val clicked = effectiveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            return JSONObject().put("ok", false).put("error", "Elemen '$query' ditemukan tetapi menolak aksi klik.")
        }
        return JSONObject()
            .put("ok", true)
            .put("executed", true)
            .put("action", "screen.click_text")
            .put("matchedText", query)
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        query: String,
        exact: Boolean,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH || !node.isVisibleToUser) return null
        val textVal = node.text?.toString()?.trim().orEmpty()
        val descVal = node.contentDescription?.toString()?.trim().orEmpty()
        val matched = if (exact) {
            textVal.equals(query, ignoreCase = true) || descVal.equals(query, ignoreCase = true)
        } else {
            (textVal.isNotEmpty() && textVal.contains(query, ignoreCase = true)) ||
                (descVal.isNotEmpty() && descVal.contains(query, ignoreCase = true))
        }
        if (matched) return node
        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), query, exact, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * Scrolls the active screen in the requested direction (`up`, `down`, `left`, `right`)
     * using a normalized swipe gesture or scrollable node action (`SCROLL` / `screen.scroll`).
     */
    private fun performScroll(direction: String): JSONObject {
        val cleanDir = direction.trim().lowercase()
        if (cleanDir !in setOf("up", "down", "left", "right")) {
            return JSONObject().put("ok", false).put("error", "Arah scroll harus up, down, left, atau right.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.coerceAtLeast(1).toFloat()
            val h = metrics.heightPixels.coerceAtLeast(1).toFloat()
            val (startX, startY, endX, endY) = when (cleanDir) {
                "down" -> listOf(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f)
                "up" -> listOf(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.75f)
                "left" -> listOf(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
                else -> listOf(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            }
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 260)
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
            }, ensureGestureCallbackHandler())
            if (dispatched && latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && outcome.get() == "completed") {
                return JSONObject()
                    .put("ok", true)
                    .put("executed", true)
                    .put("action", "screen.scroll")
                    .put("direction", cleanDir)
            }
        }
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "Tidak ada jendela aktif untuk melakukan scroll.")
        val scrollable = findFirstScrollable(root, 0)
            ?: return JSONObject().put("ok", false).put("error", "Tidak ditemukan area yang dapat di-scroll di layar aktif.")
        val actionCode = if (cleanDir == "down" || cleanDir == "right") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = scrollable.performAction(actionCode)
        return JSONObject()
            .put("ok", ok)
            .put("executed", ok)
            .put("action", "screen.scroll")
            .put("direction", cleanDir)
            .apply { if (!ok) put("error", "Gagal mengeksekusi scroll $cleanDir.") }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findFirstScrollable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * Executes a safe whitelisted system global action (`GLOBAL_ACTION` / `screen.global_action`).
     */
    private fun performSafeGlobal(globalActionName: String): JSONObject {
        val clean = globalActionName.trim().uppercase()
        val actionConstant = when (clean) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return JSONObject().put("ok", false).put("error", "Global action tidak didukung: $clean")
        }
        val ok = performGlobalAction(actionConstant)
        return JSONObject()
            .put("ok", ok)
            .put("executed", ok)
            .put("action", "screen.global_action")
            .put("globalAction", clean)
            .apply { if (!ok) put("error", "Sistem menolak global action $clean.") }
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
        @Volatile private var gestureCallbackThread: HandlerThread? = null
        @Volatile private var gestureCallbackHandler: Handler? = null
        @Volatile private var instance: JarvisAccessibilityService? = null

        @Synchronized
        private fun ensureGestureCallbackHandler(): Handler {
            val existing = gestureCallbackHandler
            val thread = gestureCallbackThread
            if (existing != null && thread != null && thread.isAlive) {
                return existing
            }
            val newThread = HandlerThread("JarvisGestureCallback").apply { start() }
            val newHandler = Handler(newThread.looper)
            gestureCallbackThread = newThread
            gestureCallbackHandler = newHandler
            return newHandler
        }

        fun isActive(): Boolean = instance != null

        fun startApprovalActivity(intent: Intent): Boolean {
            val service = instance ?: return false
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

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
            return try {
                service.performTapNormalized(xNorm, yNorm)
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "Gesture tap failed")
            }
        }

        fun clickText(targetText: String): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service untuk mengklik teks.")
            return runOnServiceThread(service) { it.performClickByText(targetText) }
        }

        fun scroll(direction: String): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service untuk melakukan scroll layar.")
            return try {
                service.performScroll(direction)
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "Scroll action failed")
            }
        }

        fun globalAction(actionName: String): JSONObject {
            val service = instance ?: return JSONObject()
                .put("ok", false).put("requiresUserPermission", true)
                .put("error", "Aktifkan JARVIS Accessibility Service untuk menjalankan global action.")
            return runOnServiceThread(service) { it.performSafeGlobal(actionName) }
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
