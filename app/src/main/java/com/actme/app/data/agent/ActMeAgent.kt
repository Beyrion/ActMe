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
import kotlinx.serialization.json.jsonObject
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
    val url: String = "",
    val code: String = "",
    val command: String = "",
    val input: String = "",
    val plan: String = "",
    @SerialName("target_text") val targetText: String = "",
    val guidance: String = "",
    @SerialName("timeout_ms") val timeoutMs: Long = 3_000L,
    @SerialName("output_files") val outputFiles: List<String> = emptyList(),
    @SerialName("generated_files") val generatedFiles: List<String> = emptyList(),
    @SerialName("expected_outputs") val expectedOutputs: List<String> = emptyList(),
    val files: List<String> = emptyList()
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

private const val HISTORY_TOOL_CONTEXT_MAX_CHARS = 12_000
private const val HISTORY_TOOL_LINE_MAX_CHARS = 1_200

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
        val responseFormat = if (config.providerFormat == "openai") agentResultResponseFormat else null
        val llmResult = openAiClient.runWithUsage(
            buildMessages(userInput, memories, systemMemories, schedules, skills, enableWebSearch, imageBase64, imageMimeType, historyMessages, webSearchResults),
            config = config,
            enableWebSearch = enableWebSearch,
            responseFormat = responseFormat
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
        val responseFormat = if (config.providerFormat == "openai") agentResultResponseFormat else null
        return openAiClient.runStreamingWithUsage(
            buildMessages(userInput, memories, systemMemories, schedules, skills, enableWebSearch, imageBase64, imageMimeType, historyMessages, webSearchResults),
            config = config,
            enableWebSearch = enableWebSearch,
            responseFormat = responseFormat
        )
    }

    fun parseRaw(raw: String): AgentResult {
        val candidates = extractJsonCandidates(raw)
        val strictResults = candidates.mapNotNull { candidate ->
            runCatching { json.decodeFromString<AgentResult>(candidate) }.getOrNull()
        }
        val result = strictResults.firstOrNull { it.systemCalls.isNotEmpty() }
            ?: strictResults.firstOrNull()
            ?: parseLooseAgentResult(raw, candidates.firstOrNull() ?: raw)
            ?: AgentResult(reply = sanitizeUserVisibleReply(raw))
        val unwrapped = unwrapNestedReply(result)
        return if (unwrapped.systemCalls.isEmpty()) {
            unwrapped.copy(reply = sanitizeUserVisibleReply(unwrapped.reply))
        } else {
            unwrapped
        }
    }

    // Guard against the model wrapping the entire tool-call JSON inside the reply field.
    // When outer system_calls is empty but reply itself parses as a valid AgentResult with
    // tool calls, use the inner result instead.
    private fun unwrapNestedReply(result: AgentResult): AgentResult {
        if (result.systemCalls.isNotEmpty()) return result
        val reply = result.reply.trim()
        if (!reply.startsWith("{") || !reply.endsWith("}")) return result
        val nested = runCatching { json.decodeFromString<AgentResult>(reply) }.getOrNull()
            ?: parseLooseAgentResult(reply, extractJson(reply))
        if (nested != null && nested.systemCalls.isNotEmpty()) {
            AppLogger.w(TAG, "unwrapNestedReply: model nested JSON in reply; systemCalls=${nested.systemCalls.size}")
            return nested
        }
        return result
    }

    private fun parseLooseAgentResult(raw: String, jsonPart: String): AgentResult? {
        val candidates = buildList {
            add(jsonPart)
            add(raw.trim())
            extractCodeFence(raw)?.let { add(it) }
            addAll(extractJsonCandidates(raw))
        }.distinct()

        val parsed = candidates.mapNotNull { parseLooseJsonCandidate(it) }
            .let { results ->
                results.firstOrNull { it.systemCalls.isNotEmpty() } ?: results.firstOrNull()
            }
        if (parsed != null) {
            AppLogger.i(TAG, "parseRaw loose replyLen=${parsed.reply.length}, system_calls=${parsed.systemCalls.size}")
        }
        return parsed
    }

    private fun parseLooseJsonCandidate(text: String): AgentResult? {
        val jsonText = extractJson(text)
        val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
        val obj = element as? JsonObject
        val calls = when (element) {
            is JsonObject -> extractSystemCalls(element)
            is JsonArray -> extractSystemCallsFromElement(element)
            else -> extractLooseSystemCalls(jsonText)
        }
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

        return if (callsElement != null) {
            extractSystemCallsFromElement(callsElement)
        } else {
            listOfNotNull(parseSystemCallObject(obj))
        }
    }

    private fun extractSystemCallsFromElement(element: kotlinx.serialization.json.JsonElement?): List<SystemCall> {
        return when (element) {
            is JsonArray -> element.flatMap { extractSystemCallsFromElement(it) }
            is JsonObject -> listOfNotNull(parseSystemCallObject(element))
            is JsonPrimitive -> element.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { text -> parseLooseAgentResult(text, extractJson(text))?.systemCalls }
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun parseSystemCallObject(callObj: JsonObject): SystemCall? {
        val functionObj = callObj["function"] as? JsonObject
        val argumentsObj = callObj.argumentsObject()
            ?: functionObj?.argumentsObject()
        val type = normalizeSystemCallType(callObj.stringValue("type")
            ?: callObj.stringValue("name")
            ?: callObj.stringValue("tool")
            ?: callObj.stringValue("function")
            ?: functionObj?.stringValue("name")
            ?: return null) ?: return null
        val query = callObj.stringValue("query")
            ?: argumentsObj?.stringValue("query")
            ?: ""
        val url = callObj.stringValue("url")
            ?: argumentsObj?.stringValue("url")
            ?: ""
        val code = callObj.stringValue("code")
            ?: argumentsObj?.stringValue("code")
            ?: ""
        val command = callObj.stringValue("command")
            ?: argumentsObj?.stringValue("command")
            ?: callObj.stringValue("cmd")
            ?: argumentsObj?.stringValue("cmd")
            ?: ""
        val input = callObj.stringValue("input")
            ?: argumentsObj?.stringValue("input")
            ?: ""
        val plan = callObj.stringValue("plan")
            ?: argumentsObj?.stringValue("plan")
            ?: callObj.stringValue("steps")
            ?: argumentsObj?.stringValue("steps")
            ?: ""
        val targetText = callObj.stringValue("target_text")
            ?: argumentsObj?.stringValue("target_text")
            ?: callObj.stringValue("targetText")
            ?: argumentsObj?.stringValue("targetText")
            ?: callObj.stringValue("text_to_type")
            ?: argumentsObj?.stringValue("text_to_type")
            ?: ""
        val guidance = callObj.stringValue("guidance")
            ?: argumentsObj?.stringValue("guidance")
            ?: callObj.stringValue("hint")
            ?: argumentsObj?.stringValue("hint")
            ?: ""
        val timeoutMs = callObj.longValue("timeout_ms")
            ?: argumentsObj?.longValue("timeout_ms")
            ?: callObj.longValue("timeoutMs")
            ?: argumentsObj?.longValue("timeoutMs")
            ?: 3_000L
        val outputFiles = callObj.stringListValue("output_files")
            ?: argumentsObj?.stringListValue("output_files")
            ?: emptyList()
        val generatedFiles = callObj.stringListValue("generated_files")
            ?: argumentsObj?.stringListValue("generated_files")
            ?: emptyList()
        val expectedOutputs = callObj.stringListValue("expected_outputs")
            ?: argumentsObj?.stringListValue("expected_outputs")
            ?: emptyList()
        val files = callObj.stringListValue("files")
            ?: argumentsObj?.stringListValue("files")
            ?: emptyList()
        return SystemCall(
            type = type,
            query = query,
            url = url,
            code = code,
            command = command,
            input = input,
            plan = plan,
            targetText = targetText,
            guidance = guidance,
            timeoutMs = timeoutMs,
            outputFiles = outputFiles,
            generatedFiles = generatedFiles,
            expectedOutputs = expectedOutputs,
            files = files
        )
    }

    private fun JsonObject.stringValue(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.longValue(key: String): Long? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.stringListValue(key: String): List<String>? {
        val element = this[key] ?: return null
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            is JsonPrimitive -> element.contentOrNull
                ?.split(',', ';', '\n')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
            else -> null
        }
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
        return extractJsonObjectBlocks(tail)
            .mapNotNull { block ->
                val type = normalizeSystemCallType(extractLooseStringField(block, "type")
                    ?: extractLooseStringField(block, "name")
                    ?: extractLooseStringField(block, "tool")
                    ?: extractLooseStringField(block, "function")
                    ?: return@mapNotNull null) ?: return@mapNotNull null
                val query = extractLooseStringField(block, "query").orEmpty()
                val url = extractLooseStringField(block, "url").orEmpty()
                val code = extractLooseStringField(block, "code").orEmpty()
                val command = extractLooseStringField(block, "command")
                    ?: extractLooseStringField(block, "cmd")
                    ?: ""
                val input = extractLooseStringField(block, "input").orEmpty()
                val plan = extractLooseStringField(block, "plan")
                    ?: extractLooseStringField(block, "steps")
                    ?: ""
                val targetText = extractLooseStringField(block, "target_text")
                    ?: extractLooseStringField(block, "targetText")
                    ?: extractLooseStringField(block, "text_to_type")
                    ?: ""
                val guidance = extractLooseStringField(block, "guidance")
                    ?: extractLooseStringField(block, "hint")
                    ?: ""
                val outputFiles = extractLooseStringArray(block, "output_files")
                val generatedFiles = extractLooseStringArray(block, "generated_files")
                val expectedOutputs = extractLooseStringArray(block, "expected_outputs")
                val files = extractLooseStringArray(block, "files")
                SystemCall(
                    type = type,
                    query = query,
                    url = url,
                    code = code,
                    command = command,
                    input = input,
                    plan = plan,
                    targetText = targetText,
                    guidance = guidance,
                    outputFiles = outputFiles,
                    generatedFiles = generatedFiles,
                    expectedOutputs = expectedOutputs,
                    files = files
                )
            }
            .toList()
    }

    private fun extractJsonObjectBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaping = false
        for (i in text.indices) {
            val ch = text[i]
            if (escaping) {
                escaping = false
                continue
            }
            if (ch == '\\' && inString) {
                escaping = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            blocks += text.substring(start, i + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return blocks
    }

    private fun normalizeSystemCallType(raw: String): String? {
        val compact = raw.trim()
        if (compact.isBlank()) return null
        val aliases = linkedMapOf(
            "python_exec" to listOf("python_exec", "run_python", "python"),
            "web_search" to listOf("web_search", "search"),
            "browse_url" to listOf("browse_url", "browser_url", "web_browse", "open_url"),
            "html_to_pdf" to listOf("html_to_pdf", "render_html_pdf", "webview_pdf"),
            "adb_shell" to listOf("adb_shell", "run_adb", "adb"),
            "gui_agent" to listOf("gui_agent", "mobile_gui_agent", "mobile_use"),
            "get_current_time" to listOf("get_current_time", "current_time")
        )
        aliases.forEach { (canonical, names) ->
            if (names.any { compact == it || compact.startsWith("$it\"") || compact.startsWith("$it,") }) {
                return canonical
            }
        }
        return compact.takeIf { value -> aliases.values.flatten().contains(value) }
    }

    private fun extractLooseStringArray(text: String, key: String): List<String> {
        val body = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\[([\\s\\S]*?)]")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(body)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .filter { it.isNotBlank() }
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
            "\"type\"",
            "\"name\"",
            "\"tool\"",
            "\"function\"",
            "\"query\"",
            "\"url\"",
            "\"code\"",
            "\"command\"",
            "\"cmd\"",
            "\"input\"",
            "\"timeout_ms\"",
            "\"timeoutMs\"",
            "\"output_files\"",
            "\"generated_files\"",
            "\"expected_outputs\"",
            "\"files\"",
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
            val role = when (entity.role) {
                "user" -> "user"
                else -> "assistant"
            }
            messages += MessagePayload(
                role = role,
                content = sanitizeHistoryContent(entity),
                imageBase64 = entity.imageBase64,
                imageMimeType = entity.imageMimeType
            )
        }
        val injectGuiReference = shouldInjectGuiAppLaunchReference(userInput, historyMessages)
        AppLogger.i(TAG, "buildMessages guiLaunchReferenceInjected=$injectGuiReference")
        val augmentedUserInput = if (injectGuiReference) {
            userInput + "\n\n" + guiAppLaunchReference()
        } else {
            userInput
        }
        messages += MessagePayload("user", augmentedUserInput, imageBase64 = imageBase64, imageMimeType = imageMimeType)
        return messages
    }

    private fun shouldInjectGuiAppLaunchReference(
        userInput: String,
        historyMessages: List<ChatMessageEntity>
    ): Boolean {
        val current = userInput.lowercase()
        if (current.contains("\"gui_agent\"") ||
            current.contains("gui agent") ||
            current.contains("gui_agent") ||
            current.contains("打开") ||
            current.contains("进入") ||
            current.contains("操作") ||
            current.contains("点击") ||
            current.contains("导航") ||
            current.contains("搜索") ||
            current.contains("发消息") ||
            current.contains("下单") ||
            current.contains("设置") ||
            current.contains("高德") ||
            current.contains("地图") ||
            current.contains("微信") ||
            current.contains("支付宝") ||
            current.contains("淘宝") ||
            current.contains("京东")
        ) {
            return true
        }
        return historyMessages.takeLast(4).any { entity ->
            val text = (entity.content + "\n" + entity.searchResult.orEmpty()).lowercase()
            text.contains("[gui_agent") ||
                text.contains("gui-agent") ||
                text.contains("\"gui_agent\"") ||
                text.contains("[gui_agent_action_error]") ||
                text.contains("[gui_agent_error]")
        }
    }

    private fun guiAppLaunchReference(): String = """
        【本轮 GUI Agent 启动参考】
        这段信息只用于本轮需要操作手机 GUI 的任务。若你决定调用 gui_agent，必须在 gui_agent.plan 的第 1 步写清楚目标 App 的包名，并要求本地 GUI executor 先打开该包名对应 App，再继续点控。不要把这段参考作为最终回复展示给用户。

        常见国产 App 打开方式/包名参考：
        - 高德地图 / Amap：com.autonavi.minimap
        - 百度地图：com.baidu.BaiduMap
        - 腾讯地图：com.tencent.map
        - 微信：com.tencent.mm
        - QQ：com.tencent.mobileqq
        - 支付宝：com.eg.android.AlipayGphone
        - 淘宝：com.taobao.taobao
        - 天猫：com.tmall.wireless
        - 京东：com.jingdong.app.mall
        - 拼多多：com.xunmeng.pinduoduo
        - 美团：com.sankuai.meituan
        - 大众点评：com.dianping.v1
        - 饿了么：me.ele
        - 抖音：com.ss.android.ugc.aweme
        - 快手：com.smile.gifmaker
        - 小红书：com.xingin.xhs
        - 哔哩哔哩：tv.danmaku.bili
        - 微博：com.sina.weibo
        - 百度：com.baidu.searchbox
        - 夸克：com.quark.browser
        - UC 浏览器：com.UCMobile
        - WPS：cn.wps.moffice_eng
        - 手机设置：com.android.settings
        - 小米应用商店：com.xiaomi.market

        gui_agent.plan 推荐格式：
        1. Open package <packageName> from launcher.
        2. Wait for the app home screen.
        3. Tap the exact visible entry/search/input field.
        4. Type target_text exactly if input is needed.
        5. Continue one visible action at a time.

        示例：
        {"type":"gui_agent","command":"Open Amap and navigate to destination","plan":"1. Open package com.autonavi.minimap from launcher.\n2. Wait for Amap home screen.\n3. Tap the top search box.\n4. Type target_text exactly.\n5. Tap the matching destination result.\n6. Tap route/navigation.","target_text":"华东师范大学普陀校区","guidance":"","timeout_ms":120000}
    """.trimIndent()

    private fun sanitizeHistoryContent(entity: ChatMessageEntity): String {
        if (entity.role == "tool_execution") {
            return buildString {
                appendLine("[历史工具执行]")
                if (entity.content.isNotBlank()) {
                    appendLine(entity.content)
                }
                if (!entity.searchResult.isNullOrBlank()) {
                    appendLine("[历史工具中间结果]")
                    appendLine(compactHistoryToolContext(entity.searchResult.trim()))
                }
            }.trim()
        }
        if (entity.role != "assistant") return entity.content
        val withoutProgress = if (entity.content.contains("执行过程：") && entity.content.contains("\n---\n")) {
            entity.content.substringAfterLast("\n---\n")
        } else {
            entity.content
        }
        val visible = withoutProgress
            .replace(Regex("\\n?---\\n[🔍📖🌐] \\[展开(?:搜索结果|网页阅读内容|联网资料)]\\(search://result\\)"), "")
            .trim()
        val toolContext = entity.searchResult.orEmpty().trim()
        return if (toolContext.isBlank()) visible else "$visible\n\n[历史工具/中间结果]\n${compactHistoryToolContext(toolContext)}"
    }

    private fun compactHistoryToolContext(text: String): String {
        if (text.isBlank()) return text
        val compact = text.lineSequence()
            .filter { line ->
                line.contains("[GUI_AGENT") ||
                    line.contains("[PYTHON_") ||
                    line.contains("[ADB_") ||
                    line.contains("[HTML_PDF_") ||
                    line.contains("[BROWSE_") ||
                    line.contains(" action=") ||
                    line.contains(" ok=") ||
                    line.contains(" error=") ||
                    line.contains("执行步骤") ||
                    line.contains("命令执行完整输出") ||
                    !text.contains("[GUI_AGENT")
            }
            .joinToString("\n") { it.take(HISTORY_TOOL_LINE_MAX_CHARS) }
        return compact.take(HISTORY_TOOL_CONTEXT_MAX_CHARS)
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
        timezoneId: String,
        nowLocalIso: String,
        config: ProviderConfig
    ): ScheduleBatchPlan? {
        val prompt = """
            你是 ActMe 的云端日程整理助手。你的任务不是看图片，而是基于“端侧图片模型提取出的 OCR 文字结果”生成最终日程列表。
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
            - 你需要从 OCR 文字中判断应创建几条日程，并生成最终结构化结果。
            - 同一图片中多个事项要分别输出为多条 schedules。
            - 没有明确 reminder_time 的事项不要输出。
            - 如果 OCR 文字中信息重复或冲突，你应合并、去重或修正。
            - 不要输出无法支撑的猜测性事项。
            - 最多输出 8 条。

            端侧 OCR 文本：
            $sourceText
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
            "Multi-step workflow: you may call system_calls, observe the returned results, then call more system_calls in the next pass. You have discretion to choose the number and order of tool calls. Use web_search to discover sources, browse_url to inspect specific pages, get_current_time for exact time, and python_exec for calculation, parsing, sorting, deduplication, JSON/CSV/text processing, and other deterministic transformations. For broad, uncertain, recent, or source-sensitive questions, consider browsing multiple non-duplicate pages to confirm information. For simple questions, direct answers or one search may be enough. Do not repeat the same query or URL unless there is a clear reason. Stop calling tools when the answer is sufficiently supported, the user likely wants a quick answer, or the tool budget is nearly exhausted."
        } else {
            ""
        }

        val searchResultsSection = if (!webSearchResults.isNullOrBlank()) {
            "\n联网搜索结果：\n$webSearchResults\n\n如果这些结果已经足够回答当前问题，优先复用它们并给出结论，不要为了形式继续搜索。只有缺少关键证据、来源质量不足、用户明确要求继续核实，或结果之间明显冲突时，才继续调用 web_search/browse_url。\n"
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
            Native Python workflow:
            - Use python_exec whenever deterministic code is useful: calculation, parsing, regex extraction, JSON/CSV/table processing, Excel reading/writing, sorting, deduplication, validation, data transformation, or reusable helper logic.
            - python_exec call format: {"type":"python_exec","code":"print(2+2)\nemit({'answer': 4})","input":"optional text or JSON","timeout_ms":3000,"output_files":["workspace-relative paths of files this call creates, such as report.pdf or sample.xlsx"]}
            - Each python_exec runs Python code in a sandbox. Available variables/functions: input_text, input_json, emit(value), set_result(value), result, workspace_dir, report_font_dir, read_excel(path, max_rows=200, max_sheets=10), write_excel(filename, sheets), write_report(markdown_text, base_name="report", title=None), save_script(name, source), load_script(name), list_scripts(), compile_script(name), run_script(name).
            - You may import standard-library modules and installed Python packages when available, such as struct, csv, json, math, statistics, markdown, numpy, pandas, openpyxl, PIL, matplotlib, reportlab, fpdf, fontTools/fonttools, or defusedxml.
            - The Python sandbox allows broad file read/write/delete/rename access at the Python layer; Android's app sandbox and system permissions are the authority for what actually succeeds. Do not use subprocess, ctypes, multiprocessing, pip, venv, or system shell calls.
            - For any user-visible generated file, write under workspace_dir by using relative filenames/paths such as "report.pdf" or "reports/report.pdf". Do not put absolute /data/... paths in output_files; the app maps relative paths into workspace_dir and shows them in chat.
            - For one-off tasks, put Python directly in code and call emit(value) with the final structured result.
            - For reusable or non-trivial logic, first write a .py script with save_script("tools/name.py", source). Then call compile_script("tools/name.py"). If compile_script returns ok=false, inspect error, fix the source with save_script, compile again, then run_script("tools/name.py"). Do not run an uncompiled reusable script unless the task is urgent and simple.
            - Saved scripts run with the same globals as python_exec, so they can read input_text/input_json, call read_excel/write_excel, and return via emit(value) or result.
            - For uploaded .xlsx/.xlsm files, call read_excel(path) using the workspace path in the user message. For exporting Excel, call write_excel("filename.xlsx", {"Sheet1":[["col"],["value"]]}) and include the filename in output_files. For reports, call write_report(markdown_text, "report_name", title="...") to create Markdown and HTML.
            - To create PDF from a report, first call write_report in python_exec, then call html_to_pdf with url set to the generated .html path and output_files containing the target .pdf path, for example {"type":"html_to_pdf","url":"report.html","output_files":["report.pdf"]}. The host renders HTML to PDF with Android WebView.
            - For PDF/report generation, do not manually register fonts from /system/fonts or other absolute paths, and do not hand-write PDF boilerplate unless both write_report and html_to_pdf fail. report_font_dir is an app-owned runtime font directory outside workspace_dir; it is readable and should not be listed as an output file.
            - When passing long Markdown to write_report, use Python triple-quoted strings: markdown_text = '''# Title\n...'''. Do not put a multi-line report into a normal quoted string such as md = "# Title ...", because embedded quotes and newlines will break Python/JSON parsing.
            - When python_exec creates any file, including PDF, Excel, CSV, image, JSON, or text, fill output_files with every generated relative filename/path.
            Native ADB workflow:
            - Use adb_shell only for diagnostics, read-only inspection, logcat, package listing, settings queries, screenshots, or an explicit low-level ADB command requested by the user as an ADB command.
            - Any operation in another app MUST use gui_agent. Do not use adb_shell to operate third-party app UI, launch app deep links, or chain am/input commands for a user task.
            - adb_shell call format: {"type":"adb_shell","command":"dumpsys window | head -50","timeout_ms":15000}
            - Prefer read-only commands first, such as dumpsys, settings get, pm list, logcat -d, uiautomator dump, or screencap.
            - ADB is powerful. Do not run destructive package/data/file commands unless the user explicitly asks for that exact action.
            Native GUI agent workflow:
            - If the user asks ActMe to operate another Android app or complete a phone UI task, call gui_agent. Do not replace it with adb_shell deep links or manual input commands, even if a deep link seems available.
            - gui_agent call format: {"type":"gui_agent","command":"Open Settings and ...","plan":"1. Open the target app.\n2. Tap the search box.\n3. Type the destination.\n4. Choose the result and start navigation.","target_text":"exact text to type, if any","guidance":"optional correction after reading the previous GUI agent result or error","timeout_ms":120000}
            - The cloud/main agent is the planner. It cannot see screenshots, but it must provide a concise text plan in gui_agent.plan before each GUI execution. The local GUI agent is the visual executor: it sees screenshots and executes the current next action according to command + plan + guidance.
            - Always write gui_agent.command as concise ASCII English. Put the exact destination/search/input string in gui_agent.target_text. The local GUI executor will force type actions to use target_text, because the visual model must not guess or rewrite input text.
            - After a gui_agent run returns [GUI_AGENT_ACTION_ERROR], [GUI_AGENT_ERROR], parse failure, wrong action, wrong click, or stalled app flow, inspect that returned text and call gui_agent again with an updated plan and concise guidance. The guidance field is injected into the GUI model prompt as cloud-agent guidance.
            - If a GUI ADB action failed, do not stop with a generic apology. Use the error to revise the plan, for example: "previous type failed, first tap the visible search box, then paste text".
            - gui_agent uses the saved wireless ADB connection; if ADB is not connected it will start the screenshot-based wireless debugging pairing watcher first.
            - During GUI execution, the app shows a small overlay in the top-right corner. The GUI model receives screenshots and must output one action per step. Coordinates may be normalized 0-1000 or absolute pixels; the executor logs scaling and maps them to the real screenshot size.
            - The GUI loop stops when the model returns answer/terminate, the step budget is reached, or repeated parsing/execution failures occur.
            【关于本 App】
            $systemMemoryText

            【系统能力】
            你拥有以下系统级技能，通过 system_calls 字段调用：
            1. get_current_time — 获取当前精确时间（含时区、星期）
            2. web_search — 联网搜索最新信息，需要提供 query 参数
            3. browse_url — 使用内置浏览器打开网页并读取渲染后的文本，需要提供 url 参数
            4. html_to_pdf — 使用 Android WebView 把工作区 HTML 文件渲染为 PDF，url 填 .html 路径，output_files 填 .pdf 路径
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
        return extractJsonCandidates(text).firstOrNull() ?: text
    }

    fun sanitizeUserVisibleReply(text: String): String {
        if (text.isBlank()) return ""
        var cleaned = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).replace(text) { match ->
            val body = match.groupValues.getOrNull(1).orEmpty()
            if (looksLikeAgentProtocol(body)) "" else match.value
        }
        extractJsonCandidates(cleaned)
            .filter { looksLikeAgentProtocol(it) }
            .forEach { candidate -> cleaned = cleaned.replace(candidate, "") }
        cleaned = stripDanglingProtocolTail(cleaned)
        return cleaned
            .lineSequence()
            .filterNot { line -> looksLikeAgentProtocol(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun stripDanglingProtocolTail(text: String): String {
        val markerIndexes = listOf(
            "\"system_calls\"",
            "\"system_call\"",
            "\"tool_calls\"",
            "\"tool_call\""
        ).map { text.indexOf(it) }.filter { it >= 0 }
        val marker = markerIndexes.minOrNull() ?: return text
        val protocolStart = listOf(
            text.lastIndexOf("{", marker),
            text.lastIndexOf("```", marker)
        ).filter { it >= 0 }.minOrNull() ?: marker
        return text.substring(0, protocolStart)
    }

    private fun looksLikeAgentProtocol(text: String): Boolean {
        val compact = text.take(20_000)
        return compact.contains("\"system_calls\"") ||
            compact.contains("\"system_call\"") ||
            compact.contains("\"tool_calls\"") ||
            compact.contains("\"tool_call\"") ||
            compact.contains("\"type\"") && (
                compact.contains("\"python_exec\"") ||
                    compact.contains("\"web_search\"") ||
                    compact.contains("\"browse_url\"") ||
                    compact.contains("\"browser_url\"") ||
                    compact.contains("\"html_to_pdf\"") ||
                    compact.contains("\"render_html_pdf\"") ||
                    compact.contains("\"webview_pdf\"") ||
                    compact.contains("\"adb_shell\"") ||
                    compact.contains("\"gui_agent\"") ||
                    compact.contains("\"mobile_gui_agent\"") ||
                    compact.contains("\"mobile_use\"") ||
                    compact.contains("\"get_current_time\"")
                )
    }

    private fun extractJsonCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        extractCodeFence(text)?.let { fenced ->
            if (fenced.startsWith("{") && fenced.endsWith("}")) {
                candidates += fenced
            }
        }

        var start = -1
        var depth = 0
        var inString = false
        var escaping = false

        for (i in text.indices) {
            val ch = text[i]
            if (escaping) {
                escaping = false
                continue
            }
            if (ch == '\\' && inString) {
                escaping = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (ch) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            candidates += text.substring(start, i + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return candidates.distinct()
    }

    companion object {
        private const val TAG = "ActMeAgent"

        // response_format injected for OpenAI-compatible providers that support json_schema.
        // Enforces output structure at the token level, preventing the model from nesting
        // tool-call JSON inside the reply field. Unsupported providers fall back gracefully
        // via shouldRetryWithoutResponseFormat in OpenAiResponsesClient.
        val agentResultResponseFormat: JsonObject by lazy {
            Json.parseToJsonElement(
                """{"type":"json_schema","json_schema":{"name":"agent_result","strict":true,"schema":{"type":"object","properties":{"reply":{"type":"string"},"system_calls":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"},"query":{"type":"string"},"url":{"type":"string"},"code":{"type":"string"},"command":{"type":"string"},"input":{"type":"string"},"plan":{"type":"string"},"target_text":{"type":"string"},"guidance":{"type":"string"},"timeout_ms":{"type":"number"},"output_files":{"type":"array","items":{"type":"string"}},"generated_files":{"type":"array","items":{"type":"string"}},"expected_outputs":{"type":"array","items":{"type":"string"}},"files":{"type":"array","items":{"type":"string"}}},"required":["type","query","url","code","command","input","plan","target_text","guidance","timeout_ms","output_files","generated_files","expected_outputs","files"],"additionalProperties":false}},"memory_updates":{"type":"array","items":{"type":"object","properties":{"category":{"type":"string"},"content":{"type":"string"}},"required":["category","content"],"additionalProperties":false}},"schedule_updates":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"detail":{"type":"string"},"start_at":{"anyOf":[{"type":"integer"},{"type":"null"}]},"reminder_at":{"anyOf":[{"type":"integer"},{"type":"null"}]},"repeat_type":{"anyOf":[{"type":"string"},{"type":"null"}]},"repeat_days_of_week":{"type":"array","items":{"type":"integer"}},"repeat_day_of_month":{"anyOf":[{"type":"integer"},{"type":"null"}]},"reminder_time":{"anyOf":[{"type":"string"},{"type":"null"}]}},"required":["title","detail","start_at","reminder_at","repeat_type","repeat_days_of_week","repeat_day_of_month","reminder_time"],"additionalProperties":false}},"skill_updates":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"trigger_keywords":{"type":"array","items":{"type":"string"}},"action_template":{"type":"string"}},"required":["name","description","trigger_keywords","action_template"],"additionalProperties":false}}},"required":["reply","system_calls","memory_updates","schedule_updates","skill_updates"],"additionalProperties":false}}}"""
            ).jsonObject
        }
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
