package com.jarvis.mobile.device

import android.content.Context
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.app.ActivityManager
import org.json.JSONObject

class DeviceController(private val context: Context) {
    fun status(): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(memory)
        return JSONObject()
            .put("ok", true)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("lowMemory", memory.lowMemory)
            .put("externalStorageState", Environment.getExternalStorageState())
    }
}
