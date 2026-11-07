package com.jarvis.mobile.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Reads a bounded contact list only after Android's READ_CONTACTS permission is granted. */
class ContactController(private val context: Context) {
    fun list(): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("ok", false).put("requiresUserPermission", true)
                .put("error", "Izinkan akses Kontak di Pengaturan Android terlebih dahulu.")
        }
        val rows = JSONArray()
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER)
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, null, null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val phoneCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            while (cursor.moveToNext() && rows.length() < 100) {
                val id = cursor.getString(idCol) ?: continue
                val item = JSONObject().put("name", cursor.getString(nameCol).orEmpty())
                if (cursor.getInt(phoneCol) > 0) {
                    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                        arrayOf(id), null)?.use { phones ->
                        if (phones.moveToFirst()) item.put("phone", phones.getString(0).orEmpty())
                    }
                }
                rows.put(item)
            }
        }
        return JSONObject().put("ok", true).put("contacts", rows).put("count", rows.length())
    }
}
