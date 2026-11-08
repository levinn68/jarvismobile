package com.jarvis.mobile.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.mobile.ui.JarvisColors
import com.jarvis.mobile.ui.JarvisIntelligenceTier
import com.jarvis.mobile.ui.JarvisShapes

data class JarvisModelItem(
    val id: String,
    val name: String,
    val providerLabel: String,
    val summary: String,
    val categories: List<String>,
    val tier: JarvisIntelligenceTier,
    val provider: String = providerLabel.lowercase(),
    val available: Boolean = true,
    val discoveredLive: Boolean = false
)

data class JarvisProviderItem(
    val id: String,
    val name: String,
    val connected: Boolean,
    val accountCount: Int,
    val enabled: Boolean,
    val integrationState: String = if (connected) "working_live" else "ui_configured_only",
    val connectionStatus: String = if (connected) "connected" else "not_connected",
    val endpoint: String = "",
    val modelsEndpoint: String = "",
    val authType: String = "Bearer API Key",
    val keyFormatHint: String = "sk-...",
    val docsUrl: String = "",
    val defaultModel: String = "",
    val supportedModels: List<String> = emptyList(),
    val maskedApiKey: String = "",
    val lastTestedAt: String = "",
    val errorCode: String = "",
    val errorMessage: String = ""
)

data class JarvisAccountItem(
    val accountId: String,
    val provider: String,
    val label: String,
    val emailHint: String,
    val active: Boolean,
    val quotaUsedTokens: Int = 0,
    val quotaLimitTokens: Int = 200000,
    val isDefault: Boolean = false,
    val maskedKey: String = "sk***",
    val status: String = "configured_unverified",
    val connectionVerified: Boolean = false,
    val lastTestedAt: String = "",
    val errorMessage: String = ""
)

val IndependentProviderCatalog = listOf(
    JarvisProviderItem(
        id = "openai",
        name = "OpenAI",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://api.openai.com/v1/chat/completions",
        modelsEndpoint = "https://api.openai.com/v1/models",
        authType = "Bearer API Key",
        keyFormatHint = "sk-proj-... / sk-...",
        docsUrl = "https://platform.openai.com/docs/api-reference",
        defaultModel = "gpt-4.1-mini",
        supportedModels = listOf("gpt-4o", "gpt-4.1-mini", "o3-mini")
    ),
    JarvisProviderItem(
        id = "gemini",
        name = "Google Gemini",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        authType = "Google AI Studio Key",
        keyFormatHint = "AIzaSy...",
        docsUrl = "https://ai.google.dev/gemini-api/docs",
        defaultModel = "gemini-2.5-flash",
        supportedModels = listOf("gemini-1.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
    ),
    JarvisProviderItem(
        id = "anthropic",
        name = "Anthropic Claude",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://api.anthropic.com/v1/messages",
        modelsEndpoint = "https://api.anthropic.com/v1/models",
        authType = "x-api-key Header",
        keyFormatHint = "sk-ant-api03-...",
        docsUrl = "https://docs.anthropic.com/en/api/getting-started",
        defaultModel = "claude-3-5-sonnet-latest",
        supportedModels = listOf("claude-3-5-sonnet", "claude-3-5-haiku")
    ),
    JarvisProviderItem(
        id = "deepseek",
        name = "DeepSeek",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://api.deepseek.com/chat/completions",
        modelsEndpoint = "https://api.deepseek.com/models",
        authType = "Bearer API Key",
        keyFormatHint = "sk-...",
        docsUrl = "https://api-docs.deepseek.com/",
        defaultModel = "deepseek-chat",
        supportedModels = listOf("deepseek-v3", "deepseek-chat", "deepseek-reasoner")
    ),
    JarvisProviderItem(
        id = "groq",
        name = "Groq",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        modelsEndpoint = "https://api.groq.com/openai/v1/models",
        authType = "Bearer API Key",
        keyFormatHint = "gsk_...",
        docsUrl = "https://console.groq.com/docs/overview",
        defaultModel = "openai/gpt-oss-120b",
        supportedModels = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile")
    ),
    JarvisProviderItem(
        id = "openrouter",
        name = "OpenRouter",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        modelsEndpoint = "https://openrouter.ai/api/v1/models",
        authType = "Bearer API Key",
        keyFormatHint = "sk-or-v1-...",
        docsUrl = "https://openrouter.ai/docs/quick-start",
        defaultModel = "openai/gpt-4.1-mini",
        supportedModels = listOf("qwen-2.5-72b", "openai/gpt-4.1-mini")
    ),
    JarvisProviderItem(
        id = "custom",
        name = "Custom OpenAI-Compatible",
        connected = false,
        accountCount = 0,
        enabled = true,
        integrationState = "ui_configured_only",
        connectionStatus = "not_connected",
        endpoint = "https://api.openai.com/v1/chat/completions",
        modelsEndpoint = "https://api.openai.com/v1/models",
        authType = "Bearer API Key",
        keyFormatHint = "Bearer Key sesuai endpoint",
        docsUrl = "https://platform.openai.com/docs/api-reference/chat",
        defaultModel = "gpt-4o-mini",
        supportedModels = listOf("gpt-4o-mini")
    )
)

val DefaultB8Models = listOf(
    JarvisModelItem("gpt-4o", "GPT-4o", "OpenAI", "Multimodal", listOf("Semua", "Teks", "Vision", "Kode"), JarvisIntelligenceTier.APEX, provider = "openai"),
    JarvisModelItem("gpt-4.1-mini", "GPT-4.1 Mini", "OpenAI", "Cepat & Efisien", listOf("Semua", "Teks", "Vision", "Kode"), JarvisIntelligenceTier.PULSE, provider = "openai"),
    JarvisModelItem("o3-mini", "OpenAI o3-mini", "OpenAI", "Penalaran STEM & Kode", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.SPARK, provider = "openai"),
    JarvisModelItem("claude-3-5-sonnet", "Claude 3.5 Sonnet", "Anthropic", "Analisis & Riset", listOf("Semua", "Teks", "Vision", "Kode"), JarvisIntelligenceTier.SPARK, provider = "anthropic"),
    JarvisModelItem("claude-3-5-haiku", "Claude 3.5 Haiku", "Anthropic", "Respons Cepat", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.CORE, provider = "anthropic"),
    JarvisModelItem("gemini-1.5-pro", "Gemini 1.5 Pro", "Google", "Multimodal & Panjang", listOf("Semua", "Teks", "Vision", "Kode"), JarvisIntelligenceTier.PULSE, provider = "gemini"),
    JarvisModelItem("gemini-2.5-flash", "Gemini 2.5 Flash", "Google", "Multimodal Cepat", listOf("Semua", "Teks", "Vision", "Kode"), JarvisIntelligenceTier.CORE, provider = "gemini"),
    JarvisModelItem("llama-3.3-70b", "Llama 3.3 70B", "Groq / Meta", "Efisien & Open", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.CORE, provider = "groq"),
    JarvisModelItem("deepseek-v3", "DeepSeek-V3", "DeepSeek", "Penalaran & Kode", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.SPARK, provider = "deepseek"),
    JarvisModelItem("deepseek-reasoner", "DeepSeek R1", "DeepSeek", "Chain-of-Thought Reasoning", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.APEX, provider = "deepseek"),
    JarvisModelItem("qwen-2.5-72b", "Qwen 2.5", "OpenRouter", "Multibahasa & General", listOf("Semua", "Teks", "Kode"), JarvisIntelligenceTier.APEX, provider = "openrouter")
)

/**
 * Validates on the client side that an API key is not cross-used on an incompatible provider
 * before sending it to the backend.
 */
fun validateProviderKeyClientSide(providerId: String, rawKey: String): String? {
    val clean = rawKey.trim()
    if (clean.isEmpty()) return "API Key wajib diisi untuk provider $providerId."
    if (clean.length < 8) return "API Key terlalu pendek."
    val p = providerId.lowercase()
    if (p == "gemini" && (clean.startsWith("sk-") || clean.startsWith("gsk_"))) {
        return "API Key OpenAI/Claude/Groq ('sk-*' / 'gsk_*') tidak dapat digunakan untuk Google Gemini. Gunakan Google AI Studio Key ('AIza...')."
    }
    if (p == "anthropic" && (clean.startsWith("AIza") || clean.startsWith("gsk_") || clean.startsWith("sk-proj-") || clean.startsWith("sk-or-"))) {
        return "API Key ini milik provider lain dan tidak dapat digunakan untuk Anthropic Claude ('sk-ant-...')."
    }
    if (p == "openai" && (clean.startsWith("AIza") || clean.startsWith("sk-ant-") || clean.startsWith("gsk_") || clean.startsWith("sk-or-"))) {
        return "API Key ini milik provider lain dan tidak dapat digunakan untuk OpenAI."
    }
    if (p == "groq" && (clean.startsWith("AIza") || clean.startsWith("sk-ant-") || clean.startsWith("sk-proj-") || clean.startsWith("sk-or-"))) {
        return "API Key ini milik provider lain dan tidak dapat digunakan untuk Groq ('gsk_...')."
    }
    return null
}

/**
 * Storyboard Screen 31 (4.1): Model Selector (`Models`).
 * Models are strictly bound to their owning provider and reflect real provider connection availability.
 */
@Composable
fun JarvisModelSelectorScreen(
    selectedModelId: String,
    selectedTier: JarvisIntelligenceTier,
    onSelectModel: (JarvisModelItem) -> Unit,
    onSelectTier: (JarvisIntelligenceTier) -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
    models: List<JarvisModelItem> = DefaultB8Models,
    connectedProviderIds: Set<String> = emptySet()
) {
    var activeFilter by remember { mutableStateOf("Semua") }
    var activeProviderFilter by remember { mutableStateOf("semua") }
    val filters = listOf("Semua", "Teks", "Vision", "Kode")
    val providerFilterTabs = listOf(
        "semua" to "Semua Provider",
        "openai" to "OpenAI",
        "gemini" to "Gemini",
        "anthropic" to "Claude",
        "deepseek" to "DeepSeek",
        "groq" to "Groq",
        "openrouter" to "OpenRouter"
    )

    val filteredModels = remember(activeFilter, activeProviderFilter, models, connectedProviderIds) {
        models.filter { item ->
            val matchesCategory = activeFilter == "Semua" || activeFilter in item.categories
            val matchesProvider = activeProviderFilter == "semua" ||
                item.provider.equals(activeProviderFilter, ignoreCase = true) ||
                (activeProviderFilter == "groq" && item.provider.equals("meta", ignoreCase = true))
            matchesCategory && matchesProvider
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = JarvisColors.Ice)
                }
                Text("Models", color = JarvisColors.Ice, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onOpenProviders) {
                Text("Kelola AI Providers", color = JarvisColors.Cyan, fontSize = 12.sp)
            }
        }

        Text(
            "Setiap model terikat secara independen pada provider masing-masing. Model hanya aktif bila provider terkait telah terhubung.",
            color = JarvisColors.TextMuted,
            fontSize = 12.sp
        )

        // Intelligence Tier Selector (CORE · PULSE · SPARK · APEX)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JarvisIntelligenceTier.values().forEach { tier ->
                val isSelected = selectedTier == tier
                Box(
                    modifier = Modifier
                        .clip(JarvisShapes.Chip)
                        .background(if (isSelected) Color(0xFF0E334E) else JarvisColors.PanelSolid)
                        .border(1.dp, if (isSelected) JarvisColors.Cyan else JarvisColors.GlassBorder, JarvisShapes.Chip)
                        .clickable { onSelectTier(tier) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "${tier.code} · ${tier.defaultModelName}",
                        color = if (isSelected) JarvisColors.Cyan else JarvisColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Provider-Scoped Filter Tabs (OpenAI only shows OpenAI models, Gemini only shows Gemini models, etc.)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            providerFilterTabs.forEach { (provId, provLabel) ->
                val active = activeProviderFilter == provId
                Box(
                    modifier = Modifier
                        .clip(JarvisShapes.Chip)
                        .background(if (active) JarvisColors.Cyan.copy(alpha = 0.22f) else JarvisColors.PanelSolid)
                        .border(1.dp, if (active) JarvisColors.Cyan else JarvisColors.GlassBorder, JarvisShapes.Chip)
                        .clickable { activeProviderFilter = provId }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = provLabel,
                        color = if (active) JarvisColors.Ice else JarvisColors.TextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Category Filter Chips (Semua | Teks | Vision | Kode)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                val active = activeFilter == filter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(JarvisShapes.Chip)
                        .background(if (active) Color(0xFF0E2B44) else JarvisColors.PanelSolid)
                        .border(1.dp, if (active) JarvisColors.Cyan else JarvisColors.GlassBorder, JarvisShapes.Chip)
                        .clickable { activeFilter = filter }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(filter, color = if (active) JarvisColors.Ice else JarvisColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Model Cards
        filteredModels.forEach { item ->
            val isProviderConnected = connectedProviderIds.isEmpty() ||
                connectedProviderIds.contains(item.provider.lowercase()) ||
                (item.provider.equals("meta", ignoreCase = true) && connectedProviderIds.contains("groq"))
            val isAvailable = item.available && isProviderConnected
            val selected = item.id == selectedModelId && isAvailable

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(if (selected) Color(0xFF0B2238) else JarvisColors.PanelSolid)
                    .border(
                        1.dp,
                        when {
                            selected -> JarvisColors.Cyan
                            !isAvailable -> JarvisColors.GlassBorder
                            else -> JarvisColors.CyanBorder
                        },
                        JarvisShapes.Card
                    )
                    .clickable {
                        if (isAvailable) {
                            onSelectModel(item)
                            onSelectTier(item.tier)
                        } else {
                            onOpenProviders()
                        }
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0E2942))
                            .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(item.name, color = JarvisColors.Ice, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${item.providerLabel} (${item.provider.uppercase()}) · ${item.summary}",
                            color = JarvisColors.TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (isAvailable) "Tersedia (${item.provider.uppercase()})" else "Provider ${item.provider.uppercase()} Belum Terhubung — Ketuk untuk Konfigurasi",
                            color = if (isAvailable) JarvisColors.Ok else JarvisColors.Warn,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (selected) JarvisColors.Cyan else JarvisColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Storyboard Screen 32 (4.2): AI Providers — Independent Provider Management & 11-Step Add Flow.
 */
@Composable
fun JarvisProvidersScreen(
    providers: List<JarvisProviderItem>,
    onToggleProvider: (String, Boolean) -> Unit,
    onOpenMultiAccounts: (String) -> Unit,
    onAddProvider: (String, String) -> Unit,
    onBack: () -> Unit,
    onSaveIndependentProviderConfig: ((providerId: String, accountLabel: String, emailHint: String, apiKey: String, customEndpoint: String, defaultModel: String) -> Unit)? = null,
    onTestProviderConnection: ((providerId: String, apiKey: String, customEndpoint: String, onResult: (Boolean, String, List<String>) -> Unit) -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current

    // Step 1 & 2: Provider Catalog Modal -> Select one provider -> Open dedicated provider config page
    var showCatalogModal by remember { mutableStateOf(false) }
    var activeConfigProviderId by remember { mutableStateOf<String?>(null) }

    // Per-provider independent configuration drafts
    var accountLabelDraft by remember { mutableStateOf("") }
    var emailDraft by remember { mutableStateOf("") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var endpointDraft by remember { mutableStateOf("") }
    var defaultModelDraft by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf("") }
    var isStatusError by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    val discoveredModelsState = remember { mutableStateListOf<String>() }

    val configuredProvider = activeConfigProviderId?.let { targetId ->
        providers.find { it.id.equals(targetId, ignoreCase = true) }
            ?: IndependentProviderCatalog.find { it.id.equals(targetId, ignoreCase = true) }
    }

    fun openProviderConfigPage(p: JarvisProviderItem) {
        activeConfigProviderId = p.id
        accountLabelDraft = "${p.name} Personal"
        emailDraft = ""
        apiKeyDraft = ""
        endpointDraft = p.endpoint.ifBlank {
            IndependentProviderCatalog.find { it.id == p.id }?.endpoint.orEmpty()
        }
        defaultModelDraft = p.defaultModel.ifBlank {
            IndependentProviderCatalog.find { it.id == p.id }?.defaultModel.orEmpty()
        }
        discoveredModelsState.clear()
        discoveredModelsState.addAll(p.supportedModels)
        statusBanner = when {
            p.connected -> "Status: TERHUBUNG (Live Connection Terverifikasi)"
            p.connectionStatus == "configured_unverified" -> "Status: TERSIMPAN (Belum Diuji — Jalankan Test Connection)"
            p.connectionStatus == "connection_failed" -> "Status: GAGAL KONEKSI (${p.errorCode.ifBlank { "ERROR" }}: ${p.errorMessage})"
            else -> "Status: BELUM TERHUBUNG — Masukkan API Key khusus ${p.name}"
        }
        isStatusError = p.connectionStatus == "connection_failed"
    }

    if (configuredProvider != null) {
        // Dedicated Per-Provider Configuration Page (Steps 3–11 of Section 2)
        val catSpec = IndependentProviderCatalog.find { it.id == configuredProvider.id } ?: configuredProvider
        val effectiveDocsUrl = configuredProvider.docsUrl.ifBlank { catSpec.docsUrl }
        val effectiveKeyHint = configuredProvider.keyFormatHint.ifBlank { catSpec.keyFormatHint }
        val effectiveAuthType = configuredProvider.authType.ifBlank { catSpec.authType }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { activeConfigProviderId = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Kembali ke Daftar Provider", tint = JarvisColors.Ice)
                }
                Text(
                    text = "Konfigurasi ${configuredProvider.name}",
                    color = JarvisColors.Ice,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Provider Info & Official API Documentation Card (Steps 4 & 5)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.CyanBorder, JarvisShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${configuredProvider.name} (ID: ${configuredProvider.id})",
                        color = JarvisColors.Cyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (configuredProvider.connected) "CONNECTED (VERIFIED)" else configuredProvider.connectionStatus.uppercase(),
                        color = when {
                            configuredProvider.connected -> JarvisColors.Ok
                            configuredProvider.connectionStatus == "configured_unverified" -> JarvisColors.Warn
                            configuredProvider.connectionStatus == "connection_failed" -> JarvisColors.CoralDanger
                            else -> JarvisColors.TextMuted
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text("Autentikasi: $effectiveAuthType", color = JarvisColors.Ice, fontSize = 12.sp)
                Text("Format API Key: $effectiveKeyHint", color = JarvisColors.TextMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Text("Endpoint API: ${endpointDraft.ifBlank { catSpec.endpoint }}", color = JarvisColors.TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (configuredProvider.maskedApiKey.isNotBlank()) {
                    Text("Kredensial Tersimpan (AES-256-GCM): ${configuredProvider.maskedApiKey}", color = JarvisColors.Ok, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
                if (effectiveDocsUrl.isNotBlank()) {
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(effectiveDocsUrl) } },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = JarvisColors.Cyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buka Dokumentasi API Resmi (${configuredProvider.name})", color = JarvisColors.Cyan, fontSize = 12.sp)
                    }
                }
            }

            // Independent Credential & Account Input Form (Steps 6, 7, 8)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Kredensial & Akun Independen ${configuredProvider.name}", color = JarvisColors.Ice, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Kunci API dienkripsi dengan AES-256-GCM di backend dan tidak pernah dibagi dengan provider lain.",
                    color = JarvisColors.TextMuted,
                    fontSize = 11.5.sp
                )
                OutlinedTextField(
                    value = accountLabelDraft,
                    onValueChange = { accountLabelDraft = it },
                    singleLine = true,
                    label = { Text("Nama Akun (mis. ${configuredProvider.name} Personal / Work)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emailDraft,
                    onValueChange = { emailDraft = it },
                    singleLine = true,
                    label = { Text("Email / Identitas Akun (Opsional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("API Key Khusus ${configuredProvider.name} ($effectiveKeyHint)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endpointDraft,
                    onValueChange = { endpointDraft = it },
                    singleLine = true,
                    label = { Text("API Endpoint (${configuredProvider.name})") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = defaultModelDraft,
                    onValueChange = { defaultModelDraft = it },
                    singleLine = true,
                    label = { Text("Model Default ${configuredProvider.name}") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (statusBanner.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isStatusError) JarvisColors.CoralDanger.copy(alpha = 0.16f) else Color(0xFF0B2238))
                            .border(1.dp, if (isStatusError) JarvisColors.CoralDanger else JarvisColors.Cyan, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = statusBanner,
                            color = if (isStatusError) JarvisColors.CoralDanger else JarvisColors.Ice,
                            fontSize = 12.sp
                        )
                    }
                }

                // Step 8: Save Configuration Separately (Sets status to CONFIGURED_UNVERIFIED, NOT immediately Connected!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = accountLabelDraft.isNotBlank() && (apiKeyDraft.isNotBlank() || configuredProvider.accountCount > 0),
                        onClick = {
                            if (apiKeyDraft.isNotBlank()) {
                                val err = validateProviderKeyClientSide(configuredProvider.id, apiKeyDraft)
                                if (err != null) {
                                    isStatusError = true
                                    statusBanner = err
                                    return@Button
                                }
                            }
                            if (onSaveIndependentProviderConfig != null) {
                                onSaveIndependentProviderConfig(
                                    configuredProvider.id,
                                    accountLabelDraft.trim(),
                                    emailDraft.trim().ifBlank { "${configuredProvider.id}@user.jarvis" },
                                    apiKeyDraft.trim(),
                                    endpointDraft.trim(),
                                    defaultModelDraft.trim()
                                )
                            } else if (apiKeyDraft.isNotBlank()) {
                                onAddProvider(configuredProvider.id, apiKeyDraft.trim())
                            }
                            apiKeyDraft = ""
                            isStatusError = false
                            statusBanner = "Konfigurasi ${configuredProvider.name} disimpan (Status: CONFIGURED_UNVERIFIED). Jalankan Test Connection untuk memverifikasi koneksi live."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Cyan),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1. Simpan Konfigurasi", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    // Steps 9, 10, 11: Run Live Test Connection & Model Discovery
                    OutlinedButton(
                        enabled = !isTestingConnection,
                        onClick = {
                            if (apiKeyDraft.isNotBlank()) {
                                val err = validateProviderKeyClientSide(configuredProvider.id, apiKeyDraft)
                                if (err != null) {
                                    isStatusError = true
                                    statusBanner = err
                                    return@OutlinedButton
                                }
                            }
                            if (onTestProviderConnection != null) {
                                isTestingConnection = true
                                statusBanner = "Menguji koneksi live ke ${configuredProvider.name} & mengambil daftar model..."
                                isStatusError = false
                                onTestProviderConnection(
                                    configuredProvider.id,
                                    apiKeyDraft.trim(),
                                    endpointDraft.trim()
                                ) { ok, msg, models ->
                                    isTestingConnection = false
                                    isStatusError = !ok
                                    statusBanner = msg
                                    if (models.isNotEmpty()) {
                                        discoveredModelsState.clear()
                                        discoveredModelsState.addAll(models)
                                    }
                                    apiKeyDraft = ""
                                }
                            } else {
                                isStatusError = true
                                statusBanner = "Handler Test Connection belum terhubung ke Worker."
                            }
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.Cyan),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isTestingConnection) "Menguji..." else "2. Test Connection",
                            color = JarvisColors.Ice,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Discovered & Supported Models for this Provider (Step 10)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Katalog Model Khusus ${configuredProvider.name} (${discoveredModelsState.size} Model)",
                    color = JarvisColors.Ice,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
                if (discoveredModelsState.isEmpty()) {
                    Text(
                        text = "Jalankan Test Connection untuk menemukan model yang tersedia dari API ${configuredProvider.name}.",
                        color = JarvisColors.TextMuted,
                        fontSize = 12.sp
                    )
                } else {
                    discoveredModelsState.forEach { modelId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF081624))
                                .clickable { defaultModelDraft = modelId }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(modelId, color = JarvisColors.Ice, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                text = if (defaultModelDraft == modelId) "DEFAULT" else "Jadikan Default",
                                color = if (defaultModelDraft == modelId) JarvisColors.Ok else JarvisColors.Cyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onOpenMultiAccounts(configuredProvider.id) },
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.Cyan)
            ) {
                Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = JarvisColors.Cyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Kelola Multi-Akun ${configuredProvider.name} (${configuredProvider.accountCount} Akun)",
                    color = JarvisColors.Ice,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }

    // Main Independent Provider List View
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = JarvisColors.Ice)
            }
            Text("AI Providers", color = JarvisColors.Ice, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Setiap provider memiliki konfigurasi endpoint, API key terenkripsi, multi-akun, dan katalog model yang terpisah secara independen.",
            color = JarvisColors.TextMuted,
            fontSize = 12.sp
        )

        providers.forEach { p ->
            val statusColor = when {
                !p.enabled -> JarvisColors.TextDim
                p.connected -> JarvisColors.Ok
                p.connectionStatus == "configured_unverified" -> JarvisColors.Warn
                p.connectionStatus == "connection_failed" -> JarvisColors.CoralDanger
                else -> JarvisColors.TextDim
            }
            val statusLabel = when {
                !p.enabled -> "Dinonaktifkan · ${p.accountCount} akun"
                p.connected -> "Terhubung (Live Terverifikasi) · ${p.accountCount} akun"
                p.connectionStatus == "configured_unverified" -> "Terkonfigurasi (Belum Diuji) · ${p.accountCount} akun"
                p.connectionStatus == "connection_failed" -> "Gagal Koneksi (${p.errorCode.ifBlank { " Periksa API Key" }})"
                else -> "Belum terhubung · 0 akun pengguna"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, if (p.connected) JarvisColors.CyanBorder else JarvisColors.GlassBorder, JarvisShapes.Card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { openProviderConfigPage(p) }
                    ) {
                        Text(p.name, color = JarvisColors.Ice, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusLabel,
                                color = statusColor,
                                fontSize = 11.5.sp
                            )
                        }
                        if (p.defaultModel.isNotBlank()) {
                            Text(
                                text = "Default Model: ${p.defaultModel}",
                                color = JarvisColors.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Switch(
                        checked = p.enabled,
                        onCheckedChange = { onToggleProvider(p.id, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = JarvisColors.Ice, checkedTrackColor = JarvisColors.Cyan)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { openProviderConfigPage(p) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.Cyan.copy(alpha = 0.6f))
                    ) {
                        Text("Konfigurasi & Uji", color = JarvisColors.Cyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { onOpenMultiAccounts(p.id) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.GlassBorder)
                    ) {
                        Text("Akun (${p.accountCount})", color = JarvisColors.Ice, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Step 1: Button to open Provider Catalog
        OutlinedButton(
            onClick = { showCatalogModal = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = JarvisShapes.Chip,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.Cyan)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = JarvisColors.Cyan)
            Spacer(Modifier.width(8.dp))
            Text("Tambah Provider", color = JarvisColors.Ice, fontWeight = FontWeight.Bold)
        }
    }

    // Step 1 & 2: Provider Catalog Selection Modal
    if (showCatalogModal) {
        AlertDialog(
            onDismissRequest = { showCatalogModal = false },
            containerColor = Color(0xFF0B1826),
            title = {
                Text("Pilih Provider dari Katalog", color = JarvisColors.Ice, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Pilih satu provider untuk membuka halaman konfigurasi independen, memasukkan API key khusus provider tersebut, dan menjalankan Test Connection:",
                        color = JarvisColors.TextMuted,
                        fontSize = 12.sp
                    )
                    IndependentProviderCatalog.forEach { catItem ->
                        val existing = providers.find { it.id == catItem.id } ?: catItem
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0E2338))
                                .border(1.dp, JarvisColors.CyanBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    showCatalogModal = false
                                    openProviderConfigPage(existing)
                                }
                                .padding(12.dp)
                        ) {
                            Text(catItem.name, color = JarvisColors.Ice, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = "${catItem.authType} · Format: ${catItem.keyFormatHint}",
                                color = JarvisColors.Cyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = catItem.endpoint,
                                color = JarvisColors.TextMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCatalogModal = false }) {
                    Text("Tutup", color = JarvisColors.TextMuted)
                }
            }
        )
    }
}

/**
 * Storyboard Screen 33 (4.3): Multi-Accounts — Independent Per-Provider Multi-Account Management.
 * Supports Add, Edit, Delete, Enable/Disable, Set Primary/Default, Test Connection per Account, and Quota View.
 */
@Composable
fun JarvisMultiAccountsScreen(
    selectedProvider: String,
    accounts: List<JarvisAccountItem>,
    onSelectProviderTab: (String) -> Unit,
    onToggleAccountActive: (String) -> Unit,
    onAddAccount: (String, String, String, String) -> Unit,
    onBack: () -> Unit,
    onEditAccount: ((accountId: String, provider: String, label: String, email: String, apiKey: String) -> Unit)? = null,
    onDeleteAccount: ((accountId: String, provider: String) -> Unit)? = null,
    onSetDefaultAccount: ((accountId: String, provider: String) -> Unit)? = null,
    onTestAccountConnection: ((accountId: String, provider: String, onResult: (Boolean, String) -> Unit) -> Unit)? = null
) {
    var showAddOrEditModal by remember { mutableStateOf(false) }
    var editingAccountId by remember { mutableStateOf<String?>(null) }
    var labelDraft by remember { mutableStateOf("") }
    var emailDraft by remember { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") }
    var modalError by remember { mutableStateOf("") }
    var accountFeedbackBanner by remember { mutableStateOf("") }

    val providerTabs = listOf(
        "openai" to "OpenAI",
        "gemini" to "Gemini",
        "anthropic" to "Claude",
        "deepseek" to "DeepSeek",
        "groq" to "Groq",
        "openrouter" to "OpenRouter",
        "custom" to "Custom"
    )
    // Strictly isolate accounts for the selected provider only
    val visibleAccounts = accounts.filter { it.provider.equals(selectedProvider, ignoreCase = true) }
    val primaryQuota = visibleAccounts.find { it.isDefault } ?: visibleAccounts.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = JarvisColors.Ice)
            }
            Text("Multi-Accounts (${selectedProvider.uppercase()})", color = JarvisColors.Ice, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Kelola banyak akun secara terpisah untuk masing-masing provider (mis. Personal, Work, Research). Akun antar-provider dan Worker Secret diisolasi penuh.",
            color = JarvisColors.TextMuted,
            fontSize = 12.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            providerTabs.forEach { (id, label) ->
                val active = selectedProvider.equals(id, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(JarvisShapes.Chip)
                        .background(if (active) Color(0xFF0E2B44) else JarvisColors.PanelSolid)
                        .border(1.dp, if (active) JarvisColors.Cyan else JarvisColors.GlassBorder, JarvisShapes.Chip)
                        .clickable {
                            accountFeedbackBanner = ""
                            onSelectProviderTab(id)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (active) JarvisColors.Ice else JarvisColors.TextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (accountFeedbackBanner.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0B2238))
                    .border(1.dp, JarvisColors.Cyan, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(accountFeedbackBanner, color = JarvisColors.Ice, fontSize = 12.sp)
            }
        }

        if (visibleAccounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.Card)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Belum ada akun pribadi untuk provider ${selectedProvider.uppercase()}. Ketuk tombol Tambah Akun di bawah untuk menambahkan.",
                    color = JarvisColors.TextMuted,
                    fontSize = 12.sp
                )
            }
        }

        visibleAccounts.forEach { acc ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(
                        1.dp,
                        if (acc.isDefault) JarvisColors.Cyan else if (acc.active) JarvisColors.CyanBorder else JarvisColors.GlassBorder,
                        JarvisShapes.Card
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = JarvisColors.Cyan, modifier = Modifier.size(28.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(acc.label, color = JarvisColors.Ice, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (acc.isDefault) {
                                    Text(
                                        text = "UTAMA",
                                        color = JarvisColors.Cyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text("${acc.emailHint} · Key: ${acc.maskedKey}", color = JarvisColors.TextMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                text = when {
                                    acc.connectionVerified || acc.status == "working_live" -> "Status Koneksi: TERHUBUNG (Live Verified)"
                                    acc.status == "connection_failed" -> "Status Koneksi: GAGAL (${acc.errorMessage.ifBlank { "Periksa API Key" }})"
                                    else -> "Status Koneksi: BELUM DIUJI (Configured Unverified)"
                                },
                                color = when {
                                    acc.connectionVerified || acc.status == "working_live" -> JarvisColors.Ok
                                    acc.status == "connection_failed" -> JarvisColors.CoralDanger
                                    else -> JarvisColors.Warn
                                },
                                fontSize = 11.sp
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { onToggleAccountActive(acc.accountId) }
                    ) {
                        Text(
                            if (acc.active) "Aktif" else "Nonaktif",
                            color = if (acc.active) JarvisColors.Ok else JarvisColors.TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (acc.active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (acc.active) JarvisColors.Ok else JarvisColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Per-account action bar: Test Connection, Set Default, Edit, Delete
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onTestAccountConnection != null) {
                        TextButton(
                            onClick = {
                                accountFeedbackBanner = "Menguji koneksi akun '${acc.label}' (${acc.provider.uppercase()})..."
                                onTestAccountConnection(acc.accountId, acc.provider) { _, msg ->
                                    accountFeedbackBanner = msg
                                }
                            }
                        ) {
                            Text("Test Koneksi", color = JarvisColors.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!acc.isDefault && onSetDefaultAccount != null) {
                        TextButton(onClick = { onSetDefaultAccount(acc.accountId, acc.provider) }) {
                            Text("Jadikan Utama", color = JarvisColors.Ice, fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        onClick = {
                            editingAccountId = acc.accountId
                            labelDraft = acc.label
                            emailDraft = acc.emailHint
                            keyDraft = ""
                            modalError = ""
                            showAddOrEditModal = true
                        }
                    ) {
                        Text("Edit", color = JarvisColors.Ice, fontSize = 11.sp)
                    }
                    if (onDeleteAccount != null) {
                        TextButton(onClick = { onDeleteAccount(acc.accountId, acc.provider) }) {
                            Text("Hapus", color = JarvisColors.CoralDanger, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                editingAccountId = null
                labelDraft = "${selectedProvider.replaceFirstChar { it.uppercase() }} Personal"
                emailDraft = ""
                keyDraft = ""
                modalError = ""
                showAddOrEditModal = true
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = JarvisShapes.Chip,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.Cyan)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = JarvisColors.Cyan)
            Spacer(Modifier.width(6.dp))
            Text("Tambah Akun ${selectedProvider.replaceFirstChar { it.uppercase() }}", color = JarvisColors.Ice, fontWeight = FontWeight.SemiBold)
        }

        if (primaryQuota != null) {
            val pct = (primaryQuota.quotaUsedTokens.toFloat() / primaryQuota.quotaLimitTokens.coerceAtLeast(1)).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.Card)
                    .background(JarvisColors.PanelSolid)
                    .border(1.dp, JarvisColors.GlassBorder, JarvisShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Kuota & Penggunaan (${primaryQuota.label} · ${selectedProvider.uppercase()})",
                    color = JarvisColors.Ice,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Terpakai", color = JarvisColors.TextMuted, fontSize = 11.5.sp)
                    Text("${(pct * 100).toInt()}%", color = JarvisColors.Cyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(JarvisShapes.Chip),
                    color = JarvisColors.Cyan,
                    trackColor = Color(0xFF06101B)
                )
                Text(
                    "${primaryQuota.quotaUsedTokens} / ${primaryQuota.quotaLimitTokens} token",
                    color = JarvisColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    if (showAddOrEditModal) {
        val isEditing = editingAccountId != null
        AlertDialog(
            onDismissRequest = { showAddOrEditModal = false },
            containerColor = Color(0xFF0B1826),
            title = {
                Text(
                    text = if (isEditing) "Edit Akun ${selectedProvider.uppercase()}" else "Tambah Akun ${selectedProvider.uppercase()}",
                    color = JarvisColors.Ice,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = labelDraft,
                        onValueChange = { labelDraft = it },
                        singleLine = true,
                        label = { Text("Nama Akun (mis. ${selectedProvider.uppercase()} Work)") }
                    )
                    OutlinedTextField(
                        value = emailDraft,
                        onValueChange = { emailDraft = it },
                        singleLine = true,
                        label = { Text("Email / Identitas") }
                    )
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (isEditing) "API Key Baru (Kosongkan jika tetap)" else "API Key Khusus ${selectedProvider.uppercase()}") }
                    )
                    if (modalError.isNotBlank()) {
                        Text(modalError, color = JarvisColors.CoralDanger, fontSize = 11.5.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = labelDraft.isNotBlank() && (isEditing || keyDraft.isNotBlank()),
                    onClick = {
                        if (keyDraft.isNotBlank()) {
                            val err = validateProviderKeyClientSide(selectedProvider, keyDraft)
                            if (err != null) {
                                modalError = err
                                return@TextButton
                            }
                        }
                        if (isEditing && onEditAccount != null) {
                            onEditAccount(
                                editingAccountId!!,
                                selectedProvider,
                                labelDraft.trim(),
                                emailDraft.trim().ifBlank { "${selectedProvider}@user.jarvis" },
                                keyDraft.trim()
                            )
                        } else {
                            onAddAccount(
                                selectedProvider,
                                labelDraft.trim(),
                                emailDraft.trim().ifBlank { "${selectedProvider}@user.jarvis" },
                                keyDraft.trim()
                            )
                        }
                        keyDraft = ""
                        modalError = ""
                        showAddOrEditModal = false
                    }
                ) { Text("Simpan", color = JarvisColors.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showAddOrEditModal = false }) { Text("Batal", color = JarvisColors.TextMuted) }
            }
        )
    }
}