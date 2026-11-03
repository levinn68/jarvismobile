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
    private val vault = DeviceCredentialVault(context)
    private val io = Executors.newSingleThreadExecutor()
    fun pair(enrollmentSecret: String, deviceName: String, osVersion: String, appVersion: String = "0.9.0"): JSONObject {
        require(enrollmentSecret.isNotBlank()) { "Enrollment secret must be entered by the device owner" }
        val id = "android-" + java.util.UUID.randomUUID().toString()
        val body = JSONObject().put("deviceId", id).put("platform", "android")
            .put("name", deviceName.take(80)).put("osVersion", osVersion.take(40))
            .put("appVersion", appVersion).put("capabilities", JSONArray(listOf("apps.list", "apps.launch", "system.read")))
        val connection = (URL(baseUrl.trimEnd('/') + "/v1/devices/enroll").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 12000; connection.readTimeout = 20000; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + enrollmentSecret)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (code !in 200..299) throw IllegalStateException("Pairing HTTP " + code + ": " + result.optString("error", "Pairing failed"))
            val token = result.optString("token")
            if (token.isBlank()) throw IllegalStateException("Pairing response did not contain a device token")
            configure(id, token)
            return result
        } finally { connection.disconnect() }
    }
    fun configure(deviceId: String, token: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid device ID" }
        require(token.isNotBlank()) { "Device token is required" }
        vault.save(deviceId, token)
    }
    fun isConfigured() = !deviceId().isNullOrBlank() && !token().isNullOrBlank()
    fun deviceId() = vault.deviceId()
    private fun token() = vault.token()
    fun clearCredentials() { vault.clear() }

    fun updateCapabilities(capabilities: List<String>): JSONObject {
        require(capabilities.size <= 40)
        val body = JSONObject().put("capabilities", JSONArray(capabilities.distinct()))
        return request("PUT", "/v1/devices/${deviceId()}/capabilities", body)
    }

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
