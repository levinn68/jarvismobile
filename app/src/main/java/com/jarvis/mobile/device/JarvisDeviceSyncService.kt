package com.jarvis.mobile.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.R

/**
 * User-started foreground sync service. It polls only while explicitly enabled
 * and is stopped by the user. Android may still restrict background execution.
 */
class JarvisDeviceSyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var client: JarvisDeviceClient
    private lateinit var executor: DeviceCommandExecutor
    private var running = false
    @Volatile private var pollInFlight = false
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            if (!pollInFlight) {
                pollInFlight = true
                client.pollOnce(executor) { message ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
                    pollInFlight = false
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    override fun onCreate() {
        super.onCreate()
        client = JarvisDeviceClient(this)
        executor = DeviceCommandExecutor(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS Device Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSync()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!client.isConfigured()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Device sync is active"))
        if (!running) {
            running = true
            handler.post(poll)
        }
        return START_STICKY
    }
    private fun stopSync() {
        running = false
        pollInFlight = false
        handler.removeCallbacks(poll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }
    private fun notification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS device connection")
            .setContentText(status.take(120))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    override fun onDestroy() { stopSync(); client.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL_ID = "jarvis_device_sync"
        private const val NOTIFICATION_ID = 4110
        private const val POLL_INTERVAL_MS = 15000L
        const val ACTION_START = "com.jarvis.mobile.device.START_SYNC"
        const val ACTION_STOP = "com.jarvis.mobile.device.STOP_SYNC"
        fun start(context: Context) {
            val intent = Intent(context, JarvisDeviceSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, JarvisDeviceSyncService::class.java).setAction(ACTION_STOP)) }
    }
}
