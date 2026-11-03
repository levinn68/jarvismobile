package com.jarvis.mobile.account

import com.jarvis.mobile.BuildConfig

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class JarvisSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String?,
    val displayName: String?
)

data class SavedConversation(val id: String, val title: String, val updatedAt: String)
data class SavedMessage(val id: String, val role: String, val content: String, val createdAt: String)

/** Supabase Auth + PostgREST adapter. Client uses only the public anon key; RLS protects user data. */
class SupabaseJarvisClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jarvis_supabase_session", Context.MODE_PRIVATE)
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    private var activeConversationId: String? = prefs.getString("active_conversation_id", null)

    fun hasSession(): Boolean = !prefs.getString("access_token", null).isNullOrBlank()
    fun currentUserId(): String? = prefs.getString("user_id", null)
    fun currentEmail(): String? = prefs.getString("email", null)
    fun currentName(): String? = prefs.getString("display_name", null)

    suspend fun signInWithGoogle(activityContext: Context): JarvisSession {
        require(baseUrl.startsWith("https://") && anonKey.isNotBlank() && googleWebClientId.isNotBlank()) {
            "Konfigurasi belum lengkap: SUPABASE_ANON_KEY dan GOOGLE_WEB_CLIENT_ID harus diatur."
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(googleWebClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = withContext(Dispatchers.Main) {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        }
        val googleCredential = if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenCredential.createFrom(credential.data)
        } else {
            throw IllegalStateException("Google Sign-In tidak mengembalikan ID token yang valid.")
        }

        val response = withContext(Dispatchers.IO) {
            request(
                "/auth/v1/token?grant_type=id_token",
                "POST",
                JSONObject().put("provider", "google").put("id_token", googleCredential.idToken),
                token = null
            )
        }
        val user = response.optJSONObject("user")
            ?: throw IllegalStateException("Supabase tidak mengembalikan profil pengguna.")
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        val session = JarvisSession(
            accessToken = response.getString("access_token"),
            refreshToken = response.getString("refresh_token"),
            userId = user.getString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() },
            displayName = metadata.optString("full_name", metadata.optString("name")).takeIf { it.isNotBlank() }
        )
        prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putString("display_name", session.displayName)
            .apply()
        return session
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = prefs.getString("access_token", null)
        if (!token.isNullOrBlank()) runCatching {
            request("/auth/v1/logout", "POST", JSONObject(), token = token)
        }
        prefs.edit().clear().apply()
        activeConversationId = null
    }

    suspend fun startNewConversation() = withContext(Dispatchers.IO) {
        activeConversationId = null
        prefs.edit().remove("active_conversation_id").apply()
    }

    suspend fun saveTurn(userText: String, assistantText: String) = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext
        var conversationId = activeConversationId
        if (conversationId == null) {
            val title = userText.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "Percakapan baru" }
            conversationId = createConversation(title)
            activeConversationId = conversationId
            prefs.edit().putString("active_conversation_id", conversationId).apply()
        }
        appendMessage(conversationId, "user", userText)
        appendMessage(conversationId, "assistant", assistantText)
    }

    suspend fun listConversations(): List<SavedConversation> = withContext(Dispatchers.IO) {
        val rows = requestArray(
            "/rest/v1/jarvis_conversations?select=id,title,updated_at&order=updated_at.desc",
            "GET"
        )
        (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let {
                SavedConversation(it.optString("id"), it.optString("title", "Percakapan baru"), it.optString("updated_at"))
            }
        }
    }

    suspend fun createConversation(title: String): String = withContext(Dispatchers.IO) {
        val rows = requestArray(
            "/rest/v1/jarvis_conversations?select=id",
            "POST",
            JSONArray().put(JSONObject().put("title", title.ifBlank { "Percakapan baru" })),
            prefer = "return=representation"
        )
        rows.optJSONObject(0)?.getString("id") ?: throw IllegalStateException("Gagal membuat percakapan.")
    }

    suspend fun renameConversation(id: String, title: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/jarvis_conversations?id=eq.${encode(id)}",
            "PATCH",
            JSONObject().put("title", title),
            token = accessToken(),
            prefer = "return=minimal"
        )
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/jarvis_conversations?id=eq.${encode(id)}",
            "DELETE",
            JSONObject(),
            token = accessToken(),
            prefer = "return=minimal"
        )
        if (activeConversationId == id) {
            activeConversationId = null
            prefs.edit().remove("active_conversation_id").apply()
        }
    }

    suspend fun listMessages(conversationId: String): List<SavedMessage> = withContext(Dispatchers.IO) {
        val rows = requestArray(
            "/rest/v1/jarvis_messages?select=id,role,content,created_at&conversation_id=eq.${encode(conversationId)}&order=created_at.asc",
            "GET"
        )
        (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let {
                SavedMessage(it.optString("id"), it.optString("role"), it.optString("content"), it.optString("created_at"))
            }
        }
    }

    suspend fun appendMessage(conversationId: String, role: String, content: String) = withContext(Dispatchers.IO) {
        require(role == "user" || role == "assistant") { "Role pesan tidak valid." }
        val row = JSONObject()
            .put("conversation_id", conversationId)
            .put("role", role)
            .put("content", content)
        request("/rest/v1/jarvis_messages", "POST", JSONArray().put(row), token = accessToken(), prefer = "return=minimal")
    }

    private fun accessToken(): String = prefs.getString("access_token", null)
        ?: throw IllegalStateException("Silakan masuk dengan Google terlebih dahulu.")

    private fun requestArray(
        path: String,
        method: String,
        body: JSONArray? = null,
        prefer: String? = null
    ): JSONArray {
        val raw = http(path, method, body?.toString(), accessToken(), prefer)
        return JSONArray(raw.ifBlank { "[]" })
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject,
        token: String? = null,
        prefer: String? = null
    ): JSONObject {
        val raw = http(path, method, body.toString(), token, prefer)
        return if (raw.startsWith("{")) JSONObject(raw) else JSONObject()
    }

    private fun request(
        path: String,
        method: String,
        body: JSONArray,
        token: String? = null,
        prefer: String? = null
    ): JSONArray {
        val raw = http(path, method, body.toString(), token ?: accessToken(), prefer)
        return if (raw.startsWith("[")) JSONArray(raw) else JSONArray()
    }

    private fun http(path: String, method: String, body: String?, token: String?, prefer: String?): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("apikey", anonKey)
            connection.setRequestProperty("Authorization", "Bearer ${token ?: anonKey}")
            connection.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val json = JSONObject(response)
                    json.optString("message").ifBlank { json.optString("error_description") }
                }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Supabase HTTP $code")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
