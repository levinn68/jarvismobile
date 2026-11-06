package com.jarvis.mobile.account

import android.content.Context
import android.util.Base64
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.jarvis.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class JarvisSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String?,
    val displayName: String?
)

data class SavedConversation(val id: String, val title: String, val updatedAt: String)
data class SavedMessage(val id: String, val role: String, val content: String, val createdAt: String)

enum class AuthStage(val code: String, val progressLabel: String) {
    AUTH_CONFIG_VALIDATION("AUTH_CONFIG_VALIDATION", "Memvalidasi konfigurasi Google OAuth & Supabase..."),
    GOOGLE_CREDENTIAL_REQUEST("GOOGLE_CREDENTIAL_REQUEST", "Membuka pemilihan akun Google..."),
    GOOGLE_ID_TOKEN_RECEIVED("GOOGLE_ID_TOKEN_RECEIVED", "Memverifikasi Google ID Token..."),
    SUPABASE_TOKEN_EXCHANGE("SUPABASE_TOKEN_EXCHANGE", "Menukar Google ID Token dengan sesi Supabase..."),
    SUPABASE_SESSION_VALIDATION("SUPABASE_SESSION_VALIDATION", "Memvalidasi sesi dan profil Supabase..."),
    SESSION_VAULT_SAVE("SESSION_VAULT_SAVE", "Menyimpan sesi terenkripsi ke perangkat..."),
    AUTH_SUCCESS("AUTH_SUCCESS", "Akun Google berhasil tersambung ke Supabase.")
}

class JarvisAuthException(
    val stage: AuthStage,
    val diagnosticMessage: String,
    val statusCode: Int = 0,
    cause: Throwable? = null
) : IllegalStateException("[${stage.code}] $diagnosticMessage", cause)

internal class SupabaseHttpException(
    val statusCode: Int,
    message: String,
    val errorCode: String = "",
    val hint: String = ""
) : IllegalStateException(message)

/** Supabase Auth/PostgREST adapter. Uses only public key; RLS enforces account isolation. */
class SupabaseJarvisClient(context: Context) {
    private val appContext = context.applicationContext
    private val vault = SupabaseSessionVault(appContext)
    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
    private val buildWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    @Volatile private var activeConversationId: String? = null
    private val conversationLock = Any()

    init {
        activeConversationId = appContext
            .getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE)
            .getString("active_conversation_id", null)
    }

    fun hasSession(): Boolean = !vault.read()?.optString("access_token").isNullOrBlank()
    fun currentUserId(): String? = vault.read()?.optString("user_id")?.takeIf { it.isNotBlank() }
    fun currentEmail(): String? = vault.read()?.optString("email")?.takeIf { it.isNotBlank() }
    fun currentName(): String? = vault.read()?.optString("display_name")?.takeIf { it.isNotBlank() }
    fun activeConversationId(): String? = activeConversationId
    fun currentAccessToken(): String = accessToken()
    fun refreshSessionBlocking(): String =
        ensureSession(vault.read(), forceRefresh = true).optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sesi Supabase tidak valid.")
    fun resolveGoogleWebClientId(): String {
        val overrideId = appContext
            .getSharedPreferences("jarvis_secure_settings", Context.MODE_PRIVATE)
            .getString("google_web_client_id", null)
            ?.trim()
            .orEmpty()
        if (overrideId.isNotBlank()) return overrideId
        if (buildWebClientId.isNotBlank()) return buildWebClientId
        val resId = appContext.resources.getIdentifier("default_web_client_id", "string", appContext.packageName)
        if (resId != 0) {
            val fromRes = runCatching { appContext.getString(resId).trim() }.getOrDefault("")
            if (fromRes.isNotBlank()) return fromRes
        }
        return ""
    }

    fun saveGoogleWebClientIdOverride(clientId: String) {
        appContext
            .getSharedPreferences("jarvis_secure_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("google_web_client_id", clientId.trim())
            .apply()
    }

    suspend fun signInWithGoogle(
        activityContext: Context,
        onStageChanged: (AuthStage, String) -> Unit = { _, _ -> }
    ): JarvisSession {
        // STAGE 1: AUTH_CONFIG_VALIDATION
        onStageChanged(AuthStage.AUTH_CONFIG_VALIDATION, AuthStage.AUTH_CONFIG_VALIDATION.progressLabel)
        if (!baseUrl.startsWith("https://") || baseUrl.length < 12 || anonKey.isBlank()) {
            throw JarvisAuthException(
                AuthStage.AUTH_CONFIG_VALIDATION,
                "Konfigurasi Supabase belum lengkap. Periksa SUPABASE_URL dan SUPABASE_ANON_KEY."
            )
        }
        val webClientId = resolveGoogleWebClientId()
        if (webClientId.isBlank()) {
            throw JarvisAuthException(
                AuthStage.AUTH_CONFIG_VALIDATION,
                "GOOGLE_WEB_CLIENT_ID kosong. Atur secret GOOGLE_WEB_CLIENT_ID saat build atau isi Google Web Client ID pada menu Pengaturan / Akun."
            )
        }
        if (!isValidGoogleWebClientId(webClientId)) {
            throw JarvisAuthException(
                AuthStage.AUTH_CONFIG_VALIDATION,
                "Format GOOGLE_WEB_CLIENT_ID tidak valid. Gunakan OAuth 2.0 Client ID bertipe Web application (<project>-<hash>.apps.googleusercontent.com), bukan Android Client ID."
            )
        }

        // STAGE 2: GOOGLE_CREDENTIAL_REQUEST
        onStageChanged(AuthStage.GOOGLE_CREDENTIAL_REQUEST, AuthStage.GOOGLE_CREDENTIAL_REQUEST.progressLabel)
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            withContext(Dispatchers.Main) {
                CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            }
        } catch (error: GetCredentialCancellationException) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_CREDENTIAL_REQUEST,
                "Login Google dibatalkan oleh pengguna.",
                cause = error
            )
        } catch (error: NoCredentialException) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_CREDENTIAL_REQUEST,
                "Tidak ada akun Google yang tersedia di perangkat. Tambahkan akun Google di Pengaturan Android dan pastikan Google Play Services aktif.",
                cause = error
            )
        } catch (error: GetCredentialException) {
            val rawDetail = sanitizeDiagnosticText(error.message.orEmpty())
            val errType = sanitizeDiagnosticText(error.type)
            val combined = "$errType $rawDetail".lowercase()
            val hint = when {
                combined.contains("developer") ||
                    combined.contains("28444") ||
                    Regex("\\b10\\b").containsMatchIn(combined) ->
                    "Google menolak konfigurasi OAuth aplikasi (DEVELOPER_ERROR). Pastikan: (1) OAuth Android Client terdaftar untuk package com.jarvis.mobile.assistant dengan SHA-1 sertifikat APK yang sesuai, dan (2) GOOGLE_WEB_CLIENT_ID adalah Web application Client ID dari project Google Cloud yang sama."
                combined.contains("network") || combined.contains("timeout") || combined.contains("7") ->
                    "Koneksi ke Google Play Services bermasalah. Periksa koneksi internet perangkat."
                combined.contains("interrupted") || combined.contains("14") ->
                    "Permintaan login Google terputus sebelum selesai. Silakan coba pilih akun kembali."
                else ->
                    "Credential Manager gagal mengambil kredensial Google. Periksa Google Play Services dan konfigurasi OAuth."
            }
            throw JarvisAuthException(
                AuthStage.GOOGLE_CREDENTIAL_REQUEST,
                if (rawDetail.isNotBlank()) "$hint Detail: ${rawDetail.take(180)}" else hint,
                cause = error
            )
        } catch (error: Throwable) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_CREDENTIAL_REQUEST,
                "Gagal membuka Credential Manager: ${sanitizeDiagnosticText(error.message.orEmpty()).ifBlank { error.javaClass.simpleName }}",
                cause = error
            )
        }

        // STAGE 3: GOOGLE_ID_TOKEN_RECEIVED
        onStageChanged(AuthStage.GOOGLE_ID_TOKEN_RECEIVED, AuthStage.GOOGLE_ID_TOKEN_RECEIVED.progressLabel)
        val google = extractGoogleIdTokenCredential(credential)
        val rawIdToken = google.idToken.trim()
        val tokenClaims = validateGoogleIdTokenStructure(rawIdToken, webClientId)

        // STAGE 4: SUPABASE_TOKEN_EXCHANGE
        onStageChanged(AuthStage.SUPABASE_TOKEN_EXCHANGE, AuthStage.SUPABASE_TOKEN_EXCHANGE.progressLabel)
        val tokenPayload = JSONObject()
            .put("provider", "google")
            .put("id_token", rawIdToken)
        // If Google Play Services embedded a nonce claim inside the ID token and caller supplied one, forward safely.
        val tokenNonce = tokenClaims.optString("nonce").trim()
        if (tokenNonce.isNotBlank()) {
            tokenPayload.put("nonce", tokenNonce)
        }

        val response = try {
            withContext(Dispatchers.IO) {
                requestJson(
                    "/auth/v1/token?grant_type=id_token",
                    "POST",
                    tokenPayload,
                    bearer = null,
                    refresh = false
                )
            }
        } catch (error: SupabaseHttpException) {
            throw mapSupabaseTokenExchangeError(error)
        } catch (error: JarvisAuthException) {
            throw error
        } catch (error: Exception) {
            throw JarvisAuthException(
                AuthStage.SUPABASE_TOKEN_EXCHANGE,
                "Gagal menghubungi Supabase Auth saat pertukaran token: ${sanitizeDiagnosticText(error.message.orEmpty()).ifBlank { "periksa koneksi internet" }}",
                cause = error
            )
        }

        // STAGE 5: SUPABASE_SESSION_VALIDATION
        onStageChanged(AuthStage.SUPABASE_SESSION_VALIDATION, AuthStage.SUPABASE_SESSION_VALIDATION.progressLabel)
        val session = validateSupabaseAuthResponse(response)
        val user = response.optJSONObject("user")!!
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        val now = System.currentTimeMillis() / 1000L
        val expiresAt = response.optLong("expires_at", 0L).takeIf { it > now }
            ?: (now + response.optLong("expires_in", 3600L).coerceAtLeast(60L))

        val sessionRecord = JSONObject(response.toString())
            .put("user_id", session.userId)
            .put("email", session.email ?: "")
            .put(
                "display_name",
                session.displayName
                    ?: metadata.optString("full_name", metadata.optString("name", google.displayName.orEmpty()))
            )
            .put("expires_at", expiresAt)

        // STAGE 6: SESSION_VAULT_SAVE
        onStageChanged(AuthStage.SESSION_VAULT_SAVE, AuthStage.SESSION_VAULT_SAVE.progressLabel)
        try {
            withContext(Dispatchers.IO) {
                vault.save(sessionRecord)
                val verified = vault.read()
                if (verified == null ||
                    verified.optString("access_token").isBlank() ||
                    verified.optString("user_id") != session.userId
                ) {
                    throw IllegalStateException("Sesi tersimpan tetapi gagal dibaca ulang dari SupabaseSessionVault.")
                }
            }
        } catch (error: Exception) {
            throw JarvisAuthException(
                AuthStage.SESSION_VAULT_SAVE,
                "Gagal menyimpan sesi Supabase secara lokal: ${sanitizeDiagnosticText(error.message.orEmpty())}",
                cause = error
            )
        }

        setActiveConversation(null)

        // STAGE 7: AUTH_SUCCESS
        onStageChanged(AuthStage.AUTH_SUCCESS, AuthStage.AUTH_SUCCESS.progressLabel)
        return session
    }

    private fun extractGoogleIdTokenCredential(credential: Credential): GoogleIdTokenCredential {
        try {
            if (credential is GoogleIdTokenCredential) {
                return credential
            }
            if (credential is CustomCredential) {
                val isSupportedGoogleIdToken =
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL ||
                        credential.type.endsWith(".TYPE_GOOGLE_ID_TOKEN_CREDENTIAL") ||
                        credential.type.endsWith(".TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL")
                if (isSupportedGoogleIdToken) {
                    return GoogleIdTokenCredential.createFrom(credential.data)
                }
                throw JarvisAuthException(
                    AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                    "Tipe kredensial yang diterima bukan Google ID Token (${sanitizeDiagnosticText(credential.type)})."
                )
            }
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Google Sign-In mengembalikan tipe kredensial yang tidak didukung (${credential.javaClass.simpleName})."
            )
        } catch (error: JarvisAuthException) {
            throw error
        } catch (error: GoogleIdTokenParsingException) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Gagal mengurai GoogleIdTokenCredential dari bundel Credential Manager.",
                cause = error
            )
        } catch (error: Exception) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Google Sign-In tidak mengembalikan ID token yang valid: ${sanitizeDiagnosticText(error.message.orEmpty())}",
                cause = error
            )
        }
    }

    private fun validateGoogleIdTokenStructure(idToken: String, expectedWebClientId: String): JSONObject {
        if (idToken.isBlank()) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Google ID Token kosong setelah pemilihan akun."
            )
        }
        val parts = idToken.split('.')
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Format Google ID Token tidak valid (bukan JWT 3-segmen)."
            )
        }
        val claims = try {
            val decodedBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decodedBytes, Charsets.UTF_8))
        } catch (error: Exception) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Payload Google ID Token tidak dapat didekode sebagai JSON.",
                cause = error
            )
        }
        val aud = claims.optString("aud").trim()
        if (aud.isNotBlank() && expectedWebClientId.isNotBlank() && aud != expectedWebClientId) {
            throw JarvisAuthException(
                AuthStage.GOOGLE_ID_TOKEN_RECEIVED,
                "Audience (aud) pada Google ID Token tidak cocok dengan GOOGLE_WEB_CLIENT_ID yang dikonfigurasi."
            )
        }
        return claims
    }

    private fun mapSupabaseTokenExchangeError(error: SupabaseHttpException): JarvisAuthException {
        val cleanMsg = sanitizeDiagnosticText(error.message.orEmpty())
        val codeInfo = if (error.errorCode.isNotBlank()) " [${sanitizeDiagnosticText(error.errorCode)}]" else ""
        val hintInfo = if (error.hint.isNotBlank() && !cleanMsg.contains(error.hint)) " (${sanitizeDiagnosticText(error.hint)})" else ""
        val detail = "$cleanMsg$codeInfo$hintInfo".trim()
        val description = when (error.statusCode) {
            400 -> "Supabase menolak pertukaran Google ID Token (HTTP 400 Bad Request): $detail. Pastikan provider Google aktif di Supabase Auth dan Web Client ID terdaftar di Authorized Client IDs Supabase."
            401 -> "Supabase menolak otorisasi permintaan (HTTP 401 Unauthorized): $detail. Periksa SUPABASE_ANON_KEY dan konfigurasi provider Google di Supabase."
            403 -> "Supabase menolak akses login Google (HTTP 403 Forbidden): $detail. Periksa apakah pendaftaran akun baru (signups) atau domain email diizinkan pada Supabase Auth."
            429 -> "Terlalu banyak percobaan login ke Supabase (HTTP 429 Rate Limit): $detail. Tunggu beberapa saat lalu coba kembali."
            in 500..599 -> "Server Supabase mengalami gangguan internal (HTTP ${error.statusCode}): $detail. Pastikan Google provider dan Client ID pada dashboard Supabase Auth sudah dikonfigurasi dengan benar."
            else -> "Pertukaran token Supabase gagal (HTTP ${error.statusCode}): $detail"
        }
        return JarvisAuthException(
            AuthStage.SUPABASE_TOKEN_EXCHANGE,
            description,
            statusCode = error.statusCode,
            cause = error
        )
    }

    private fun validateSupabaseAuthResponse(response: JSONObject): JarvisSession {
        val accessToken = response.optString("access_token").trim()
        if (accessToken.isBlank()) {
            throw JarvisAuthException(
                AuthStage.SUPABASE_SESSION_VALIDATION,
                "Respons Supabase tidak memiliki access_token."
            )
        }
        val refreshToken = response.optString("refresh_token").trim()
        if (refreshToken.isBlank()) {
            throw JarvisAuthException(
                AuthStage.SUPABASE_SESSION_VALIDATION,
                "Respons Supabase tidak memiliki refresh_token."
            )
        }
        val user = response.optJSONObject("user")
            ?: throw JarvisAuthException(
                AuthStage.SUPABASE_SESSION_VALIDATION,
                "Respons Supabase tidak memiliki objek user."
            )
        val userId = user.optString("id").trim()
        if (userId.isBlank()) {
            throw JarvisAuthException(
                AuthStage.SUPABASE_SESSION_VALIDATION,
                "Profil user Supabase tidak memiliki id."
            )
        }
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        val email = user.optString("email").trim().takeIf { it.isNotBlank() }
        val displayName = metadata.optString("full_name", metadata.optString("name")).trim().takeIf { it.isNotBlank() }
        return JarvisSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            displayName = displayName
        )
    }

    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val stored = vault.read() ?: return@withContext false
        try {
            val ok = ensureSession(stored).optString("access_token").isNotBlank()
            if (ok) runCatching { flushPendingTurnsInternal() }
            ok
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
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
            if (error is kotlinx.coroutines.CancellationException) throw error
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
        val userId = currentUserId() ?: throw IllegalStateException("Sesi pengguna tidak ditemukan.")
        var conversation = synchronized(conversationLock) {
            var current = activeConversationId
            if (current == null) {
                val title = userText.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "Percakapan baru" }
                current = createConversationBlocking(title)
                activeConversationId = current
                appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit()
                    .putString("active_conversation_id", current).apply()
            }
            current
        }
        val userMessageId = java.util.UUID.nameUUIDFromBytes("$userId:$turnId:user".toByteArray(Charsets.UTF_8)).toString()
        val assistantMessageId = java.util.UUID.nameUUIDFromBytes("$userId:$turnId:assistant".toByteArray(Charsets.UTF_8)).toString()
        val nowMs = System.currentTimeMillis()
        val userCreatedAt = formatUtcIso(nowMs)
        val assistantCreatedAt = formatUtcIso(nowMs + 1L)

        fun buildBatch(convId: String): JSONArray = JSONArray()
            .put(
                JSONObject()
                    .put("client_id", userMessageId)
                    .put("conversation_id", convId)
                    .put("role", "user")
                    .put("content", userText.take(32000))
                    .put("created_at", userCreatedAt)
            )
            .put(
                JSONObject()
                    .put("client_id", assistantMessageId)
                    .put("conversation_id", convId)
                    .put("role", "assistant")
                    .put("content", assistantText.take(32000))
                    .put("created_at", assistantCreatedAt)
            )

        try {
            requestArray(
                "/rest/v1/jarvis_messages?on_conflict=client_id",
                "POST",
                buildBatch(conversation!!),
                prefer = "resolution=ignore-duplicates,return=minimal"
            )
        } catch (error: SupabaseHttpException) {
            if (error.statusCode in setOf(403, 404, 409)) {
                val title = userText.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "Percakapan baru" }
                conversation = createConversationBlocking(title)
                activeConversationId = conversation
                appContext.getSharedPreferences("jarvis_chat_state", Context.MODE_PRIVATE).edit()
                    .putString("active_conversation_id", conversation).apply()
                requestArray(
                    "/rest/v1/jarvis_messages?on_conflict=client_id",
                    "POST",
                    buildBatch(conversation!!),
                    prefer = "resolution=ignore-duplicates,return=minimal"
                )
            } else {
                throw error
            }
        }

        runCatching {
            requestJson(
                "/rest/v1/jarvis_conversations?id=eq.${encode(conversation!!)}",
                "PATCH",
                JSONObject().put("updated_at", utcNow())
            )
        }
    }

    private fun createConversationBlocking(title: String): String {
        val cleanTitle = title.trim().take(120).ifBlank { "Percakapan baru" }
        val rows = requestArray(
            "/rest/v1/jarvis_conversations?select=id",
            "POST",
            JSONArray().put(JSONObject().put("title", cleanTitle)),
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
                .put("user_id", currentUserId().orEmpty())
        )
        prefs.edit().putString("pending_turns", existing.toString()).apply()
    }

    private fun flushPendingTurnsInternal() {
        val activeUserId = currentUserId() ?: return
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
            val itemUserId = item.optString("user_id")
            if (itemUserId.isNotBlank() && itemUserId != activeUserId) {
                continue
            }
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

    private fun formatUtcIso(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(epochMs))

    private fun utcNow(): String = formatUtcIso(System.currentTimeMillis())

    suspend fun listConversations(): List<SavedConversation> = withContext(Dispatchers.IO) {
        val rows = requestArray("/rest/v1/jarvis_conversations?select=id,title,updated_at&order=updated_at.desc&limit=100", "GET")
        (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let {
                SavedConversation(it.optString("id"), it.optString("title", "Percakapan baru"), it.optString("updated_at"))
            }
        }
    }

    suspend fun createConversation(title: String): String = withContext(Dispatchers.IO) {
        createConversationBlocking(title.ifBlank { "Percakapan baru" })
    }

    suspend fun listMessages(conversationId: String): List<SavedMessage> = withContext(Dispatchers.IO) {
        val rows = requestArray(
            "/rest/v1/jarvis_messages?select=id,role,content,created_at&conversation_id=eq.${encode(conversationId)}&order=created_at.asc,id.asc&limit=500",
            "GET"
        )
        (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let {
                SavedMessage(it.optString("id"), it.optString("role"), it.optString("content"), it.optString("created_at"))
            }
        }
    }

    suspend fun renameConversation(id: String, title: String) = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "Judul percakapan tidak boleh kosong." }
        requestJson("/rest/v1/jarvis_conversations?id=eq.${encode(id)}", "PATCH", JSONObject().put("title", title.trim().take(80)))
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        requestJson("/rest/v1/jarvis_conversations?id=eq.${encode(id)}", "DELETE", JSONObject())
        if (activeConversationId == id) setActiveConversation(null)
    }

    @Synchronized
    private fun ensureSession(existing: JSONObject? = vault.read(), forceRefresh: Boolean = false): JSONObject {
        val stored = existing ?: throw IllegalStateException("Silakan masuk dengan Google terlebih dahulu.")
        val now = System.currentTimeMillis() / 1000L
        if (!forceRefresh && stored.optString("access_token").isNotBlank() && stored.optLong("expires_at", 0L) > now + 90L) {
            return stored
        }
        val refresh = stored.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sesi habis. Masuk kembali dengan Google.")
        val fresh = requestJson(
            "/auth/v1/token?grant_type=refresh_token",
            "POST",
            JSONObject().put("refresh_token", refresh),
            bearer = null,
            refresh = false
        )
        val merged = JSONObject(stored.toString())
        listOf("access_token", "refresh_token", "token_type", "expires_in", "expires_at").forEach {
            if (fresh.has(it)) merged.put(it, fresh.get(it))
        }
        fresh.optJSONObject("user")?.let { u ->
            if (u.has("id")) merged.put("user_id", u.getString("id"))
            if (u.has("email")) merged.put("email", u.getString("email"))
            val metadata = u.optJSONObject("user_metadata")
            if (metadata != null) merged.put("display_name", metadata.optString("full_name", metadata.optString("name")))
        }
        val refreshedNow = System.currentTimeMillis() / 1000L
        val expiresAt = fresh.optLong("expires_at", 0L).takeIf { it > refreshedNow }
            ?: (refreshedNow + fresh.optLong("expires_in", 3600L).coerceAtLeast(60L))
        merged.put("expires_at", expiresAt)
        if (merged.optString("access_token").isBlank() || merged.optString("refresh_token").isBlank()) {
            throw IllegalStateException("Supabase tidak mengembalikan token hasil refresh.")
        }
        vault.save(merged)
        return merged
    }

    private fun accessToken(): String = ensureSession().optString("access_token").takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Sesi Supabase tidak valid.")

    private fun requestArray(path: String, method: String, body: JSONArray? = null, prefer: String? = null): JSONArray {
        val raw = try {
            http(path, method, body?.toString(), accessToken(), prefer)
        } catch (error: SupabaseHttpException) {
            if (error.statusCode == 401) {
                http(path, method, body?.toString(), refreshSessionBlocking(), prefer)
            } else {
                throw error
            }
        }
        return JSONArray(raw.ifBlank { "[]" })
    }

    private fun requestJson(
        path: String,
        method: String,
        body: JSONObject? = null,
        bearer: String? = null,
        prefer: String? = null,
        refresh: Boolean = true
    ): JSONObject {
        val token = if (bearer != null) bearer else if (refresh) accessToken() else null
        val raw = try {
            http(path, method, body?.toString(), token, prefer)
        } catch (error: SupabaseHttpException) {
            if (error.statusCode == 401 && bearer == null && refresh) {
                http(path, method, body?.toString(), refreshSessionBlocking(), prefer)
            } else {
                throw error
            }
        }
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
            // Crucial fix for Supabase `sb_publishable_*` keys:
            // Only send `Authorization: Bearer ...` when an authenticated user JWT (`token`) is present,
            // or when `anonKey` itself is a legacy 3-segment JWT (`eyJ...`).
            // Sending `Authorization: Bearer sb_publishable_...` causes Supabase gateway/GoTrue
            // to reject `/auth/v1/token?grant_type=id_token` with invalid JWT errors.
            val effectiveBearer = when {
                !token.isNullOrBlank() -> token
                isJwtFormattedKey(anonKey) -> anonKey
                else -> null
            }
            if (effectiveBearer != null) {
                connection.setRequestProperty("Authorization", "Bearer $effectiveBearer")
            }
            connection.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = if (stream != null) String(com.jarvis.mobile.stability.JarvisStabilityGuard.readBoundedBytes(stream, 2 * 1024 * 1024), Charsets.UTF_8) else ""
            if (code !in 200..299) {
                throw parseSupabaseHttpError(code, response)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val WEB_CLIENT_ID_REGEX = Regex("^[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com$")

        internal fun isValidGoogleWebClientId(value: String): Boolean {
            val trimmed = value.trim()
            if (!WEB_CLIENT_ID_REGEX.matches(trimmed)) return false
            val lower = trimmed.lowercase()
            if (lower.contains("android") || lower.startsWith("0-") || lower.contains("placeholder") || lower.contains("example")) {
                return false
            }
            return true
        }

        internal fun isJwtFormattedKey(key: String): Boolean {
            val trimmed = key.trim()
            return trimmed.startsWith("eyJ") && trimmed.count { it == '.' } == 2
        }

        internal fun sanitizeDiagnosticText(input: String): String {
            if (input.isBlank()) return ""
            return input
                // Redact any 3-part JWT tokens
                .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED_JWT]")
                // Redact Supabase publishable/secret keys
                .replace(Regex("sb_(?:publishable|secret)_[A-Za-z0-9_-]+"), "[REDACTED_KEY]")
                // Redact JSON token values if echoed
                .replace(Regex("(?i)(\"(?:id_token|access_token|refresh_token|client_secret|apikey)\"\\s*:\\s*\")([^\"]+)(\")"), "$1[REDACTED]$3")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(280)
        }

        internal fun parseSupabaseHttpError(statusCode: Int, responseBody: String): SupabaseHttpException {
            var primaryMessage = ""
            var errorCode = ""
            var hint = ""
            runCatching {
                if (responseBody.trim().startsWith("{")) {
                    val json = JSONObject(responseBody)
                    primaryMessage = sequenceOf(
                        json.optString("error_description"),
                        json.optString("message"),
                        json.optString("msg"),
                        json.optString("error")
                    ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    errorCode = sequenceOf(
                        json.optString("error_code"),
                        json.optString("code")
                    ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    hint = json.optString("hint").trim()
                }
            }
            val safeMessage = sanitizeDiagnosticText(primaryMessage).ifBlank { "Supabase HTTP $statusCode" }
            val safeCode = sanitizeDiagnosticText(errorCode)
            val safeHint = sanitizeDiagnosticText(hint)
            return SupabaseHttpException(
                statusCode = statusCode,
                message = safeMessage,
                errorCode = safeCode,
                hint = safeHint
            )
        }
    }
}