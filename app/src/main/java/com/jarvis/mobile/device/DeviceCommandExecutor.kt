package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes only known, typed commands. OS permissions and Android user consent remain authoritative.
 *
 * Screen control (tap/type) is never executed from [execute]. Those actions must be routed through
 * [ScreenActionApprovalActivity]; only [executeApproved] may run them, and only after a real
 * on-device confirmation. Remote `userConfirmed` flags are never trusted here.
 */
class DeviceCommandExecutor(private val context: Context) {
    private val appController = AppController(context)
    private val fileController = FileController(context)
    private val deviceController = DeviceController(context)
    private val notificationController = NotificationController(context)
    private val cameraController = CameraController(context)
    private val contactController = ContactController(context)
    private val calendarController = CalendarController(context)

    fun executeApproved(command: JSONObject): JSONObject {
        val commandId = command.optString("commandId").trim()
        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            return JSONObject()
                .put("ok", false)
                .put("approved", true)
                .put("executed", false)
                .put("duplicate", true)
                .put("error", "Command sudah diproses sebelumnya dan tidak dijalankan ulang.")
        }

        if (isCommandExpired(command)) {
            markHandled(commandId)
            return JSONObject()
                .put("ok", false)
                .put("approved", true)
                .put("executed", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa sebelum dieksekusi.")
        }

        // Defense in depth: ignore any client-supplied confirmation flag.
        if (command.optBoolean("userConfirmed", false) && !command.optBoolean(LOCAL_APPROVAL_MARKER, false)) {
            return JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "Remote userConfirmed diabaikan. Approval harus dari interaksi lokal.")
        }

        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        val result = when (action) {
            "screen.tap" -> executeTap(params)
            "screen.type" -> executeType(params)
            else -> JSONObject()
                .put("ok", false)
                .put("approved", true)
                .put("executed", false)
                .put("error", "Aksi tidak didukung untuk eksekusi setelah approval: $action")
        }

        if (commandId.isNotBlank()) markHandled(commandId)
        return result.put("approved", true).put("action", action)
    }

    fun execute(command: JSONObject): JSONObject {
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()

        if (action == "screen.tap" || action == "screen.type") {
            return requestOnDeviceApproval(command)
        }

        val commandId = command.optString("commandId").trim()
        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            return JSONObject()
                .put("ok", false)
                .put("duplicate", true)
                .put("error", "Command sudah diproses sebelumnya.")
        }

        if (isCommandExpired(command)) {
            markHandled(commandId)
            return JSONObject().put("ok", false).put("expired", true).put("error", "Command kedaluwarsa.")
        }

        val result = when (action) {
            "apps.list" -> appController.listApps()
            "apps.launch" -> appController.launch(params.optString("packageName"))
            "apps.launch_label" -> appController.launchByLabel(params.optString("appName"))
            "system.status" -> deviceController.status()
            "notifications.list" -> notificationController.list()
            "screen.read" -> JarvisAccessibilityService.snapshot()
            "contacts.list" -> contactController.list()
            "calendar.list" -> calendarController.list()
            "camera.capture" -> JSONObject()
                .put("ok", false)
                .put("requiresForegroundUi", true)
                .put("error", "Camera capture must be launched from an Activity after a visible user confirmation")
            "files.list", "files.search", "files.read" -> fileController.execute(action, params)
            else -> JSONObject().put("ok", false).put("error", "Unsupported action on this Android connector")
        }

        if (commandId.isNotBlank()) markHandled(commandId)
        return result
    }

    /**
     * Launches the foreground approval UI. Does not execute the action.
     * Acknowledgement is performed by ScreenActionApprovalActivity after the user chooses.
     */
    fun requestOnDeviceApproval(command: JSONObject): JSONObject {
        val commandId = command.optString("commandId").trim()
        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            return JSONObject()
                .put("ok", false)
                .put("duplicate", true)
                .put("error", "Command sudah diproses sebelumnya.")
        }
        if (isCommandExpired(command)) {
            markHandled(commandId)
            return JSONObject().put("ok", false).put("expired", true).put("error", "Command kedaluwarsa sebelum ditampilkan untuk approval.")
        }

        return try {
            val intent = Intent(context, ScreenActionApprovalActivity::class.java)
                .putExtra(ScreenActionApprovalActivity.EXTRA_COMMAND, command.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            JSONObject()
                .put("ok", true)
                .put("pendingApproval", true)
                .put("executed", false)
                .put("message", "Menunggu persetujuan pengguna di perangkat.")
        } catch (error: Exception) {
            JSONObject()
                .put("ok", false)
                .put("executed", false)
                .put("error", "Gagal membuka layar persetujuan: ${error.message}")
        }
    }

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

    private fun isCommandExpired(command: JSONObject): Boolean {
        val createdAt = command.optString("createdAt").trim()
        if (createdAt.isBlank()) return false
        return try {
            val createdMs = java.time.Instant.parse(createdAt).toEpochMilli()
            System.currentTimeMillis() - createdMs > COMMAND_TTL_MS
        } catch (_: Exception) {
            false
        }
    }

    private fun isAlreadyHandled(commandId: String): Boolean {
        if (commandId.isBlank()) return false
        val memo = recentHandled[commandId]
        if (memo != null && SystemClock.elapsedRealtime() - memo < DEDUPE_WINDOW_MS) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = prefs.getLong(prefixKey(commandId), 0L)
        return stamp > 0L && System.currentTimeMillis() - stamp < DEDUPE_WINDOW_MS
    }

    private fun markHandled(commandId: String) {
        if (commandId.isBlank()) return
        recentHandled[commandId] = SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(prefixKey(commandId), System.currentTimeMillis())
            .apply()
        pruneHandled()
    }

    private fun pruneHandled() {
        val now = SystemClock.elapsedRealtime()
        recentHandled.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
    }

    private fun prefixKey(commandId: String) = "cmd_$commandId"

    companion object {
        const val LOCAL_APPROVAL_MARKER = "_jarvisLocalApproval"
        private const val PREFS = "jarvis_command_dedupe"
        private const val COMMAND_TTL_MS = 5 * 60 * 1000L
        private const val DEDUPE_WINDOW_MS = 15 * 60 * 1000L
        private val recentHandled = ConcurrentHashMap<String, Long>()

        fun isScreenControlAction(action: String): Boolean =
            action == "screen.tap" || action == "screen.type"
    }
}
