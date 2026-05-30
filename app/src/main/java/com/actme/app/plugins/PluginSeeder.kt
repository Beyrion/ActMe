package com.actme.app.plugins

import android.content.Context
import com.actme.app.util.AppLogger
import com.actme.app.data.local.PluginBundleEntity
import com.actme.app.data.local.PluginDao
import org.json.JSONObject

/**
 * Seeds builtin plugins from assets/plugins/ into plugin_bundles DB on first launch.
 *
 * Each plugin lives in a directory (folder-format .actme):
 *   assets/plugins/<id>/
 *     meta.json         — id, name, description, tools, permissions
 *     execute.js        — execute_script
 *     management.html   — management page HTML
 *
 * Subsequent launches are no-ops (checked by pluginId).
 */
object PluginSeeder {
    /** Always overwrites builtin plugins from assets so JS/HTML changes take effect on next launch. */
    suspend fun seedBuiltins(context: Context, pluginDao: PluginDao) {
        val entries = context.assets.list("plugins") ?: return

        entries.forEach { entry ->
            val contents = context.assets.list("plugins/$entry")
            if (contents.isNullOrEmpty()) return@forEach  // skip stray files

            try {
                val metaJson = context.assets.open("plugins/$entry/meta.json")
                    .bufferedReader().readText()
                val meta = JSONObject(metaJson)
                val id = meta.getString("id")

                val executeScript = runCatching {
                    context.assets.open("plugins/$entry/execute.js")
                        .bufferedReader().readText()
                }.getOrElse { "" }

                val managementHtml = runCatching {
                    context.assets.open("plugins/$entry/management.html")
                        .bufferedReader().readText()
                }.getOrElse { "" }

                meta.put("is_builtin", true)
                meta.put("execute_script", executeScript)
                meta.put("management_html", managementHtml)

                // Load cards/<tool>.html → "cards": {"tool_name": "<html fragment>"}
                val cardFiles = runCatching {
                    context.assets.list("plugins/$entry/cards")
                }.getOrNull()
                if (!cardFiles.isNullOrEmpty()) {
                    val cardsObj = org.json.JSONObject()
                    cardFiles.forEach { file ->
                        if (file.endsWith(".html")) {
                            val toolName = file.removeSuffix(".html")
                            val html = runCatching {
                                context.assets.open("plugins/$entry/cards/$file")
                                    .bufferedReader().readText()
                            }.getOrElse { "" }
                            if (html.isNotBlank()) cardsObj.put(toolName, html)
                        }
                    }
                    if (cardsObj.length() > 0) meta.put("cards", cardsObj)
                }

                pluginDao.upsertBundle(
                    PluginBundleEntity(
                        pluginId = id,
                        name = meta.getString("name"),
                        description = meta.getString("description"),
                        bundleJson = meta.toString(),
                        isBuiltin = true,
                        enabled = true
                    )
                )
                AppLogger.i(TAG, "seeded plugin: $id")
            } catch (e: Exception) {
                AppLogger.e(TAG, "failed to seed $entry", e)
            }
        }
    }

    /** Re-seed a single builtin plugin from assets. Returns true if found and upserted. */
    suspend fun seedBuiltin(context: Context, pluginDao: PluginDao, pluginId: String): Boolean {
        val contents = context.assets.list("plugins/$pluginId")
        if (contents.isNullOrEmpty()) return false
        return try {
            val metaJson = context.assets.open("plugins/$pluginId/meta.json")
                .bufferedReader().readText()
            val meta = JSONObject(metaJson)

            val executeScript = runCatching {
                context.assets.open("plugins/$pluginId/execute.js")
                    .bufferedReader().readText()
            }.getOrElse { "" }

            val managementHtml = runCatching {
                context.assets.open("plugins/$pluginId/management.html")
                    .bufferedReader().readText()
            }.getOrElse { "" }

            meta.put("is_builtin", true)
            meta.put("execute_script", executeScript)
            meta.put("management_html", managementHtml)

            val cardFiles = runCatching {
                context.assets.list("plugins/$pluginId/cards")
            }.getOrNull()
            if (!cardFiles.isNullOrEmpty()) {
                val cardsObj = org.json.JSONObject()
                cardFiles.forEach { file ->
                    if (file.endsWith(".html")) {
                        val toolName = file.removeSuffix(".html")
                        val html = runCatching {
                            context.assets.open("plugins/$pluginId/cards/$file")
                                .bufferedReader().readText()
                        }.getOrElse { "" }
                        if (html.isNotBlank()) cardsObj.put(toolName, html)
                    }
                }
                if (cardsObj.length() > 0) meta.put("cards", cardsObj)
            }

            pluginDao.upsertBundle(
                PluginBundleEntity(
                    pluginId = meta.getString("id"),
                    name = meta.getString("name"),
                    description = meta.getString("description"),
                    bundleJson = meta.toString(),
                    isBuiltin = true,
                    enabled = true
                )
            )
            AppLogger.i(TAG, "re-seeded plugin: $pluginId")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to re-seed $pluginId", e)
            false
        }
    }

    /** Clear all plugin_items data and alarms for a plugin. */
    suspend fun clearPluginData(pluginDao: PluginDao, pluginAlarmManager: PluginAlarmManager, pluginId: String) {
        pluginDao.deleteAllItemsForPlugin(pluginId)
        pluginAlarmManager.cancelAll(pluginId)
        AppLogger.i(TAG, "cleared data: $pluginId")
    }

    private const val TAG = "PluginSeeder"
}
