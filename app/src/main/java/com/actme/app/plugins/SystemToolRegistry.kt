package com.actme.app.plugins

import com.actme.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SystemToolDef(
    val name: String,
    val description: String,
    val parametersSchema: String
)

class SystemToolRegistry {

    val tools: List<SystemToolDef>
        get() = listOf(
            SystemToolDef(
                name = "get_current_info",
                description = "获取当前时间、日期、时区、星期等基本信息，无需参数",
                parametersSchema = """{"type":"object","properties":{},"required":[]}"""
            ),
            SystemToolDef(
                name = "web_search",
                description = "联网搜索最新信息，传入搜索关键词返回结果摘要",
                parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}"""
            )
        )

    fun buildPrompt(): String {
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("[系统工具]")
            appendLine("  系统工具使用 plugin=\"system\"，参数格式已在下方列出，可直接调用无需 plugin_queries。")
            tools.forEach { t ->
                appendLine("  tool: ${t.name}")
                appendLine("  description: ${t.description}")
                appendLine("  parameters: ${t.parametersSchema}")
            }
        }
    }

    fun getDisplayName(): String = "系统工具"

    suspend fun execute(toolName: String, args: JSONObject): ToolCallResult {
        return when (toolName) {
            "get_current_info" -> executeGetCurrentInfo()
            "web_search" -> executeWebSearch(args.optString("query", ""))
            else -> ToolCallResult(false, "未知系统工具: $toolName")
        }
    }

    private fun executeGetCurrentInfo(): ToolCallResult {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val nowLocal = now.atZone(zone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dayOfWeek = nowLocal.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL, java.util.Locale.CHINESE
        )

        val timestampMs = now.toEpochMilli()
        val info = buildString {
            appendLine("当前时间: ${nowLocal.format(formatter)}")
            appendLine("时区: ${zone.id}")
            appendLine("星期: $dayOfWeek")
            appendLine("Unix时间戳(毫秒): $timestampMs")
        }

        return ToolCallResult(
            success = true,
            message = info.trim(),
            data = mapOf(
                "current_time" to nowLocal.format(formatter),
                "timezone" to zone.id,
                "day_of_week" to dayOfWeek,
                "timestamp_ms" to timestampMs.toString()
            )
        )
    }

    private suspend fun executeWebSearch(query: String): ToolCallResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext ToolCallResult(false, "搜索关键词不能为空")
        }

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://lite.duckduckgo.com/lite/?q=$encodedQuery")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            )
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val response = connection.inputStream.bufferedReader().readText()

            val textOnly = response
                .replace(Regex("<[^>]+>"), "\n")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("&quot;"), "\"")
                .replace(Regex("&#[0-9]+;"), "")
                .replace(Regex("\n\\s*\n+"), "\n")
                .trim()
                .take(2000)

            ToolCallResult(
                success = true,
                message = textOnly.ifBlank { "未找到搜索结果" },
                data = mapOf("query" to query)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "web_search failed: ${e.message}")
            ToolCallResult(false, "搜索失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SystemToolRegistry"
    }
}
