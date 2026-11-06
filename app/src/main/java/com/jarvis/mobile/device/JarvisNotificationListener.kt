package com.jarvis.mobile.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android only binds this service after the user explicitly enables Notification Access in Settings.
 * Notification contents are kept in memory and are not uploaded automatically.
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(lock) {
            val key = sbn.key ?: "${sbn.packageName}:${sbn.id}"
            removeByKeyLocked(key)
            val item = JSONObject()
                .put("key", key)
                .put("packageName", sbn.packageName)
                .put("postTime", sbn.postTime)
                .put("title", sbn.notification.extras.getCharSequence("android.title")?.toString()?.take(160) ?: "")
                .put("text", sbn.notification.extras.getCharSequence("android.text")?.toString()?.take(500) ?: "")
            recent.put(item)
            while (recent.length() > 50) recent.remove(0)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(lock) {
            val key = sbn.key ?: "${sbn.packageName}:${sbn.id}"
            removeByKeyLocked(key)
        }
    }

    companion object {
        private val lock = Any()
        private val recent = JSONArray()

        private fun removeByKeyLocked(key: String) {
            for (i in recent.length() - 1 downTo 0) {
                if (recent.optJSONObject(i)?.optString("key") == key) {
                    recent.remove(i)
                }
            }
        }

        fun snapshot(): JSONArray = synchronized(lock) {
            JSONArray().also { copy ->
                for (i in 0 until recent.length()) {
                    val src = recent.optJSONObject(i) ?: continue
                    copy.put(
                        JSONObject()
                            .put("packageName", src.optString("packageName"))
                            .put("postTime", src.optLong("postTime"))
                            .put("title", src.optString("title"))
                            .put("text", src.optString("text"))
                    )
                }
            }
        }

        fun clear() = synchronized(lock) { while (recent.length() > 0) recent.remove(0) }
    }
}