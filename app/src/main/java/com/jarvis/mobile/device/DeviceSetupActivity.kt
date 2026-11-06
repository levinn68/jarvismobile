package com.jarvis.mobile.device

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.jarvis.mobile.account.SupabaseJarvisClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-facing pairing and permission setup styled with the JARVIS Dark Futuristic HUD theme.
 * Secrets are entered only at runtime.
 */
class DeviceSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val authBusy = AtomicBoolean(false)
    private lateinit var client: JarvisDeviceClient
    private lateinit var supabaseClient: SupabaseJarvisClient
    private lateinit var cameraController: CameraController
    private lateinit var status: TextView
    @Volatile private var activeAccessToken: String = ""
    @Volatile private var activeUserId: String? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun hudSurfaceDrawable(
        fillColor: Int,
        strokeColor: Int,
        cornerDp: Int = 14,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(cornerDp).toFloat()
        setColor(fillColor)
        setStroke(dp(strokeWidthDp).coerceAtLeast(1), strokeColor)
    }

    private fun ensureGoogleSessionBlocking(): Pair<String, String> {
        val existingToken = activeAccessToken.ifBlank {
            runCatching { supabaseClient.currentAccessToken() }.getOrDefault("")
        }
        val existingUser = activeUserId?.takeIf { it.isNotBlank() } ?: supabaseClient.currentUserId()
        if (existingToken.isNotBlank() && !existingUser.isNullOrBlank()) {
            activeAccessToken = existingToken
            activeUserId = existingUser
            return existingToken to existingUser
        }
        val session = runBlocking {
            supabaseClient.signInWithGoogle(this@DeviceSetupActivity) { stage, label ->
                runOnUiThread { status.text = "[${stage.code}] $label" }
            }
        }
        activeAccessToken = session.accessToken
        activeUserId = session.userId
        return session.accessToken to session.userId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#040911")
        window.navigationBarColor = Color.parseColor("#040911")

        client = JarvisDeviceClient(this)
        supabaseClient = SupabaseJarvisClient(this)
        cameraController = CameraController(this)

        val bgDeep = Color.parseColor("#040911")
        val cardBg = Color.parseColor("#081421")
        val buttonBg = Color.parseColor("#0B1E32")
        val dangerBg = Color.parseColor("#230D16")
        val cyan = Color.parseColor("#00E5FF")
        val cyanBorder = Color.parseColor("#1D5B78")
        val dangerBorder = Color.parseColor("#FF5E6E")
        val ice = Color.parseColor("#ECF9FF")
        val muted = Color.parseColor("#7B9EB2")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(bgDeep)
        }

        // Top HUD Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "J.A.R.V.I.S. DEVICE CONTROL"
            setTextColor(ice)
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        titleCol.addView(TextView(this).apply {
            text = "PAIRING · CAPABILITY GATE · SECURITY TELEMETRY"
            setTextColor(cyan)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
        val backBtn = Button(this).apply {
            text = "KEMBALI"
            isAllCaps = false
            setTextColor(cyan)
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = hudSurfaceDrawable(cardBg, cyanBorder, cornerDp = 10)
            minHeight = dp(44)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            contentDescription = "Kembali ke layar utama JARVIS"
            setOnClickListener { finish() }
        }
        headerRow.addView(titleCol)
        headerRow.addView(backBtn)
        layout.addView(headerRow)

        fun sectionHeader(title: String) {
            layout.addView(TextView(this).apply {
                text = title
                setTextColor(cyan)
                textSize = 11.5f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.1f
                setPadding(dp(2), dp(14), dp(2), dp(6))
            })
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(ice)
            textSize = 13.5f
            setLineSpacing(0f, 1.25f)
            background = hudSurfaceDrawable(cardBg, cyanBorder, cornerDp = 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            layout.addView(this)
        }

        fun button(text: String, isDestructive: Boolean = false, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (isDestructive) Color.parseColor("#FF8A96") else ice)
            textSize = 13.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = hudSurfaceDrawable(
                fillColor = if (isDestructive) dangerBg else buttonBg,
                strokeColor = if (isDestructive) dangerBorder else cyanBorder,
                cornerDp = 12
            )
            contentDescription = text
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { action() }
            layout.addView(this)
        }

        activeAccessToken = intent.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty().ifBlank {
            runCatching { if (supabaseClient.hasSession()) supabaseClient.currentAccessToken() else "" }.getOrDefault("")
        }
        activeUserId = intent.getStringExtra(EXTRA_SUPABASE_USER_ID)?.takeIf { it.isNotBlank() }
            ?: supabaseClient.currentUserId()

        sectionHeader("STATUS KONEKSI & TARGET PERANGKAT")
        status = label(
            when {
                client.isConfigured() -> "Terhubung: ${client.deviceId()} (${supabaseClient.currentEmail() ?: "Akun aktif"})"
                supabaseClient.hasSession() -> "Akun Google aktif (${supabaseClient.currentEmail() ?: supabaseClient.currentUserId()}). Siap pairing perangkat."
                else -> "Belum masuk dengan Google / belum pairing"
            }
        )

        val targetDevice = EditText(this).apply {
            hint = "ID perangkat target (kosong = perangkat ini)"
            inputType = 0x00000001
            setTextColor(ice)
            setHintTextColor(muted)
            textSize = 13.5f
            minHeight = dp(48)
            background = hudSurfaceDrawable(cardBg, cyanBorder, cornerDp = 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(getSharedPreferences("jarvis_device_secure", MODE_PRIVATE).getString("target_device_id", ""))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            layout.addView(this)
        }

        sectionHeader("AUTENTIKASI & PAIRING MULTI-DEVICE")
        button("Hubungkan akun Google & pair perangkat ini") {
            if (!authBusy.compareAndSet(false, true)) return@button
            status.text = "Memverifikasi sesi Google dan memasangkan perangkat..."
            worker.execute {
                val message = try {
                    val (token, userId) = ensureGoogleSessionBlocking()
                    val account = client.createOrRestoreAccountWithSupabase(token)
                    val accountId = account.optJSONObject("account")?.optString("accountId").orEmpty()
                    if (accountId.removePrefix("acct-") != userId) {
                        throw SecurityException("Akun Google berubah saat pairing. Buka ulang pengaturan perangkat.")
                    }
                    client.pair(client.storedAccountToken(userId), Build.MODEL, Build.VERSION.RELEASE)
                    "[AUTH_SUCCESS] Akun Google terhubung (${supabaseClient.currentEmail() ?: accountId}). Perangkat berhasil dipair: ${client.deviceId()}"
                } catch (e: Exception) {
                    "Gagal menghubungkan akun Google: ${e.message}"
                } finally {
                    authBusy.set(false)
                }
                runOnUiThread { status.text = message }
            }
        }
        button("Buat kode pairing QR untuk desktop") {
            val token = activeAccessToken.ifBlank { runCatching { supabaseClient.currentAccessToken() }.getOrDefault("") }
            val userId = activeUserId ?: supabaseClient.currentUserId()
            if (token.isBlank() || userId.isNullOrBlank()) {
                status.text = "Silakan tekan 'Hubungkan akun Google & pair perangkat ini' terlebih dahulu."
                return@button
            }
            startActivity(
                Intent(this, PairingQrActivity::class.java)
                    .putExtra(PairingQrActivity.EXTRA_SUPABASE_ACCESS_TOKEN, token)
                    .putExtra(PairingQrActivity.EXTRA_SUPABASE_USER_ID, userId)
            )
        }
        button("Salin token pairing desktop (berlaku 1 jam)") {
            status.text = "Memperbarui token pairing..."
            worker.execute {
                val token = try {
                    val (accessToken, userId) = ensureGoogleSessionBlocking()
                    client.createOrRestoreAccountWithSupabase(accessToken)
                    client.storedAccountToken(userId).takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Token akun tidak tersedia")
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (token.isNullOrBlank()) {
                        status.text = "Gagal menyiapkan token pairing. Periksa koneksi dan sesi Google."
                    } else {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS desktop pairing token", token))
                        status.text = "Token pairing disalin. Berlaku 1 jam; gunakan hanya saat menjalankan perintah pair pada desktop."
                    }
                }
            }
        }
        button("Lihat semua perangkat dalam akun") {
            status.text = "Memuat daftar perangkat..."
            worker.execute {
                val message = try {
                    val (accessToken, userId) = ensureGoogleSessionBlocking()
                    client.createOrRestoreAccountWithSupabase(accessToken)
                    val devices = client.listAccountDevices(userId)
                    val output = StringBuilder("Total perangkat: ${devices.length()}")
                    for (i in 0 until devices.length()) {
                        val item = devices.optJSONObject(i) ?: continue
                        output.append("\n• ").append(item.optString("name"))
                            .append(" — ").append(item.optString("platform"))
                            .append(if (item.optBoolean("online")) " [ONLINE]" else "")
                            .append("\n  ").append(item.optString("deviceId"))
                    }
                    output.toString()
                } catch (e: Exception) {
                    "Gagal memuat perangkat: ${e.message}"
                }
                runOnUiThread { status.text = message }
            }
        }
        button("Gunakan perangkat target untuk perintah chat") {
            val target = targetDevice.text.toString().trim()
            if (target.isNotEmpty() && !target.matches(Regex("[A-Za-z0-9_-]{8,80}"))) {
                status.text = "Format ID perangkat tidak valid"
                return@button
            }
            getSharedPreferences("jarvis_device_secure", MODE_PRIVATE).edit()
                .putString("target_device_id", target.ifBlank { client.deviceId().orEmpty() }).apply()
            status.text = if (target.isBlank()) "Target diatur ke perangkat ini" else "Target perangkat disimpan: $target"
        }

        sectionHeader("IZIN AKSES & KEMAMPUAN PERANGKAT")
        button("Pilih folder yang boleh diakses") {
            startActivityForResult(FileController(this).folderPickerIntent(), REQ_FOLDER_PICKER)
        }
        button("Uji kamera lokal (konfirmasi pengguna)") {
            val capture = cameraController.captureIntent()
            if (capture == null) {
                status.text = "Aplikasi kamera sistem tidak ditemukan di perangkat ini."
            } else {
                startActivityForResult(capture, REQ_CAMERA_PREVIEW)
            }
        }
        button("Pengaturan akses notifikasi") {
            startActivity(NotificationController(this).openAccessSettings())
        }
        button("Pengaturan aksesibilitas (baca & kontrol layar)") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Izinkan akses kontak & kalender") {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.CAMERA
                ),
                REQ_PERMISSIONS
            )
        }
        button("Perbarui izin & kemampuan perangkat") {
            if (!client.isConfigured()) {
                status.text = "Pairing perangkat terlebih dahulu"
                return@button
            }
            status.text = "Memeriksa izin dan kemampuan Android..."
            worker.execute {
                val permissionManager = DevicePermissionManager(this)
                val capabilities = permissionManager.registeredCapabilities()
                val permissionSummary = listOf(
                    "Mikrofon" to permissionManager.isGranted("voice.microphone"),
                    "Kamera (Izin OS)" to (permissionManager.missingPermissions("camera.capture").isEmpty()),
                    "Kontak" to permissionManager.isGranted("contacts.read"),
                    "Kalender" to permissionManager.isGranted("calendar.read"),
                    "Akses notifikasi" to permissionManager.isGranted("notifications.read"),
                    "Baca/kontrol layar" to permissionManager.isGranted("screen.read"),
                    "Folder pilihan" to permissionManager.hasUsableSelectedFolder()
                ).joinToString("\n") { (name, granted) ->
                    "$name: ${if (granted) "AKTIF" else "BELUM DIIZINKAN"}"
                }
                val message = try {
                    client.updateCapabilities(capabilities)
                    "Kemampuan terdaftar di akun:\n${capabilities.joinToString()}\n\nStatus izin perangkat:\n$permissionSummary\n\nCatatan: kamera hanya dapat dipicu secara lokal oleh pengguna dan tidak diiklankan sebagai perintah jarak jauh tanpa pengawasan."
                } catch (e: Exception) {
                    "Gagal memperbarui kemampuan: ${e.message}\n\nStatus izin perangkat:\n$permissionSummary"
                }
                runOnUiThread { status.text = message }
            }
        }

        sectionHeader("SINKRONISASI & KEAMANAN")
        button("Kirim perintah status ke perangkat ini") {
            val id = client.deviceId()
            val token = activeAccessToken.ifBlank { runCatching { supabaseClient.currentAccessToken() }.getOrDefault("") }
            val userId = activeUserId ?: supabaseClient.currentUserId()
            if (id.isNullOrBlank() || userId.isNullOrBlank() || token.isBlank() || !client.hasAccountToken(userId)) {
                status.text = "Pair perangkat terlebih dahulu"
                return@button
            }
            status.text = "Mengirim perintah status..."
            worker.execute {
                val message = try {
                    client.createOrRestoreAccountWithSupabase(token)
                    val queued = client.queueCommand(id, "system.status", expectedSupabaseUserId = userId)
                    if (queued.optBoolean("ok")) "Perintah masuk antrean. Pastikan sinkronisasi aktif untuk mengeksekusi."
                    else "Perintah ditolak: ${queued.optString("reason", queued.optString("error", "tidak diizinkan"))}"
                } catch (e: Exception) {
                    "Gagal mengirim perintah: ${e.message}"
                }
                runOnUiThread { status.text = message }
            }
        }
        button("Sinkronisasi perangkat: MULAI") {
            if (!client.isConfigured()) {
                status.text = "Pairing perangkat terlebih dahulu"
                return@button
            }
            try {
                JarvisDeviceSyncService.start(this)
                status.text = "Sinkronisasi dimulai"
            } catch (e: Exception) {
                status.text = "Tidak dapat memulai: ${e.message}"
            }
        }
        button("Sinkronisasi perangkat: STOP") {
            JarvisDeviceSyncService.stop(this)
            status.text = "Sinkronisasi dihentikan"
        }
        button("Cabut akses perangkat target dari akun", isDestructive = true) {
            val id = targetDevice.text.toString().trim().ifBlank { client.deviceId().orEmpty() }
            if (!id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) {
                status.text = "Masukkan ID perangkat yang valid terlebih dahulu"
                return@button
            }
            val token = activeAccessToken.ifBlank { runCatching { supabaseClient.currentAccessToken() }.getOrDefault("") }
            val userId = activeUserId ?: supabaseClient.currentUserId()
            if (token.isBlank() || userId.isNullOrBlank()) {
                status.text = "Sesi Google tidak tersedia. Hubungkan akun Google terlebih dahulu."
                return@button
            }
            AlertDialog.Builder(this)
                .setTitle("Cabut akses perangkat?")
                .setMessage("Perangkat $id akan dikeluarkan dari akun JARVIS dan tidak dapat lagi mengambil perintah.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Cabut akses") { _, _ ->
                    status.text = "Mencabut akses perangkat..."
                    worker.execute {
                        val message = try {
                            client.createOrRestoreAccountWithSupabase(token)
                            val result = client.removeDevice(id, userId)
                            if (result.optBoolean("revoked")) {
                                if (id == client.deviceId()) {
                                    JarvisDeviceSyncService.stop(this@DeviceSetupActivity)
                                    client.clearCredentials()
                                }
                                "Akses perangkat $id berhasil dicabut dari akun."
                            } else {
                                "Server tidak mengonfirmasi pencabutan perangkat."
                            }
                        } catch (e: Exception) {
                            "Gagal mencabut akses perangkat: ${e.message}"
                        }
                        runOnUiThread { status.text = message }
                    }
                }
                .show()
        }
        button("Putuskan pairing perangkat ini", isDestructive = true) {
            AlertDialog.Builder(this)
                .setTitle("Putuskan pairing lokal?")
                .setMessage("Kredensial pairing pada perangkat ini akan dihapus dan sinkronisasi latar belakang akan dihentikan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Putuskan") { _, _ ->
                    JarvisDeviceSyncService.stop(this)
                    client.clearCredentials()
                    status.text = "Perangkat tidak lagi terhubung"
                }
                .show()
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgDeep)
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER_PICKER && resultCode == RESULT_OK && data?.data != null) {
            try {
                FileController(this).saveSelectedFolder(data.data!!)
                status.text = "Folder dipilih dan izin disimpan"
            } catch (e: Exception) {
                status.text = "Gagal menyimpan izin: ${e.message}"
            }
        } else if (requestCode == REQ_CAMERA_PREVIEW) {
            val summary = cameraController.describeResult(resultCode, data)
            status.text = if (summary.optBoolean("ok")) {
                "Kamera lokal berhasil mengembalikan pratinjau (${summary.optInt("width")}x${summary.optInt("height")} px)."
            } else if (summary.optBoolean("cancelled")) {
                "Pengambilan gambar kamera dibatalkan oleh pengguna."
            } else {
                "Uji kamera gagal: ${summary.optString("error", "unknown")}"
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        if (::client.isInitialized) client.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SUPABASE_ACCESS_TOKEN = "jarvis_supabase_access_token"
        const val EXTRA_SUPABASE_USER_ID = "jarvis_supabase_user_id"
        private const val REQ_FOLDER_PICKER = 501
        private const val REQ_CAMERA_PREVIEW = 502
        private const val REQ_PERMISSIONS = 602
    }
}