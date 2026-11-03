package com.jarvis.mobile.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Reports permission state. It never bypasses Android permission prompts or system settings. */
class DevicePermissionManager(private val context: Context) {
    fun requiredFor(capability: String): List<String> = when (capability) {
        "camera.capture" -> listOf(Manifest.permission.CAMERA)
        "voice.microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
        "notifications.post" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "notifications.read" -> emptyList() // Requires a user-enabled Notification Listener service.
        else -> emptyList()
    }
    fun isGranted(capability: String): Boolean {
        if (capability == "files.read" || capability == "files.write")
            return context.getSharedPreferences(FileController.PREFS, Context.MODE_PRIVATE)
                .getString(FileController.TREE_URI, null) != null
        if (capability == "notifications.read") return false
        return requiredFor(capability).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    fun missingPermissions(capability: String): List<String> =
        requiredFor(capability).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
