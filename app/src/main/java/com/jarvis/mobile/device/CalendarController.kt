package com.jarvis.mobile.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Reads a bounded 30-day calendar window only after Android's READ_CALENDAR permission is granted. */
class CalendarController(private val context: Context) {
    fun list(): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("ok", false).put("requiresUserPermission", true)
                .put("error", "Izinkan akses Kalender di Pengaturan Android terlebih dahulu.")
        }
        val begin = System.currentTimeMillis()
        val end = begin + 30L * 24L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString()).appendPath(end.toString()).build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION)
        val events = JSONArray()
        context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locationCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            while (cursor.moveToNext() && events.length() < 50) {
                events.put(JSONObject().put("title", cursor.getString(titleCol).orEmpty())
                    .put("begin", cursor.getLong(beginCol)).put("end", cursor.getLong(endCol))
                    .put("location", if (cursor.isNull(locationCol)) "" else cursor.getString(locationCol).orEmpty()))
            }
        }
        return JSONObject().put("ok", true).put("events", events).put("count", events.length())
            .put("windowDays", 30)
    }
}