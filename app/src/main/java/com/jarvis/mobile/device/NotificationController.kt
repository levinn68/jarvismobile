package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

class NotificationController(private val context: Context) {
    fun openAccessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun list(): JSONObject {
        val enabled = DevicePermissionManager(context).isNotificationListenerEnabled()
        return JSONObject()
            .put("ok", enabled)
            .put("requiresUserPermission", !enabled)
            .put("error", if (enabled) JSONObject.NULL else "Aktifkan Notification Access untuk JARVIS di Pengaturan Android.")
            .put("notifications", if (enabled) JarvisNotificationListener.snapshot() else org.json.JSONArray())
            .put("retention", "In-memory only; notification content is not uploaded automatically.")
    }
}