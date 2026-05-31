package com.actme.app.data.agent

import com.actme.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
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

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
        } catch (e: Exception) {
            AppLogger.w(TAG, "BING-WARMUP: FAIL " + e.message)
        }
    }

    // ---- Backends ----

    private data class SearchBackend(
        val name: String,
        val timeoutMs: Long,
        val execute: suspend (String) -> List<Pair<String, String>>?
    )

    private val SEARCH_BACKENDS = listOf(
        SearchBackend("Bing",           8_000) { q -> searchBingHtml(q) },
        SearchBackend("DuckDuckGo API",  5_000) { q -> searchDuckDuckGoApi(q) },
        SearchBackend("DuckDuckGo HTML", 8_000) { q -> searchDuckDuckGoHtml(q) },
        SearchBackend("Baidu",          8_000) { q -> searchBaiduHtml(q) },
        SearchBackend("SearXNG",         8_000) { q -> searchSearXngPublic(q) },
    )

    // ----------------------------------------------------------------

    suspend fun execute(calls: List<SystemCall>): String {
        val results = mutableListOf<String>()
        for (call in calls) {
            val r = executeOne(call)
            if (r != null) results.add(r)
        }
        return results.joinToString("\n---\n")
    }

    private suspend fun executeOne(call: SystemCall): String? {
        return when (call.type) {
            "get_current_time" -> executeGetCurrentTime()
            "web_search" -> executeWebSearch(call.query)
            else -> { AppLogger.w(TAG, "Unknown call: " + call.type); null }
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
            if (raw.isEmpty()) {
                AppLogger.w(TAG, "SEARCH-RESULT[" + (i + 1) + "]: " + backend.name + " -> EMPTY after " + elapsed + "ms")
                continue
            }
            AppLogger.i(TAG, "SEARCH-RESULT[" + (i + 1) + "]: " + backend.name + " -> " + raw.size + " hits in " + elapsed + "ms")
            for ((j, pair) in raw.withIndex()) {
                AppLogger.i(TAG, "SEARCH-HIT[" + j + "]: " + pair.first + " | " + pair.second.take(100))
            }
            return@withContext formatResults(query, backend.name, raw)
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

    private suspend fun searchBingHtml(query: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        ensureCookieHandler()
        warmUpBing()

        val q = URLEncoder.encode(query, "UTF-8")
        // form=QBRE tells Bing this is a real user search (not an API call).
        // Without it, Bing may return poor results for Chinese queries.
        // pq= encodes the same query as "previous query" for session continuity.
        val pq = URLEncoder.encode(query.take(50), "UTF-8")
        val url = URL("https://www.bing.com/search?q=" + q +
            "&form=QBRE&pq=" + pq + "&qs=n&sp=-1&lq=0")
        AppLogger.i(TAG, "BING-REQ: GET " + url.toString())

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBrowserHeaders()
            setRequestProperty("Referer", "https://cn.bing.com/")
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
        results
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

    // ==================== Baidu ====================

    private fun searchBaiduHtml(query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.baidu.com/s?wd=" + q + "&ie=utf-8&rn=10")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBrowserHeaders()
            setRequestProperty("Cookie", "BAIDUID=0;")
        }
        val html = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return parseBaiduHtml(html)
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

    private fun searchDuckDuckGoApi(query: String): List<Pair<String, String>> {
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
        return results
    }

    // ==================== DuckDuckGo HTML ====================

    private fun searchDuckDuckGoHtml(query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=" + q)
        val conn = httpGet(url)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        val html = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return parseDdHtml(html)
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

    private suspend fun searchSearXngPublic(query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(query, "UTF-8")
        for (inst in SEARXNG_INSTANCES) {
            val r = trySearXngInstance(inst, q)
            if (r != null && r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private suspend fun trySearXngInstance(inst: String, q: String): List<Pair<String, String>>? {
        return try {
            val url = URL(inst + "/search?q=" + q + "&format=json&language=zh-CN")
            val conn = httpGet(url)
            conn.setRequestProperty("Accept", "application/json")
            val json = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            extractJsonArray(json, "results").mapNotNull { r ->
                val t = extractJsonString(r, "title") ?: return@mapNotNull null
                t to extractJsonString(r, "content").orEmpty()
            }.take(MAX_SEARCH_RESULTS)
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

    private fun httpGet(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setBrowserHeaders()
        }
    }
}
