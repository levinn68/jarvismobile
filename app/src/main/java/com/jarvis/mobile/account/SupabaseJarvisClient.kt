package com.jarvis.mobile.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jarvis.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class JarvisSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String?, val displayName: String?)
data class SavedConversation(val id: String, val title: String, val updatedAt: String)
data class SavedMessage(val id: String, val role: String, val content: String, val createdAt: String)

/** Supabase Auth/PostgREST adapter. Uses only public key; RLS enforces account isolation. */
class SupabaseJarvisClient(context: Context) {
    private val appContext = context.applicationContext
    private val vault = SupabaseSessionVault(appContext)
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    @Volatile private var activeConversationId: String? = null

    init { activeConversationId = appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).getString("active_conversation_id", null) }
    fun hasSession(): Boolean = !vault.read()?.optString("access_token").isNullOrBlank()
    fun currentUserId(): String? = vault.read()?.optString("user_id")?.takeIf { it.isNotBlank() }
    fun currentEmail(): String? = vault.read()?.optString("email")?.takeIf { it.isNotBlank() }
    fun currentName(): String? = vault.read()?.optString("display_name")?.takeIf { it.isNotBlank() }
    fun activeConversationId(): String? = activeConversationId
    fun currentAccessToken(): String = accessToken()

    suspend fun signInWithGoogle(activityContext: Context): JarvisSession {
        require(baseUrl.startsWith("https://") && anonKey.isNotBlank() && googleWebClientId.isNotBlank()) {
            "Konfigurasi belum lengkap: periksa GOOGLE_WEB_CLIENT_ID dan SUPABASE_ANON_KEY pada GitHub Actions."
        }
        val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false)
            .setServerClientId(googleWebClientId).setAutoSelectEnabled(false).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = withContext(Dispatchers.Main) { CredentialManager.create(activityContext).getCredential(activityContext, request).credential }
        val google = if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            GoogleIdTokenCredential.createFrom(credential.data) else throw IllegalStateException("Google Sign-In tidak mengembalikan ID token yang valid.")
        val response = withContext(Dispatchers.IO) {
            requestJson("/auth/v1/token?grant_type=id_token", "POST", JSONObject().put("provider", "google").put("id_token", google.idToken), bearer = null)
        }
        val user = response.optJSONObject("user") ?: throw IllegalStateException("Profil Supabase tidak tersedia.")
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        val session = sessionFrom(response, user)
        vault.save(response.put("user_id", session.userId).put("email", session.email ?: "").put("display_name", metadata.optString("full_name", metadata.optString("name"))))
        setActiveConversation(null)
        return session
    }

    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val stored = vault.read() ?: return@withContext false
        try { ensureSession(stored).optString("access_token").isNotBlank() } catch (_: Exception) { false }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = vault.read()?.optString("access_token")?.takeIf { it.isNotBlank() }
        if (token != null) runCatching { requestJson("/auth/v1/logout", "POST", JSONObject(), bearer = token, refresh = false) }
        vault.clear()
        activeConversationId = null
        appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun setActiveConversation(id: String?) = withContext(Dispatchers.IO) {
        activeConversationId = id
        val edit = appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit()
        if (id.isNullOrBlank()) edit.remove("active_conversation_id") else edit.putString("active_conversation_id", id)
        edit.apply()
    }
    suspend fun startNewConversation() = setActiveConversation(null)

    suspend fun saveTurn(userText: String, assistantText: String) = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext
        require(userText.isNotBlank() && assistantText.isNotBlank()) { "Pesan kosong tidak dapat disimpan." }
        var conversation = activeConversationId
        if (conversation == null) {
            val title = userText.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "Percakapan baru" }
            conversation = createConversation(title)
            setActiveConversation(conversation)
        }
        val batch = JSONArray()
            .put(JSONObject().put("conversation_id", conversation).put("role", "user").put("content", userText))
            .put(JSONObject().put("conversation_id", conversation).put("role", "assistant").put("content", assistantText))
        requestArray("/rest/v1/jarvis_messages", "POST", batch, prefer = "return=minimal")
    }

    suspend fun listConversations(): List<SavedConversation> = withContext(Dispatchers.IO) {
        val rows = requestArray("/rest/v1/jarvis_conversations?select=id,title,updated_at&order=updated_at.desc&limit=100", "GET")
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { SavedConversation(it.optString("id"), it.optString("title", "Percakapan baru"), it.optString("updated_at")) } }
    }

    suspend fun createConversation(title: String): String = withContext(Dispatchers.IO) {
        val rows = requestArray("/rest/v1/jarvis_conversations?select=id", "POST", JSONArray().put(JSONObject().put("title", title.ifBlank { "Percakapan baru" })), prefer = "return=representation")
        rows.optJSONObject(0)?.getString("id") ?: throw IllegalStateException("Gagal membuat percakapan.")
    }

    suspend fun listMessages(conversationId: String): List<SavedMessage> = withContext(Dispatchers.IO) {
        val rows = requestArray("/rest/v1/jarvis_messages?select=id,role,content,created_at&conversation_id=eq.${encode(conversationId)}&order=created_at.asc&limit=500", "GET")
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { SavedMessage(it.optString("id"), it.optString("role"), it.optString("content"), it.optString("created_at")) } }
    }

    suspend fun renameConversation(id: String, title: String) = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "Judul percakapan tidak boleh kosong." }
        requestJson("/rest/v1/jarvis_conversations?id=eq.${encode(id)}", "PATCH", JSONObject().put("title", title.trim().take(80)))
    }
    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        requestJson("/rest/v1/jarvis_conversations?id=eq.${encode(id)}", "DELETE", JSONObject())
        if (activeConversationId == id) setActiveConversation(null)
    }

    private fun sessionFrom(response: JSONObject, user: JSONObject? = response.optJSONObject("user")): JarvisSession {
        val actual = user ?: throw IllegalStateException("Profil Supabase tidak tersedia.")
        val metadata = actual.optJSONObject("user_metadata") ?: JSONObject()
        val session = JarvisSession(response.optString("access_token"), response.optString("refresh_token"), actual.optString("id"),
            actual.optString("email").takeIf { it.isNotBlank() }, metadata.optString("full_name", metadata.optString("name")).takeIf { it.isNotBlank() })
        require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank() && session.userId.isNotBlank()) { "Sesi Supabase tidak lengkap." }
        return session
    }

    @Synchronized private fun ensureSession(existing: JSONObject? = vault.read()): JSONObject {
        val stored = existing ?: throw IllegalStateException("Silakan masuk dengan Google terlebih dahulu.")
        if (stored.optString("access_token").isNotBlank() && stored.optLong("expires_at", 0L) > System.currentTimeMillis() / 1000L + 90L) return stored
        val refresh = stored.optString("refresh_token").takeIf { it.isNotBlank() } ?: throw IllegalStateException("Sesi habis. Masuk kembali dengan Google.")
        val fresh = requestJson("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh), bearer = null, refresh = false)
        val merged = JSONObject(stored.toString())
        listOf("access_token", "refresh_token", "token_type", "expires_in", "expires_at").forEach { if (fresh.has(it)) merged.put(it, fresh.get(it)) }
        fresh.optJSONObject("user")?.let { u ->
            if (u.has("id")) merged.put("user_id", u.getString("id"))
            if (u.has("email")) merged.put("email", u.getString("email"))
            val metadata = u.optJSONObject("user_metadata")
            if (metadata != null) merged.put("display_name", metadata.optString("full_name", metadata.optString("name")))
        }
        val now = System.currentTimeMillis() / 1000L
        val expiresAt = fresh.optLong("expires_at", 0L).takeIf { it > now }
            ?: (now + fresh.optLong("expires_in", 3600L).coerceAtLeast(60L))
        merged.put("expires_at", expiresAt)
        if (merged.optString("access_token").isBlank() || merged.optString("refresh_token").isBlank()) {
            throw IllegalStateException("Supabase tidak mengembalikan token hasil refresh.")
        }
        vault.save(merged)
        return merged
    }

    private fun accessToken() = ensureSession().optString("access_token").takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Sesi Supabase tidak valid.")

    private fun requestArray(path: String, method: String, body: JSONArray? = null, prefer: String? = null): JSONArray {
        val raw = http(path, method, body?.toString(), accessToken(), prefer)
        return JSONArray(raw.ifBlank { "[]" })
    }
    private fun requestJson(path: String, method: String, body: JSONObject? = null, bearer: String? = null, prefer: String? = null, refresh: Boolean = true): JSONObject {
        val token = if (bearer != null) bearer else if (refresh) accessToken() else null
        val raw = http(path, method, body?.toString(), token, prefer)
        return if (raw.startsWith("{")) JSONObject(raw) else JSONObject()
    }
    private fun http(path: String, method: String, body: String?, token: String?, prefer: String?): String {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid Supabase API path." }
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("apikey", anonKey)
            connection.setRequestProperty("Authorization", "Bearer ${token ?: anonKey}")
            connection.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { val json = JSONObject(response); json.optString("message").ifBlank { json.optString("error_description") }.ifBlank { json.optString("hint") } }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Supabase HTTP $code")
            }
            return response
        } finally { connection.disconnect() }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}