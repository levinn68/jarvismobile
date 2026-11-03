package com.jarvis.mobile.device

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

/** User-facing pairing and permission setup. Secrets are entered only at runtime. */
class DeviceSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: JarvisDeviceClient
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = JarvisDeviceClient(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(9, 17, 29))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 12, 0, 12)
            layout.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; layout.addView(this)
        }
        val googleAccessToken = intent.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()
        val supabaseUserId = intent.getStringExtra(EXTRA_SUPABASE_USER_ID)
        val targetDevice = EditText(this).apply {
            hint = "ID perangkat target (kosong = perangkat ini)"
            inputType = 0x00000001
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
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
                } catch (e: Exception) { "Gagal menghubungkan akun Google: ${e.message}" }
                runOnUiThread { status.text = message }
            }
        }
        button("Lihat semua perangkat dalam akun") {
            if (supabaseUserId.isNullOrBlank()) {
                status.text = "Sesi Google tidak tersedia. Buka pengaturan dari JARVIS."
                return@button
            }
            status.text = "Memuat daftar perangkat..."
            worker.execute {
                val message = try {
                    val devices = client.listAccountDevices(supabaseUserId)
                    val output = StringBuilder("Total perangkat: ${devices.length()}")
                    for (i in 0 until devices.length()) {
                        val item = devices.optJSONObject(i) ?: continue
                        output.append("\\n• ").append(item.optString("name"))
                            .append(" — ").append(item.optString("platform"))
                            .append("\\n  ").append(item.optString("deviceId"))
                    }
                    output.toString()
                } catch (e: Exception) { "Gagal memuat perangkat: ${e.message}" }
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
        button("Pilih folder yang boleh diakses") {
            startActivityForResult(FileController(this).folderPickerIntent(), 501)
        }
        button("Pengaturan akses notifikasi") {
            startActivity(NotificationController(this).openAccessSettings())
        }
        button("Pengaturan aksesibilitas (baca layar)") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Izinkan akses kontak & kalender") {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_CALENDAR), 602)
        }
        button("Perbarui izin & kemampuan perangkat") {
            if (!client.isConfigured()) { status.text = "Pairing perangkat terlebih dahulu"; return@button }
            status.text = "Memeriksa izin Android..."
            worker.execute {
                val capabilities = mutableListOf("apps.list", "apps.launch", "system.read")
                if (FileController(this).hasSelectedFolder()) capabilities.add("files.read")
                if (NotificationController(this).list().optBoolean("ok")) capabilities.add("notifications.read")
                if (DevicePermissionManager(this).isGranted("screen.read")) capabilities.add("screen.read")
                if (DevicePermissionManager(this).isGranted("contacts.read")) capabilities.add("contacts.read")
                if (DevicePermissionManager(this).isGranted("calendar.read")) capabilities.add("calendar.read")
                val message = try {
                    client.updateCapabilities(capabilities)
                    "Kemampuan aktif: ${capabilities.joinToString()}"
                } catch (e: Exception) { "Gagal memperbarui kemampuan: ${e.message}" }
                runOnUiThread { status.text = message }
            }
        }
        button("Kirim perintah status ke perangkat ini") {
            val id = client.deviceId()
            if (id.isNullOrBlank() || supabaseUserId.isNullOrBlank() || !client.hasAccountToken(supabaseUserId)) {
                status.text = "Pair perangkat terlebih dahulu"
                return@button
            }
            status.text = "Mengirim perintah status..."
            worker.execute {
                val message = try {
                    val queued = client.queueCommand(id, "system.status", expectedSupabaseUserId = supabaseUserId)
                    if (queued.optBoolean("ok")) "Perintah masuk antrean. Pastikan sinkronisasi aktif untuk mengeksekusi."
                    else "Perintah ditolak: ${queued.optString("reason", queued.optString("error", "tidak diizinkan"))}"
                } catch (e: Exception) { "Gagal mengirim perintah: ${e.message}" }
                runOnUiThread { status.text = message }
            }
        }
        button("Sinkronisasi perangkat: MULAI") {
            if (!client.isConfigured()) { status.text = "Pairing perangkat terlebih dahulu"; return@button }
            try { JarvisDeviceSyncService.start(this); status.text = "Sinkronisasi dimulai" }
            catch (e: Exception) { status.text = "Tidak dapat memulai: ${e.message}" }
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
        if (requestCode == 501 && resultCode == RESULT_OK && data?.data != null) {
            try {
                FileController(this).saveSelectedFolder(data.data!!)
                status.text = "Folder dipilih dan izin disimpan"
            } catch (e: Exception) { status.text = "Gagal menyimpan izin: ${e.message}" }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    companion object { const val EXTRA_SUPABASE_ACCESS_TOKEN = "jarvis_supabase_access_token"; const val EXTRA_SUPABASE_USER_ID = "jarvis_supabase_user_id" }
}
