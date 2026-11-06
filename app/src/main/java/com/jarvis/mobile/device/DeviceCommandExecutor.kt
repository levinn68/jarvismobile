package com.jarvis.mobile.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.R
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes only known, typed commands. Android permissions and local user approval remain authoritative.
 */
class DeviceCommandExecutor(private val context: Context) {
    private val appController = AppController(context)
    private val fileController = FileController(context)
    private val deviceController = DeviceController(context)
    private val notificationController = NotificationController(context)
    private val contactController = ContactController(context)
    private val calendarController = CalendarController(context)
    private val cameraController = CameraController(context)
    private val permissionManager = DevicePermissionManager(context)

    fun executeApproved(command: JSONObject): JSONObject {
        val commandId = command.optString("commandId").trim()
        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            clearPendingApproval(commandId)
            return replayHandledResult(commandId, approved = true)
        }
        if (isCommandExpired(command)) {
            val expired = JSONObject()
                .put("ok", false)
                .put("approved", true)
                .put("executed", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa atau memiliki timestamp tidak valid sebelum dieksekusi.")
            markHandled(commandId, expired)
            clearPendingApproval(commandId)
            return expired
        }
        val approvedCapability = if (command.optString("action") == "screen.read") "screen.read" else "screen.control"
        if (!permissionManager.isGranted(approvedCapability)) {
            clearPendingApproval(commandId)
            return permissionDenied(approvedCapability, missingPermissionMessage(approvedCapability))
        }
        if (!command.optBoolean(LOCAL_APPROVAL_MARKER, false)) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "Approval lokal tidak ditemukan. Perintah tidak dijalankan.")
        }
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        val result = when (action) {
            "screen.read" -> JarvisAccessibilityService.snapshot()
            "screen.tap", "TAP_COORDINATE" -> executeTap(params)
            "screen.type", "TYPE_TEXT" -> executeType(params)
            "screen.click_text", "CLICK_TEXT" -> executeClickText(params)
            "screen.scroll", "SCROLL" -> executeScroll(params)
            "screen.global_action", "GLOBAL_ACTION" -> executeGlobalAction(params)
            else -> JSONObject()
                .put("ok", false)
                .put("approved", true)
                .put("executed", false)
                .put("error", "Aksi tidak didukung setelah approval: $action")
        }
        result.put("approved", true).put("action", action)
        if (commandId.isNotBlank()) markHandled(commandId, result)
        clearPendingApproval(commandId)
        return result
    }

    /**
     * Records a local user rejection or dismissal so that if the network drops during
     * the rejection acknowledgement, subsequent poll cycles replay the rejection
     * instead of re-opening the approval dialog.
     */
    fun recordRejectedApproval(commandId: String, errorMessage: String = "Rejected by device user"): JSONObject {
        val rejected = JSONObject()
            .put("ok", false)
            .put("approved", false)
            .put("executed", false)
            .put("rejectedByUser", true)
            .put("error", errorMessage)
        if (commandId.isNotBlank()) {
            markHandled(commandId, rejected)
            clearPendingApproval(commandId)
        }
        return rejected
    }

    fun execute(command: JSONObject): JSONObject {
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        val commandId = command.optString("commandId").trim()

        if (requiresLocalApproval(action)) {
            val capability = if (action == "screen.read") "screen.read" else "screen.control"
            if (!permissionManager.isGranted(capability)) {
                return permissionDenied(capability, missingPermissionMessage(capability))
            }
            return requestOnDeviceApproval(command)
        }

        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            return replayHandledResult(commandId)
        }
        if (isCommandExpired(command)) {
            val expired = JSONObject()
                .put("ok", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa atau memiliki timestamp tidak valid.")
            markHandled(commandId, expired)
            return expired
        }

        val requiredCapability = capabilityFor(action)
        if (requiredCapability != null && !permissionManager.isGranted(requiredCapability)) {
            return permissionDenied(requiredCapability, missingPermissionMessage(requiredCapability))
        }

        val result = when (action) {
            "apps.list" -> appController.listApps()
            "apps.launch" -> appController.launch(params.optString("packageName"))
            "apps.launch_label" -> appController.launchByLabel(params.optString("appName"))
            "OPEN_APP" -> {
                val pkg = params.optString("packageName").trim()
                if (pkg.isNotBlank()) appController.launch(pkg)
                else appController.launchByLabel(params.optString("appName"))
            }
            "system.wait", "WAIT" -> executeWait(params)
            "system.status" -> deviceController.status()
            "notifications.list" -> notificationController.list()
            "contacts.list" -> contactController.list()
            "calendar.list" -> calendarController.list()
            "camera.capture" -> JSONObject()
                .put("ok", false)
                .put("requiresForegroundUi", true)
                .put("cameraIntentAvailable", cameraController.captureIntent() != null)
                .put("error", "Alur capture kamera jarak jauh dinonaktifkan demi privasi; gunakan tombol uji kamera lokal di layar pengaturan perangkat.")
            "files.list", "files.search", "files.read" -> fileController.execute(action, params)
            else -> JSONObject().put("ok", false).put("error", "Unsupported action on this Android connector")
        }

        if (commandId.isNotBlank() && !result.optBoolean("pendingApproval", false)) {
            markHandled(commandId, result)
        }
        return result
    }

    private fun capabilityFor(action: String): String? = when (action) {
        "apps.list" -> "apps.list"
        "apps.launch", "apps.launch_label", "OPEN_APP" -> "apps.launch"
        "system.status", "system.wait", "WAIT" -> "system.read"
        "notifications.list" -> "notifications.read"
        "screen.read" -> "screen.read"
        "contacts.list" -> "contacts.read"
        "calendar.list" -> "calendar.read"
        "files.list", "files.search", "files.read" -> "files.read"
        "camera.capture" -> "camera.capture"
        else -> null
    }

    private fun missingPermissionMessage(capability: String): String = when (capability) {
        "files.read" -> "Pilih folder yang boleh diakses melalui pemilih folder Android."
        "notifications.read" -> "Aktifkan Notification Access untuk JARVIS di Pengaturan Android."
        "screen.read", "screen.control" -> "Aktifkan JARVIS Accessibility Service dan pastikan layanan tersambung."
        "contacts.read" -> "Izinkan akses Kontak untuk JARVIS di Android."
        "calendar.read" -> "Izinkan akses Kalender untuk JARVIS di Android."
        "camera.capture" -> "Capture kamera belum diimplementasikan sebagai perintah jarak jauh; izin kamera saja tidak cukup."
        else -> "Izin atau kemampuan $capability belum tersedia di perangkat."
    }

    private fun permissionDenied(capability: String, message: String) = JSONObject()
        .put("ok", false)
        .put("blocked", true)
        .put("requiresUserPermission", true)
        .put("capability", capability)
        .put("error", message)

    /**
     * Only opens a visible local approval UI. The command remains pending until the user acts.
     * A persisted short TTL prevents the same queued command reopening a dialog every poll cycle.
     */
    fun requestOnDeviceApproval(command: JSONObject): JSONObject {
        val commandId = command.optString("commandId").trim()
        if (commandId.isBlank()) {
            return JSONObject().put("ok", false).put("executed", false).put("error", "Command ID wajib ada.")
        }
        if (isAlreadyHandled(commandId)) {
            clearPendingApproval(commandId)
            return replayHandledResult(commandId)
        }
        if (isCommandExpired(command)) {
            val expired = JSONObject()
                .put("ok", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa atau memiliki timestamp tidak valid.")
            markHandled(commandId, expired)
            clearPendingApproval(commandId)
            return expired
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = pendingKey(commandId)
        val now = System.currentTimeMillis()
        val pendingAt = prefs.getLong(key, 0L)
        val pendingAge = now - pendingAt
        if (pendingAt > 0L && pendingAge in 0L until COMMAND_TTL_MS) {
            return JSONObject()
                .put("ok", true)
                .put("pendingApproval", true)
                .put("alreadyPending", true)
                .put("executed", false)
        }
        prefs.edit().putLong(key, now).apply()
        return try {
            val intent = Intent(context, ScreenActionApprovalActivity::class.java)
                .putExtra(ScreenActionApprovalActivity.EXTRA_COMMAND, command.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val launchedViaAccessibility = JarvisAccessibilityService.startApprovalActivity(intent)
            if (!launchedViaAccessibility) {
                context.startActivity(intent)
            }
            postApprovalFallbackNotification(commandId, command.optString("action"), intent)
            JSONObject()
                .put("ok", true)
                .put("pendingApproval", true)
                .put("executed", false)
                .put("message", "Menunggu persetujuan pengguna di perangkat.")
        } catch (error: Exception) {
            clearPendingApproval(commandId)
            JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "Gagal membuka layar persetujuan: ${error.message}")
        }
    }

    fun clearPendingApproval(commandId: String) {
        if (commandId.isNotBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pendingKey(commandId)).apply()
            try {
                context.getSystemService(NotificationManager::class.java)?.cancel(approvalNotificationId(commandId))
            } catch (_: Exception) {
                // Ignore notification cancellation errors in unit tests.
            }
        }
    }

    private fun postApprovalFallbackNotification(commandId: String, action: String, intent: Intent) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(APPROVAL_CHANNEL_ID, "JARVIS Screen Approval", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Persetujuan tindakan layar JARVIS"
                    }
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                approvalNotificationId(commandId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Persetujuan Tindakan Layar JARVIS")
                .setContentText("Ketuk untuk meninjau dan menyetujui/menolak perintah $action.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()
            nm.notify(approvalNotificationId(commandId), notification)
        } catch (_: Exception) {
            // Best-effort fallback notification for Android 14 BAL edge cases.
        }
    }

    private fun approvalNotificationId(commandId: String): Int =
        4200 + (commandId.hashCode() and 0x7fffffff) % 1000

    private fun executeTap(params: JSONObject): JSONObject {
        if (!params.has("x") || !params.has("y")) {
            return JSONObject().put("ok", false).put("executed", false).put("error", "screen.tap membutuhkan x dan y.")
        }
        val x = params.optDouble("x", Double.NaN)
        val y = params.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "Koordinat screen.tap harus angka 0.0–1.0.")
        }
        return JarvisAccessibilityService.tap(x, y)
    }

    private fun executeType(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        if (text.isBlank() || text.length > 500) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "screen.type membutuhkan teks 1–500 karakter.")
        }
        return JarvisAccessibilityService.typeText(text)
    }

    private fun executeClickText(params: JSONObject): JSONObject {
        val text = params.optString("text", "").trim()
        if (text.isBlank() || text.length > 200) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "CLICK_TEXT membutuhkan teks 1–200 karakter.")
        }
        return JarvisAccessibilityService.clickText(text)
    }

    private fun executeScroll(params: JSONObject): JSONObject {
        val direction = params.optString("direction", "").trim().lowercase()
        if (direction !in setOf("up", "down", "left", "right")) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "SCROLL membutuhkan direction (up, down, left, atau right).")
        }
        return JarvisAccessibilityService.scroll(direction)
    }

    private fun executeGlobalAction(params: JSONObject): JSONObject {
        val globalAction = params.optString("globalAction", "").trim().uppercase()
        if (globalAction !in setOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS")) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "GLOBAL_ACTION membutuhkan BACK, HOME, RECENTS, NOTIFICATIONS, atau QUICK_SETTINGS.")
        }
        return JarvisAccessibilityService.globalAction(globalAction)
    }

    private fun executeWait(params: JSONObject): JSONObject {
        val durationMs = params.optLong("durationMs", -1L)
        if (durationMs !in 100L..5000L) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "WAIT membutuhkan durationMs antara 100 dan 5000 ms.")
        }
        return try {
            Thread.sleep(durationMs)
            JSONObject()
                .put("ok", true)
                .put("executed", true)
                .put("action", "system.wait")
                .put("waitedMs", durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            JSONObject().put("ok", false).put("error", "WAIT diinterupsi.")
        }
    }

    private fun isAlreadyHandled(commandId: String): Boolean {
        if (commandId.isBlank()) return false
        val memo = recentHandled[commandId]
        if (memo != null && SystemClock.elapsedRealtime() - memo < DEDUPE_WINDOW_MS) return true
        val stamp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(prefixKey(commandId), 0L)
        val age = System.currentTimeMillis() - stamp
        return stamp > 0L && age >= 0L && age < DEDUPE_WINDOW_MS
    }

    /**
     * Replays the original terminal result when the server retries a command whose
     * execution completed but whose acknowledgement may have been lost.
     */
    private fun replayHandledResult(commandId: String, approved: Boolean = false): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = try {
            prefs.getString(resultKey(commandId), null)?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }
        val replay = cached ?: JSONObject()
            .put("ok", false)
            .put("error", "Command sudah diproses sebelumnya; hasil asli tidak tersedia.")
        replay.put("duplicate", true).put("replayed", true)
        if (approved && !replay.has("approved")) replay.put("approved", true)
        return replay
    }

    private fun markHandled(commandId: String, result: JSONObject) {
        if (commandId.isBlank()) return
        recentHandled[commandId] = SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(prefixKey(commandId), System.currentTimeMillis())
            .putString(resultKey(commandId), result.toString())
            .apply()
        pruneHandled()
    }

    private fun pruneHandled() {
        val elapsedNow = SystemClock.elapsedRealtime()
        recentHandled.entries.removeIf { elapsedNow - it.value > DEDUPE_WINDOW_MS }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val handled = prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("cmd_") || value !is Long) return@mapNotNull null
            key to value
        }
        val expired = handled.filter { (_, stamp) ->
            val age = now - stamp
            age < 0L || age >= DEDUPE_WINDOW_MS
        }
        val active = handled.filterNot { it in expired }.sortedByDescending { it.second }
        val remove = (expired + active.drop(MAX_PERSISTED_HANDLED)).map { it.first }
        if (remove.isNotEmpty()) {
            val editor = prefs.edit()
            remove.forEach { key ->
                val commandId = key.removePrefix("cmd_")
                editor.remove(key).remove(resultKey(commandId))
            }
            editor.apply()
        }
    }

    private fun prefixKey(commandId: String) = "cmd_$commandId"
    private fun resultKey(commandId: String) = "result_$commandId"
    private fun pendingKey(commandId: String) = "pending_$commandId"

    companion object {
        const val LOCAL_APPROVAL_MARKER = "_jarvisLocalApproval"
        private const val PREFS = "jarvis_command_dedupe"
        private const val APPROVAL_CHANNEL_ID = "jarvis_screen_approval"
        const val COMMAND_TTL_MS = 5 * 60 * 1000L
        const val MAX_FUTURE_SKEW_MS = 60 * 1000L
        private const val DEDUPE_WINDOW_MS = 15 * 60 * 1000L
        private const val MAX_PERSISTED_HANDLED = 500
        private val recentHandled = ConcurrentHashMap<String, Long>()

        fun requiresLocalApproval(action: String): Boolean =
            action in setOf(
                "screen.read",
                "screen.tap", "TAP_COORDINATE",
                "screen.type", "TYPE_TEXT",
                "screen.click_text", "CLICK_TEXT",
                "screen.scroll", "SCROLL",
                "screen.global_action", "GLOBAL_ACTION"
            )

        /**
         * Parses an ISO-8601 UTC timestamp across API 23..35 without requiring API 26 desugaring.
         */
        fun parseIsoTimestampMs(raw: String): Long? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    return java.time.Instant.parse(trimmed).toEpochMilli()
                } catch (_: Exception) {
                    // Fall through to SimpleDateFormat fallback.
                }
            }
            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }
                    val position = ParsePosition(0)
                    val parsed = sdf.parse(trimmed, position)
                    if (parsed != null && position.errorIndex < 0 && position.index == trimmed.length) {
                        return parsed.time
                    }
                } catch (_: Exception) {
                    // Try next pattern.
                }
            }
            return null
        }

        /**
         * Authoritative command expiration check shared by DeviceCommandExecutor and ScreenActionApprovalActivity.
         * Rejects blank timestamps, malformed timestamps, expired commands (>5 min old),
         * and future-dated commands (>60 sec ahead of device clock).
         */
        fun isCommandExpired(command: JSONObject, nowMs: Long = System.currentTimeMillis()): Boolean {
            val createdAt = command.optString("createdAt").trim()
            val createdMs = parseIsoTimestampMs(createdAt) ?: return true
            val ageMs = nowMs - createdMs
            return ageMs < -MAX_FUTURE_SKEW_MS || ageMs > COMMAND_TTL_MS
        }
    }
}