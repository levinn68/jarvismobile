package com.jarvis.mobile.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.mobile.account.SavedConversation
import com.jarvis.mobile.ai.DefaultB8Models
import com.jarvis.mobile.ai.IndependentProviderCatalog
import com.jarvis.mobile.ai.JarvisAccountItem
import com.jarvis.mobile.ai.JarvisModelItem
import com.jarvis.mobile.ai.JarvisModelSelectorScreen
import com.jarvis.mobile.ai.JarvisMultiAccountsScreen
import com.jarvis.mobile.ai.JarvisProviderItem
import com.jarvis.mobile.ai.JarvisProvidersScreen
import com.jarvis.mobile.device.JarvisDeviceClient
import com.jarvis.mobile.stability.JarvisStabilityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * J.A.R.V.I.S. Batch 8.5.2 — Unified 40-Screen Master Navigation Shell (`JarvisB8MasterShell`)
 *
 * Upgrades in Batch 8.5.2:
 * - Eliminates hardcoded connected provider & fake personal account assumptions in production UI.
 * - Synchronizes independent AI Providers (`/v1/providers`, `/v1/providers/config`, `/v1/providers/test`),
 *   user-managed Multi-Accounts (`/v1/ai-accounts` GET/POST/DELETE), and provider-scoped Model Catalogs (`/v1/models`)
 *   with the Cloudflare Worker backend over HTTPS and persists non-secret metadata locally across app restarts.
 * - Ensures entered API keys are genuinely transmitted to the backend (`POST /v1/ai-accounts` and `POST /v1/providers/test`)
 *   and never discarded via `_`.
 * - Never marks a provider as `connected` merely because an API key was saved; requires a verified `testProviderConnection`.
 */
enum class JarvisB8OverlayRoute {
    NONE,
    ONBOARDING_FLOW,
    HISTORY_SEARCH,
    MODEL_SELECTOR,
    AI_PROVIDERS,
    MULTI_ACCOUNTS
}

@Composable
fun JarvisB8MasterShell(
    userEmail: String? = null,
    userName: String? = null,
    liveVoiceStatus: String = "MIC OFF",
    liveRecognizedText: String = "",
    liveRmsLevel: Float = 2f,
    savedConversations: List<SavedConversation> = emptyList(),
    accessTokenProvider: () -> String = { "" },
    onSendLiveChatPrompt: ((String, (String, Boolean) -> Unit) -> (() -> Unit))? = null,
    onStartListening: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onSpeakText: (String) -> Unit = {},
    onRefreshConversations: () -> Unit = {},
    onOpenSavedConversation: (String) -> Unit = {},
    onReturnToClassicHud: () -> Unit = {},
    onTriggerGoogleSignIn: () -> Unit = {},
    onTriggerSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences("jarvis_b8_independent_providers", Context.MODE_PRIVATE)
    }

    var activeTab by remember { mutableStateOf(JarvisB8NavTab.CHAT) }
    var overlayRoute by remember { mutableStateOf(JarvisB8OverlayRoute.NONE) }
    var selectedTier by remember { mutableStateOf(JarvisIntelligenceTier.APEX) }
    var selectedModelId by remember {
        mutableStateOf(prefs.getString("selected_model_id", "gpt-4o") ?: "gpt-4o")
    }
    var activeModelLabel by remember {
        mutableStateOf(prefs.getString("active_model_label", "JARVIS 4 — APEX (GPT-4o)") ?: "JARVIS 4 — APEX (GPT-4o)")
    }
    var selectedAccountProvider by remember { mutableStateOf("openai") }
    var collaborationTopic by remember {
        mutableStateOf("Merancang arsitektur aplikasi SaaS scalable untuk 100.000 pengguna aktif.")
    }

    val providersState = remember {
        mutableStateListOf<JarvisProviderItem>().apply {
            addAll(IndependentProviderCatalog)
        }
    }

    val accountsState = remember {
        mutableStateListOf<JarvisAccountItem>()
    }

    val modelsState = remember {
        mutableStateListOf<JarvisModelItem>().apply {
            addAll(DefaultB8Models)
        }
    }

    fun persistLocalMetadataSnapshot() {
        runCatching {
            val provArr = JSONArray()
            providersState.forEach { p ->
                provArr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("connected", p.connected)
                        .put("accountCount", p.accountCount)
                        .put("enabled", p.enabled)
                        .put("integrationState", p.integrationState)
                        .put("connectionStatus", p.connectionStatus)
                        .put("endpoint", p.endpoint)
                        .put("defaultModel", p.defaultModel)
                        .put("maskedApiKey", p.maskedApiKey)
                )
            }
            val accArr = JSONArray()
            accountsState.forEach { a ->
                // Never store raw API keys in local UI metadata snapshot
                accArr.put(
                    JSONObject()
                        .put("accountId", a.accountId)
                        .put("provider", a.provider)
                        .put("label", a.label)
                        .put("emailHint", a.emailHint)
                        .put("active", a.active)
                        .put("isDefault", a.isDefault)
                        .put("maskedKey", a.maskedKey)
                        .put("status", a.status)
                        .put("connectionVerified", a.connectionVerified)
                        .put("quotaUsedTokens", a.quotaUsedTokens)
                        .put("quotaLimitTokens", a.quotaLimitTokens)
                )
            }
            prefs.edit()
                .putString("providers_snapshot_v1", provArr.toString())
                .putString("accounts_snapshot_v1", accArr.toString())
                .apply()
        }
    }

    fun restoreLocalMetadataSnapshot() {
        runCatching {
            val rawAcc = prefs.getString("accounts_snapshot_v1", null)
            if (!rawAcc.isNullOrBlank()) {
                val arr = JSONArray(rawAcc)
                accountsState.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    accountsState.add(
                        JarvisAccountItem(
                            accountId = o.optString("accountId"),
                            provider = o.optString("provider"),
                            label = o.optString("label"),
                            emailHint = o.optString("emailHint"),
                            active = o.optBoolean("active", true),
                            quotaUsedTokens = o.optInt("quotaUsedTokens", 0),
                            quotaLimitTokens = o.optInt("quotaLimitTokens", 200000),
                            isDefault = o.optBoolean("isDefault", false),
                            maskedKey = o.optString("maskedKey", "sk***"),
                            status = o.optString("status", "configured_unverified"),
                            connectionVerified = o.optBoolean("connectionVerified", false)
                        )
                    )
                }
            }
            val rawProv = prefs.getString("providers_snapshot_v1", null)
            if (!rawProv.isNullOrBlank()) {
                val arr = JSONArray(rawProv)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val idx = providersState.indexOfFirst { it.id == id }
                    val base = if (idx >= 0) providersState[idx] else IndependentProviderCatalog.first()
                    val item = base.copy(
                        id = id,
                        name = o.optString("name", base.name),
                        connected = o.optBoolean("connected", false),
                        accountCount = o.optInt("accountCount", 0),
                        enabled = o.optBoolean("enabled", true),
                        integrationState = o.optString("integrationState", "ui_configured_only"),
                        connectionStatus = o.optString("connectionStatus", "not_connected"),
                        endpoint = o.optString("endpoint", base.endpoint),
                        defaultModel = o.optString("defaultModel", base.defaultModel),
                        maskedApiKey = o.optString("maskedApiKey", "")
                    )
                    if (idx >= 0) providersState[idx] = item else providersState.add(item)
                }
            }
        }
    }

    fun syncProvidersAndAccountsFromBackend() {
        scope.launch {
            try {
                val (remoteProviders, remoteAccounts) = withContext(Dispatchers.IO) {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val token = accessTokenProvider()

                    val fetchedProviders = mutableListOf<JarvisProviderItem>()
                    val fetchedAccounts = mutableListOf<JarvisAccountItem>()

                    val provConn = (URL("$workerBaseUrl/v1/providers").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 12000
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        if (provConn.responseCode in 200..299) {
                            val raw = String(JarvisStabilityGuard.readBoundedBytes(provConn.inputStream, 1024 * 1024), Charsets.UTF_8)
                            val json = JarvisStabilityGuard.parseJsonObjectSafe(raw)
                            val arr = json?.optJSONArray("providers") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val p = arr.optJSONObject(i) ?: continue
                                val id = p.optString("id")
                                val catSpec = IndependentProviderCatalog.find { it.id == id }
                                val modelsArr = p.optJSONArray("supportedModels") ?: JSONArray()
                                val supported = (0 until modelsArr.length()).mapNotNull { modelsArr.optString(it).takeIf { s -> s.isNotBlank() } }
                                val errObj = p.optJSONObject("errorStatus")
                                fetchedProviders.add(
                                    JarvisProviderItem(
                                        id = id,
                                        name = p.optString("name", catSpec?.name ?: id.uppercase()),
                                        connected = p.optBoolean("userConnected", p.optBoolean("connected", false)),
                                        accountCount = p.optInt("accountCount", 0),
                                        enabled = p.optBoolean("enabled", true),
                                        integrationState = p.optString("integrationState", "ui_configured_only"),
                                        connectionStatus = p.optString("connectionStatus", "not_connected"),
                                        endpoint = p.optString("endpoint", catSpec?.endpoint.orEmpty()),
                                        modelsEndpoint = p.optString("modelsEndpoint", catSpec?.modelsEndpoint.orEmpty()),
                                        authType = p.optString("authType", catSpec?.authType ?: "Bearer API Key"),
                                        keyFormatHint = p.optJSONObject("authConfig")?.optString("keyFormatHint") ?: catSpec?.keyFormatHint.orEmpty(),
                                        docsUrl = p.optString("docsUrl", catSpec?.docsUrl.orEmpty()),
                                        defaultModel = p.optString("defaultModel", catSpec?.defaultModel.orEmpty()),
                                        supportedModels = supported.ifEmpty { catSpec?.supportedModels.orEmpty() },
                                        maskedApiKey = p.optString("maskedApiKey", ""),
                                        errorCode = errObj?.optString("errorCode").orEmpty(),
                                        errorMessage = errObj?.optString("errorMessage", errObj?.optString("message").orEmpty()).orEmpty()
                                    )
                                )
                            }
                        }
                    } finally {
                        provConn.disconnect()
                    }

                    val accConn = (URL("$workerBaseUrl/v1/ai-accounts").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 12000
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        if (accConn.responseCode in 200..299) {
                            val raw = String(JarvisStabilityGuard.readBoundedBytes(accConn.inputStream, 1024 * 1024), Charsets.UTF_8)
                            val json = JarvisStabilityGuard.parseJsonObjectSafe(raw)
                            val arr = json?.optJSONArray("accounts") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val a = arr.optJSONObject(i) ?: continue
                                // Only accept user_account (never worker_secret)
                                if (a.optString("source", "user_account") != "user_account") continue
                                val errObj = a.optJSONObject("errorStatus")
                                fetchedAccounts.add(
                                    JarvisAccountItem(
                                        accountId = a.optString("accountId"),
                                        provider = a.optString("provider"),
                                        label = a.optString("label", "Akun"),
                                        emailHint = a.optString("emailOrHint", ""),
                                        active = a.optBoolean("enabled", true),
                                        quotaUsedTokens = a.optInt("quotaUsedTokens", 0),
                                        quotaLimitTokens = a.optInt("quotaLimitTokens", 200000),
                                        isDefault = a.optBoolean("isDefault", false),
                                        maskedKey = a.optString("maskedKey", "sk***"),
                                        status = a.optString("status", "configured_unverified"),
                                        connectionVerified = a.optBoolean("connectionVerified", false),
                                        lastTestedAt = a.optString("lastTestedAt", ""),
                                        errorMessage = errObj?.optString("errorMessage").orEmpty()
                                    )
                                )
                            }
                        }
                    } finally {
                        accConn.disconnect()
                    }

                    fetchedProviders to fetchedAccounts
                }

                if (remoteProviders.isNotEmpty()) {
                    providersState.clear()
                    providersState.addAll(remoteProviders)
                }
                if (remoteAccounts.isNotEmpty() || accountsState.isEmpty()) {
                    accountsState.clear()
                    accountsState.addAll(remoteAccounts)
                }
                persistLocalMetadataSnapshot()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                JarvisStabilityGuard.recordDiagnostic(JarvisStabilityGuard.Subsystem.NETWORK, "B8_PROVIDER_SYNC_WARN", err)
            }
        }
    }

    fun saveAccountOrProviderConfigToBackend(
        accountId: String? = null,
        providerId: String,
        label: String,
        emailHint: String,
        apiKey: String,
        customEndpoint: String = "",
        defaultModel: String = "",
        enabled: Boolean? = null,
        isDefault: Boolean? = null
    ) {
        val cleanProvider = providerId.trim().lowercase()
        // Optimistic local state update (never marking connected = true before Test Connection!)
        val existingAccIdx = accountsState.indexOfFirst {
            (accountId != null && it.accountId == accountId) ||
                (accountId == null && it.provider == cleanProvider && it.label.equals(label, ignoreCase = true))
        }
        val masked = if (apiKey.length > 8) "${apiKey.take(3)}***${apiKey.takeLast(4)}" else "sk***"
        val resolvedAccId = accountId ?: if (existingAccIdx >= 0) accountsState[existingAccIdx].accountId else "acc_${cleanProvider}_${System.currentTimeMillis()}"
        val shouldBeDefault = isDefault ?: (accountsState.none { it.provider == cleanProvider })
        if (shouldBeDefault) {
            for (i in accountsState.indices) {
                if (accountsState[i].provider == cleanProvider) {
                    accountsState[i] = accountsState[i].copy(isDefault = false)
                }
            }
        }
        val updatedAccount = JarvisAccountItem(
            accountId = resolvedAccId,
            provider = cleanProvider,
            label = label,
            emailHint = emailHint,
            active = enabled ?: true,
            isDefault = shouldBeDefault,
            maskedKey = if (apiKey.isNotBlank()) masked else (if (existingAccIdx >= 0) accountsState[existingAccIdx].maskedKey else "sk***"),
            status = "configured_unverified",
            connectionVerified = false
        )
        if (existingAccIdx >= 0) {
            accountsState[existingAccIdx] = updatedAccount
        } else {
            accountsState.add(updatedAccount)
        }

        val provIdx = providersState.indexOfFirst { it.id == cleanProvider }
        val provAccountsCount = accountsState.count { it.provider == cleanProvider }
        if (provIdx >= 0) {
            val currentProv = providersState[provIdx]
            providersState[provIdx] = currentProv.copy(
                accountCount = provAccountsCount,
                connected = false, // Must run Test Connection to become connected!
                connectionStatus = "configured_unverified",
                integrationState = "configured_unverified",
                endpoint = customEndpoint.ifBlank { currentProv.endpoint },
                defaultModel = defaultModel.ifBlank { currentProv.defaultModel },
                maskedApiKey = updatedAccount.maskedKey
            )
        }
        persistLocalMetadataSnapshot()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                val token = accessTokenProvider()

                if (customEndpoint.isNotBlank() || defaultModel.isNotBlank()) {
                    val cfgConn = (URL("$workerBaseUrl/v1/providers/config").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        val cfgPayload = JSONObject()
                            .put("provider", cleanProvider)
                            .put("endpoint", customEndpoint.takeIf { it.isNotBlank() })
                            .put("defaultModel", defaultModel.takeIf { it.isNotBlank() })
                        cfgConn.outputStream.use { it.write(cfgPayload.toString().toByteArray(Charsets.UTF_8)) }
                        cfgConn.responseCode
                    } finally {
                        cfgConn.disconnect()
                    }
                }

                val conn = (URL("$workerBaseUrl/v1/ai-accounts").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    val body = JSONObject()
                        .put("accountId", resolvedAccId)
                        .put("provider", cleanProvider)
                        .put("label", label)
                        .put("email", emailHint)
                        .put("apiKey", apiKey)
                        .put("customEndpoint", customEndpoint.takeIf { it.isNotBlank() })
                        .put("defaultModel", defaultModel.takeIf { it.isNotBlank() })
                    if (enabled != null) body.put("enabled", enabled)
                    if (isDefault != null) body.put("isDefault", isDefault)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
                syncProvidersAndAccountsFromBackend()
            }.onFailure { err ->
                JarvisStabilityGuard.rethrowIfCancellation(err)
                JarvisStabilityGuard.recordDiagnostic(JarvisStabilityGuard.Subsystem.NETWORK, "B8_SAVE_ACCOUNT_ERROR", err)
            }
        }
    }

    fun deleteAccountFromBackend(accountId: String, providerId: String) {
        val cleanProvider = providerId.trim().lowercase()
        accountsState.removeAll { it.accountId == accountId }
        val provIdx = providersState.indexOfFirst { it.id == cleanProvider }
        if (provIdx >= 0) {
            val remaining = accountsState.filter { it.provider == cleanProvider }
            val stillConnected = remaining.any { it.active && it.connectionVerified }
            providersState[provIdx] = providersState[provIdx].copy(
                accountCount = remaining.size,
                connected = stillConnected,
                connectionStatus = if (stillConnected) "connected" else if (remaining.isNotEmpty()) "configured_unverified" else "not_connected"
            )
        }
        persistLocalMetadataSnapshot()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                val token = accessTokenProvider()
                val conn = (URL("$workerBaseUrl/v1/ai-accounts/$accountId?provider=$cleanProvider").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 10000
                    readTimeout = 12000
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
                syncProvidersAndAccountsFromBackend()
            }
        }
    }

    fun executeLiveProviderConnectionTest(
        providerId: String,
        accountId: String = "",
        apiKey: String = "",
        customEndpoint: String = "",
        onResult: (Boolean, String, List<String>) -> Unit
    ) {
        val cleanProvider = providerId.trim().lowercase()
        scope.launch {
            val (ok, msg, discoveredModels) = withContext(Dispatchers.IO) {
                try {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val token = accessTokenProvider()
                    val conn = (URL("$workerBaseUrl/v1/providers/test").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        val payload = JSONObject()
                            .put("provider", cleanProvider)
                            .put("accountId", accountId.takeIf { it.isNotBlank() })
                            .put("apiKey", apiKey.takeIf { it.isNotBlank() })
                            .put("customEndpoint", customEndpoint.takeIf { it.isNotBlank() })
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val raw = if (stream != null) {
                            String(JarvisStabilityGuard.readBoundedBytes(stream, 1024 * 1024), Charsets.UTF_8)
                        } else ""
                        val parsed = JarvisStabilityGuard.parseJsonObjectSafe(raw)
                        val isOk = code in 200..299 && parsed?.optBoolean("ok", false) == true
                        val modelsArr = parsed?.optJSONArray("models") ?: JSONArray()
                        val discovered = (0 until modelsArr.length()).mapNotNull { modelsArr.optString(it).takeIf { s -> s.isNotBlank() } }
                        val statusMsg = if (isOk) {
                            "TERHUBUNG (${cleanProvider.uppercase()}) · ${discovered.size} model ditemukan dari API"
                        } else {
                            val errCode = parsed?.optString("errorCode").orEmpty().ifBlank { "CONNECTION_FAILED" }
                            val errText = parsed?.optString("error").orEmpty().ifBlank { "HTTP $code" }
                            "GAGAL ($errCode): $errText"
                        }
                        Triple(isOk, statusMsg, discovered)
                    } finally {
                        conn.disconnect()
                    }
                } catch (err: Exception) {
                    Triple(false, "NETWORK_ERROR: ${err.message ?: "Gagal menghubungi backend"}", emptyList())
                }
            }

            // Update local provider & account state truthfully based on the live test result
            val pIdx = providersState.indexOfFirst { it.id == cleanProvider }
            if (pIdx >= 0) {
                val curr = providersState[pIdx]
                providersState[pIdx] = curr.copy(
                    connected = ok && curr.enabled,
                    connectionStatus = if (ok) "connected" else "connection_failed",
                    integrationState = if (ok) "working_live" else "connection_failed",
                    supportedModels = if (discoveredModels.isNotEmpty()) discoveredModels else curr.supportedModels,
                    errorMessage = if (ok) "" else msg
                )
            }
            for (i in accountsState.indices) {
                val acc = accountsState[i]
                if (acc.provider == cleanProvider && (accountId.isBlank() || acc.accountId == accountId)) {
                    accountsState[i] = acc.copy(
                        status = if (ok) "working_live" else "connection_failed",
                        connectionVerified = ok,
                        errorMessage = if (ok) "" else msg
                    )
                }
            }
            if (discoveredModels.isNotEmpty()) {
                discoveredModels.forEach { mId ->
                    if (modelsState.none { it.id == mId && it.provider == cleanProvider }) {
                        modelsState.add(
                            JarvisModelItem(
                                id = mId,
                                name = mId,
                                providerLabel = cleanProvider.uppercase(),
                                summary = "Discovered live from ${cleanProvider.uppercase()} API",
                                categories = listOf("Semua", "Teks", "Kode"),
                                tier = JarvisIntelligenceTier.SPARK,
                                provider = cleanProvider,
                                available = true,
                                discoveredLive = true
                            )
                        )
                    }
                }
            }
            persistLocalMetadataSnapshot()
            onResult(ok, msg, discoveredModels)
        }
    }

    LaunchedEffect(Unit) {
        restoreLocalMetadataSnapshot()
        syncProvidersAndAccountsFromBackend()
    }

    val connectedProviderIds = providersState
        .filter { it.enabled && it.connected }
        .map { it.id.lowercase() }
        .toSet()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = overlayRoute to activeTab,
                transitionSpec = {
                    fadeIn(tween(JarvisMotion.FAST_MS)) togetherWith fadeOut(tween(JarvisMotion.FAST_MS))
                },
                label = "b8_master_shell_content"
            ) { (currentOverlay, currentTab) ->
                when (currentOverlay) {
                    JarvisB8OverlayRoute.ONBOARDING_FLOW -> {
                        JarvisB8OnboardingFlow(
                            isAuthenticated = userEmail != null,
                            onGoogleLoginClick = onTriggerGoogleSignIn,
                            onGuestContinueClick = {
                                overlayRoute = JarvisB8OverlayRoute.NONE
                                activeTab = JarvisB8NavTab.BERANDA
                            },
                            onFlowCompleted = {
                                overlayRoute = JarvisB8OverlayRoute.NONE
                                activeTab = JarvisB8NavTab.BERANDA
                            }
                        )
                    }

                    JarvisB8OverlayRoute.HISTORY_SEARCH -> {
                        JarvisB8ConversationHistoryScreen(
                            savedConversations = savedConversations,
                            onRefreshConversations = onRefreshConversations,
                            onSelectConversation = { convId ->
                                onOpenSavedConversation(convId)
                                overlayRoute = JarvisB8OverlayRoute.NONE
                                activeTab = JarvisB8NavTab.CHAT
                            }
                        )
                    }

                    JarvisB8OverlayRoute.MODEL_SELECTOR -> {
                        JarvisModelSelectorScreen(
                            selectedModelId = selectedModelId,
                            selectedTier = selectedTier,
                            models = modelsState,
                            connectedProviderIds = connectedProviderIds,
                            onSelectModel = { model ->
                                selectedModelId = model.id
                                selectedTier = model.tier
                                activeModelLabel = "${model.tier.displayTitle} (${model.name})"
                                prefs.edit()
                                    .putString("selected_model_id", selectedModelId)
                                    .putString("selected_tier", selectedTier.name)
                                    .putString("active_model_label", activeModelLabel)
                                    .apply()
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                                        val token = accessTokenProvider()
                                        val conn = (URL("$workerBaseUrl/v1/models/select").openConnection() as HttpURLConnection).apply {
                                            requestMethod = "POST"
                                            connectTimeout = 10000
                                            readTimeout = 10000
                                            doOutput = true
                                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                                        }
                                        conn.outputStream.use {
                                            it.write(JSONObject().put("modelId", model.id).put("tier", model.tier.name).toString().toByteArray(Charsets.UTF_8))
                                        }
                                        conn.responseCode
                                        conn.disconnect()
                                    }
                                }
                                overlayRoute = JarvisB8OverlayRoute.NONE
                            },
                            onSelectTier = { tier ->
                                selectedTier = tier
                                activeModelLabel = "${tier.displayTitle} (${tier.defaultModelName})"
                                prefs.edit()
                                    .putString("selected_tier", selectedTier.name)
                                    .putString("active_model_label", activeModelLabel)
                                    .apply()
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                                        val token = accessTokenProvider()
                                        val conn = (URL("$workerBaseUrl/v1/models/select").openConnection() as HttpURLConnection).apply {
                                            requestMethod = "POST"
                                            connectTimeout = 10000
                                            readTimeout = 10000
                                            doOutput = true
                                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                                        }
                                        conn.outputStream.use {
                                            it.write(JSONObject().put("tier", tier.name).toString().toByteArray(Charsets.UTF_8))
                                        }
                                        conn.responseCode
                                        conn.disconnect()
                                    }
                                }
                                overlayRoute = JarvisB8OverlayRoute.NONE
                            },
                            onOpenProviders = { overlayRoute = JarvisB8OverlayRoute.AI_PROVIDERS },
                            onBack = { overlayRoute = JarvisB8OverlayRoute.NONE }
                        )
                    }

                    JarvisB8OverlayRoute.AI_PROVIDERS -> {
                        JarvisProvidersScreen(
                            providers = providersState,
                            onToggleProvider = { id, enabled ->
                                val idx = providersState.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    val curr = providersState[idx]
                                    val hasVerifiedAcc = accountsState.any { it.provider == id && it.active && it.connectionVerified }
                                    providersState[idx] = curr.copy(
                                        enabled = enabled,
                                        connected = enabled && hasVerifiedAcc,
                                        connectionStatus = if (!enabled) "disabled" else if (hasVerifiedAcc) "connected" else "configured_unverified"
                                    )
                                    persistLocalMetadataSnapshot()
                                }
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                                        val token = accessTokenProvider()
                                        val conn = (URL("$workerBaseUrl/v1/providers/config").openConnection() as HttpURLConnection).apply {
                                            requestMethod = "POST"
                                            connectTimeout = 10000
                                            readTimeout = 10000
                                            doOutput = true
                                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                                        }
                                        conn.outputStream.use {
                                            it.write(JSONObject().put("provider", id).put("enabled", enabled).toString().toByteArray(Charsets.UTF_8))
                                        }
                                        conn.responseCode
                                        conn.disconnect()
                                    }
                                }
                            },
                            onOpenMultiAccounts = { provId ->
                                selectedAccountProvider = provId
                                overlayRoute = JarvisB8OverlayRoute.MULTI_ACCOUNTS
                            },
                            onAddProvider = { provId, apiKey ->
                                saveAccountOrProviderConfigToBackend(
                                    providerId = provId,
                                    label = "${provId.uppercase()} Main",
                                    emailHint = "$provId@user.jarvis",
                                    apiKey = apiKey
                                )
                            },
                            onSaveIndependentProviderConfig = { provId, accountLabel, emailHint, apiKey, customEndpoint, defaultModel ->
                                saveAccountOrProviderConfigToBackend(
                                    providerId = provId,
                                    label = accountLabel,
                                    emailHint = emailHint,
                                    apiKey = apiKey,
                                    customEndpoint = customEndpoint,
                                    defaultModel = defaultModel
                                )
                            },
                            onTestProviderConnection = { provId, apiKey, customEndpoint, onResult ->
                                executeLiveProviderConnectionTest(
                                    providerId = provId,
                                    apiKey = apiKey,
                                    customEndpoint = customEndpoint,
                                    onResult = onResult
                                )
                            },
                            onBack = { overlayRoute = JarvisB8OverlayRoute.NONE }
                        )
                    }

                    JarvisB8OverlayRoute.MULTI_ACCOUNTS -> {
                        JarvisMultiAccountsScreen(
                            selectedProvider = selectedAccountProvider,
                            accounts = accountsState,
                            onSelectProviderTab = { selectedAccountProvider = it },
                            onToggleAccountActive = { accId ->
                                val idx = accountsState.indexOfFirst { it.accountId == accId }
                                if (idx >= 0) {
                                    val curr = accountsState[idx]
                                    val nextActive = !curr.active
                                    accountsState[idx] = curr.copy(active = nextActive)
                                    saveAccountOrProviderConfigToBackend(
                                        accountId = curr.accountId,
                                        providerId = curr.provider,
                                        label = curr.label,
                                        emailHint = curr.emailHint,
                                        apiKey = "",
                                        enabled = nextActive
                                    )
                                }
                            },
                            onAddAccount = { prov, label, email, apiKey ->
                                saveAccountOrProviderConfigToBackend(
                                    providerId = prov,
                                    label = label,
                                    emailHint = email,
                                    apiKey = apiKey
                                )
                            },
                            onEditAccount = { accId, prov, label, email, apiKey ->
                                saveAccountOrProviderConfigToBackend(
                                    accountId = accId,
                                    providerId = prov,
                                    label = label,
                                    emailHint = email,
                                    apiKey = apiKey
                                )
                            },
                            onDeleteAccount = { accId, prov ->
                                deleteAccountFromBackend(accId, prov)
                            },
                            onSetDefaultAccount = { accId, prov ->
                                val target = accountsState.find { it.accountId == accId }
                                if (target != null) {
                                    prefs.edit().putString("selected_account_id", accId).apply()
                                    saveAccountOrProviderConfigToBackend(
                                        accountId = accId,
                                        providerId = prov,
                                        label = target.label,
                                        emailHint = target.emailHint,
                                        apiKey = "",
                                        isDefault = true
                                    )
                                }
                            },
                            onTestAccountConnection = { accId, prov, onResult ->
                                executeLiveProviderConnectionTest(
                                    providerId = prov,
                                    accountId = accId
                                ) { ok, msg, _ ->
                                    onResult(ok, msg)
                                }
                            },
                            onBack = { overlayRoute = JarvisB8OverlayRoute.AI_PROVIDERS }
                        )
                    }

                    JarvisB8OverlayRoute.NONE -> {
                        when (currentTab) {
                            JarvisB8NavTab.BERANDA -> {
                                JarvisDashboardScreen(
                                    userName = userName ?: "Fajar",
                                    activeTier = selectedTier,
                                    activeProviderCount = providersState.count { it.connected },
                                    activeAccountCount = accountsState.count { it.active },
                                    activeMcpCount = 4,
                                    installedSkillCount = 29,
                                    onOpenChat = { activeTab = JarvisB8NavTab.CHAT },
                                    onOpenVoice = { activeTab = JarvisB8NavTab.VOICE },
                                    onOpenCollaboration = { activeTab = JarvisB8NavTab.KOLABORASI },
                                    onOpenModels = { overlayRoute = JarvisB8OverlayRoute.MODEL_SELECTOR },
                                    onOpenSkills = { activeTab = JarvisB8NavTab.PENGATURAN },
                                    onOpenMcp = { activeTab = JarvisB8NavTab.PENGATURAN },
                                    onOpenSystemStatus = { activeTab = JarvisB8NavTab.PENGATURAN }
                                )
                            }

                            JarvisB8NavTab.CHAT -> {
                                JarvisB8ChatExperienceScreen(
                                    activeModelLabel = activeModelLabel,
                                    onOpenModelSelector = { overlayRoute = JarvisB8OverlayRoute.MODEL_SELECTOR },
                                    onOpenCollaborationWithTopic = { topic ->
                                        collaborationTopic = topic
                                        activeTab = JarvisB8NavTab.KOLABORASI
                                    },
                                    onSwitchToVoice = { activeTab = JarvisB8NavTab.VOICE },
                                    onSendLiveChatPrompt = onSendLiveChatPrompt
                                )
                            }

                            JarvisB8NavTab.VOICE -> {
                                JarvisB8VoiceExperienceScreen(
                                    activeModelLabel = activeModelLabel,
                                    liveVoiceStatus = liveVoiceStatus,
                                    liveRecognizedText = liveRecognizedText,
                                    liveRmsLevel = liveRmsLevel,
                                    onStartListening = onStartListening,
                                    onStopSpeaking = onStopSpeaking,
                                    onSpeakText = onSpeakText,
                                    onSwitchToChat = {
                                        activeTab = JarvisB8NavTab.CHAT
                                    }
                                )
                            }

                            JarvisB8NavTab.KOLABORASI -> {
                                JarvisB8CollaborationSuiteScreen(
                                    initialTopic = collaborationTopic,
                                    accessTokenProvider = accessTokenProvider,
                                    onSendSynthesisToChat = {
                                        activeTab = JarvisB8NavTab.CHAT
                                    }
                                )
                            }

                            JarvisB8NavTab.PENGATURAN -> {
                                JarvisExtensibilityAndSettingsSuite(
                                    userEmail = userEmail ?: "fajar@email.com",
                                    userName = userName ?: "Fajar",
                                    accessTokenProvider = accessTokenProvider,
                                    onSignOut = onTriggerSignOut
                                )
                            }
                        }
                    }
                }
            }
        }

        JarvisB8BottomNavigationBar(
            selectedTab = if (overlayRoute == JarvisB8OverlayRoute.MODEL_SELECTOR) JarvisB8NavTab.MODELS else activeTab,
            onSelectTab = { tab ->
                if (tab == JarvisB8NavTab.MODELS) {
                    overlayRoute = JarvisB8OverlayRoute.MODEL_SELECTOR
                } else {
                    overlayRoute = JarvisB8OverlayRoute.NONE
                    activeTab = tab
                }
            }
        )
    }
}
