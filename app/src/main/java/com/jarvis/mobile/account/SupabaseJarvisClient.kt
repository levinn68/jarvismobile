package com.jarvis.mobile.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
        require(baseUrl.startsWith("https://") && anonKey.isNotBlank()) {
            "Konfigurasi Supabase belum lengkap. Periksa SUPABASE_URL dan SUPABASE_ANON_KEY."
        }
        val webClientId = googleWebClientId.trim()
        require(webClientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) {
            "GOOGLE_WEB_CLIENT_ID tidak valid atau kosong. Gunakan OAuth Client ID bertipe Web application, bukan Android Client ID."
        }

        // This screen is an explicit Google sign-in button. Use a fresh nonce
        // for every attempt so the ID token cannot be replayed against this app.
        val rawNonceBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val rawNonce = android.util.Base64.encodeToString(
            rawNonceBytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
        )
        val hashedNonce = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val option = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            withContext(Dispatchers.Main) {
                CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            }
        } catch (error: GetCredentialCancellationException) {
            throw IllegalStateException("Login Google dibatalkan.", error)
        } catch (error: NoCredentialException) {
            throw IllegalStateException("Tidak ada akun Google yang dapat dipakai. Pastikan akun Google sudah ditambahkan ke perangkat dan Google Play Services aktif.", error)
        } catch (error: GetCredentialException) {
            val detail = error.message.orEmpty()
            val lower = detail.lowercase()
            val hint = when {
                lower.contains("developer") || lower.contains("10") || lower.contains("28444") ->
                    "Google menolak konfigurasi aplikasi (DEVELOPER_ERROR). Periksa OAuth Android Client: package com.jarvis.mobile.assistant + SHA-1 sertifikat APK, serta pastikan Web Client ID berasal dari project Google Cloud yang sama."
                lower.contains("network") -> "Koneksi Google bermasalah. Periksa internet dan Google Play Services."
                else -> "Credential Manager gagal mengambil akun Google. Periksa Google Play Services dan konfigurasi OAuth."
            }
            throw IllegalStateException(hint + " Detail: " + detail.take(240), error)
        }
        val google = if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            GoogleIdTokenCredential.createFrom(credential.data) else throw IllegalStateException("Google Sign-In tidak mengembalikan ID token yang valid.")
        val response = withContext(Dispatchers.IO) {
            requestJson("/auth/v1/token?grant_type=id_token", "POST", JSONObject().put("provider", "google").put("id_token", google.idToken).put("nonce", rawNonce), bearer = null)
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
        try {
            val ok = ensureSession(stored).optString("access_token").isNotBlank()
            if (ok) runCatching { flushPendingTurnsInternal() }
            ok
        } catch (_: Exception) { false }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = vault.read()?.optString("access_token")?.takeIf { it.isNotBlank() }
        if (token != null) runCatching { requestJson("/auth/v1/logout", "POST", JSONObject(), bearer = token, refresh = false) }
        vault.clear()
        activeConversationId = null
        appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences("jarvis_chat_offline", Context.MODE_PRIVATE).edit().clear().apply()
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
        val turnId = java.util.UUID.randomUUID().toString()
        try {
            persistTurn(userText, assistantText, turnId)
            flushPendingTurnsInternal()
        } catch (error: Exception) {
            enqueuePendingTurn(userText, assistantText, turnId)
            throw error
        }
    }

    suspend fun flushPendingTurns() = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext
        flushPendingTurnsInternal()
    }

    fun pendingTurnCount(): Int {
        val prefs = appContext.getSharedPreferences("jarvis_chat_offline", Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString("pending_turns", "[]") ?: "[]").length()
        } catch (_: Exception) {
            0
        }
    }

    private fun persistTurn(userText: String, assistantText: String, turnId: String) {
        var conversation = activeConversationId
        if (conversation == null) {
            val title = userText.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "Percakapan baru" }
            conversation = createConversationBlocking(title)
            activeConversationId = conversation
            appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit()
                .putString("active_conversation_id", conversation).apply()
        }
        val userMessageId = java.util.UUID.nameUUIDFromBytes("$turnId:user".toByteArray(Charsets.UTF_8)).toString()
        val assistantMessageId = java.util.UUID.nameUUIDFromBytes("$turnId:assistant".toByteArray(Charsets.UTF_8)).toString()
        val batch = JSONArray()
            .put(JSONObject().put("client_id", userMessageId).put("conversation_id", conversation).put("role", "user").put("content", userText))
            .put(JSONObject().put("client_id", assistantMessageId).put("conversation_id", conversation).put("role", "assistant").put("content", assistantText))
        requestArray(
            "/rest/v1/jarvis_messages?on_conflict=client_id",
            "POST",
            batch,
            prefer = "resolution=ignore-duplicates,return=minimal"
        )
        // The message batch is the durable unit. A failed metadata timestamp update
        // must not enqueue and replay the same messages a second time.
        runCatching {
            requestJson(
                "/rest/v1/jarvis_conversations?id=eq.${encode(conversation!!)}",
                "PATCH",
                JSONObject().put("updated_at", utcNow())
            )
        }
    }

    private fun createConversationBlocking(title: String): String {
        val rows = requestArray(
            "/rest/v1/jarvis_conversations?select=id",
            "POST",
            JSONArray().put(JSONObject().put("title", title.take(120))),
            prefer = "return=representation"
        )
        val id = rows.optJSONObject(0)?.optString("id").orEmpty()
        require(id.isNotBlank()) { "Gagal membuat percakapan." }
        return id
    }

    private fun enqueuePendingTurn(userText: String, assistantText: String, turnId: String) {
        val prefs = appContext.getSharedPreferences("jarvis_chat_offline", Context.MODE_PRIVATE)
        val existing = try { JSONArray(prefs.getString("pending_turns", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        while (existing.length() >= 40) existing.remove(0)
        existing.put(
            JSONObject()
                .put("user", userText.take(8000))
                .put("assistant", assistantText.take(8000))
                .put("queued_at", System.currentTimeMillis())
                .put("local_id", turnId)
        )
        prefs.edit().putString("pending_turns", existing.toString()).apply()
    }

    private fun flushPendingTurnsInternal() {
        val prefs = appContext.getSharedPreferences("jarvis_chat_offline", Context.MODE_PRIVATE)
        val raw = prefs.getString("pending_turns", null) ?: return
        val queue = try { JSONArray(raw) } catch (_: Exception) {
            prefs.edit().remove("pending_turns").apply()
            return
        }
        if (queue.length() == 0) return
        val remaining = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val localId = item.optString("local_id")
            if (localId.isNotBlank() && !seen.add(localId)) continue
            val turnId = localId.ifBlank { java.util.UUID.randomUUID().toString() }
            if (localId.isBlank()) item.put("local_id", turnId)
            val user = item.optString("user")
            val assistant = item.optString("assistant")
            if (user.isBlank() || assistant.isBlank()) continue
            try {
                persistTurn(user, assistant, turnId)
            } catch (_: Exception) {
                remaining.put(item)
                for (j in i + 1 until queue.length()) {
                    queue.optJSONObject(j)?.let { remaining.put(it) }
                }
                break
            }
        }
        if (remaining.length() == 0) prefs.edit().remove("pending_turns").apply()
        else prefs.edit().putString("pending_turns", remaining.toString()).apply()
    }

    private fun utcNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    suspend fun listConversations(): List<SavedConversation> = withContext(Dispatchers.IO) {
        val rows = requestArray("/rest/v1/jarvis_conversations?select=id,title,updated_at&order=updated_at.desc&limit=100", "GET")
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { SavedConversation(it.optString("id"), it.optString("title", "Percakapan baru"), it.optString("updated_at")) } }
    }

    suspend fun createConversation(title: String): String = withContext(Dispatchers.IO) {
        createConversationBlocking(title.ifBlank { "Percakapan baru" })
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
