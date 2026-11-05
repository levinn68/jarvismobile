package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
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
    private val permissionManager = DevicePermissionManager(context)

    fun executeApproved(command: JSONObject): JSONObject {
        val commandId = command.optString("commandId").trim()
        if (commandId.isNotBlank() && isAlreadyHandled(commandId)) {
            clearPendingApproval(commandId)
            return replayHandledResult(commandId, approved = true)
        }
        if (isCommandExpired(command)) {
            val expired = JSONObject().put("ok", false).put("approved", true).put("executed", false)
                .put("expired", true).put("error", "Command kedaluwarsa sebelum dieksekusi.")
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
            return JSONObject().put("ok", false).put("executed", false)
                .put("error", "Approval lokal tidak ditemukan. Perintah tidak dijalankan.")
        }
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        val result = when (action) {
            "screen.read" -> JarvisAccessibilityService.snapshot()
            "screen.tap" -> executeTap(params)
            "screen.type" -> executeType(params)
            else -> JSONObject().put("ok", false).put("approved", true).put("executed", false)
                .put("error", "Aksi tidak didukung setelah approval: $action")
        }
        result.put("approved", true).put("action", action)
        if (commandId.isNotBlank()) markHandled(commandId, result)
        clearPendingApproval(commandId)
        return result
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
            val expired = JSONObject().put("ok", false).put("expired", true).put("error", "Command kedaluwarsa.")
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
            "system.status" -> deviceController.status()
            "notifications.list" -> notificationController.list()
            "screen.read" -> JarvisAccessibilityService.snapshot()
            "contacts.list" -> contactController.list()
            "calendar.list" -> calendarController.list()
            "camera.capture" -> JSONObject().put("ok", false).put("requiresForegroundUi", true)
                .put("error", "Alur capture kamera belum tersedia. Tidak ada gambar yang diambil.")
            "files.list", "files.search", "files.read" -> fileController.execute(action, params)
            else -> JSONObject().put("ok", false).put("error", "Unsupported action on this Android connector")
        }

        if (commandId.isNotBlank() && result.optBoolean("ok")) markHandled(commandId, result)
        return result
    }

    private fun capabilityFor(action: String): String? = when (action) {
        "apps.list" -> "apps.list"
        "apps.launch", "apps.launch_label" -> "apps.launch"
        "system.status" -> "system.read"
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
        "camera.capture" -> "Capture kamera belum diimplementasikan; izin kamera saja tidak cukup."
        else -> "Izin atau kemampuan $capability belum tersedia di perangkat."
    }

    private fun permissionDenied(capability: String, message: String) = JSONObject()
        .put("ok", false).put("blocked", true).put("requiresUserPermission", true)
        .put("capability", capability).put("error", message)

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
            val expired = JSONObject().put("ok", false).put("expired", true).put("error", "Command kedaluwarsa.")
            markHandled(commandId, expired)
            clearPendingApproval(commandId)
            return expired
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = pendingKey(commandId)
        val now = System.currentTimeMillis()
        val pendingAt = prefs.getLong(key, 0L)
        if (pendingAt > 0L && now - pendingAt < COMMAND_TTL_MS) {
            return JSONObject().put("ok", true).put("pendingApproval", true)
                .put("alreadyPending", true).put("executed", false)
        }
        prefs.edit().putLong(key, now).apply()
        return try {
            val intent = Intent(context, ScreenActionApprovalActivity::class.java)
                .putExtra(ScreenActionApprovalActivity.EXTRA_COMMAND, command.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("pendingApproval", true).put("executed", false)
                .put("message", "Menunggu persetujuan pengguna di perangkat.")
        } catch (error: Exception) {
            clearPendingApproval(commandId)
            JSONObject().put("ok", false).put("executed", false)
                .put("error", "Gagal membuka layar persetujuan: ${error.message}")
        }
    }

    fun clearPendingApproval(commandId: String) {
        if (commandId.isNotBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pendingKey(commandId)).apply()
        }
    }

    private fun executeTap(params: JSONObject): JSONObject {
        if (!params.has("x") || !params.has("y")) return JSONObject().put("ok", false)
            .put("executed", false).put("error", "screen.tap membutuhkan x dan y.")
        val x = params.optDouble("x", Double.NaN)
        val y = params.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) {
            return JSONObject().put("ok", false).put("executed", false)
                .put("error", "Koordinat screen.tap harus angka 0.0–1.0.")
        }
        return JarvisAccessibilityService.tap(x, y)
    }

    private fun executeType(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        if (text.isBlank() || text.length > 500) return JSONObject().put("ok", false)
            .put("executed", false).put("error", "screen.type membutuhkan teks 1–500 karakter.")
        return JarvisAccessibilityService.typeText(text)
    }

    private fun isCommandExpired(command: JSONObject): Boolean {
        val createdAt = command.optString("createdAt").trim()
        if (createdAt.isBlank()) return false
        return try { System.currentTimeMillis() - java.time.Instant.parse(createdAt).toEpochMilli() > COMMAND_TTL_MS }
        catch (_: Exception) { true }
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
        val cached = try { prefs.getString(resultKey(commandId), null)?.let { JSONObject(it) } }
        catch (_: Exception) { null }
        val replay = cached ?: JSONObject().put("ok", false)
            .put("error", "Command sudah diproses sebelumnya; hasil asli tidak tersedia.")
        replay.put("duplicate", true).put("replayed", true)
        if (approved) replay.put("approved", true)
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

        // Keep persistent command results bounded; pending approval entries are untouched.
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
        private const val COMMAND_TTL_MS = 5 * 60 * 1000L
        private const val DEDUPE_WINDOW_MS = 15 * 60 * 1000L
        private const val MAX_PERSISTED_HANDLED = 500
        private val recentHandled = ConcurrentHashMap<String, Long>()
        fun requiresLocalApproval(action: String): Boolean =
            action == "screen.read" || action == "screen.tap" || action == "screen.type"
    }
}
