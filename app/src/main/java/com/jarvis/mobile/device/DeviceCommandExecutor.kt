package com.jarvis.mobile.device

import android.content.Context
import org.json.JSONObject

/**
 * Executes only known, typed commands. OS permissions and Android user consent remain authoritative.
 */
class DeviceCommandExecutor(context: Context) {
    private val appController = AppController(context)
    private val fileController = FileController(context)
    private val deviceController = DeviceController(context)
    private val notificationController = NotificationController(context)
    private val cameraController = CameraController(context)
    private val contactController = ContactController(context)
    private val calendarController = CalendarController(context)

    fun execute(command: JSONObject): JSONObject {
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        return when (action) {
            "apps.list" -> appController.listApps()
            "apps.launch" -> appController.launch(params.optString("packageName"))
            "apps.launch_label" -> appController.launchByLabel(params.optString("appName"))
            "system.status" -> deviceController.status()
            "notifications.list" -> notificationController.list()
            "screen.read" -> JarvisAccessibilityService.snapshot()
            "contacts.list" -> contactController.list()
            "calendar.list" -> calendarController.list()
            "camera.capture" -> JSONObject().put("ok", false).put("requiresForegroundUi", true).put("error", "Camera capture must be launched from an Activity after a visible user confirmation")
            "files.list", "files.search", "files.read" ->
                fileController.execute(action, params)
            else -> JSONObject().put("ok", false).put("error", "Unsupported action on this Android connector")
        }
    }
}
