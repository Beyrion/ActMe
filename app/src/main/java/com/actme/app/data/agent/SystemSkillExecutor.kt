package com.actme.app.data.agent

import com.actme.app.util.AppLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Executes system-level skills that the Agent can request via system_calls.
 *
 * Supported types:
 * - get_current_time — returns formatted current time with timezone info
 * - web_search     — performs DuckDuckGo HTML search and returns top results
 */
object SystemSkillExecutor {

    private const val TAG = "SystemSkillExecutor"
    private const val MAX_SEARCH_RESULTS = 5

    /**
     * Execute a list of system calls and return a formatted result string
     * to inject back into the system prompt.
     */
    suspend fun execute(calls: List<SystemCall>): String {
        val results = mutableListOf<String>()

        for (call in calls) {
            val result = executeOne(call)
            if (result != null) {
                results.add(result)
            }
        }

        return results.joinToString("\n---\n")
    }

    private fun executeOne(call: SystemCall): String? {
        return when (call.type) {
            "get_current_time" -> executeGetCurrentTime()
            "web_search" -> executeWebSearch(call.query)
            else -> {
                AppLogger.w(TAG, "Unknown system call type: ${call.type}")
                null
            }
        }
    }

    // ---- get_current_time ----

    private fun executeGetCurrentTime(): String {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dayOfWeek = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val timestampMs = now.atZone(zone).toInstant().toEpochMilli()

        val result = buildString {
            appendLine("【当前时间】")
            appendLine("日期时间: $formatted ($dayOfWeek)")
            appendLine("时区: ${zone.id}")
            appendLine("Unix毫秒时间戳: $timestampMs")
        }

        AppLogger.i(TAG, "get_current_time: $formatted ($dayOfWeek)")
        return result
    }

    // ---- web_search ----

    private fun executeWebSearch(query: String): String {
        if (query.isBlank()) return "【搜索错误】查询关键词为空"

        AppLogger.i(TAG, "web_search: query=$query")

        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encodedQuery")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }

            val html = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                reader.readText()
            }

            connection.disconnect()

            val results = parseDuckDuckGoHtml(html)
            if (results.isEmpty()) {
                "【搜索结果】未找到与「$query」相关的结果。"
            } else {
                buildString {
                    appendLine("【联网搜索结果：$query】")
                    results.forEachIndexed { index, (title, snippet) ->
                        appendLine("${index + 1}. $title")
                        appendLine("   $snippet")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "web_search failed: ${e.message}")
            "【搜索错误】搜索「$query」时发生网络错误: ${e.message}。请基于已有知识回答。"
        }
    }

    /**
     * Parse DuckDuckGo HTML search results.
     * Extracts title + snippet from result__body elements.
     */
    private fun parseDuckDuckGoHtml(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // Extract result blocks: each result has a class="result__body"
        val bodyStart = "<div class=\"result__body\">"
        val bodyEnd = "</div>"
        val linkClass = "result__a"
        val snippetClass = "result__snippet"

        var searchFrom = 0
        while (results.size < MAX_SEARCH_RESULTS) {
            val bodyIdx = html.indexOf(bodyStart, searchFrom)
            if (bodyIdx == -1) break

            val bodyContentEnd = findClosingDiv(html, bodyIdx + bodyStart.length)
            if (bodyContentEnd == -1) {
                searchFrom = bodyIdx + bodyStart.length
                continue
            }

            val bodyHtml = html.substring(bodyIdx, bodyContentEnd)
            val title = extractDdTag(bodyHtml, "a", "result__a") ?: extractDdTag(bodyHtml, "a", null)
            val snippet = extractDdTag(bodyHtml, "a", "result__snippet")

            if (!title.isNullOrBlank()) {
                val cleanTitle = stripHtml(title)
                val cleanSnippet = if (!snippet.isNullOrBlank()) stripHtml(snippet) else ""
                if (cleanTitle.isNotBlank()) {
                    results.add(cleanTitle to cleanSnippet)
                }
            }

            searchFrom = bodyContentEnd + bodyEnd.length
        }

        return results
    }

    private fun findClosingDiv(html: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < html.length - 5 && depth > 0) {
            if (html.startsWith("<div", i, ignoreCase = true)) {
                // Only count <div (not </div)
                if (html[i + 4] == ' ' || html[i + 4] == '>' || html[i + 4] == '\t') {
                    depth++
                }
            } else if (html.startsWith("</div>", i, ignoreCase = true)) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun extractDdTag(html: String, tag: String, className: String?): String? {
        val classAttr = if (className != null) "class=\"$className\"" else ""
        val openTag = if (className != null) "<$tag $classAttr" else "<$tag"

        val startIdx = html.indexOf(openTag)
        if (startIdx == -1) return null

        // Find end of opening tag
        val tagEnd = html.indexOf('>', startIdx)
        if (tagEnd == -1) return null

        val closeTag = "</$tag>"
        val endIdx = html.indexOf(closeTag, tagEnd)
        if (endIdx == -1) return null

        return html.substring(tagEnd + 1, endIdx)
    }

    private fun stripHtml(text: String): String {
        return text
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
