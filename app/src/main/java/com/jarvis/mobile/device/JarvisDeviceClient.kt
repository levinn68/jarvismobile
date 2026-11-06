package com.jarvis.mobile.device

import android.content.Context
import com.jarvis.mobile.BuildConfig
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Account-scoped enrollment and per-device command transport. */
class JarvisDeviceClient(
    context: Context,
    private val baseUrl: String = resolveWorkerBaseUrl(context)
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val vault = DeviceCredentialVault(appContext)
    private val io = Executors.newSingleThreadExecutor()

    fun createOrRestoreAccountWithSupabase(accessToken: String): JSONObject {
        require(accessToken.isNotBlank()) { "Google/Supabase session is required" }
        val result = rawRequest("POST", "/v1/accounts/google", null, accessToken)
        val accountId = result.optJSONObject("account")?.optString("accountId").orEmpty()
        val supabaseUserId = accountId.removePrefix("acct-")
        val accountToken = result.optString("accountToken")
        require(supabaseUserId.matches(Regex("[A-Fa-f0-9-]{36}")) && accountToken.isNotBlank()) {
            "Worker returned an invalid Google-linked account session"
        }
        vault.saveAccountToken(accountToken, supabaseUserId)
        return result
    }

    fun createAccount(enrollmentSecret: String): JSONObject {
        require(enrollmentSecret.isNotBlank()) { "Bootstrap secret is required" }
        val result = rawRequest("POST", "/v1/accounts/create", null, enrollmentSecret)
        val accountId = result.optJSONObject("account")?.optString("accountId").orEmpty()
        val supabaseUserId = accountId.removePrefix("acct-")
        result.optString("accountToken").takeIf { it.isNotBlank() }?.let { vault.saveAccountToken(it, supabaseUserId) }
        return result
    }

    fun pair(accountToken: String, deviceName: String, osVersion: String, appVersion: String = "0.9.2"): JSONObject {
        require(accountToken.isNotBlank()) { "JARVIS account token is required" }
        val id = "android-" + java.util.UUID.randomUUID().toString()
        val body = JSONObject()
            .put("deviceId", id)
            .put("platform", "android")
            .put("name", deviceName.take(80))
            .put("osVersion", osVersion.take(40))
            .put("appVersion", appVersion)
            .put("capabilities", JSONArray(listOf("apps.list", "apps.launch", "system.read")))
        val result = rawRequest("POST", "/v1/devices/enroll", body, accountToken)
        val token = result.optString("token")
        if (token.isBlank()) throw IllegalStateException("Pairing response did not contain a device token")
        configure(id, token)
        return result
    }

    /**
     * Creates a short-lived one-time pairing code for desktop agents.
     * The code is bound to the current Google-linked account and expires in ~5 minutes.
     */
    fun createPairingSession(expectedSupabaseUserId: String, deviceHintName: String = "Desktop"): JSONObject {
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account")
        val body = JSONObject().put("deviceHint", JSONObject().put("name", deviceHintName.take(80)))
        return rawRequest("POST", "/v1/pairing/sessions", body, accountToken)
    }

    fun pairingSessionStatus(code: String, expectedSupabaseUserId: String): JSONObject {
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account")
        return rawRequest("GET", "/v1/pairing/sessions/status?code=${java.net.URLEncoder.encode(code, "UTF-8")}", null, accountToken)
    }

    fun cancelPairingSession(code: String, expectedSupabaseUserId: String): JSONObject {
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account")
        val body = JSONObject().put("code", code.trim().uppercase())
        return rawRequest("POST", "/v1/pairing/sessions/cancel", body, accountToken)
    }

    fun listAccountDevices(expectedSupabaseUserId: String? = null): JSONArray {
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account. Reconnect the device.")
        return rawRequest("GET", "/v1/devices", null, accountToken).optJSONArray("devices") ?: JSONArray()
    }

    fun removeDevice(deviceId: String, expectedSupabaseUserId: String): JSONObject {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid device ID" }
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account")
        return rawRequest("DELETE", "/v1/devices/$deviceId", null, accountToken)
    }

    fun hasAccountToken(expectedSupabaseUserId: String? = null): Boolean = !vault.accountToken(expectedSupabaseUserId).isNullOrBlank()
    fun storedAccountToken(expectedSupabaseUserId: String? = null): String = vault.accountToken(expectedSupabaseUserId).orEmpty()
    fun storedAccountUserId(): String? = vault.accountUserId()
    fun clearAccountToken() = vault.clearAccountToken()

    fun queueCommand(
        deviceId: String,
        action: String,
        params: JSONObject = JSONObject(),
        expectedSupabaseUserId: String? = null,
        idempotencyKey: String? = null
    ): JSONObject {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid device ID" }
        require(action.length in 1..80) { "Invalid action" }
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("Pair this device with the currently signed-in Google account first")
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("action", action)
            .put("params", params)
        if (!idempotencyKey.isNullOrBlank()) {
            body.put("idempotencyKey", idempotencyKey.take(120))
        }
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
    fun clearCredentials() {
        vault.clear()
        appContext.getSharedPreferences(ACK_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun updateCapabilities(capabilities: List<String>): JSONObject {
        require(capabilities.size <= 40)
        val body = JSONObject().put("capabilities", JSONArray(capabilities.distinct()))
        return request("PUT", "/v1/devices/${deviceId()}/capabilities", body)
    }

    fun heartbeat(): JSONObject = request("POST", "/v1/devices/${deviceId()}/heartbeat")
    fun pollCommands(): JSONArray = request("GET", "/v1/device/commands?deviceId=${deviceId()}").optJSONArray("commands") ?: JSONArray()
    fun acknowledge(commandId: String, result: JSONObject): JSONObject =
        request("POST", "/v1/device/commands/ack", JSONObject().put("deviceId", deviceId()).put("commandId", commandId).put("result", result))

    fun enqueuePendingAck(commandId: String, result: JSONObject) {
        if (commandId.isBlank()) return
        val prefs = appContext.getSharedPreferences(ACK_PREFS, Context.MODE_PRIVATE)
        val queue = try { JSONArray(prefs.getString(ACK_QUEUE_KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val filtered = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            if (item.optString("commandId") != commandId) {
                filtered.put(item)
            }
        }
        while (filtered.length() >= 50) filtered.remove(0)
        filtered.put(
            JSONObject()
                .put("commandId", commandId)
                .put("result", result)
                .put("queuedAt", System.currentTimeMillis())
        )
        prefs.edit().putString(ACK_QUEUE_KEY, filtered.toString()).apply()
    }

    private fun flushPendingAcks(): Int {
        val prefs = appContext.getSharedPreferences(ACK_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(ACK_QUEUE_KEY, null) ?: return 0
        val queue = try { JSONArray(raw) } catch (_: Exception) {
            prefs.edit().remove(ACK_QUEUE_KEY).apply()
            return 0
        }
        if (queue.length() == 0) return 0
        val remaining = JSONArray()
        val now = System.currentTimeMillis()
        var failures = 0
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val commandId = item.optString("commandId")
            val result = item.optJSONObject("result") ?: continue
            val queuedAt = item.optLong("queuedAt", 0L)
            if (commandId.isBlank() || (queuedAt > 0L && now - queuedAt > 15 * 60 * 1000L)) continue
            try {
                acknowledge(commandId, result)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (!msg.contains("HTTP 404") && !msg.contains("HTTP 400") && !msg.contains("HTTP 403")) {
                    remaining.put(item)
                    failures++
                }
            }
        }
        if (remaining.length() == 0) prefs.edit().remove(ACK_QUEUE_KEY).apply()
        else prefs.edit().putString(ACK_QUEUE_KEY, remaining.toString()).apply()
        return failures
    }

    fun commandStatus(targetDeviceId: String, commandId: String, expectedSupabaseUserId: String? = null): JSONObject {
        require(targetDeviceId.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        require(commandId.matches(Regex("[A-Fa-f0-9-]{36}")))
        val accountToken = vault.accountToken(expectedSupabaseUserId)
            ?: throw IllegalStateException("JARVIS account token does not belong to the current Google account")
        return rawRequest("GET", "/v1/device/command/status?deviceId=$targetDeviceId&commandId=$commandId", null, accountToken)
    }

    fun pollOnce(executor: DeviceCommandExecutor, onResult: (String) -> Unit = {}) {
        if (!isConfigured()) return
        io.execute {
            var pendingApprovals = 0
            var acknowledgementFailures = 0
            try {
                heartbeat()
                acknowledgementFailures += flushPendingAcks()
                val commands = pollCommands()
                for (i in 0 until commands.length()) {
                    val command = commands.optJSONObject(i) ?: continue
                    val id = command.optString("commandId")
                    if (id.isBlank()) continue
                    val result = try {
                        executor.execute(command)
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: "Execution failed")
                    }
                    if (result.optBoolean("pendingApproval", false)) {
                        pendingApprovals++
                        continue
                    }
                    try {
                        acknowledge(id, result)
                    } catch (_: Exception) {
                        enqueuePendingAck(id, result)
                        acknowledgementFailures++
                    }
                }
                val summary = buildList {
                    add("Device sync completed")
                    if (pendingApprovals > 0) add("$pendingApprovals command menunggu persetujuan lokal")
                    if (acknowledgementFailures > 0) add("$acknowledgementFailures acknowledgement tertunda")
                }.joinToString("; ")
                onResult(summary)
            } catch (e: Exception) {
                onResult("Device sync failed: ${e.message}")
            }
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
            val raw = if (stream != null) String(com.jarvis.mobile.stability.JarvisStabilityGuard.readBoundedBytes(stream, 2 * 1024 * 1024), Charsets.UTF_8) else ""
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (code !in 200..299) throw IllegalStateException("JARVIS API HTTP $code: ${result.optString("error", "Request failed")}")
            return result
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        io.shutdownNow()
        try {
            io.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val ACK_PREFS = "jarvis_pending_acks"
        private const val ACK_QUEUE_KEY = "pending_ack_items"

        fun resolveWorkerBaseUrl(context: Context): String {
            val custom = context.applicationContext
                .getSharedPreferences("jarvis_secure_settings", Context.MODE_PRIVATE)
                .getString("worker_base_url", null)
                ?.trim()
                ?.trimEnd('/')
            if (!custom.isNullOrBlank() && custom.startsWith("https://")) {
                return custom
            }
            return BuildConfig.JARVIS_WORKER_URL.trimEnd('/')
        }
    }
}