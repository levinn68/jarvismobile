package com.jarvis.mobile.device

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject

/**
 * Foreground, user-visible approval gate for remote screen interaction.
 *
 * Approval is only recorded when the user taps a dialog button on this device.
 * Remote flags such as userConfirmed are ignored. Rejected commands are
 * cached in DeviceCommandExecutor deduplication storage and acknowledged as
 * failed so they are never re-prompted or passed to the Accessibility executor.
 */
class ScreenActionApprovalActivity : Activity() {
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#B3040911")))
        window?.decorView?.filterTouchesWhenObscured = true

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

        if (DeviceCommandExecutor.requiresLocalApproval(action).not()) {
            Toast.makeText(this, "Aksi tidak memerlukan layar persetujuan.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val executor = DeviceCommandExecutor(this)
        if (isExpired(command)) {
            val expiredResult = JSONObject()
                .put("ok", false)
                .put("approved", false)
                .put("executed", false)
                .put("expired", true)
                .put("error", "Command kedaluwarsa atau memiliki timestamp tidak valid sebelum pengguna menyetujui.")
            executor.clearPendingApproval(commandId)
            acknowledge(commandId, expiredResult)
            Toast.makeText(this, "Perintah kedaluwarsa atau timestamp tidak valid.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val description = when (action) {
            "screen.read" -> "JARVIS meminta membaca teks yang sedang terlihat pada layar. Konten hanya dikembalikan untuk perintah ini."
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

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("KONFIRMASI KEAMANAN J.A.R.V.I.S.")
            .setMessage(
                "$description\n\n" +
                    "Tindakan hanya dijalankan pada perangkat ini setelah kamu memilih Izinkan sekali.\n" +
                    "Pilih Tolak untuk membatalkan tanpa mengeksekusi apa pun."
            )
            .setCancelable(true)
            .setNegativeButton("Tolak") { _, _ ->
                if (finished) return@setNegativeButton
                finished = true
                val rejected = executor.recordRejectedApproval(commandId, "Rejected by device user")
                acknowledge(commandId, rejected)
                Toast.makeText(this, "Tindakan ditolak.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setPositiveButton("Izinkan sekali") { _, _ ->
                if (finished) return@setPositiveButton
                finished = true
                if (isExpired(command)) {
                    executor.clearPendingApproval(commandId)
                    acknowledge(
                        commandId,
                        JSONObject()
                            .put("ok", false)
                            .put("approved", false)
                            .put("executed", false)
                            .put("expired", true)
                            .put("error", "Command kedaluwarsa sebelum sempat dijalankan.")
                    )
                    Toast.makeText(this, "Perintah sudah kedaluwarsa.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                // Mark local approval only. Remote userConfirmed is never accepted as authority.
                command.put(DeviceCommandExecutor.LOCAL_APPROVAL_MARKER, true)
                Thread {
                    val result = try {
                        executor.executeApproved(command)
                    } catch (error: Exception) {
                        executor.clearPendingApproval(commandId)
                        JSONObject()
                            .put("ok", false)
                            .put("approved", true)
                            .put("executed", false)
                            .put("error", error.message ?: "Screen action failed")
                    }
                    acknowledge(commandId, result)
                    val ok = result.optBoolean("ok") && result.optBoolean("executed", result.optBoolean("ok"))
                    runOnUiThread {
                        Toast.makeText(
                            this@ScreenActionApprovalActivity,
                            if (ok) "Tindakan berhasil dijalankan." else "Tindakan gagal: ${result.optString("error", "unknown")}",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }.start()
            }
            .setOnCancelListener {
                if (finished) return@setOnCancelListener
                finished = true
                val dismissed = executor.recordRejectedApproval(commandId, "Approval dismissed by device user")
                acknowledge(commandId, dismissed)
                finish()
            }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#00E5FF"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#FF5E6E"))
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        (dialog.window?.decorView as? ViewGroup)?.filterTouchesWhenObscured = true
    }

    /**
     * Uses the unified, strict timestamp validator in DeviceCommandExecutor so
     * missing, malformed, expired (>5 min), and future (>60 sec skew) timestamps
     * are consistently rejected across both Activity and Executor.
     */
    private fun isExpired(command: JSONObject): Boolean =
        DeviceCommandExecutor.isCommandExpired(command)

    private fun acknowledge(commandId: String, result: JSONObject) {
        if (commandId.isBlank()) return
        Thread {
            try {
                JarvisDeviceClient(this).use { client ->
                    try {
                        client.acknowledge(commandId, result)
                    } catch (_: Exception) {
                        client.enqueuePendingAck(commandId, result)
                    }
                }
            } catch (_: Exception) {
                // Saved in executor dedupe cache and pending ACK queue for next poll cycle.
            }
        }.start()
    }

    companion object {
        const val EXTRA_COMMAND = "com.jarvis.mobile.device.SCREEN_APPROVAL_COMMAND"
    }
}