package com.jarvis.mobile.device

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Reports capabilities that are both implemented and currently usable.
 * It never requests, bypasses, or silently elevates Android permissions.
 */
class DevicePermissionManager(private val context: Context) {
    fun requiredFor(capability: String): List<String> = when (capability) {
        "camera.capture" -> listOf(Manifest.permission.CAMERA)
        "contacts.read" -> listOf(Manifest.permission.READ_CONTACTS)
        "calendar.read" -> listOf(Manifest.permission.READ_CALENDAR)
        "voice.microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
        "notifications.post" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "notifications.read" -> emptyList()
        else -> emptyList()
    }

    fun isGranted(capability: String): Boolean = when (capability) {
        "files.read" -> hasUsableSelectedFolder(requireWrite = false)
        // JARVIS does not currently implement a remote file-write action.
        "files.write" -> false
        "notifications.read" -> isNotificationListenerEnabled()
        "screen.read", "screen.control" -> isAccessibilityEnabled() && JarvisAccessibilityService.isActive()
        "camera.capture" -> false // Permission alone is not an implemented capture workflow.
        else -> requiredFor(capability).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun missingPermissions(capability: String): List<String> =
        requiredFor(capability).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasUsableSelectedFolder(requireWrite: Boolean = false): Boolean {
        val raw = context.getSharedPreferences(FileController.PREFS, Context.MODE_PRIVATE)
            .getString(FileController.TREE_URI, null) ?: return false
        return try {
            val uri = Uri.parse(raw)
            val persisted = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && (!requireWrite || it.isWritePermission)
            }
            val documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                uri, android.provider.DocumentsContract.getTreeDocumentId(uri)
            )
            val documentExists = context.contentResolver.query(
                documentUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
            persisted && documentExists
        } catch (_: Exception) {
            false
        }
    }

    fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val component = ComponentName(context, JarvisNotificationListener::class.java).flattenToString()
        return enabledListeners.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        val component = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        val services = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled && services.split(':').any { it.equals(component, ignoreCase = true) }
    }

    /** Only advertise actions with an implemented execution path and currently valid grants. */
    fun registeredCapabilities(): List<String> = buildList {
        add("apps.list")
        add("apps.launch")
        add("system.read")
        if (isGranted("files.read")) add("files.read")
        if (isGranted("notifications.read")) add("notifications.read")
        if (isGranted("screen.read")) {
            add("screen.read")
            add("screen.control")
        }
        if (isGranted("contacts.read")) add("contacts.read")
        if (isGranted("calendar.read")) add("calendar.read")
    }

    fun registeredActions(): List<String> = buildList {
        addAll(listOf("apps.list", "apps.launch", "apps.launch_label", "system.status"))
        if (isGranted("files.read")) addAll(listOf("files.list", "files.search", "files.read"))
        if (isGranted("notifications.read")) add("notifications.list")
        if (isGranted("screen.read")) addAll(listOf("screen.read", "screen.tap", "screen.type"))
        if (isGranted("contacts.read")) add("contacts.list")
        if (isGranted("calendar.read")) add("calendar.list")
    }

    fun permissionReport(): Map<String, Boolean> = linkedMapOf(
        "microphone" to isGranted("voice.microphone"),
        "cameraPermissionGranted" to (missingPermissions("camera.capture").isEmpty()),
        "cameraCaptureImplemented" to false,
        "contacts" to isGranted("contacts.read"),
        "calendar" to isGranted("calendar.read"),
        "notificationsRead" to isGranted("notifications.read"),
        "screenAccessibilityEnabled" to isAccessibilityEnabled(),
        "screenServiceConnected" to JarvisAccessibilityService.isActive(),
        "screenReadAndControl" to isGranted("screen.read"),
        "selectedFolderRead" to hasUsableSelectedFolder(),
        "selectedFolderWrite" to hasUsableSelectedFolder(requireWrite = true)
    )
}
