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
        val bootstrapSecret = EditText(this).apply {
            hint = "Bootstrap secret (khusus membuat akun)"
            inputType = 0x00000081
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            layout.addView(this)
        }
        val accountToken = EditText(this).apply {
            hint = "JARVIS account token (bisa dipakai di perangkat lain)"
            inputType = 0x00000001
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setTextIsSelectable(true)
            layout.addView(this)
        }
        status = label(if (client.isConfigured()) "Terhubung: ${client.deviceId()}" else "Belum pairing")
        button("Buat akun JARVIS") {
            val value = bootstrapSecret.text.toString()
            bootstrapSecret.text.clear()
            if (value.isBlank()) { status.text = "Masukkan bootstrap secret"; return@button }
            status.text = "Membuat akun..."
            worker.execute {
                try {
                    val result = client.createAccount(value)
                    val token = result.optString("accountToken")
                    runOnUiThread {
                        accountToken.setText(token)
                        status.text = "Akun dibuat: ${result.optJSONObject("account")?.optString("accountId")}. Simpan account token untuk pairing perangkat lain."
                    }
                } catch (e: Exception) { runOnUiThread { status.text = "Gagal membuat akun: ${e.message}" } }
            }
        }
        button("Pair perangkat ke akun") {
            val value = accountToken.text.toString().trim()
            if (value.isBlank()) { status.text = "Masukkan account token"; return@button }
            status.text = "Menghubungkan perangkat ke akun..."
            worker.execute {
                val message = try {
                    client.pair(value, Build.MODEL, Build.VERSION.RELEASE)
                    "Pairing berhasil: ${client.deviceId()}"
                } catch (e: Exception) { "Pairing gagal: ${e.message}" }
                runOnUiThread { status.text = message }
            }
        }
        button("Lihat semua perangkat dalam akun") {
            val value = accountToken.text.toString().trim()
            if (value.isBlank()) { status.text = "Masukkan account token"; return@button }
            status.text = "Memuat daftar perangkat..."
            worker.execute {
                val message = try {
                    val devices = client.listAccountDevices(value)
                    val output = StringBuilder("Total perangkat: ${devices.length()}")
                    for (i in 0 until devices.length()) {
                        val item = devices.optJSONObject(i) ?: continue
                        output.append("\n• ").append(item.optString("name"))
                            .append(" — ").append(item.optString("platform"))
                            .append("\n  ").append(item.optString("deviceId"))
                    }
                    output.toString()
                } catch (e: Exception) { "Gagal memuat perangkat: ${e.message}" }
                runOnUiThread { status.text = message }
            }
        }
        button("Pilih folder yang boleh diakses") {
            startActivityForResult(FileController(this).folderPickerIntent(), 501)
        }
        button("Pengaturan akses notifikasi") {
            startActivity(NotificationController(this).openAccessSettings())
        }
        button("Perbarui izin & kemampuan perangkat") {
            if (!client.isConfigured()) { status.text = "Pairing perangkat terlebih dahulu"; return@button }
            status.text = "Memeriksa izin Android..."
            worker.execute {
                val capabilities = mutableListOf("apps.list", "apps.launch", "system.read")
                if (FileController(this).hasSelectedFolder()) capabilities.add("files.read")
                if (NotificationController(this).list().optBoolean("ok")) capabilities.add("notifications.read")
                val message = try {
                    client.updateCapabilities(capabilities)
                    "Kemampuan aktif: ${capabilities.joinToString()}"
                } catch (e: Exception) { "Gagal memperbarui kemampuan: ${e.message}" }
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
                FileController(this).saveSelectedFolder(data.data!!, data.flags)
                status.text = "Folder dipilih dan izin disimpan"
            } catch (e: Exception) { status.text = "Gagal menyimpan izin: ${e.message}" }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
