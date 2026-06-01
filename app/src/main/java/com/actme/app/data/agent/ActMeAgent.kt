package com.actme.app.data.agent

import com.actme.app.data.local.MemoryCategories
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.MemoryItemEntity
import com.actme.app.data.local.ScheduleEntity
import com.actme.app.data.local.SkillEntity
import com.actme.app.data.remote.MessagePayload
import com.actme.app.data.remote.LlmStreamChunk
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderConfig
import com.actme.app.data.remote.TokenUsage
import com.actme.app.util.AppLogger
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AgentResult(
    val reply: String = "",
    @SerialName("memory_updates") val memoryUpdates: List<MemoryUpdate> = emptyList(),
    @SerialName("schedule_updates") val scheduleUpdates: List<ScheduleUpdate> = emptyList(),
    @SerialName("skill_updates") val skillUpdates: List<SkillUpdate> = emptyList(),
    @SerialName("system_calls") val systemCalls: List<SystemCall> = emptyList()
)

data class AgentTurnResult(
    val result: AgentResult,
    val usage: TokenUsage? = null
)

@Serializable
data class SystemCall(
    val type: String,
    val query: String = "",
    val url: String = ""
)

@Serializable
data class MemoryUpdate(
    val category: String,
    val content: String
)

@Serializable
data class ScheduleUpdate(
    val title: String,
    val detail: String = "",
    @SerialName("start_at") val startAt: Long? = null,
    @SerialName("reminder_at") val reminderAt: Long? = null,
    @SerialName("repeat_type") val repeatType: String? = null,
    @SerialName("repeat_days_of_week") val repeatDaysOfWeek: List<Int> = emptyList(),
    @SerialName("repeat_day_of_month") val repeatDayOfMonth: Int? = null,
    @SerialName("reminder_time") val reminderTime: String? = null
)

@Serializable
data class SkillUpdate(
    val name: String,
    val description: String,
    @SerialName("trigger_keywords") val triggerKeywords: List<String>,
    @SerialName("action_template") val actionTemplate: String
)

@Serializable
data class ScheduleSubAgentPlan(
    val title: String,
    val detail: String? = null,
    @SerialName("repeat_type") val repeatType: String,
    @SerialName("one_time_date") val oneTimeDate: String? = null,
    @SerialName("reminder_time") val reminderTime: String? = null,
    @SerialName("weekly_days") val weeklyDays: List<Int>? = null,
    @SerialName("monthly_day") val monthlyDay: Int? = null
)

@Serializable
data class ScheduleBatchPlan(
    val schedules: List<ScheduleSubAgentPlan> = emptyList()
)

class ActMeAgent(private val openAiClient: OpenAiResponsesClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun runTurn(
        userInput: String,
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        webSearchResults: String? = null
    ): AgentResult {
        return runTurnWithUsage(
            userInput = userInput,
            memories = memories,
            systemMemories = systemMemories,
            schedules = schedules,
            skills = skills,
            config = config,
            enableWebSearch = enableWebSearch,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType,
            historyMessages = historyMessages,
            webSearchResults = webSearchResults
        ).result
    }

    suspend fun runTurnWithUsage(
        userInput: String,
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        webSearchResults: String? = null
    ): AgentTurnResult {
        val llmResult = openAiClient.runWithUsage(
            buildMessages(userInput, memories, systemMemories, schedules, skills, enableWebSearch, imageBase64, imageMimeType, historyMessages, webSearchResults),
            config = config,
            enableWebSearch = enableWebSearch
        )
        return AgentTurnResult(parseRaw(llmResult.text), llmResult.usage)
    }

    fun runTurnStreaming(
        userInput: String,
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        webSearchResults: String? = null
    ): Flow<String> {
        return openAiClient.runStreaming(
            buildMessages(userInput, memories, systemMemories, schedules, skills, enableWebSearch, imageBase64, imageMimeType, historyMessages, webSearchResults),
            config = config,
            enableWebSearch = enableWebSearch
        )
    }

    fun runTurnStreamingWithUsage(
        userInput: String,
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        webSearchResults: String? = null
    ): Flow<LlmStreamChunk> {
        return openAiClient.runStreamingWithUsage(
            buildMessages(userInput, memories, systemMemories, schedules, skills, enableWebSearch, imageBase64, imageMimeType, historyMessages, webSearchResults),
            config = config,
            enableWebSearch = enableWebSearch
        )
    }

    fun parseRaw(raw: String): AgentResult {
        val jsonPart = extractJson(raw)
        return runCatching { json.decodeFromString<AgentResult>(jsonPart) }
            .onFailure { AppLogger.w(TAG, "parseRaw strict failed: ${it.message}; raw=${raw.take(300)}") }
            .getOrNull()
            ?: parseLooseAgentResult(raw, jsonPart)
            ?: AgentResult(reply = raw.trim())
    }

    private fun parseLooseAgentResult(raw: String, jsonPart: String): AgentResult? {
        val candidates = buildList {
            add(jsonPart)
            add(raw.trim())
            extractCodeFence(raw)?.let { add(it) }
        }.distinct()

        for (candidate in candidates) {
            val parsed = parseLooseJsonCandidate(candidate)
            if (parsed != null) {
                AppLogger.i(TAG, "parseRaw loose replyLen=${parsed.reply.length}, system_calls=${parsed.systemCalls.size}")
                return parsed
            }
        }
        return null
    }

    private fun parseLooseJsonCandidate(text: String): AgentResult? {
        val jsonText = extractJson(text)
        val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
        val obj = element as? JsonObject
        val calls = obj?.let { extractSystemCalls(it) } ?: extractLooseSystemCalls(jsonText)
        val reply = obj?.get("reply")?.jsonPrimitive?.contentOrNull
            ?: extractLooseStringField(jsonText, "reply")
            ?: ""
        if (calls.isEmpty() && reply.isBlank()) return null
        return AgentResult(reply = reply, systemCalls = calls)
    }

    private fun extractSystemCalls(obj: JsonObject): List<SystemCall> {
        val callsElement = obj["system_calls"]
            ?: obj["system_call"]
            ?: obj["tool_calls"]
            ?: obj["tool_call"]
            ?: obj["calls"]

        val normalized = when (callsElement) {
            is JsonArray -> callsElement
            is JsonObject -> JsonArray(listOf(callsElement))
            else -> return emptyList()
        }

        return normalized.mapNotNull { callElement ->
            val callObj = callElement as? JsonObject ?: return@mapNotNull null
            val functionObj = callObj["function"] as? JsonObject
            val argumentsObj = callObj.argumentsObject()
                ?: functionObj?.argumentsObject()
            val type = callObj.stringValue("type")
                ?: callObj.stringValue("name")
                ?: callObj.stringValue("tool")
                ?: callObj.stringValue("function")
                ?: functionObj?.stringValue("name")
                ?: return@mapNotNull null
            val query = callObj.stringValue("query")
                ?: argumentsObj?.stringValue("query")
                ?: ""
            val url = callObj.stringValue("url")
                ?: argumentsObj?.stringValue("url")
                ?: ""
            SystemCall(type = type, query = query, url = url)
        }
    }

    private fun JsonObject.stringValue(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.argumentsObject(): JsonObject? {
        val direct = this["arguments"] as? JsonObject
        if (direct != null) return direct
        val encoded = stringValue("arguments") ?: return null
        return runCatching { json.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
    }

    private fun extractCodeFence(raw: String): String? {
        val match = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractLooseStringField(text: String, field: String): String? {
        val marker = "\"$field\""
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val colonIdx = text.indexOf(':', markerIdx + marker.length)
        if (colonIdx < 0) return null
        var pos = colonIdx + 1
        while (pos < text.length && text[pos].isWhitespace()) pos++
        if (pos >= text.length || text[pos] != '"') return null

        val result = StringBuilder()
        var i = pos + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                if (i + 1 >= text.length) break
                result.append(unescapeJsonChar(text[i + 1]))
                i += 2
            } else if (c == '"' && isLikelyJsonStringTerminator(text, i)) {
                return result.toString()
            } else {
                result.append(c)
                i++
            }
        }
        return result.toString().ifBlank { null }
    }

    private fun extractLooseSystemCalls(text: String): List<SystemCall> {
        val callsIdx = listOf("system_calls", "system_call", "tool_calls", "tool_call", "calls")
            .map { text.indexOf("\"$it\"") }
            .filter { it >= 0 }
            .minOrNull() ?: return emptyList()
        val tail = text.substring(callsIdx)
        return Regex("\\{[^{}]*(?:\"type\"|\"name\"|\"tool\"|\"function\")\\s*:\\s*\"([^\"]+)\"[^{}]*\\}")
            .findAll(tail)
            .mapNotNull { match ->
                val block = match.value
                val type = Regex("\"(?:type|name|tool|function)\"\\s*:\\s*\"([^\"]+)\"")
                    .find(block)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val query = Regex("\"query\"\\s*:\\s*\"([^\"]*)\"")
                    .find(block)?.groupValues?.getOrNull(1).orEmpty()
                val url = Regex("\"url\"\\s*:\\s*\"([^\"]*)\"")
                    .find(block)?.groupValues?.getOrNull(1).orEmpty()
                SystemCall(type = type, query = query, url = url)
            }
            .toList()
    }

    private fun unescapeJsonChar(ch: Char): Char {
        return when (ch) {
            '"' -> '"'
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '\\' -> '\\'
            'b' -> '\b'
            else -> ch
        }
    }

    private fun isLikelyJsonStringTerminator(text: String, quoteIndex: Int): Boolean {
        var i = quoteIndex + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return true
        if (text[i] == '}') return true
        if (text[i] != ',') return false
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return true
        if (text[i] != '"') return false
        val nextKeys = listOf(
            "\"memory_updates\"",
            "\"schedule_updates\"",
            "\"skill_updates\"",
            "\"system_calls\"",
            "\"system_call\"",
            "\"tool_calls\"",
            "\"tool_call\"",
            "\"calls\""
        )
        return nextKeys.any { text.startsWith(it, i) }
    }

    private fun buildMessages(
        userInput: String,
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        enableWebSearch: Boolean,
        imageBase64: String?,
        imageMimeType: String?,
        historyMessages: List<ChatMessageEntity>,
        webSearchResults: String? = null
    ): List<MessagePayload> {
        val systemPrompt = buildSystemPrompt(memories, systemMemories, schedules, skills, enableWebSearch, webSearchResults)
        val messages = mutableListOf<MessagePayload>()
        messages += MessagePayload("system", systemPrompt)
        historyMessages.forEach { entity ->
            messages += MessagePayload(
                role = entity.role,
                content = sanitizeHistoryContent(entity),
                imageBase64 = entity.imageBase64,
                imageMimeType = entity.imageMimeType
            )
        }
        messages += MessagePayload("user", userInput, imageBase64 = imageBase64, imageMimeType = imageMimeType)
        return messages
    }

    private fun sanitizeHistoryContent(entity: ChatMessageEntity): String {
        if (entity.role != "assistant") return entity.content
        val withoutProgress = if (entity.content.contains("执行过程：") && entity.content.contains("\n---\n")) {
            entity.content.substringAfterLast("\n---\n")
        } else {
            entity.content
        }
        return withoutProgress
            .replace(Regex("\\n?---\\n[🔍📖🌐] \\[展开(?:搜索结果|网页阅读内容|联网资料)]\\(search://result\\)"), "")
            .trim()
    }

    suspend fun generateReminderInsight(title: String, detail: String, config: ProviderConfig): String {
        val prompt = """
            你是 ActMe 的提醒增强助手。
            用户收到如下提醒：
            标题：$title
            细节：$detail
            请输出 2~3 句中文建议，要求可执行、具体、简短。
        """.trimIndent()
        return openAiClient.run(
            listOf(
                MessagePayload("system", "你是一个务实的中文行动教练。"),
                MessagePayload("user", prompt)
            ),
            config = config
        )
    }

    suspend fun runScheduleSubAgent(
        rawRequest: String,
        timezoneId: String,
        nowLocalIso: String,
        config: ProviderConfig
    ): ScheduleSubAgentPlan? {
        val prompt = """
            你是 ActMe 的日程子Agent，只负责把用户请求转换为结构化日程配置。
            当前时区：$timezoneId
            当前本地时间：$nowLocalIso

            仅输出 JSON，格式：
            {
              "title":"日程标题",
              "detail":"补充说明",
              "repeat_type":"NONE|DAILY|WEEKLY|MONTHLY",
              "one_time_date":"yyyy-MM-dd",
              "reminder_time":"HH:mm",
              "weekly_days":[1,3,5],
              "monthly_day":15
            }

            规则：
            - repeat_type=NONE 时必须给 one_time_date 与 reminder_time。
            - repeat_type=DAILY 只需 reminder_time。
            - repeat_type=WEEKLY 需 reminder_time 和 weekly_days（1=周一...7=周日）。
            - repeat_type=MONTHLY 需 reminder_time 和 monthly_day（1-31）。
            - 如果用户没说标题，自动生成一个简洁标题。
            - 不要使用程序默认值兜底，必须根据用户语义和候选信息明确给出最终配置。

            用户原始请求：
            $rawRequest
        """.trimIndent()

        val raw = openAiClient.run(
            listOf(
                MessagePayload("system", "你是严谨的日程结构化助手。"),
                MessagePayload("user", prompt)
            ),
            config = config
        )
        val jsonPart = extractJson(raw)
        val parsed = runCatching { json.decodeFromString<ScheduleSubAgentPlan>(jsonPart) }.getOrNull()
        AppLogger.i(TAG, "schedule sub-agent parsed=${parsed != null}")
        return parsed
    }

    suspend fun refineImageScheduleCandidates(
        sourceText: String,
        localCandidates: List<ScheduleSubAgentPlan>,
        timezoneId: String,
        nowLocalIso: String,
        config: ProviderConfig
    ): ScheduleBatchPlan? {
        val candidatesJson = runCatching { json.encodeToString(localCandidates) }.getOrDefault("[]")
        val prompt = """
            你是 ActMe 的云端日程整理助手。你的任务不是看图片，而是基于“端侧图片模型提取出的原文摘要 + 粗候选日程”生成最终日程列表。
            当前时区：$timezoneId
            当前本地时间：$nowLocalIso

            仅输出 JSON，格式：
            {
              "schedules":[
                {
                  "title":"日程标题",
                  "detail":"补充说明",
                  "repeat_type":"NONE|DAILY|WEEKLY|MONTHLY",
                  "one_time_date":"yyyy-MM-dd",
                  "reminder_time":"HH:mm",
                  "weekly_days":[1,3,5],
                  "monthly_day":15
                }
              ]
            }

            规则：
            - 你可以调整端侧候选的标题、重复规则、日期、时间，并决定最终保留几条日程。
            - 同一图片中多个事项要分别输出为多条 schedules。
            - 没有明确 reminder_time 的事项不要输出。
            - 如果端侧候选明显重复或冲突，你应合并、去重或修正。
            - 不要输出无法支撑的猜测性事项。
            - 最多输出 8 条。

            端侧提取的原文摘要：
            $sourceText

            端侧粗候选日程：
            $candidatesJson
        """.trimIndent()

        val raw = openAiClient.run(
            listOf(
                MessagePayload("system", "你是严谨的中文日程结构化助手。"),
                MessagePayload("user", prompt)
            ),
            config = config
        )
        val parsed = runCatching {
            json.decodeFromString<ScheduleBatchPlan>(extractJson(raw))
        }.getOrNull()
        AppLogger.i(TAG, "image schedule refine parsed=${parsed != null}, count=${parsed?.schedules?.size ?: 0}")
        return parsed
    }

    private fun buildSystemPrompt(
        memories: List<MemoryItemEntity>,
        systemMemories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        enableWebSearch: Boolean,
        webSearchResults: String? = null
    ): String {
        val localZone = ZoneId.systemDefault()
        val zoneId = localZone.id
        val now = LocalDateTime.now(localZone)
        val nowFormatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dayOfWeek = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val currentTimeInfo = "当前时间：$nowFormatted ($dayOfWeek)，时区：$zoneId"

        // System memories — app identity and capabilities, injected first
        val systemMemoryText = if (systemMemories.isEmpty()) "" else systemMemories
            .joinToString("\n") { "- ${it.content}" }

        val userMemoryText = if (memories.isEmpty()) "暂无" else memories
            .take(40)
            .joinToString("\n") { "- [${it.category}] ${it.content}" }
        val scheduleText = if (schedules.isEmpty()) "暂无" else schedules
            .take(20)
            .joinToString("\n") { "- ${it.title} @${it.reminderAt} (${it.repeatType})" }
        val skillText = if (skills.isEmpty()) "暂无" else skills
            .take(20)
            .joinToString("\n") { "- ${it.name}: ${it.triggerKeywords}" }

        val webSearchHint = if (enableWebSearch) {
            "当前轮已开启联网查询能力。如果需要搜索最新信息，在 system_calls 中添加 {\"type\":\"web_search\",\"query\":\"搜索关键词\"}。"
        } else {
            "当前轮未开启联网查询，请仅基于已有信息回复。"
        }

        val browseUrlHint = if (enableWebSearch) {
            "已有明确网页 URL 需要打开读取时，可在 system_calls 中添加 {\"type\":\"browse_url\",\"url\":\"https://example.com\"}。该技能会调用内置浏览器并返回渲染后的页面文本。你可以根据任务复杂度和信息充分性，自主决定是否继续打开搜索结果中的相关网页。通常优先浏览官网、一手来源、公告、产品说明、新闻原文、文档、价格页等权威来源；如果搜索摘要已经足够回答简单问题，也可以直接回答。若搜索结果只给出类似 \"https://www.boc.cn › fimarkets\" 的面包屑 URL，可还原为 \"https://www.boc.cn/fimarkets\" 后浏览。"
        } else {
            ""
        }

        val multiStepHint = if (enableWebSearch) {
            "Multi-step workflow: you may call system_calls, observe the returned results, then call more system_calls in the next pass. You have discretion to choose the number and order of tool calls. Use web_search to discover sources, browse_url to inspect specific pages, and get_current_time for exact time. For broad, uncertain, recent, or source-sensitive questions, consider browsing multiple non-duplicate pages to confirm information. For simple questions, direct answers or one search may be enough. Do not repeat the same query or URL unless there is a clear reason. Stop calling tools when the answer is sufficiently supported, the user likely wants a quick answer, or the tool budget is nearly exhausted."
        } else {
            ""
        }

        val searchResultsSection = if (!webSearchResults.isNullOrBlank()) {
            "\n联网搜索结果：\n$webSearchResults\n"
        } else ""

        return """
            你是 App 内置的 ActMe Agent，必须用中文回复。
            目标：帮助用户行动、整理记忆、安排日程、维护技能。
            记忆分类（可写）：${MemoryCategories.writable.joinToString("、")}。
            你可以在每次对话中判断是否需要写入 memory_updates 或 schedule_updates。
            若用户提出可复用策略，可以写入 skill_updates。
            $webSearchHint
            $browseUrlHint
            $multiStepHint
            【关于本 App】
            $systemMemoryText

            【系统能力】
            你拥有以下系统级技能，通过 system_calls 字段调用：
            1. get_current_time — 获取当前精确时间（含时区、星期）
            2. web_search — 联网搜索最新信息，需要提供 query 参数
            3. browse_url — 使用内置浏览器打开网页并读取渲染后的文本，需要提供 url 参数
            示例：{"system_calls":[{"type":"web_search","query":"2026年最新..."},{"type":"browse_url","url":"https://example.com"}]}
            当 system_calls 非空时，reply 可留空；系统执行后会返回结果让你继续生成最终回复。
            【时间信息】
            $currentTimeInfo
            时间字段一律输出 Unix 毫秒时间戳（不是秒），并严格按本地时区理解"今天/明天/每天12点"等表达。
            $searchResultsSection
            只输出 JSON，不要 Markdown，不要额外解释。格式如下：
            {
              "reply": "给用户的中文回复",
              "memory_updates": [{"category":"短期目标","content":"..."}],
              "schedule_updates": [{
                "title":"...",
                "detail":"...",
                "start_at":0,
                "reminder_at":0,
                "repeat_type":"NONE|DAILY|WEEKLY|MONTHLY",
                "repeat_days_of_week":[1,3,5],
                "repeat_day_of_month":15,
                "reminder_time":"12:00"
              }],
              "skill_updates": [{"name":"...","description":"...","trigger_keywords":["..."],"action_template":"..."}],
              "system_calls": [{"type":"get_current_time"},{"type":"web_search","query":"搜索内容"}]
            }
            说明：
            - 一次性提醒：repeat_type 用 NONE，并给出 reminder_at。
            - 每天提醒：repeat_type 用 DAILY，并给出 reminder_time(如 12:00)。
            - 每周提醒：repeat_type 用 WEEKLY，并给出 repeat_days_of_week(1=周一...7=周日) 与 reminder_time。
            - 每月提醒：repeat_type 用 MONTHLY，并给出 repeat_day_of_month 与 reminder_time。
            - 需要时间信息时优先用系统注入的当前时间，仅在需要精确对比时才调用 get_current_time。

            用户记忆：
            $userMemoryText

            当前日程：
            $scheduleText

            当前技能：
            $skillText
        """.trimIndent()
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return text
        return text.substring(start, end + 1)
    }

    companion object {
        private const val TAG = "ActMeAgent"
    }
}

/**
 * Incrementally extracts the "reply" field value from a streaming JSON response.
 * Feed SSE text chunks via [consume]; it returns newly displayable characters each call.
 */
class ReplyExtractor {
    private val raw = StringBuilder()
    private var scanPos = 0
    private var state = 0  // 0=searching, 1=in_reply, 2=done

    fun consume(chunk: String): String? {
        raw.append(chunk)
        if (state == 2) return null
        val text = raw.toString()

        if (state == 0) {
            val markerIdx = text.indexOf("\"reply\":", scanPos.coerceAtLeast(0))
            if (markerIdx == -1) {
                scanPos = (text.length - "\"reply\":".length).coerceAtLeast(0)
                return null
            }
            var pos = markerIdx + "\"reply\":".length
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) pos++
            if (pos >= text.length || text[pos] != '"') {
                scanPos = pos
                return null
            }
            scanPos = pos + 1
            state = 1
        }

        val result = StringBuilder()
        var i = scanPos
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                if (i + 1 < text.length) {
                    val unescaped: Char = when (text[i + 1]) {
                        '"' -> '"'
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        'b' -> '\b'
                        else -> text[i + 1]
                    }
                    result.append(unescaped)
                    i += 2
                } else {
                    // Incomplete escape at end of buffer — wait for next chunk
                    break
                }
            } else if (c == '"') {
                val terminatorState = replyTerminatorState(text, i)
                if (terminatorState == 1) {
                    state = 2
                    scanPos = i + 1
                    break
                } else if (terminatorState == -1) {
                    break
                } else {
                    result.append(c)
                    i++
                }
            } else {
                result.append(c)
                i++
            }
        }
        if (state == 1) scanPos = i

        return result.toString().ifEmpty { null }
    }

    fun getRaw(): String = raw.toString()

    private fun replyTerminatorState(text: String, quoteIndex: Int): Int {
        var i = quoteIndex + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return -1
        if (text[i] == '}') return 1
        if (text[i] != ',') return 0
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return -1
        if (text[i] != '"') return 0
        val nextKeys = listOf(
            "\"memory_updates\"",
            "\"schedule_updates\"",
            "\"skill_updates\"",
            "\"system_calls\"",
            "\"system_call\"",
            "\"tool_calls\"",
            "\"tool_call\"",
            "\"calls\""
        )
        val couldBeKey = nextKeys.any { key ->
            key.startsWith(text.substring(i).take(key.length)) || text.startsWith(key, i)
        }
        if (!couldBeKey) return 0
        return if (nextKeys.any { text.startsWith(it, i) }) 1 else -1
    }
}
