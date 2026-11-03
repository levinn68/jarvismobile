package com.jarvis.mobile.device

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import org.json.JSONObject

/** Foreground, user-visible approval gate for remote screen interaction. */
class ScreenActionApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_COMMAND)
        val command = try { raw?.let(::JSONObject) } catch (_: Exception) { null }
        if (command == null) { finish(); return }
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        val description = when (action) {
            "screen.tap" -> "JARVIS meminta menekan layar pada posisi ${params.optDouble("x")}, ${params.optDouble("y")}."
            "screen.type" -> "JARVIS meminta mengetik teks berikut:\n\n${params.optString("text").take(500)}"
            else -> "JARVIS meminta tindakan layar: $action"
        }
        AlertDialog.Builder(this)
            .setTitle("Izinkan tindakan JARVIS?")
            .setMessage("$description\n\nTindakan hanya dijalankan pada perangkat ini setelah kamu memilih Izinkan.")
            .setNegativeButton("Tolak") { _, _ ->
                val id = command.optString("commandId")
                if (id.isNotBlank()) {
                    try {
                        JarvisDeviceClient(this).use {
                            it.acknowledge(id, JSONObject().put("ok", false).put("approved", false).put("error", "Rejected by device user"))
                        }
                    } catch (_: Exception) {}
                }
                finish()
            }
            .setPositiveButton("Izinkan sekali") { _, _ ->
                val result = try { DeviceCommandExecutor(this).executeApproved(command) }
                catch (error: Exception) { JSONObject().put("ok", false).put("error", error.message ?: "Screen action failed") }
                val id = command.optString("commandId")
                if (id.isNotBlank()) {
                    try { JarvisDeviceClient(this).use { it.acknowledge(id, result) } } catch (_: Exception) {}
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_COMMAND = "com.jarvis.mobile.device.SCREEN_APPROVAL_COMMAND"
    }
}
