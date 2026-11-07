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

    fun launchByLabel(appName: String): JSONObject {
        val query = appName.trim()
        if (query.isBlank() || query.length > 100)
            return JSONObject().put("ok", false).put("error", "Nama aplikasi harus 1-100 karakter")
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = queryLauncherApps(pm, intent)
        val exact = apps.filter { it.loadLabel(pm).toString().equals(query, ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else apps.filter {
            it.loadLabel(pm).toString().contains(query, ignoreCase = true)
        }
        if (matches.size != 1) {
            val options = JSONArray()
            matches.take(8).forEach { info ->
                options.put(JSONObject().put("label", info.loadLabel(pm).toString())
                    .put("packageName", info.activityInfo.packageName))
            }
            return JSONObject().put("ok", false)
                .put("error", if (matches.isEmpty()) "Aplikasi tidak ditemukan" else "Nama aplikasi cocok dengan beberapa aplikasi; sebutkan nama lebih spesifik")
                .put("matches", options)
        }
        return launch(matches.first().activityInfo.packageName)
    }

    fun launch(packageName: String): JSONObject {
        val clean = packageName.trim()
        if (clean.isBlank() || clean.length > 200 || !PACKAGE_NAME_REGEX.matches(clean))
            return JSONObject().put("ok", false).put("error", "A valid packageName is required")
        val intent = context.packageManager.getLaunchIntentForPackage(clean)
            ?: return JSONObject().put("ok", false).put("error", "App is not installed or has no launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("launchedPackage", clean)
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

@Suppress("DEPRECATION")
private fun queryLauncherApps(pm: PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else pm.queryIntentActivities(intent, 0)
