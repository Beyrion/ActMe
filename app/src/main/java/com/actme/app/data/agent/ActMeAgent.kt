package com.actme.app.data.agent

import com.actme.app.data.local.MemoryCategories
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.MemoryItemEntity
import com.actme.app.data.local.ScheduleEntity
import com.actme.app.data.local.SkillEntity
import com.actme.app.data.remote.MessagePayload
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderConfig
import com.actme.app.plugins.PluginRegistry
import com.actme.app.plugins.SystemToolRegistry
import com.actme.app.util.AppLogger
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject

@Serializable
data class AgentResult(
    val reply: String,
    @SerialName("memory_updates") val memoryUpdates: List<MemoryUpdate> = emptyList(),
    @SerialName("skill_updates") val skillUpdates: List<SkillUpdate> = emptyList(),
    @SerialName("plugin_queries") val pluginQueries: List<String> = emptyList(),
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList()
)

@Serializable
data class MemoryUpdate(
    val category: String,
    val content: String
)

@Serializable
data class SkillUpdate(
    val name: String,
    val description: String,
    @SerialName("trigger_keywords") val triggerKeywords: List<String>,
    @SerialName("action_template") val actionTemplate: String
)

@Serializable
data class ToolCall(
    val plugin: String,
    val tool: String,
    val arguments: Map<String, JsonElement> = emptyMap()
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

class ActMeAgent(private val openAiClient: OpenAiResponsesClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Multi-step agent loop:
     * 1. First pass: agent sees plugin summaries + may output plugin_queries or tool_calls directly.
     * 2. If plugin_queries: inject full tool defs and re-run.
     * 3. If tool_calls: execute via registry, inject results, re-run for final reply.
     * 4. Return when agent outputs a plain reply (no pending queries/calls).
     */
    suspend fun runTurn(
        userInput: String,
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        pluginRegistry: PluginRegistry,
        systemToolRegistry: SystemToolRegistry,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        /** Called just before a tool is executed. Return the DB message ID of the placeholder. */
        onToolCallStarted: (suspend (pluginId: String, toolName: String) -> Long)? = null,
        /** Called after a tool finishes. Update the placeholder identified by [msgId]. */
        onToolCallFinished: (suspend (msgId: Long, pluginId: String, toolName: String, result: com.actme.app.plugins.ToolCallResult) -> Unit)? = null,
    ): AgentResult {
        val messages = buildMessages(
            userInput, memories, schedules, skills, pluginRegistry, systemToolRegistry,
            enableWebSearch, imageBase64, imageMimeType, historyMessages
        ).toMutableList()

        var lastResult = AgentResult(reply = "")
        repeat(MAX_LOOPS) { loop ->
            val raw = openAiClient.run(messages, config = config, enableWebSearch = enableWebSearch)
            lastResult = parseRaw(raw)
            AppLogger.i(TAG, "agent loop $loop: pluginQueries=${lastResult.pluginQueries.size}, toolCalls=${lastResult.toolCalls.size}, hasReply=${lastResult.reply.isNotBlank()}")

            when {
                lastResult.pluginQueries.isNotEmpty() -> {
                    // Inject full tool definitions for requested plugins
                    val toolDefs = lastResult.pluginQueries.joinToString("\n\n") {
                        pluginRegistry.buildToolsPrompt(it)
                    }
                    messages += MessagePayload("assistant", raw)
                    messages += MessagePayload(
                        "user",
                        "[工具定义]\n$toolDefs\n\n请根据以上工具定义完成用户的请求，输出 tool_calls。"
                    )
                }
                lastResult.toolCalls.isNotEmpty() -> {
                    // Execute tools and inject results
                    val resultLines = mutableListOf<String>()
                    for (call in lastResult.toolCalls) {
                        val argsJson = call.arguments.entries
                            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
                        AppLogger.d(TAG, "agent loop $loop executing: ${call.plugin}.${call.tool} args=$argsJson")
                        val msgId = onToolCallStarted?.invoke(call.plugin, call.tool)
                        val result = if (call.plugin == "system") {
                            systemToolRegistry.execute(call.tool, JSONObject(argsJson))
                        } else {
                            pluginRegistry.execute(call.plugin, call.tool, JSONObject(argsJson))
                        }
                        onToolCallFinished?.invoke(msgId ?: -1L, call.plugin, call.tool, result)
                        if (result.success) {
                            AppLogger.i(TAG, "agent loop $loop tool OK: ${call.plugin}.${call.tool} msg=${result.message}")
                        } else {
                            AppLogger.e(TAG, "agent loop $loop tool FAILED: ${call.plugin}.${call.tool} msg=${result.message} data=${result.data}")
                        }
                        resultLines += "[${call.plugin}.${call.tool}] ${if (result.success) "成功" else "失败"}: ${result.message}"
                    }
                    val results = resultLines.joinToString("\n")
                    messages += MessagePayload("assistant", raw)
                    messages += MessagePayload(
                        "user",
                        "[工具执行结果]\n$results\n\n请根据以上结果用中文回复用户。只输出 JSON，格式：{\"reply\":\"...\",\"memory_updates\":[...]}"
                    )
                }
                else -> return lastResult
            }
        }
        return lastResult
    }

    fun runTurnStreaming(
        userInput: String,
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        pluginRegistry: PluginRegistry,
        systemToolRegistry: SystemToolRegistry,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList()
    ): Flow<String> {
        return openAiClient.runStreaming(
            buildMessages(userInput, memories, schedules, skills, pluginRegistry,
                systemToolRegistry, enableWebSearch, imageBase64, imageMimeType, historyMessages),
            config = config,
            enableWebSearch = enableWebSearch
        )
    }

    fun parseRaw(raw: String): AgentResult {
        val jsonPart = extractJson(raw)
        return runCatching { json.decodeFromString<AgentResult>(jsonPart) }.getOrNull()
            ?: AgentResult(reply = raw)
    }

    private fun buildMessages(
        userInput: String,
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        pluginRegistry: PluginRegistry,
        systemToolRegistry: SystemToolRegistry,
        enableWebSearch: Boolean,
        imageBase64: String?,
        imageMimeType: String?,
        historyMessages: List<ChatMessageEntity>
    ): List<MessagePayload> {
        val systemPrompt = buildSystemPrompt(memories, schedules, skills, pluginRegistry, systemToolRegistry, enableWebSearch)
        val messages = mutableListOf<MessagePayload>()
        messages += MessagePayload("system", systemPrompt)
        historyMessages.forEach { entity ->
            messages += MessagePayload(
                role = entity.role,
                content = entity.content,
                imageBase64 = entity.imageBase64,
                imageMimeType = entity.imageMimeType
            )
        }
        messages += MessagePayload("user", userInput, imageBase64 = imageBase64, imageMimeType = imageMimeType)
        return messages
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
        AppLogger.d(TAG, "schedule sub-agent raw response (${raw.length} chars): ${raw.take(600)}")
        val jsonPart = extractJson(raw)
        val parsed = runCatching { json.decodeFromString<ScheduleSubAgentPlan>(jsonPart) }.getOrNull()
        if (parsed != null) {
            AppLogger.i(TAG, "schedule sub-agent parsed: title=${parsed.title} repeatType=${parsed.repeatType} " +
                "reminderTime=${parsed.reminderTime} oneTimeDate=${parsed.oneTimeDate} " +
                "weeklyDays=${parsed.weeklyDays} monthlyDay=${parsed.monthlyDay}")
        } else {
            AppLogger.e(TAG, "schedule sub-agent parse failed, jsonPart: ${jsonPart.take(300)}")
        }
        AppLogger.i(TAG, "schedule sub-agent parsed=${parsed != null}")
        return parsed
    }

    private fun buildSystemPrompt(
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        pluginRegistry: PluginRegistry,
        systemToolRegistry: SystemToolRegistry,
        enableWebSearch: Boolean
    ): String {
        val localZone = ZoneId.systemDefault().id
        val now = java.time.Instant.now()
        val nowEpochMs = now.toEpochMilli()
        val nowLocal = now.atZone(java.time.ZoneId.of(localZone))
        val nowLocalStr = nowLocal.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val memoryText = if (memories.isEmpty()) "暂无" else memories
            .take(40)
            .joinToString("\n") { "- [${it.category}] ${it.content}" }
        val scheduleText = if (schedules.isEmpty()) "暂无" else schedules
            .take(20)
            .joinToString("\n") { "- [${it.id}] ${it.title} @${it.reminderAt} (${it.repeatType})" }
        val skillText = if (skills.isEmpty()) "暂无" else skills
            .take(20)
            .joinToString("\n") { "- ${it.name}: ${it.triggerKeywords}" }

        val webSearchHint = if (enableWebSearch) {
            "当前轮已开启联网查询能力：请你自行判断是否需要联网。只有在涉及最新信息、外部事实核验、价格/新闻/政策等场景时才调用联网工具。"
        } else {
            "当前轮未开启联网查询，请仅基于已有信息回复。"
        }

        val systemToolsPrompt = systemToolRegistry.buildPrompt()
        val pluginToolsPrompt = pluginRegistry.buildAllToolsPrompt()

        return """
            你是 App 内置的 ActMe Agent，必须用中文回复。
            目标：帮助用户行动、整理记忆、安排日程、维护技能。
            记忆分类只能使用：${MemoryCategories.all.joinToString("、")}。
            $webSearchHint
            当前时间：$nowLocalStr（$localZone 时区）
            当前 Unix 毫秒时间戳：$nowEpochMs（timestamp_now）
            时间字段一律输出 Unix 毫秒时间戳（不是秒）。计算 reminder_at 时，以 timestamp_now 为基准：今天 = timestamp_now 所在日期的指定时间，明天 = 加 86400000 ms，依此类推。

            $systemToolsPrompt

            $pluginToolsPrompt

            只输出 JSON，不要 Markdown，不要额外解释。格式如下：
            {
              "reply": "给用户的中文回复",
              "memory_updates": [{"category":"短期目标","content":"..."}],
              "skill_updates": [{"name":"...","description":"...","trigger_keywords":["..."],"action_template":"..."}],
              "plugin_queries": ["builtin.schedule"],
              "tool_calls": [{"plugin":"builtin.schedule","tool":"create_schedule","arguments":{...}}]
            }

            说明：
            - 系统工具（plugin="system"）参数格式已在 [系统工具] 中完整列出，可直接调用无需 plugin_queries。
            - 如果需要调用插件工具但不清楚具体参数格式，先输出 plugin_queries，系统会注入工具定义后再次调用你。
            - 如果已知工具参数格式，直接输出 tool_calls，不用先查询。
            - 纯对话、记忆更新等不需要工具的请求，直接输出 reply 即可，无需 plugin_queries 或 tool_calls。
            - 创建日程时，repeat_type=NONE 需给出 reminder_at（Unix ms），重复类型需给 reminder_time（HH:mm）。
            - 日程 id 参见下方 [当前日程] 列表中的 [id]。

            当前记忆：
            $memoryText

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
        private const val MAX_LOOPS = 5
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
                    break
                }
            } else if (c == '"') {
                state = 2
                scanPos = i + 1
                break
            } else {
                result.append(c)
                i++
            }
        }
        if (state == 1) scanPos = i

        return result.toString().ifEmpty { null }
    }

    fun getRaw(): String = raw.toString()
}
