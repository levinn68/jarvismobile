package com.jarvis.mobile.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var connectivityManager: ConnectivityManager? = null
    @Volatile private var running = false
    @Volatile private var pollInFlight = false
    @Volatile private var consecutiveFailures = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!running) return
            consecutiveFailures = 0
            handler.post {
                if (running && !pollInFlight) {
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            if (!pollInFlight) {
                pollInFlight = true
                val wakeLock = try {
                    getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:DeviceSyncPoll")
                        ?.apply {
                            setReferenceCounted(false)
                            acquire(30_000L)
                        }
                } catch (_: Exception) {
                    null
                }
                try {
                    client.pollOnce(executor) { message ->
                        try {
                            if (wakeLock?.isHeld == true) wakeLock.release()
                        } catch (_: Exception) {}
                        pollInFlight = false
                        val isError = message.startsWith("Sync error:") || message.startsWith("Device sync failed:")
                        if (isError) {
                            consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_STEPS)
                        } else {
                            consecutiveFailures = 0
                        }
                        if (running && (message.contains("HTTP 401") || message.contains("HTTP 404"))) {
                            handler.post {
                                if (running) {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, notification("Perangkat dicabut atau sesi tidak berlaku. Sinkronisasi dihentikan."))
                                    stopSync()
                                    stopSelf()
                                }
                            }
                        } else if (running) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(message))
                        }
                    }
                } catch (error: Exception) {
                    try {
                        if (wakeLock?.isHeld == true) wakeLock.release()
                    } catch (_: Exception) {}
                    pollInFlight = false
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_STEPS)
                    if (running) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification("Device sync failed: ${error.message}"))
                    }
                }
            }
            if (running) handler.postDelayed(this, nextPollDelayMs(consecutiveFailures))
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = JarvisDeviceClient(this)
        executor = DeviceCommandExecutor(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS Device Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // Ignore if network callback registration is restricted.
            }
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
        try {
            val statusNotification = notification("Device sync is active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, statusNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, statusNotification)
            }
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            consecutiveFailures = 0
            handler.post(poll)
        }
        return START_STICKY
    }

    private fun stopSync() {
        running = false
        handler.removeCallbacks(poll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

    override fun onDestroy() {
        stopSync()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
        if (::client.isInitialized) client.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "jarvis_device_sync"
        private const val NOTIFICATION_ID = 4110
        const val POLL_INTERVAL_MS = 15000L
        const val MAX_POLL_INTERVAL_MS = 120000L
        private const val MAX_BACKOFF_STEPS = 3
        const val ACTION_START = "com.jarvis.mobile.device.START_SYNC"
        const val ACTION_STOP = "com.jarvis.mobile.device.STOP_SYNC"

        fun nextPollDelayMs(failures: Int): Long {
            val clamped = failures.coerceIn(0, MAX_BACKOFF_STEPS)
            return (POLL_INTERVAL_MS * (1L shl clamped)).coerceAtMost(MAX_POLL_INTERVAL_MS)
        }

        fun start(context: Context) {
            val intent = Intent(context, JarvisDeviceSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JarvisDeviceSyncService::class.java).setAction(ACTION_STOP))
        }
    }
}