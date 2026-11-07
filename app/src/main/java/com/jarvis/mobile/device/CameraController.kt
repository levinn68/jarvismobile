package com.jarvis.mobile.device

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import org.json.JSONObject

/** Opens Android's system camera UI; the user controls capture and must confirm in the camera app. */
class CameraController(private val context: Context) {
    fun captureIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }
    fun describeResult(resultCode: Int, data: Intent?): JSONObject {
        if (resultCode != android.app.Activity.RESULT_OK)
            return JSONObject().put("ok", false).put("cancelled", true)
        val thumbnail = data?.extras?.get("data") as? android.graphics.Bitmap
            ?: return JSONObject().put("ok", false).put("error", "Camera returned no preview image")
        return JSONObject().put("ok", true).put("width", thumbnail.width).put("height", thumbnail.height)
            .put("note", "Preview image returned by the system camera. Full-resolution capture requires a user-approved output URI.")
    }
}
