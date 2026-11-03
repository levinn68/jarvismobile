package com.jarvis.mobile.device

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Authenticated client for the JARVIS device command API.
 * Device tokens are stored in app-private preferences; never embed bootstrap secrets in the APK.
 */
class JarvisDeviceClient(context: Context, private val baseUrl: String = "https://jarvis-api.alvinorasya1304.workers.dev") {
    private val prefs = context.getSharedPreferences("jarvis_device_secure", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    fun configure(deviceId: String, token: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid device ID" }
        require(token.isNotBlank()) { "Device token is required" }
        prefs.edit().putString("device_id", deviceId).putString("device_token", token).apply()
    }
    fun isConfigured() = !deviceId().isNullOrBlank() && !token().isNullOrBlank()
    fun deviceId() = prefs.getString("device_id", null)
    private fun token() = prefs.getString("device_token", null)
    fun clearCredentials() { prefs.edit().remove("device_id").remove("device_token").apply() }

    fun heartbeat(): JSONObject = request("POST", "/v1/devices/${deviceId()}/heartbeat")
    fun pollCommands(): JSONArray = request("GET", "/v1/device/commands?deviceId=${deviceId()}").optJSONArray("commands") ?: JSONArray()
    fun acknowledge(commandId: String, result: JSONObject): JSONObject =
        request("POST", "/v1/device/commands/ack", JSONObject().put("deviceId", deviceId()).put("commandId", commandId).put("result", result))

    fun pollOnce(executor: DeviceCommandExecutor, onResult: (String) -> Unit = {}) {
        if (!isConfigured()) return
        io.execute {
            try {
                heartbeat()
                val commands = pollCommands()
                for (i in 0 until commands.length()) {
                    val command = commands.optJSONObject(i) ?: continue
                    val id = command.optString("commandId")
                    if (id.isBlank()) continue
                    val result = try { executor.execute(command) }
                    catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Execution failed") }
                    try { acknowledge(id, result) } catch (e: Exception) { onResult("Acknowledgement failed: ${e.message}") }
                }
                onResult("Device sync completed")
            } catch (e: Exception) { onResult("Device sync failed: ${e.message}") }
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val id = deviceId() ?: throw IllegalStateException("Device is not paired")
        val bearer = token() ?: throw IllegalStateException("Device token is missing")
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (code !in 200..299) throw IllegalStateException("JARVIS API HTTP $code: ${result.optString("error", "Request failed")}")
            return result
        } finally { connection.disconnect() }
    }
}
