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

    fun execute(command: JSONObject): JSONObject {
        val action = command.optString("action")
        val params = command.optJSONObject("params") ?: JSONObject()
        return when (action) {
            "apps.list" -> appController.listApps()
            "apps.launch" -> appController.launch(params.optString("packageName"))
            "system.status" -> deviceController.status()
            "files.list", "files.search", "files.read" ->
                fileController.execute(action, params)
            else -> JSONObject().put("ok", false).put("error", "Unsupported action on this Android connector")
        }
    }
}
