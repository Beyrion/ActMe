package com.actme.app.data.agent

import com.actme.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.CookieHandler
import java.util.zip.GZIPInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object SystemSkillExecutor {

    private const val TAG = "SystemSkillExecutor"
    private const val MAX_SEARCH_RESULTS = 5

    private val USER_AGENT = "Mozilla/5.0"

    private val cookieManager: CookieManager by lazy {
        CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
    }

    @Volatile private var cookieHandlerInstalled = false

    private fun ensureCookieHandler() {
        if (!cookieHandlerInstalled) {
            synchronized(this) {
                if (!cookieHandlerInstalled) {
                    CookieHandler.setDefault(cookieManager)
                    cookieHandlerInstalled = true
                }
            }
        }
    }

    @Volatile private var bingWarmedUp = false

    private suspend fun warmUpBing() {
        if (bingWarmedUp) return
        try {
            withTimeoutOrNull(5_000L) {
                withContext(Dispatchers.IO) {
                    ensureCookieHandler()
                    val url = URL("https://cn.bing.com/")
                    AppLogger.i(TAG, "BING-WARMUP: GET cn.bing.com/")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000; readTimeout = 5_000
                        setBrowserHeaders()
                    }
                    val code = conn.responseCode
                    AppLogger.i(TAG, "BING-WARMUP: HTTP " + code)
                    conn.inputStream.use { it.read() }
                    conn.disconnect()
                }
            }
            bingWarmedUp = true
            val cookies = cookieManager.cookieStore.cookies
            AppLogger.i(TAG, "BING-WARMUP: OK, cookies=" + cookies.size)
            for (c in cookies) {
                AppLogger.i(TAG, "BING-COOKIE: " + c.name + "=" + c.value.take(40) + " domain=" + c.domain + " secure=" + c.secure)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "BING-WARMUP: FAIL " + e.message)
        }
    }

    // ---- Backends ----

    private data class SearchBackend(
        val name: String,
        val timeoutMs: Long,
        val execute: suspend (String) -> SearchResponse?
    )

    private data class SearchResponse(
        val requestUrl: String,
        val results: List<Pair<String, String>>
    )

    private val SEARCH_BACKENDS = listOf(
        SearchBackend("Bing Gecko",    12_000) { q -> searchBingGecko(q) },
        SearchBackend("Bing",           8_000) { q -> searchBingHtml(q) },
        SearchBackend("DuckDuckGo API",  5_000) { q -> searchDuckDuckGoApi(q) },
        SearchBackend("DuckDuckGo HTML", 8_000) { q -> searchDuckDuckGoHtml(q) },
        SearchBackend("Baidu",          8_000) { q -> searchBaiduHtml(q) },
        SearchBackend("SearXNG",         8_000) { q -> searchSearXngPublic(q) },
    )

    data class ToolStepEvent(
        val type: Type,
        val title: String,
        val detail: String = ""
    ) {
        enum class Type { STARTED, FINISHED, FAILED }
    }

    // ----------------------------------------------------------------

    suspend fun execute(
        calls: List<SystemCall>,
        onStep: suspend (ToolStepEvent) -> Unit = {}
    ): String {
        val results = mutableListOf<String>()
        for (call in calls) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val r = executeOne(call, onStep)
            if (r != null) results.add(r)
        }
        return results.joinToString("\n---\n")
    }

    private suspend fun executeOne(
        call: SystemCall,
        onStep: suspend (ToolStepEvent) -> Unit
    ): String? {
        val title = toolTitle(call)
        val detail = toolDetail(call)
        onStep(ToolStepEvent(ToolStepEvent.Type.STARTED, title, detail))
        return try {
            val result = when (call.type) {
                "get_current_time" -> executeGetCurrentTime()
                "web_search" -> executeWebSearch(call.query)
                "browse_url", "browser_url", "web_browse", "open_url" -> executeBrowseUrl(call.url.ifBlank { call.query })
                "python_exec", "run_python", "python" -> executePython(call)
                "adb_shell", "adb", "run_adb" -> executeAdbShell(call)
                else -> {
                    AppLogger.w(TAG, "Unknown call: " + call.type)
                    "[TOOL_ERROR] Unknown system call: ${call.type}"
                }
            }
            onStep(ToolStepEvent(ToolStepEvent.Type.FINISHED, title, summarizeToolResult(result)))
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            onStep(ToolStepEvent(ToolStepEvent.Type.FAILED, title, message))
            "[TOOL_ERROR] $title failed: $message"
        }
    }

    private fun toolTitle(call: SystemCall): String {
        if (call.type == "python_exec" || call.type == "run_python" || call.type == "python") {
            return "Run Python"
        }
        if (call.type == "adb_shell" || call.type == "adb" || call.type == "run_adb") {
            return "Run ADB"
        }
        return when (call.type) {
            "get_current_time" -> "获取当前时间"
            "web_search" -> "联网搜索"
            "browse_url", "browser_url", "web_browse", "open_url" -> "打开网页"
            else -> "执行系统技能"
        }
    }

    private fun toolDetail(call: SystemCall): String {
        if (call.type == "python_exec" || call.type == "run_python" || call.type == "python") {
            return call.code.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "python_exec" }.take(240)
        }
        if (call.type == "adb_shell" || call.type == "adb" || call.type == "run_adb") {
            return adbCommand(call).take(240)
        }
        return when (call.type) {
            "web_search" -> call.query
            "browse_url", "browser_url", "web_browse", "open_url" -> call.url.ifBlank { call.query }
            else -> call.type
        }.take(240)
    }

    private fun summarizeToolResult(result: String): String {
        if (result.contains("[PYTHON_RESULT]")) return "Python completed, ${result.length} chars"
        if (result.contains("[PYTHON_ERROR]")) return "Python failed"
        if (result.contains("[ADB_RESULT]")) return "ADB completed, ${result.length} chars"
        if (result.contains("[ADB_ERROR]")) return "ADB failed"
        return when {
            result.contains("[BROWSE_RESULT]") -> "网页内容已读取，${result.length} 字符"
            result.contains("[BROWSE_ERROR]") -> "网页读取失败"
            result.contains("联网搜索") || result.contains("SEARCH") -> "搜索完成，${result.length} 字符"
            result.contains("错误") || result.contains("[TOOL_ERROR]") -> "执行失败"
            else -> "完成，${result.length} 字符"
        }
    }

    // ---- get_current_time ----

    private fun executeGetCurrentTime(): String {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val f = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dow = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val ts = now.atZone(zone).toInstant().toEpochMilli()
        AppLogger.i(TAG, "TIME: " + f + " (" + dow + ")")
        return "【当前时间】\n日期时间: " + f + " (" + dow + ")\n时区: " + zone.id + "\nUnix毫秒时间戳: " + ts
    }

    // ---- python_exec ----

    private suspend fun executePython(call: SystemCall): String {
        val code = call.code.ifBlank { call.query }
        if (code.isBlank()) return "[PYTHON_ERROR] Empty Python code."
        AppLogger.i(TAG, "PYTHON-EXEC: chars=${code.length}, inputChars=${call.input.length}, timeoutMs=${call.timeoutMs}")
        val result = PythonSkillEngine.execute(code, call.input, call.timeoutMs)
        return buildString {
            appendLine(if (result.ok) "[PYTHON_RESULT]" else "[PYTHON_ERROR]")
            appendLine("elapsed_ms: ${result.elapsed_ms}")
            if (result.stdout.isNotBlank()) {
                appendLine("stdout:")
                appendLine(result.stdout.trimEnd())
            }
            if (result.result != null) {
                appendLine("result:")
                appendLine(result.result.toString())
            }
            if (result.stderr.isNotBlank()) {
                appendLine("stderr:")
                appendLine(result.stderr.trimEnd())
            }
            if (result.error.isNotBlank()) {
                appendLine("error:")
                appendLine(result.error.trimEnd())
            }
        }.trim()
    }

    // ---- adb_shell ----

    private suspend fun executeAdbShell(call: SystemCall): String {
        val command = adbCommand(call)
        if (command.isBlank()) return "[ADB_ERROR] Empty adb shell command."
        val timeoutMs = call.timeoutMs.coerceIn(1_000L, 60_000L)
        AppLogger.i(TAG, "ADB-EXEC: command=${command.take(160)}, timeoutMs=$timeoutMs")
        val result = AdbSkillEngine.shell(command, timeoutMs = timeoutMs)
        return buildString {
            appendLine(if (result.ok) "[ADB_RESULT]" else "[ADB_ERROR]")
            result.exitCode?.let { appendLine("exit_code: $it") }
            if (result.output.isNotBlank()) {
                appendLine("stdout:")
                appendLine(result.output.trimEnd())
            }
            if (result.error.isNotBlank()) {
                appendLine("stderr:")
                appendLine(result.error.trimEnd())
            }
            if (!result.ok && result.error.isBlank() && result.output.isBlank()) {
                appendLine("error:")
                appendLine("ADB command failed without output.")
            }
        }.trim()
    }

    private fun adbCommand(call: SystemCall): String {
        val raw = call.command.ifBlank { call.code }.ifBlank { call.query }.trim()
        return raw.removePrefix("adb shell ").removePrefix("shell ").trim()
    }

    // ---- browse_url ----

    private suspend fun executeBrowseUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val url = normalizeHttpUrl(rawUrl)
            ?: return@withContext "[BROWSE_ERROR] Invalid URL; only http/https pages are supported."

        AppLogger.i(TAG, "BROWSE-URL: $url")
        val renderedText = GeckoSearchEngine.search(url, timeoutMs = 15_000L)
        if (!renderedText.isNullOrBlank()) {
            "[BROWSE_RESULT]\n$renderedText"
        } else {
            AppLogger.w(TAG, "BROWSE-GECKO-EMPTY: fallback to HTTP text extraction")
            val httpText = fetchUrlText(url)
            if (httpText.isNullOrBlank()) {
                "[BROWSE_ERROR] The built-in browser and HTTP fallback could not read page content."
            } else {
                "[BROWSE_RESULT]\n$httpText"
            }
        }
    }

    private fun normalizeHttpUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val parsed = runCatching { URL(withScheme) }.getOrNull() ?: return null
        val protocol = parsed.protocol.lowercase(Locale.US)
        if (protocol != "http" && protocol != "https") return null
        if (parsed.host.isNullOrBlank()) return null
        return parsed.toString()
    }

    private fun fetchUrlText(url: String): String? {
        return try {
            val parsed = URL(url)
            val conn = httpGet(parsed)
            val code = conn.responseCode
            val type = conn.contentType.orEmpty()
            AppLogger.i(TAG, "BROWSE-HTTP-RESP: HTTP $code type=$type")
            val body = try {
                readResponseBody(conn)
            } finally {
                conn.disconnect()
            }
            val text = htmlToReadableText(body)
            AppLogger.i(TAG, "BROWSE-HTTP-DONE: chars=${text.length}")
            if (text.isBlank()) null else buildString {
                appendLine("Page title: ${extractHtmlTitle(body)}")
                appendLine("Page text:")
                append(text.take(16_000))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "BROWSE-HTTP-ERROR: ${e.message}")
            null
        }
    }

    private fun extractHtmlTitle(html: String): String {
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        return stripAll(title)
    }

    private fun htmlToReadableText(html: String): String {
        val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: html
        val cleaned = body
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|tr|h[1-6]|section|article|table)>", RegexOption.IGNORE_CASE), "\n")
        return stripAll(cleaned)
    }

    // ---- web_search ----

    private suspend fun executeWebSearch(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "【搜索错误】查询关键词为空"

        AppLogger.i(TAG, "==================== WEB_SEARCH START ====================")
        AppLogger.i(TAG, "SEARCH-QUERY: " + query)
        AppLogger.i(TAG, "SEARCH-BACKENDS: " + SEARCH_BACKENDS.map { it.name })
        AppLogger.i(TAG, "SEARCH-STATE: cookieHandler=" + cookieHandlerInstalled + " bingWarmed=" + bingWarmedUp)

        for ((i, backend) in SEARCH_BACKENDS.withIndex()) {
            val t0 = System.currentTimeMillis()
            AppLogger.i(TAG, "SEARCH-TRY[" + (i + 1) + "]: " + backend.name + " timeout=" + backend.timeoutMs + "ms")

            val raw = withTimeoutOrNull(backend.timeoutMs) { backend.execute(query) }
            val elapsed = System.currentTimeMillis() - t0

            if (raw == null) {
                AppLogger.w(TAG, "SEARCH-RESULT[" + (i + 1) + "]: " + backend.name + " -> NULL (timeout/exception) after " + elapsed + "ms")
                continue
            }
            if (raw.results.isEmpty()) {
                AppLogger.w(TAG, "SEARCH-RESULT[" + (i + 1) + "]: " + backend.name + " -> EMPTY after " + elapsed + "ms")
                continue
            }
            AppLogger.i(TAG, "SEARCH-RESULT[" + (i + 1) + "]: " + backend.name + " -> " + raw.results.size + " hits in " + elapsed + "ms")
            for ((j, pair) in raw.results.withIndex()) {
                AppLogger.i(TAG, "SEARCH-HIT[" + j + "]: " + pair.first + " | " + pair.second.take(100))
            }
            AppLogger.i(TAG, "SEARCH-URL[" + (i + 1) + "]: " + raw.requestUrl)
            return@withContext formatResults(query, backend.name, raw.results)
        }

        AppLogger.e(TAG, "==================== WEB_SEARCH FAILED (all backends) ====================")
        "【搜索错误】所有搜索后端均失败。请稍后重试或基于已有知识回答。"
    }

    private fun formatResults(query: String, source: String, results: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.appendLine("【联网搜索结果：" + query + "】（来源：" + source + "）")
        for ((i, pair) in results.take(MAX_SEARCH_RESULTS).withIndex()) {
            sb.appendLine("" + (i + 1) + ". " + pair.first)
            if (pair.second.isNotBlank()) sb.appendLine("   " + pair.second)
        }
        return sb.toString()
    }

    // ==================== Bing ====================

    private suspend fun searchBingGecko(query: String): SearchResponse? = withContext(Dispatchers.IO) {
        val url = buildBingUrl(query)
        val renderedText = GeckoSearchEngine.search(url.toString())
        if (renderedText.isNullOrBlank()) null else SearchResponse(
            url.toString(),
            listOf("Bing 渲染页面文本（交由模型抽取）" to renderedText)
        )
    }

    private suspend fun searchBingHtml(query: String): SearchResponse = withContext(Dispatchers.IO) {
        ensureCookieHandler()
        warmUpBing()

        val url = buildBingUrl(query)
        AppLogger.i(TAG, "BING-REQ: GET " + url.toString())

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBingSearchHeaders()
        }

        val code = conn.responseCode
        val len = conn.contentLength
        val ct = conn.contentType
        AppLogger.i(TAG, "BING-RESP: HTTP " + code + " len=" + len + " type=" + ct)

        // Log all response headers
        for ((k, v) in conn.headerFields) {
            if (k != null) AppLogger.i(TAG, "BING-HEADER: " + k + "=" + v)
        }

        val html = try {
            readResponseBody(conn)
        } finally {
            conn.disconnect()
        }

        AppLogger.i(TAG, "BING-HTML: " + html.length + " chars")
        AppLogger.i(TAG, "BING-HTML-PREVIEW: " + html.take(600))
        AppLogger.i(TAG, "BING-HTML-HAS-b_algo: " + html.contains("b_algo"))
        AppLogger.i(TAG, "BING-HTML-HAS-h2: " + html.contains("<h2"))

        val results = parseBingHtml(html)
        AppLogger.i(TAG, "BING-PARSE: " + results.size + " results")
        SearchResponse(url.toString(), results)
    }

    private fun buildBingUrl(query: String): URL {
        val q = URLEncoder.encode(query, "UTF-8")
        // form=QBRE tells Bing this is a real user search (not an API call).
        // pq= encodes the same query as "previous query" for session continuity.
        val pq = URLEncoder.encode(query.take(50), "UTF-8")
        val region = if (shouldUseChinaRegion(query)) "&cc=cn" else ""
        return URL("https://www.bing.com/search?q=" + q +
            "&form=QBRE&pq=" + pq + "&qs=n&sp=-1&lq=0" + region)
    }

    private fun parseBingHtml(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val algoStart = "<li class=\"b_algo\""
        val algoEnd = "</li>"

        var pos = 0
        while (results.size < MAX_SEARCH_RESULTS) {
            val li = html.indexOf(algoStart, pos)
            if (li == -1) break
            val end = html.indexOf(algoEnd, li)
            if (end == -1) { pos = li + algoStart.length; continue }

            val block = html.substring(li, end)
            val title = extractTagContent(block, "h2")
            val cap = extractClassContent(block, "div", "b_caption")
            var snippet = if (cap != null) extractTagContent(cap, "p") else null
            if (snippet.isNullOrBlank()) snippet = extractTagContent(block, "p")

            val ct = stripAll(title); val cs = stripAll(snippet)
            if (ct.isNotBlank() && !ct.startsWith("http")) {
                results.add(ct to cs)
            }
            pos = end + algoEnd.length
        }

        if (results.isEmpty()) {
            AppLogger.w(TAG, "BING-PARSE-FALLBACK: b_algo empty, trying generic h2. HTML[0..500]=" + html.take(500))
            results.addAll(parseGenericH2Links(html))
            AppLogger.i(TAG, "BING-PARSE-FALLBACK: generic h2 found " + results.size)
        }
        return results
    }

    private fun parseGenericH2Links(html: String): List<Pair<String, String>> {
        val r = mutableListOf<Pair<String, String>>()
        val re = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) {
            if (r.size >= MAX_SEARCH_RESULTS) break
            val t = stripAll(m.groupValues[2])
            if (t.isNotBlank()) r.add(t to "")
        }
        return r
    }

    private fun shouldUseChinaRegion(query: String): Boolean {
        return query.contains("积存金") ||
            query.contains("银行黄金") ||
            query.contains("银行金价") ||
            query.contains("贵金属")
    }

    // ==================== Baidu ====================

    private fun searchBaiduHtml(query: String): SearchResponse {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.baidu.com/s?wd=" + q + "&ie=utf-8&rn=10")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBrowserHeaders()
            setRequestProperty("Cookie", "BAIDUID=0;")
        }
        val html = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return SearchResponse(url.toString(), parseBaiduHtml(html))
    }

    private fun parseBaiduHtml(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val patterns = listOf("c-container", "result c-container")
        var pos = 0
        while (results.size < MAX_SEARCH_RESULTS) {
            var bestIdx = Int.MAX_VALUE; var bestEnd = -1; var bestBlock = ""
            for (cls in patterns) {
                val marker = "class=\"" + cls + "\""
                val idx = html.indexOf(marker, pos)
                if (idx != -1 && idx < bestIdx) {
                    val ds = html.lastIndexOf("<div", idx)
                    if (ds != -1) {
                        val de = findClosingTag(html, "div", ds)
                        if (de != -1) { bestIdx = ds; bestEnd = de; bestBlock = html.substring(ds, de) }
                    }
                }
            }
            if (bestEnd == -1) break
            var title: String? = null
            val h3 = extractTagContent(bestBlock, "h3")
            if (h3 != null) title = extractTagContent(h3, "a") ?: h3
            if (title.isNullOrBlank()) title = extractAllLinks(bestBlock).firstOrNull()
            var snippet = extractClassContentRegex(bestBlock, "span", "content-right")
            if (snippet.isNullOrBlank()) snippet = extractClassContent(bestBlock, "span", "c-abstract")
            if (snippet.isNullOrBlank()) snippet = extractClassContent(bestBlock, "div", "c-abstract")
            val ct = stripAll(title); val cs = stripAll(snippet)
            if (ct.isNotBlank() && !ct.startsWith("http")) results.add(ct to cs)
            pos = bestEnd + 6
        }
        return results
    }

    // ==================== DuckDuckGo API ====================

    private fun searchDuckDuckGoApi(query: String): SearchResponse {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.duckduckgo.com/?q=" + q + "&format=json&no_html=1&skip_disambig=1")
        val conn = httpGet(url)
        val json = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        val results = mutableListOf<Pair<String, String>>()
        val at = extractJsonString(json, "AbstractText")
        val aSrc = extractJsonString(json, "AbstractSource")
        if (!at.isNullOrBlank()) results.add((aSrc ?: "DuckDuckGo") to at)
        for (t in extractJsonArray(json, "RelatedTopics")) {
            if (results.size >= MAX_SEARCH_RESULTS) break
            val tx = extractJsonString(t, "Text")
            if (!tx.isNullOrBlank()) results.add("相关结果" to tx)
        }
        for (r in extractJsonArray(json, "Results")) {
            if (results.size >= MAX_SEARCH_RESULTS) break
            val tx = extractJsonString(r, "Text")
            if (!tx.isNullOrBlank()) results.add("搜索结果" to tx)
        }
        return SearchResponse(url.toString(), results)
    }

    // ==================== DuckDuckGo HTML ====================

    private fun searchDuckDuckGoHtml(query: String): SearchResponse {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=" + q)
        val conn = httpGet(url)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        val html = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return SearchResponse(url.toString(), parseDdHtml(html))
    }

    private fun parseDdHtml(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val marker = "<div class=\"result__body\">"
        var pos = 0
        while (results.size < MAX_SEARCH_RESULTS) {
            val idx = html.indexOf(marker, pos)
            if (idx == -1) break
            val end = findClosingTag(html, "div", idx)
            if (end == -1) { pos = idx + marker.length; continue }
            val block = html.substring(idx, end)
            val title = extractClassContent(block, "a", "result__a") ?: extractTagContent(block, "a")
            val snippet = extractClassContent(block, "a", "result__snippet")
            val ct = stripAll(title)
            if (ct.isNotBlank()) results.add(ct to stripAll(snippet))
            pos = end + 6
        }
        return results
    }

    // ==================== SearXNG ====================

    private val SEARXNG_INSTANCES = listOf(
        "https://search.sapti.me",
        "https://searx.be",
        "https://search.inetol.net",
        "https://priv.au",
    )

    private suspend fun searchSearXngPublic(query: String): SearchResponse {
        val q = URLEncoder.encode(query, "UTF-8")
        for (inst in SEARXNG_INSTANCES) {
            val r = trySearXngInstance(inst, q)
            if (r != null && r.results.isNotEmpty()) return r
        }
        return SearchResponse("SearXNG: " + SEARXNG_INSTANCES.joinToString(", ") { it + "/search?q=" + q + "&format=json&language=zh-CN" }, emptyList())
    }

    private suspend fun trySearXngInstance(inst: String, q: String): SearchResponse? {
        return try {
            val url = URL(inst + "/search?q=" + q + "&format=json&language=zh-CN")
            val conn = httpGet(url)
            conn.setRequestProperty("Accept", "application/json")
            val json = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            val results = extractJsonArray(json, "results").mapNotNull { r ->
                val t = extractJsonString(r, "title") ?: return@mapNotNull null
                t to extractJsonString(r, "content").orEmpty()
            }.take(MAX_SEARCH_RESULTS)
            SearchResponse(url.toString(), results)
        } catch (e: Exception) {
            AppLogger.w(TAG, "SEARXNG: " + inst + " " + e.message)
            null
        }
    }

    /**
     * Reorder Chinese query terms to avoid Bing over-matching on a generic first term.
     * Bing tokenizes Chinese queries left-to-right; if the first term contains a
     * high-frequency word (e.g. "中国"), it dominates and later terms are ignored.
     *
     * Strategy: sort by length descending — longer terms are more specific.
     */
    private fun reorderQuery(query: String): String {
        val terms = query.split(Regex("[\\s，,]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.size <= 1) return query

        // Sort by length desc: "今日黄金价格"(6) before "中国"(2)
        val reordered = terms.sortedByDescending { it.length }.joinToString(" ")
        return if (reordered == query) query else reordered
    }

    // ==================== HTML helpers ====================

    private fun extractTagContent(html: String, tag: String): String? {
        val open = "<" + tag; val close = "</" + tag + ">"
        val start = indexOfTagOpen(html, open) ?: return null
        val cs = html.indexOf('>', start) + 1
        if (cs <= 0) return null
        val end = html.indexOf(close, cs)
        if (end == -1) return null
        return html.substring(cs, end)
    }

    private fun extractClassContent(html: String, tag: String, cls: String): String? {
        val marker = "class=\"" + cls + "\""
        val mi = html.indexOf(marker)
        if (mi == -1) return null
        val ts = html.lastIndexOf("<" + tag, mi)
        if (ts == -1) return null
        val cs = html.indexOf('>', mi) + 1
        if (cs <= 0) return null
        val end = html.indexOf("</" + tag + ">", cs)
        if (end == -1) return null
        return html.substring(cs, end)
    }

    private fun extractClassContentRegex(html: String, tag: String, prefix: String): String? {
        val re = Regex("<" + tag + "[^>]*class=\"" + Regex.escape(prefix) + "[^\"]*\"[^>]*>")
        val m = re.find(html) ?: return null
        val cs = m.range.last + 1
        val end = html.indexOf("</" + tag + ">", cs)
        if (end == -1) return null
        return html.substring(cs, end)
    }

    private fun extractAllLinks(html: String): List<String> {
        val r = mutableListOf<String>()
        for (m in Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val t = stripAll(m.groupValues[1])
            if (t.isNotBlank()) r.add(t)
        }
        return r
    }

    private fun findClosingTag(html: String, tag: String, start: Int): Int {
        val open = "<" + tag; val close = "</" + tag + ">"
        var depth = 0; var i = start
        while (i < html.length - close.length) {
            if (html.startsWith(open, i) && (html[i + tag.length + 1] == ' ' || html[i + tag.length + 1] == '>')) depth++
            else if (html.startsWith(close, i)) { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    private fun indexOfTagOpen(html: String, open: String): Int? {
        val idx = html.indexOf(open)
        if (idx == -1) return null
        val next = html.getOrNull(idx + open.length) ?: return null
        return if (next == ' ' || next == '>' || next == '\t' || next == '\n') idx else null
    }

    private fun stripAll(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    // ==================== JSON helpers ====================

    private fun extractJsonString(json: String, key: String): String? {
        val p = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return p.find(json)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
    }

    private fun extractJsonArray(json: String, key: String): List<String> {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\[").find(json) ?: return emptyList()
        val items = mutableListOf<String>()
        var i = m.range.last + 1; var depth = 1
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '{' -> { val e = findMatchingBrace(json, i); if (e > i) { items.add(json.substring(i, e + 1)); i = e + 1 } else i++ }
                ']' -> { depth--; i++ }
                '"' -> { i = skipJsonString(json, i) }
                else -> i++
            }
        }
        return items
    }

    private fun findMatchingBrace(json: String, start: Int): Int {
        var depth = 0; var i = start
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '"' -> { i = skipJsonString(json, i); continue }
            }
            i++
        }
        return -1
    }

    private fun skipJsonString(json: String, start: Int): Int {
        var i = start + 1
        while (i < json.length) { when { json[i] == '\\' -> i += 2; json[i] == '"' -> return i + 1; else -> i++ } }
        return i
    }

    /**
     * Read response body, auto-detecting gzip.
     * HttpURLConnection normally decompresses gzip automatically, but on some
     * OEM ROMs (ColorOS, MIUI) the auto-decompression may not trigger when
     * custom headers are set. This method falls back to manual decompression
     * if the response starts with the gzip magic bytes (0x1F 0x8B).
     */
    private fun readResponseBody(conn: HttpURLConnection): String {
        val raw = conn.inputStream.use { it.readBytes() }
        return if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
            AppLogger.i(TAG, "  detected gzip magic bytes, manual decompress (${raw.size} raw bytes)")
            GZIPInputStream(java.io.ByteArrayInputStream(raw)).use { gz ->
                BufferedReader(InputStreamReader(gz, "UTF-8")).use { it.readText() }
            }
        } else {
            String(raw, Charsets.UTF_8)
        }
    }

    // ==================== HTTP ====================

    private fun HttpURLConnection.setBrowserHeaders() {
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        // Do NOT set Accept-Encoding manually — HttpURLConnection adds it and auto-decompresses gzip
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Sec-Fetch-Dest", "document")
        setRequestProperty("Sec-Fetch-Mode", "navigate")
        setRequestProperty("Sec-Fetch-Site", "none")
        setRequestProperty("Sec-Fetch-User", "?1")
        setRequestProperty("Upgrade-Insecure-Requests", "1")
    }

    private fun HttpURLConnection.setBingSearchHeaders() {
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    }

    private fun httpGet(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBrowserHeaders()
        }
    }
}
