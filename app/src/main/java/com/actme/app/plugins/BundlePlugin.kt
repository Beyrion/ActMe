package com.actme.app.plugins

import com.actme.app.util.AppLogger
import com.actme.app.data.local.PluginDao
import com.actme.app.data.local.PluginPermissionEntity
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Generic Plugin implementation that drives execution from a JSON bundle's execute_script
 * via a WebView managed by [PluginRuntimeManager].
 */
class BundlePlugin(
    private val bundleJson: String,
    private val runtimeManager: PluginRuntimeManager,
    private val pluginDao: PluginDao
) : Plugin {
    private val bundle = JSONObject(bundleJson)

    override val id: String = bundle.getString("id")
    override val name: String = bundle.getString("name")
    override val description: String = bundle.getString("description")
    override val isBuiltin: Boolean = bundle.optBoolean("is_builtin", false)

    override val tools: List<ToolDef> by lazy {
        val arr = bundle.optJSONArray("tools") ?: return@lazy emptyList()
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            ToolDef(
                name = t.getString("name"),
                description = t.getString("description"),
                parametersSchema = t.optString("parameters", "{}")
            )
        }
    }

    private val executeScript: String by lazy {
        bundle.optString("execute_script", "")
    }

    private val cards: Map<String, String> by lazy {
        val obj = bundle.optJSONObject("cards") ?: return@lazy emptyMap()
        obj.keys().asSequence().associateWith { key -> obj.getString(key) }
    }

    private suspend fun hasNetworkPermission(): Boolean {
        val perm = pluginDao.getPermission(id, "network")
        return perm?.granted == true
    }

    override suspend fun execute(toolName: String, args: JSONObject): ToolCallResult {
        if (executeScript.isBlank()) {
            return ToolCallResult(false, "execute_script is empty for plugin: $id")
        }
        val hasNetwork = hasNetworkPermission()
        runtimeManager.get(id, executeScript, hasNetwork)   // ensure ready; increments refCount
        return try {
            // JSON is valid JS — only escape backslashes and single-quotes that wrap toolName
            val argsJs = args.toString()
                .replace("\\", "\\\\")
                .replace("</script>", "<\\/script>")

            val callId = "${id}_${System.nanoTime()}"
            val deferred = runtimeManager.registerCall(callId)

            val js = """
                (function(){
                  try {
                    Promise.resolve(execute('$toolName', $argsJs))
                      .then(function(r){ActMeBridge.resolveCall('$callId',JSON.stringify(r));})
                      .catch(function(e){ActMeBridge.resolveCall('$callId','{"success":false,"message":"'+String(e)+'"}');});
                  } catch(e) {
                    ActMeBridge.resolveCall('$callId','{"success":false,"message":"'+String(e)+'"}');
                  }
                })();
            """.trimIndent()
            runtimeManager.evaluateJavascript(id, js)

            val raw = withTimeout(EXECUTE_TIMEOUT_MS) { deferred.await() }
            AppLogger.d(TAG, "$id.$toolName raw JS result: ${raw.take(300)}")
            parseResult(raw)
        } catch (e: Exception) {
            AppLogger.e(TAG, "execute failed: $id.$toolName", e)
            ToolCallResult(false, "执行超时或错误: ${e.message}")
        } finally {
            runtimeManager.release(id)
        }
    }

    private fun parseResult(raw: String): ToolCallResult {
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            return ToolCallResult(false, "结果解析失败: $raw")
        }
        val success = obj.optBoolean("success", false)
        val message = obj.optString("message", "")
        val dataMap = mutableMapOf<String, String>()
        obj.optJSONObject("data")?.let { d ->
            d.keys().forEach { k -> dataMap[k] = d.optString(k) }
        }
        return ToolCallResult(success, message, dataMap)
    }

    override fun getCardHtml(toolName: String, data: Map<String, String>): String? =
        cards[toolName]

    override fun getManagementHtml(): String? =
        bundle.optString("management_html").takeIf { it.isNotBlank() }

    companion object {
        private const val EXECUTE_TIMEOUT_MS = 5_000L
        private const val TAG = "BundlePlugin"
    }
}
