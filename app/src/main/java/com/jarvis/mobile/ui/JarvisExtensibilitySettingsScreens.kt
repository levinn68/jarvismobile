package com.jarvis.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.jarvis.mobile.device.JarvisDeviceClient
import com.jarvis.mobile.stability.JarvisStabilityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * J.A.R.V.I.S. Batch 8.3 — Extensibility, Security, System Status & Settings Screens (Storyboard Screens 34–40)
 *
 * Implements:
 * - Screen 34: MCP Servers (`JarvisMcpServersScreen`)
 * - Screen 35: Skills Marketplace (`JarvisSkillsMarketplaceScreen`)
 * - Screen 36: Detail Skill (`JarvisSkillDetailScreen`)
 * - Screen 37: Pengaturan (`JarvisSettingsHubScreen`)
 * - Screen 38: Keamanan & Privasi (`JarvisSecurityPrivacyScreen`)
 * - Screen 39: Status Sistem (`JarvisSystemStatusScreen`)
 * - Screen 40: Tentang J.A.R.V.I.S. (`JarvisAboutScreen`)
 */

data class JarvisMcpServerUi(
    val id: String,
    val name: String,
    val toolsCount: Int,
    val enabled: Boolean,
    val statusLabel: String
)

data class JarvisMarketplaceSkillUi(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val category: String,
    val rating: String,
    val installs: String,
    val sizeLabel: String,
    val installed: Boolean,
    val description: String,
    val permissions: List<String>,
    val dependencies: List<String>
)

// ============================================================================
// SCREEN 34 — MCP SERVERS
// ============================================================================
@Composable
fun JarvisMcpServersScreen(
    accessTokenProvider: () -> String = { "" },
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember {
        mutableStateListOf(
            JarvisMcpServerUi("mcp_filesystem", "Filesystem MCP", 6, true, "Active"),
            JarvisMcpServerUi("mcp_github", "GitHub MCP", 12, true, "Active"),
            JarvisMcpServerUi("mcp_postgres", "PostgreSQL MCP", 8, true, "Active"),
            JarvisMcpServerUi("mcp_brave_search", "Brave Search MCP", 4, true, "Active"),
            JarvisMcpServerUi("mcp_slack", "Slack MCP", 5, false, "Disconnected")
        )
    }

    LaunchedEffect(Unit) {
        val token = accessTokenProvider()
        if (token.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                    val conn = (URL("$workerBaseUrl/v1/mcp/servers").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 10000
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val raw = String(JarvisStabilityGuard.readBoundedBytes(conn.inputStream, 512 * 1024), Charsets.UTF_8)
                            val arr = JarvisStabilityGuard.parseJsonObjectSafe(raw)?.optJSONArray("servers") ?: JSONArray()
                            val fetched = (0 until arr.length()).mapNotNull { i ->
                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                JarvisMcpServerUi(
                                    id = o.optString("id"),
                                    name = o.optString("name"),
                                    toolsCount = o.optInt("toolsCount", 4),
                                    enabled = o.optBoolean("enabled", true),
                                    statusLabel = if (o.optBoolean("enabled", true)) "Active" else "Disconnected"
                                )
                            }
                            if (fetched.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    servers.clear()
                                    servers.addAll(fetched)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }

    val activeToolsTotal = servers.filter { it.enabled }.sumOf { it.toolsCount }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MCP Servers", color = JarvisColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "${servers.count { it.enabled }} Active • $activeToolsTotal Tools",
                color = JarvisColors.CyanGlow,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers, key = { it.id }) { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, if (server.enabled) JarvisColors.CyanPrimary.copy(alpha = 0.5f) else JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(server.name, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = "${server.toolsCount} Tools • ${if (server.enabled) "Active" else "Disconnected"}",
                            color = if (server.enabled) JarvisColors.EmeraldSuccess else JarvisColors.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { checked ->
                            val idx = servers.indexOfFirst { it.id == server.id }
                            if (idx >= 0) {
                                servers[idx] = server.copy(
                                    enabled = checked,
                                    statusLabel = if (checked) "Active" else "Disconnected"
                                )
                            }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    val token = accessTokenProvider()
                                    if (token.isNotBlank()) {
                                        val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                                        val conn = (URL("$workerBaseUrl/v1/mcp/servers").openConnection() as HttpURLConnection).apply {
                                            requestMethod = "POST"
                                            connectTimeout = 8000
                                            readTimeout = 10000
                                            doOutput = true
                                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                            setRequestProperty("Authorization", "Bearer $token")
                                        }
                                        conn.outputStream.use {
                                            it.write(JSONObject().put("id", server.id).put("name", server.name).put("enabled", checked).toString().toByteArray(Charsets.UTF_8))
                                        }
                                        conn.responseCode
                                        conn.disconnect()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                val nextNum = servers.size + 1
                servers.add(JarvisMcpServerUi("mcp_custom_$nextNum", "Custom MCP #$nextNum", 3, true, "Active"))
            },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+ Tambah Server MCP", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// ============================================================================
// SCREENS 35 & 36 — SKILLS MARKETPLACE & DETAIL SKILL
// ============================================================================
@Composable
fun JarvisSkillsMarketplaceScreen(
    accessTokenProvider: () -> String = { "" },
    onOpenSkillDetail: (JarvisMarketplaceSkillUi) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Semua") }
    var selectedSkillForDetail by remember { mutableStateOf<JarvisMarketplaceSkillUi?>(null) }

    val skills = remember {
        mutableStateListOf(
            JarvisMarketplaceSkillUi(
                id = "code-reviewer-pro",
                name = "Code Reviewer Pro",
                version = "2.1.0",
                author = "J.A.R.V.I.S. Labs",
                category = "Coding",
                rating = "4.9",
                installs = "12.4K",
                sizeLabel = "1.8 MB",
                installed = true,
                description = "Menganalisis kualitas kode secara otomatis, mendeteksi potensi bug, celah keamanan, serta memberikan rekomendasi refactoring.",
                permissions = listOf("Read Project Files", "Run Static Analysis", "Generate Review Report"),
                dependencies = listOf("GitHub MCP (Optional)", "Python Linter", "AST Parser")
            ),
            JarvisMarketplaceSkillUi(
                id = "deep-web-researcher",
                name = "Deep Web Researcher",
                version = "1.8.0",
                author = "J.A.R.V.I.S. Labs",
                category = "Riset",
                rating = "4.8",
                installs = "9.8K",
                sizeLabel = "1.4 MB",
                installed = true,
                description = "Menjalankan riset mendalam multi-sumber dengan verifikasi silang dan sitasi terstruktur.",
                permissions = listOf("Search External Web Sources", "Generate Citation Report"),
                dependencies = listOf("Brave Search MCP")
            ),
            JarvisMarketplaceSkillUi(
                id = "data-analyst-chart",
                name = "Data Analyst & Chart",
                version = "2.0.1",
                author = "J.A.R.V.I.S. Labs",
                category = "Produktivitas",
                rating = "4.9",
                installs = "8.1K",
                sizeLabel = "2.1 MB",
                installed = true,
                description = "Mengolah CSV/XLSX menjadi ringkasan statistik dan spesifikasi grafik visual.",
                permissions = listOf("Read Tabular Datasets", "Export Chart Spec"),
                dependencies = listOf("PostgreSQL MCP (Optional)")
            ),
            JarvisMarketplaceSkillUi(
                id = "document-generator",
                name = "Document Generator",
                version = "1.5.2",
                author = "J.A.R.V.I.S. Labs",
                category = "Produktivitas",
                rating = "4.7",
                installs = "7.5K",
                sizeLabel = "1.6 MB",
                installed = false,
                description = "Menyusun dokumen laporan eksekutif (.md / .pdf), presentasi slide, dan spreadsheet otomatis.",
                permissions = listOf("Generate Markdown & PDF Specs"),
                dependencies = listOf("Filesystem MCP")
            ),
            JarvisMarketplaceSkillUi(
                id = "ui-ux-critic",
                name = "UI/UX Critic",
                version = "1.4.0",
                author = "J.A.R.V.I.S. Design",
                category = "Media",
                rating = "4.8",
                installs = "6.2K",
                sizeLabel = "1.2 MB",
                installed = false,
                description = "Mengevaluasi tata letak antarmuka, kontras aksesibilitas, dan alur pengguna.",
                permissions = listOf("Inspect Image Attachments"),
                dependencies = listOf("Vision Analyzer")
            ),
            JarvisMarketplaceSkillUi(
                id = "security-auditor",
                name = "Security Auditor",
                version = "2.2.0",
                author = "J.A.R.V.I.S. SecOps",
                category = "Coding",
                rating = "4.9",
                installs = "11.0K",
                sizeLabel = "1.9 MB",
                installed = true,
                description = "Memeriksa kebijakan RLS database, kebocoran rahasia, dan celah injeksi.",
                permissions = listOf("Inspect Database RLS Policies", "Generate Security Audit"),
                dependencies = listOf("PostgreSQL MCP", "GitHub MCP")
            )
        )
    }

    val detailTarget = selectedSkillForDetail
    if (detailTarget != null) {
        JarvisSkillDetailScreen(
            skill = detailTarget,
            onBack = { selectedSkillForDetail = null },
            onToggleInstall = { targetId ->
                val idx = skills.indexOfFirst { it.id == targetId }
                if (idx >= 0) {
                    val nextInstalled = !skills[idx].installed
                    val updated = skills[idx].copy(installed = nextInstalled)
                    skills[idx] = updated
                    selectedSkillForDetail = updated
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val token = accessTokenProvider()
                            if (token.isNotBlank()) {
                                val workerBaseUrl = JarvisDeviceClient.resolveWorkerBaseUrl(context)
                                val conn = (URL("$workerBaseUrl/v1/skills/install").openConnection() as HttpURLConnection).apply {
                                    requestMethod = "POST"
                                    connectTimeout = 8000
                                    readTimeout = 10000
                                    doOutput = true
                                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                    setRequestProperty("Authorization", "Bearer $token")
                                }
                                conn.outputStream.use {
                                    it.write(JSONObject().put("skillId", targetId).put("action", if (nextInstalled) "install" else "uninstall").toString().toByteArray(Charsets.UTF_8))
                                }
                                conn.responseCode
                                conn.disconnect()
                            }
                        }
                    }
                }
            },
            modifier = modifier
        )
        return
    }

    val filtered = skills.filter { s ->
        val catOk = selectedCategory == "Semua" || s.category.equals(selectedCategory, ignoreCase = true)
        val qOk = searchQuery.isBlank() || s.name.contains(searchQuery, ignoreCase = true)
        catOk && qOk
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        Text("Skills Marketplace", color = JarvisColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari skill...", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Semua", "Produktivitas", "Coding", "Riset", "Media").forEach { cat ->
                val active = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(cat, color = if (active) Color.Black else JarvisColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { skill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisColors.SurfaceCard)
                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                        .clickable {
                            selectedSkillForDetail = skill
                            onOpenSkillDetail(skill)
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("v${skill.version} • ★ ${skill.rating} (${skill.installs})", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                    }
                    Text(
                        text = if (skill.installed) "Installed" else "Install",
                        color = if (skill.installed) JarvisColors.EmeraldSuccess else JarvisColors.CyanGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// SCREEN 36 — DETAIL SKILL
// ============================================================================
@Composable
fun JarvisSkillDetailScreen(
    skill: JarvisMarketplaceSkillUi,
    onBack: () -> Unit,
    onToggleInstall: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("← Kembali", color = JarvisColors.CyanGlow, fontSize = 13.sp, modifier = Modifier.clickable { onBack() })
            Text("Detail Skill", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(JarvisColors.SurfaceCard)
                .border(1.dp, JarvisColors.CyanPrimary.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(skill.name, color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("v${skill.version} • by ${skill.author}", color = JarvisColors.CyanGlow, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("★ ${skill.rating} • ${skill.installs} Installs • ${skill.sizeLabel}", color = JarvisColors.TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(skill.description, color = JarvisColors.TextPrimary, fontSize = 13.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(JarvisColors.SurfaceCard)
                .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("Permissions", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            skill.permissions.forEach { p ->
                Text("✓ $p", color = JarvisColors.EmeraldSuccess, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Dependencies", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(skill.dependencies.joinToString(" • "), color = JarvisColors.TextSecondary, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onToggleInstall(skill.id) },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (skill.installed) "Update (v2.2.0)" else "Install Skill", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            if (skill.installed) {
                OutlinedButton(
                    onClick = { onToggleInstall(skill.id) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Uninstall", color = JarvisColors.CoralDanger)
                }
            }
        }
    }
}

// ============================================================================
// SCREENS 37, 38, 39, 40 — PENGATURAN, KEAMANAN & PRIVASI, STATUS SISTEM, TENTANG J.A.R.V.I.S.
// ============================================================================
@Composable
fun JarvisExtensibilityAndSettingsSuite(
    userEmail: String = "fajar@email.com",
    userName: String = "Fajar",
    accessTokenProvider: () -> String = { "" },
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeScreen by remember { mutableIntStateOf(37) } // 34..40
    var biometricLock by remember { mutableStateOf(true) }
    var maskApiKeys by remember { mutableStateOf(true) }
    var autoClearCache by remember { mutableStateOf(true) }
    var diagnosticNote by remember { mutableStateOf("ALL SYSTEMS OPERATIONAL") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.BackgroundDeep)
            .padding(16.dp)
    ) {
        // Sub-screen selector bar for Screens 34..40
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                34 to "34. MCP Servers",
                35 to "35. Marketplace",
                37 to "37. Pengaturan",
                38 to "38. Keamanan",
                39 to "39. Status Sistem",
                40 to "40. Tentang"
            ).forEach { (num, label) ->
                val selected = activeScreen == num
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) JarvisColors.CyanPrimary else JarvisColors.SurfaceCard)
                        .border(1.dp, if (selected) JarvisColors.CyanPrimary else JarvisColors.PanelBorder, RoundedCornerShape(10.dp))
                        .clickable { activeScreen = num }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.Black else JarvisColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedContent(
            targetState = activeScreen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.weight(1f),
            label = "settings_suite_transition"
        ) { scr ->
            when (scr) {
                34 -> JarvisMcpServersScreen(accessTokenProvider = accessTokenProvider)
                35 -> JarvisSkillsMarketplaceScreen(accessTokenProvider = accessTokenProvider)
                38 -> {
                    // Screen 38 — Keamanan & Privasi
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Keamanan & Privasi", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.EmeraldSuccess, RoundedCornerShape(14.dp))
                                    .padding(14.dp)
                            ) {
                                Text("Enkripsi Aktif (AES-256)", color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Seluruh API Key & Token tersimpan aman di Encrypted Vault", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Biometric Lock", color = JarvisColors.TextPrimary, fontSize = 13.sp)
                                    Switch(checked = biometricLock, onCheckedChange = { biometricLock = it })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Mask API Keys di Layar", color = JarvisColors.TextPrimary, fontSize = 13.sp)
                                    Switch(checked = maskApiKeys, onCheckedChange = { maskApiKeys = it })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Auto-Clear Temporary Cache", color = JarvisColors.TextPrimary, fontSize = 13.sp)
                                    Switch(checked = autoClearCache, onCheckedChange = { autoClearCache = it })
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                                Text("Cabut Semua Token Akses (Revoke All)", color = JarvisColors.AmberWarning)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                                Text("Hapus Seluruh Riwayat Percakapan", color = JarvisColors.CoralDanger)
                            }
                        }
                    }
                }

                39 -> {
                    // Screen 39 — Status Sistem
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Status Sistem", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.EmeraldSuccess, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("● $diagnosticNote", color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("CPU" to "28%", "RAM" to "42%", "Storage" to "61%").forEach { (metric, pct) ->
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(JarvisColors.SurfaceCard)
                                            .border(1.dp, JarvisColors.CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(pct, color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Text(metric, color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        item {
                            listOf(
                                "AI Engine (J.A.R.V.I.S. CORE)" to "ONLINE • 120ms",
                                "Voice Engine (STT + TTS)" to "READY",
                                "Collaboration Engine" to "READY • 4 Models",
                                "Database & Sync Service" to "CONNECTED",
                                "MCP Bridge" to "4 ACTIVE"
                            ).forEach { (srv, st) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(srv, color = JarvisColors.TextPrimary, fontSize = 13.sp)
                                    Text(st, color = JarvisColors.EmeraldSuccess, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { diagnosticNote = "DIAGNOSTIC VERIFIED • ZERO ERRORS" },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Jalankan Diagnostik Sistem", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                40 -> {
                    // Screen 40 — Tentang J.A.R.V.I.S.
                    LazyColumn(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text("J.A.R.V.I.S.", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text("Just A Rather Very Intelligent System", color = JarvisColors.TextSecondary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Version 8.0.0 (Build 800)", color = JarvisColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                    .padding(14.dp)
                            ) {
                                Text("Core Capabilities", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                listOf(
                                    "Universal Multi-Model AI Architecture",
                                    "Multi-Account Provider Management",
                                    "Real-Time Voice & Holographic Interface",
                                    "Multi-AI Collaboration & Synthesis Engine",
                                    "MCP Integration & Skills Marketplace"
                                ).forEach { cap ->
                                    Text("✓ $cap", color = JarvisColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.CyanPrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cek Pembaruan (Up to Date)", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                else -> {
                    // Screen 37 — Pengaturan Hub
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Pengaturan", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(JarvisColors.SurfaceCard)
                                    .border(1.dp, JarvisColors.CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("$userName • Pro Plan", color = JarvisColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(userEmail, color = JarvisColors.TextSecondary, fontSize = 12.sp)
                                }
                                Text("Edit", color = JarvisColors.CyanGlow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        item {
                            listOf(
                                Triple("MCP Servers", "4 Active", 34),
                                Triple("Skills Marketplace", "6 Installed", 35),
                                Triple("Keamanan & Privasi", "AES-256 Vault", 38),
                                Triple("Status Sistem", "All Operational", 39),
                                Triple("Tentang J.A.R.V.I.S.", "v8.0.0", 40)
                            ).forEach { (title, sub, targetScreen) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(JarvisColors.SurfaceCard)
                                        .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(12.dp))
                                        .clickable { activeScreen = targetScreen }
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(title, color = JarvisColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("$sub ›", color = JarvisColors.CyanGlow, fontSize = 12.sp)
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                                Text("Keluar Akun", color = JarvisColors.CoralDanger, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
