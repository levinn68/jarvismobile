package com.jarvis.mobile.device

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

/**
 * Foreground, user-visible approval gate for remote screen interaction.
 *
 * Approval is only recorded when the user taps a dialog button on this device.
 * Remote flags such as userConfirmed are ignored. Rejected commands are
 * acknowledged as failed and never passed to the Accessibility executor.
 */
class ScreenActionApprovalActivity : Activity() {
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_COMMAND)
        val command = try {
            raw?.let(::JSONObject)
        } catch (_: Exception) {
            null
        }
        if (command == null) {
            Toast.makeText(this, "Perintah layar tidak valid.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val commandId = command.optString("commandId").trim()
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()

        if (DeviceCommandExecutor.isScreenControlAction(action).not()) {
            Toast.makeText(this, "Aksi tidak memerlukan layar persetujuan.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isExpired(command)) {
            DeviceCommandExecutor(this).clearPendingApproval(commandId)
            acknowledge(commandId, JSONObject()
                .put("ok", false)
                .put("approved", false)
                .put("executed", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa sebelum pengguna menyetujui."))
            Toast.makeText(this, "Perintah kedaluwarsa.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val description = when (action) {
            "screen.tap" -> {
                val x = params.optDouble("x", Double.NaN)
                val y = params.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) {
                    "Koordinat ketukan tidak valid."
                } else {
                    "JARVIS meminta menekan layar pada posisi normalisasi x=${"%.3f".format(x)}, y=${"%.3f".format(y)}."
                }
            }
            "screen.type" -> {
                val text = params.optString("text").take(500)
                "JARVIS meminta mengetik teks berikut:\n\n$text"
            }
            else -> "JARVIS meminta tindakan layar: $action"
        }

        AlertDialog.Builder(this)
            .setTitle("Izinkan tindakan JARVIS?")
            .setMessage(
                "$description\n\n" +
                    "Tindakan hanya dijalankan pada perangkat ini setelah kamu memilih Izinkan.\n" +
                    "Pilih Tolak untuk membatalkan tanpa mengeksekusi apa pun."
            )
            .setCancelable(true)
            .setNegativeButton("Tolak") { _, _ ->
                if (finished) return@setNegativeButton
                finished = true
                DeviceCommandExecutor(this@ScreenActionApprovalActivity).clearPendingApproval(commandId)
                acknowledge(
                    commandId,
                    JSONObject()
                        .put("ok", false)
                        .put("approved", false)
                        .put("executed", false)
                        .put("error", "Rejected by device user")
                )
                Toast.makeText(this, "Tindakan ditolak.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setPositiveButton("Izinkan sekali") { _, _ ->
                if (finished) return@setPositiveButton
                finished = true
                // Mark local approval only. Remote userConfirmed is never accepted as authority.
                command.put(DeviceCommandExecutor.LOCAL_APPROVAL_MARKER, true)
                val result = try {
                    DeviceCommandExecutor(this).executeApproved(command)
                } catch (error: Exception) {
                    DeviceCommandExecutor(this@ScreenActionApprovalActivity).clearPendingApproval(commandId)
                    JSONObject()
                        .put("ok", false)
                        .put("approved", true)
                        .put("executed", false)
                        .put("error", error.message ?: "Screen action failed")
                }
                acknowledge(commandId, result)
                val ok = result.optBoolean("ok") && result.optBoolean("executed", result.optBoolean("ok"))
                Toast.makeText(
                    this,
                    if (ok) "Tindakan berhasil dijalankan." else "Tindakan gagal: ${result.optString("error", "unknown")}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            .setOnCancelListener {
                if (finished) return@setOnCancelListener
                finished = true
                DeviceCommandExecutor(this@ScreenActionApprovalActivity).clearPendingApproval(commandId)
                acknowledge(
                    commandId,
                    JSONObject()
                        .put("ok", false)
                        .put("approved", false)
                        .put("executed", false)
                        .put("error", "Approval dismissed by device user")
                )
                finish()
            }
            .show()
    }

    private fun isExpired(command: JSONObject): Boolean {
        val createdAt = command.optString("createdAt").trim()
        if (createdAt.isBlank()) return false
        return try {
            val createdMs = java.time.Instant.parse(createdAt).toEpochMilli()
            System.currentTimeMillis() - createdMs > COMMAND_TTL_MS
        } catch (_: Exception) {
            false
        }
    }

    private fun acknowledge(commandId: String, result: JSONObject) {
        if (commandId.isBlank()) return
        Thread {
            try {
                JarvisDeviceClient(this).use { it.acknowledge(commandId, result) }
            } catch (_: Exception) {
                // Best-effort; queue TTL will eventually expire the command.
            }
        }.start()
    }

    companion object {
        const val EXTRA_COMMAND = "com.jarvis.mobile.device.SCREEN_APPROVAL_COMMAND"
        private const val COMMAND_TTL_MS = 5 * 60 * 1000L
    }
}
