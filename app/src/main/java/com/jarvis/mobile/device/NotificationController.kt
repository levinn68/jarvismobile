package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

class NotificationController(private val context: Context) {
    fun openAccessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun list(): JSONObject {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
        return JSONObject().put("ok", enabled)
            .put("requiresUserPermission", !enabled)
            .put("notifications", if (enabled) JarvisNotificationListener.snapshot() else org.json.JSONArray())
    }
}
