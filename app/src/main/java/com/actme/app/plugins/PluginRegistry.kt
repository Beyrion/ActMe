package com.actme.app.plugins

import com.actme.app.util.AppLogger
import com.actme.app.data.local.PluginDao
import org.json.JSONObject

class PluginRegistry {
    private val plugins = linkedMapOf<String, Plugin>()

    fun register(plugin: Plugin) {
        plugins[plugin.id] = plugin
        AppLogger.i(TAG, "registered: ${plugin.id}")
    }

    fun unregister(pluginId: String) { plugins.remove(pluginId) }

    /** Re-read a single plugin from DB and re-register (for after re-seed). */
    suspend fun reloadPlugin(pluginId: String, pluginDao: PluginDao, runtimeManager: PluginRuntimeManager) {
        runtimeManager.onPluginDisabled(pluginId) // dispose stale WebViews
        val entity = pluginDao.getById(pluginId) ?: return
        register(BundlePlugin(entity.bundleJson, runtimeManager, pluginDao))
        AppLogger.i(TAG, "reloaded: $pluginId")
    }

    /** Load all enabled plugins from DB, wrapping each as BundlePlugin. */
    suspend fun loadFromDb(pluginDao: PluginDao, runtimeManager: PluginRuntimeManager) {
        pluginDao.getEnabledNow().forEach { entity ->
            runCatching {
                register(BundlePlugin(entity.bundleJson, runtimeManager, pluginDao))
            }.onFailure { AppLogger.e(TAG, "load failed: ${entity.pluginId}", it) }
        }
        AppLogger.i(TAG, "loaded ${plugins.size} plugin(s) from DB")
    }

    fun getAll(): List<Plugin> = plugins.values.toList()
    fun get(pluginId: String): Plugin? = plugins[pluginId]

    fun buildSummaryPrompt(): String {
        if (plugins.isEmpty()) return ""
        return buildString {
            appendLine("[可用插件]")
            plugins.values.forEach { p -> appendLine("${p.id}: ${p.name} — ${p.description}") }
        }
    }

    fun buildToolsPrompt(pluginId: String): String {
        val p = plugins[pluginId] ?: return ""
        return buildString {
            appendLine("[${p.id} 工具列表]")
            p.tools.forEach { t ->
                appendLine("  tool: ${t.name}")
                appendLine("  description: ${t.description}")
                appendLine("  parameters: ${t.parametersSchema}")
            }
        }
    }

    fun buildAllToolsPrompt(): String = plugins.keys.joinToString("\n") { buildToolsPrompt(it) }

    suspend fun execute(pluginId: String, toolName: String, args: JSONObject): ToolCallResult {
        val plugin = plugins[pluginId] ?: return ToolCallResult(false, "Plugin 未找到: $pluginId")
        return runCatching { plugin.execute(toolName, args) }
            .getOrElse { e -> AppLogger.e(TAG, "execute: $pluginId.$toolName", e); ToolCallResult(false, "执行出错: ${e.message}") }
    }

    companion object {
        private const val TAG = "PluginRegistry"
    }
}
