package com.jarvis.mobile.device

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Account-scoped enrollment and per-device command transport. */
class JarvisDeviceClient(context: Context, private val baseUrl: String = "https://jarvis-api.alvinorasya1304.workers.dev") {
    private val vault = DeviceCredentialVault(context)
    private val io = Executors.newSingleThreadExecutor()

    fun createAccount(enrollmentSecret: String): JSONObject {
        require(enrollmentSecret.isNotBlank()) { "Bootstrap secret is required" }
        val result = rawRequest("POST", "/v1/accounts/create", null, enrollmentSecret)
        result.optString("accountToken").takeIf { it.isNotBlank() }?.let { vault.saveAccountToken(it) }
        return result
    }

    fun pair(accountToken: String, deviceName: String, osVersion: String, appVersion: String = "0.9.0"): JSONObject {
        require(accountToken.isNotBlank()) { "JARVIS account token is required" }
        val id = "android-" + java.util.UUID.randomUUID().toString()
        val body = JSONObject().put("deviceId", id).put("platform", "android")
            .put("name", deviceName.take(80)).put("osVersion", osVersion.take(40))
            .put("appVersion", appVersion).put("capabilities", JSONArray(listOf("apps.list", "apps.launch", "system.read")))
        val result = rawRequest("POST", "/v1/devices/enroll", body, accountToken)
        val token = result.optString("token")
        if (token.isBlank()) throw IllegalStateException("Pairing response did not contain a device token")
        configure(id, token)
        vault.saveAccountToken(accountToken)
        return result
    }

    fun listAccountDevices(accountToken: String = vault.accountToken().orEmpty()): JSONArray {
        require(accountToken.isNotBlank()) { "JARVIS account token is missing" }
        return rawRequest("GET", "/v1/devices", null, accountToken).optJSONArray("devices") ?: JSONArray()
    }

    fun hasAccountToken(): Boolean = !vault.accountToken().isNullOrBlank()
    fun storedAccountToken(): String = vault.accountToken().orEmpty()

    /** Queue a typed action for a device owned by this JARVIS account. */
    fun queueCommand(deviceId: String, action: String, params: JSONObject = JSONObject()): JSONObject {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid device ID" }
        require(action.length in 1..80) { "Invalid action" }
        val accountToken = vault.accountToken() ?: throw IllegalStateException("Pair or create a JARVIS account first")
        val body = JSONObject().put("deviceId", deviceId).put("action", action).put("params", params)
        return rawRequest("POST", "/v1/device/command", body, accountToken)
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

    fun commandStatus(targetDeviceId: String, commandId: String): JSONObject {
        require(targetDeviceId.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        require(commandId.matches(Regex("[A-Fa-f0-9-]{36}")))
        val accountToken = vault.accountToken() ?: throw IllegalStateException("JARVIS account token is missing")
        return rawRequest("GET", "/v1/device/command/status?deviceId=$targetDeviceId&commandId=$commandId", null, accountToken)
    }

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
        val bearer = token() ?: throw IllegalStateException("Device token is missing")
        return rawRequest(method, path, body, bearer)
    }

    private fun rawRequest(method: String, path: String, body: JSONObject? = null, bearer: String): JSONObject {
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
