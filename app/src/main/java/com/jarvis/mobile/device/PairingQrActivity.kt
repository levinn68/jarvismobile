package com.jarvis.mobile.device

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground pairing UI styled with the JARVIS Dark Futuristic HUD theme:
 * generates a one-time 8-character code, shows a QR bitmap of the payload,
 * counts down the 5-minute TTL, polls redeem status, and lets the user cancel.
 */
class PairingQrActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var client: JarvisDeviceClient
    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var countdownView: TextView
    private lateinit var deviceView: TextView
    private lateinit var qrView: ImageView
    private lateinit var createButton: Button
    private lateinit var cancelButton: Button
    private lateinit var copyButton: Button

    private var countdown: CountDownTimer? = null
    private var pollRunnable: Runnable? = null
    private var activeCode: String = ""
    private var expiresAtMs: Long = 0L
    private val cancelled = AtomicBoolean(false)
    private var googleAccessToken: String = ""
    private var supabaseUserId: String = ""

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
        setColor(fillColor)        setStroke(dp(strokeWidthDp).coerceAtLeast(1), strokeColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#040911")
        window.navigationBarColor = Color.parseColor("#040911")

        client = JarvisDeviceClient(this)
        googleAccessToken = intent.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()
        supabaseUserId = intent.getStringExtra(EXTRA_SUPABASE_USER_ID).orEmpty()

        val bgDeep = Color.parseColor("#040911")
        val cardBg = Color.parseColor("#081421")
        val buttonBg = Color.parseColor("#0B1E32")
        val cyan = Color.parseColor("#00E5FF")
        val cyanBorder = Color.parseColor("#1D5B78")
        val ice = Color.parseColor("#ECF9FF")
        val muted = Color.parseColor("#7B9EB2")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(bgDeep)
        }

        fun title(text: String, size: Float = 18f, isMuted: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(if (isMuted) muted else ice)
            textSize = size
            if (!isMuted) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
            root.addView(this)
        }

        title("PAIRING DESKTOP J.A.R.V.I.S.", 19f)
        title("Buat kode sekali pakai. Desktop memasukkan kode atau memindai QR. Kode kedaluwarsa dalam 5 menit.", 13.5f, isMuted = true)

        codeView = TextView(this).apply {
            text = "———"
            setTextColor(cyan)
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = hudSurfaceDrawable(cardBg, cyanBorder, cornerDp = 14)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            letterSpacing = 0.22f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            root.addView(this)
        }

        countdownView = TextView(this).apply {
            text = "Belum ada sesi aktif"
            setTextColor(muted)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
            root.addView(this)
        }

        val qrSizePx = (resources.displayMetrics.widthPixels * 0.68f).toInt().coerceIn(dp(200), dp(320))
        qrView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(qrSizePx, qrSizePx)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(12)
            layoutParams = lp
            background = hudSurfaceDrawable(Color.parseColor("#0A1A2B"), cyanBorder, cornerDp = 16)
            contentDescription = "Kode QR Pairing Desktop JARVIS"
            root.addView(this)
        }

        statusView = TextView(this).apply {
            text = if (googleAccessToken.isBlank() || supabaseUserId.isBlank()) {
                "Sesi Google tidak tersedia. Buka dari pengaturan JARVIS setelah masuk."
            } else {
                "Siap membuat kode pairing."
            }
            setTextColor(ice)
            textSize = 14f
            background = hudSurfaceDrawable(cardBg, cyanBorder, cornerDp = 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            root.addView(this)
        }

        deviceView = TextView(this).apply {
            text = "Perangkat terhubung: —"
            setTextColor(Color.parseColor("#00F59B"))
            textSize = 13.5f
            setPadding(dp(4), dp(4), dp(4), dp(14))
            root.addView(this)
        }

        fun styledButton(label: String, destructive: Boolean = false): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (destructive) Color.parseColor("#FF8A96") else ice)
            textSize = 13.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = hudSurfaceDrawable(
                fillColor = if (destructive) Color.parseColor("#230D16") else buttonBg,
                strokeColor = if (destructive) Color.parseColor("#FF5E6E") else cyanBorder,
                cornerDp = 12
            )
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        createButton = styledButton("Buat Kode Pairing").apply {
            isEnabled = googleAccessToken.isNotBlank() && supabaseUserId.isNotBlank()
            setOnClickListener { createSession() }
            root.addView(this)
        }
        copyButton = styledButton("Salin Kode").apply {
            isEnabled = false
            setOnClickListener {
                if (activeCode.isBlank()) return@setOnClickListener
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS pairing code", activeCode))
                Toast.makeText(this@PairingQrActivity, "Kode disalin", Toast.LENGTH_SHORT).show()
            }
            root.addView(this)
        }
        cancelButton = styledButton("Batalkan Pairing", destructive = true).apply {
            isEnabled = false
            setOnClickListener { cancelSession("Dibatalkan oleh pengguna.") }
            root.addView(this)
        }
        styledButton("Tutup").apply {
            setOnClickListener { finish() }
            root.addView(this)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgDeep)
            isFillViewport = true
            addView(root)
        })
    }

    private fun createSession() {
        if (googleAccessToken.isBlank() || supabaseUserId.isBlank()) {
            statusView.text = "Sesi Google tidak tersedia."
            return
        }
        cancelled.set(false)
        stopPolling()
        countdown?.cancel()
        createButton.isEnabled = false
        statusView.text = "Membuat sesi pairing…"
        codeView.text = "……"
        deviceView.text = "Perangkat terhubung: —"
        qrView.setImageBitmap(null)

        worker.execute {
            val result = try {
                client.createOrRestoreAccountWithSupabase(googleAccessToken)
                client.createPairingSession(supabaseUserId, deviceHintName = "Desktop")
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "Gagal membuat sesi")
            }
            mainHandler.post {
                createButton.isEnabled = true
                if (!result.optBoolean("ok", result.has("code"))) {
                    statusView.text = "Gagal: ${result.optString("error", "tidak diketahui")}"
                    return@post
                }
                activeCode = result.optString("code").uppercase()
                val expiresIn = result.optInt("expiresIn", 300).coerceIn(30, 600)
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                val payload = result.optString("qrPayload").ifBlank {
                    "jarvis-pair://v1?code=$activeCode"
                }
                codeView.text = activeCode.chunked(4).joinToString(" ")
                copyButton.isEnabled = true
                cancelButton.isEnabled = true
                statusView.text = "Menunggu desktop memasukkan kode…"
                try {
                    qrView.setImageBitmap(encodeQr(payload, 512))
                } catch (error: Exception) {
                    statusView.text = "Kode siap, tetapi QR gagal digambar: ${error.message}"
                }
                startCountdown(expiresIn * 1000L)
                startPolling()
            }
        }
    }

    private fun startCountdown(durationMs: Long) {
        countdown?.cancel()
        countdown = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = (millisUntilFinished / 1000).toInt()
                val m = totalSec / 60
                val s = totalSec % 60
                countdownView.text = "Berlaku: %d:%02d".format(m, s)
            }

            override fun onFinish() {
                countdownView.text = "Kedaluwarsa"
                if (!cancelled.get()) {
                    statusView.text = "Kode pairing kedaluwarsa. Buat kode baru."
                    cancelButton.isEnabled = false
                    stopPolling()
                }
            }
        }.start()
    }

    private fun startPolling() {
        stopPolling()
        val tick = object : Runnable {
            override fun run() {
                if (cancelled.get() || activeCode.isBlank()) return
                if (System.currentTimeMillis() >= expiresAtMs) {
                    statusView.text = "Kode pairing kedaluwarsa."
                    cancelButton.isEnabled = false
                    return
                }
                worker.execute {
                    val status = try {
                        client.pairingSessionStatus(activeCode, supabaseUserId)
                    } catch (error: Exception) {
                        JSONObject().put("ok", false).put("error", error.message ?: "poll failed")
                    }
                    mainHandler.post {
                        if (cancelled.get()) return@post
                        if (!status.optBoolean("ok")) {
                            // Transient network error or session status check failed; refresh linked devices
                            // and continue polling until expiration so a single network hiccup never aborts pairing.
                            refreshLinkedDevice()
                            if (!cancelled.get() && System.currentTimeMillis() < expiresAtMs) {
                                mainHandler.postDelayed(this, POLL_MS)
                            }
                            return@post
                        }
                        val session = status.optJSONObject("session")
                        val state = session?.optString("status").orEmpty()
                        when (state) {
                            "pending" -> {
                                statusView.text = "Menunggu desktop…"
                                mainHandler.postDelayed(this, POLL_MS)
                            }
                            "redeemed" -> {
                                val deviceId = session?.optString("redeemedDeviceId").orEmpty()
                                statusView.text = "Berhasil! Desktop terhubung."
                                countdown?.cancel()
                                countdownView.text = "Selesai"
                                cancelButton.isEnabled = false
                                deviceView.text = if (deviceId.isNotBlank()) {
                                    "Perangkat terhubung: $deviceId"
                                } else {
                                    "Perangkat terhubung: (lihat daftar perangkat)"
                                }
                                refreshLinkedDevice()
                                Toast.makeText(this@PairingQrActivity, "Pairing berhasil", Toast.LENGTH_LONG).show()
                            }
                            "locked" -> {
                                statusView.text = "Kode dikunci karena terlalu banyak percobaan."
                                cancelButton.isEnabled = false
                                countdown?.cancel()
                            }
                            else -> {
                                statusView.text = "Status sesi: $state"
                                mainHandler.postDelayed(this, POLL_MS)
                            }
                        }
                    }
                }
            }
        }
        pollRunnable = tick
        mainHandler.postDelayed(tick, POLL_MS)
    }

    private fun refreshLinkedDevice() {
        worker.execute {
            val message = try {
                client.createOrRestoreAccountWithSupabase(googleAccessToken)
                val devices = client.listAccountDevices(supabaseUserId)
                val lines = StringBuilder()
                for (i in 0 until devices.length()) {
                    val item = devices.optJSONObject(i) ?: continue
                    val platform = item.optString("platform")
                    if (platform == "android") continue
                    lines.append("• ")
                        .append(item.optString("name"))
                        .append(" (")
                        .append(platform)
                        .append(")\n  ")
                        .append(item.optString("deviceId"))
                        .append('\n')
                }
                if (lines.isEmpty()) "Belum ada desktop terdaftar." else lines.toString().trim()
            } catch (error: Exception) {
                "Gagal memuat perangkat: ${error.message}"
            }
            mainHandler.post { deviceView.text = "Desktop dalam akun:\n$message" }
        }
    }

    private fun cancelSession(reason: String) {
        val codeToCancel = activeCode
        cancelled.set(true)
        stopPolling()
        countdown?.cancel()
        activeCode = ""
        codeView.text = "———"
        countdownView.text = "Dibatalkan"
        statusView.text = reason
        cancelButton.isEnabled = false
        copyButton.isEnabled = false
        qrView.setImageBitmap(null)
        if (codeToCancel.isNotBlank() && supabaseUserId.isNotBlank()) {
            worker.execute {
                runCatching { client.cancelPairingSession(codeToCancel, supabaseUserId) }
            }
        }
    }

    private fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun encodeQr(payload: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun onDestroy() {
        cancelled.set(true)
        stopPolling()
        countdown?.cancel()
        worker.shutdownNow()
        if (::client.isInitialized) client.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SUPABASE_ACCESS_TOKEN = "jarvis_supabase_access_token"
        const val EXTRA_SUPABASE_USER_ID = "jarvis_supabase_user_id"
        private const val POLL_MS = 4000L
    }
}
