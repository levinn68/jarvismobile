package com.jarvis.mobile.device

import android.content.Context
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.app.ActivityManager
import org.json.JSONArray
import org.json.JSONObject

class DeviceController(private val context: Context) {
    fun status(): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(memory)

        val permissions = DevicePermissionManager(context)
        val states = JSONObject()
            .put("microphone", permissions.isGranted("voice.microphone"))
            .put("camera", permissions.isGranted("camera.capture"))
            .put("contacts", permissions.isGranted("contacts.read"))
            .put("calendar", permissions.isGranted("calendar.read"))
            .put("notifications", permissions.isGranted("notifications.read"))
            .put("screenAccessibility", permissions.isGranted("screen.read"))
            .put("screenControl", permissions.isGranted("screen.control"))
            .put("selectedFolder", permissions.hasUsableSelectedFolder())

        val capabilities = JSONArray().apply {
            permissions.registeredCapabilities().forEach { put(it) }
        }
        val actions = JSONArray().apply {
            listOf(
                "apps.list", "apps.launch", "apps.launch_label", "system.status",
                "notifications.list", "screen.read", "screen.tap", "screen.type",
                "contacts.list", "calendar.list", "files.list", "files.search", "files.read"
            ).forEach { put(it) }
        }

        return JSONObject()
            .put("ok", true)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("lowMemory", memory.lowMemory)
            .put("availableMemoryBytes", memory.availMem)
            .put("totalMemoryBytes", memory.totalMem)
            .put("externalStorageState", Environment.getExternalStorageState())
            .put("permissions", states)
            .put("registeredCapabilities", capabilities)
            .put("supportedActions", actions)
            .put("accessModel", "Android permissions and user-approved actions; no root or permission bypass")
    }
}
