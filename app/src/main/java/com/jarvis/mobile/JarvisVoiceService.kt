package com.jarvis.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process eligible for an active microphone
 * session while Voice mode is on. Recognition still runs in MainActivity so
 * lifecycle, audio focus, and UI state stay consistent. The user can stop Voice
 * mode from the notification action or from the in-app toggle.
 */
class JarvisVoiceService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS Voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when JARVIS voice mode is active"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Notify the activity (if alive) to tear down recognizer + UI state.
                sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (!startMicrophoneForeground()) return START_NOT_STICKY
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification())
            }
            else -> {
                if (!startMicrophoneForeground()) return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startMicrophoneForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
            stopSelf()
            return false
        }
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: SecurityException) {
            sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.jarvis.mobile.R.drawable.ic_jarvis)
            .setContentTitle("JARVIS is listening")
            .setContentText("Voice mode active. Tap to return, or stop from the action.")
            .setContentIntent(openApp)
            .addAction(0, "Stop Voice", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.action.STOP_VOICE"
        const val ACTION_REFRESH = "com.jarvis.mobile.action.REFRESH_VOICE"
        private const val CHANNEL_ID = "jarvis_voice_session"
        private const val NOTIFICATION_ID = 4107
    }
}
