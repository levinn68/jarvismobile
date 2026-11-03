package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class AppController(private val context: Context) {
    fun listApps(): JSONObject {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = queryLauncherApps(pm, intent)
        val data = JSONArray()
        apps.sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { info ->
            data.put(JSONObject().put("label", info.loadLabel(pm).toString())
                .put("packageName", info.activityInfo.packageName))
        }
        return JSONObject().put("ok", true).put("apps", data).put("count", data.length())
    }

    fun launch(packageName: String): JSONObject {
        if (packageName.isBlank() || packageName.length > 200)
            return JSONObject().put("ok", false).put("error", "A valid packageName is required")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject().put("ok", false).put("error", "App is not installed or has no launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("launchedPackage", packageName)
    }
}

@Suppress("DEPRECATION")
private fun queryLauncherApps(pm: PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else pm.queryIntentActivities(intent, 0)
