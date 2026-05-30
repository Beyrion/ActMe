package com.actme.app.plugins

import android.webkit.JavascriptInterface
import com.actme.app.util.AppLogger
import com.actme.app.data.local.PluginDao
import com.actme.app.data.local.PluginItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * @JavascriptInterface bridge between plugin WebViews and Android platform capabilities.
 *
 * Generic primitives only — no plugin-specific business logic.
 *
 * Exposed via actme-bridge.js as:
 *   ActMe.storage.{getAll, get, set, delete}
 *   ActMe.alarm.{set, cancel}
 *   ActMe.permission.isGranted
 *   ActMe.execute()          — management page only, routes to execute_script tools
 *   ActMe.notify()
 *   ActMe.back()
 */
class PluginBridge(
    private val pluginId: String,
    private val pluginDao: PluginDao,
    private val pluginAlarmManager: PluginAlarmManager,
    private val onExecuteTool: (suspend (String, JSONObject) -> ToolCallResult)? = null,
    private val onBack: () -> Unit = {},
    private val onResolveCall: ((callId: String, resultJson: String) -> Unit)? = null
) {
    // ── storage ──────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun storageGetAll(): String = runBlocking(Dispatchers.IO) {
        try {
            val items = pluginDao.getItemsForPlugin(pluginId)
            JSONArray().also { arr ->
                items.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("key", item.itemKey)
                        put("data", runCatching { JSONObject(item.dataJson) }.getOrDefault(JSONObject()))
                    })
                }
            }.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "storageGetAll: plugin=$pluginId", e); "[]"
        }
    }

    @JavascriptInterface
    fun storageGet(key: String): String = runBlocking(Dispatchers.IO) {
        try {
            val item = pluginDao.getItemsForPlugin(pluginId).firstOrNull { it.itemKey == key }
            item?.dataJson ?: "null"
        } catch (e: Exception) {
            AppLogger.e(TAG, "storageGet: plugin=$pluginId key=$key", e); "null"
        }
    }

    @JavascriptInterface
    fun storageSet(key: String, dataJson: String): Boolean = runBlocking(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            pluginDao.upsertItem(PluginItemEntity(pluginId, key, dataJson, now, now))
            true
        }.getOrElse { AppLogger.e(TAG, "storageSet: plugin=$pluginId key=$key", it); false }
    }

    @JavascriptInterface
    fun storageDelete(key: String): Boolean = runBlocking(Dispatchers.IO) {
        runCatching { pluginDao.deleteItem(pluginId, key); true }
            .getOrElse { AppLogger.e(TAG, "storageDelete: plugin=$pluginId key=$key", it); false }
    }

    // ── alarm ─────────────────────────────────────────────────────────────────

    /**
     * Schedule a one-shot or recurring alarm.
     * @param repeatJson JSON: {"type":"NONE"|"DAILY"|"WEEKLY"|"MONTHLY","time":"HH:mm","days":[1,3],"day":15}
     */
    @JavascriptInterface
    fun alarmSet(key: String, triggerMs: Long, title: String, body: String, repeatJson: String): Boolean =
        runBlocking {
            runCatching {
                pluginAlarmManager.schedule(pluginId, key, triggerMs, title, body, repeatJson)
                true
            }.getOrElse { AppLogger.e(TAG, "alarmSet: plugin=$pluginId key=$key", it); false }
        }

    @JavascriptInterface
    fun alarmCancel(key: String): Boolean = runBlocking {
        runCatching {
            pluginAlarmManager.cancel(pluginId, key)
            true
        }.getOrElse { AppLogger.e(TAG, "alarmCancel: plugin=$pluginId key=$key", it); false }
    }

    // ── execute (management page → execute_script) ───────────────────────────

    @JavascriptInterface
    fun execute(toolName: String, argsJson: String): String = runBlocking {
        val handler = onExecuteTool ?: return@runBlocking errorJson("execute not available on this page")
        try {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val r = handler(toolName, args)
            JSONObject().apply {
                put("success", r.success); put("message", r.message)
                val d = JSONObject(); r.data.forEach { (k, v) -> d.put(k, v) }; put("data", d)
            }.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "execute: plugin=$pluginId tool=$toolName", e); errorJson(e.message ?: "error")
        }
    }

    // ── permission / notify / back / resolveCall ──────────────────────────────

    @JavascriptInterface
    fun permissionIsGranted(permissionId: String): Boolean = runBlocking(Dispatchers.IO) {
        pluginDao.getPermission(pluginId, permissionId)?.granted == true
    }

    @JavascriptInterface
    fun notify(title: String, body: String) { AppLogger.i(TAG, "notify: plugin=$pluginId title=$title") }

    @JavascriptInterface
    fun back() { onBack() }

    @JavascriptInterface
    fun resolveCall(callId: String, resultJson: String) {
        onResolveCall?.invoke(callId, resultJson)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun errorJson(msg: String) = """{"success":false,"message":"$msg","data":{}}"""

    companion object {
        const val JS_INTERFACE_NAME = "ActMeBridge"
        private const val TAG = "PluginBridge"
    }
}
