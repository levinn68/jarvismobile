package com.jarvis.mobile.device

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** User-facing pairing and permission setup. Secrets are entered only at runtime. */
class DeviceSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: JarvisDeviceClient
    private lateinit var cameraController: CameraController
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = JarvisDeviceClient(this)
        cameraController = CameraController(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(9, 17, 29))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 12, 0, 12)
            layout.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            layout.addView(this)
        }
        val googleAccessToken = intent.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()
        val supabaseUserId = intent.getStringExtra(EXTRA_SUPABASE_USER_ID)
        val targetDevice = EditText(this).apply {
            hint = "ID perangkat target (kosong = perangkat ini)"
            inputType = 0x00000001
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(getSharedPreferences("jarvis_device_secure", MODE_PRIVATE).getString("target_device_id", ""))
            layout.addView(this)
        }
        status = label(if (client.isConfigured()) "Terhubung: ${client.deviceId()}" else "Belum pairing")
        button("Hubungkan akun Google & pair perangkat ini") {
            if (googleAccessToken.isBlank() || supabaseUserId.isNullOrBlank()) {
                status.text = "Buka pengaturan perangkat dari JARVIS setelah masuk dengan Google."
                return@button
            }
            status.text = "Memverifikasi sesi Google dan memasangkan perangkat..."
            worker.execute {
                val message = try {
                    val account = client.createOrRestoreAccountWithSupabase(googleAccessToken)
                    val accountId = account.optJSONObject("account")?.optString("accountId").orEmpty()
                    if (accountId.removePrefix("acct-") != supabaseUserId) {
                        throw SecurityException("Akun Google berubah saat pairing. Buka ulang pengaturan perangkat.")
                    }
                    client.pair(client.storedAccountToken(supabaseUserId), Build.MODEL, Build.VERSION.RELEASE)
                    "Akun Google terhubung ($accountId). Perangkat berhasil dipair: ${client.deviceId()}"
                } catch (e: Exception) {
                    "Gagal menghubungkan akun Google: ${e.message}"
                }
                runOnUiThread { status.text = message }
            }
        }
        button("Buat kode pairing QR untuk desktop") {
            if (googleAccessToken.isBlank() || supabaseUserId.isNullOrBlank()) {
                status.text = "Sesi Google tidak tersedia. Buka pengaturan dari JARVIS."
                return@button
            }
            startActivity(
                Intent(this, PairingQrActivity::class.java)
                    .putExtra(PairingQrActivity.EXTRA_SUPABASE_ACCESS_TOKEN, googleAccessToken)
                    .putExtra(PairingQrActivity.EXTRA_SUPABASE_USER_ID, supabaseUserId)
            )
        }
        button("Salin token pairing desktop (berlaku 1 jam)") {
            if (googleAccessToken.isBlank() || supabaseUserId.isNullOrBlank()) {
                status.text = "Sesi Google tidak tersedia. Buka pengaturan dari JARVIS."
                return@button
            }
            status.text = "Memperbarui token pairing..."
            worker.execute {
                val token = try {
                    client.createOrRestoreAccountWithSupabase(googleAccessToken)
                    client.storedAccountToken(supabaseUserId).takeIf { it.isNotBlank() }
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
            if (supabaseUserId.isNullOrBlank() || googleAccessToken.isBlank()) {
                status.text = "Sesi Google tidak tersedia. Buka pengaturan dari JARVIS."
                return@button
            }
            status.text = "Memuat daftar perangkat..."
            worker.execute {
                val message = try {
                    client.createOrRestoreAccountWithSupabase(googleAccessToken)
                    val devices = client.listAccountDevices(supabaseUserId)
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

        button("Cabut akses perangkat target dari akun") {
            val id = targetDevice.text.toString().trim().ifBlank { client.deviceId().orEmpty() }
            if (!id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) {
                status.text = "Masukkan ID perangkat yang valid terlebih dahulu"
                return@button
            }
            if (googleAccessToken.isBlank() || supabaseUserId.isNullOrBlank()) {
                status.text = "Sesi Google tidak tersedia. Buka pengaturan dari JARVIS."
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
                            client.createOrRestoreAccountWithSupabase(googleAccessToken)
                            val result = client.removeDevice(id, supabaseUserId)
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
        button("Kirim perintah status ke perangkat ini") {
            val id = client.deviceId()
            if (id.isNullOrBlank() || supabaseUserId.isNullOrBlank() || googleAccessToken.isBlank() || !client.hasAccountToken(supabaseUserId)) {
                status.text = "Pair perangkat terlebih dahulu"
                return@button
            }
            status.text = "Mengirim perintah status..."
            worker.execute {
                val message = try {
                    client.createOrRestoreAccountWithSupabase(googleAccessToken)
                    val queued = client.queueCommand(id, "system.status", expectedSupabaseUserId = supabaseUserId)
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
        button("Putuskan pairing") {
            JarvisDeviceSyncService.stop(this)
            client.clearCredentials()
            status.text = "Perangkat tidak lagi terhubung"
        }
        val scroll = ScrollView(this).apply { addView(layout) }
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