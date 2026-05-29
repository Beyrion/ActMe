package com.actme.app.data.agent

import com.actme.app.data.local.MemoryCategories
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.MemoryItemEntity
import com.actme.app.data.local.ScheduleEntity
import com.actme.app.data.local.SkillEntity
import com.actme.app.data.remote.MessagePayload
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderConfig
import android.util.Log
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentResult(
    val reply: String,
    @SerialName("memory_updates") val memoryUpdates: List<MemoryUpdate> = emptyList(),
    @SerialName("schedule_updates") val scheduleUpdates: List<ScheduleUpdate> = emptyList(),
    @SerialName("skill_updates") val skillUpdates: List<SkillUpdate> = emptyList()
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

class ActMeAgent(private val openAiClient: OpenAiResponsesClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun runTurn(
        userInput: String,
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        historyMessages: List<ChatMessageEntity> = emptyList()
    ): AgentResult {
        val systemPrompt = buildSystemPrompt(memories, schedules, skills, enableWebSearch)
        val messages = mutableListOf<MessagePayload>()
        messages += MessagePayload("system", systemPrompt)

        // 添加历史消息（按时间顺序）
        historyMessages.forEach { entity ->
            messages += MessagePayload(
                role = entity.role,
                content = entity.content,
                imageBase64 = entity.imageBase64,
                imageMimeType = entity.imageMimeType
            )
        }

        // 添加当前用户消息
        messages += MessagePayload("user", userInput, imageBase64 = imageBase64, imageMimeType = imageMimeType)

        val raw = openAiClient.run(messages, config = config, enableWebSearch = enableWebSearch)

        val jsonPart = extractJson(raw)
        val parsed = runCatching { json.decodeFromString<AgentResult>(jsonPart) }.getOrNull()
        return parsed ?: AgentResult(reply = raw)
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
        Log.i(TAG, "schedule sub-agent parsed=${parsed != null}")
        return parsed
    }

    private fun buildSystemPrompt(
        memories: List<MemoryItemEntity>,
        schedules: List<ScheduleEntity>,
        skills: List<SkillEntity>,
        enableWebSearch: Boolean
    ): String {
        val localZone = ZoneId.systemDefault().id
        val memoryText = if (memories.isEmpty()) "暂无" else memories
            .take(40)
            .joinToString("\n") { "- [${it.category}] ${it.content}" }
        val scheduleText = if (schedules.isEmpty()) "暂无" else schedules
            .take(20)
            .joinToString("\n") { "- ${it.title} @${it.reminderAt} (${it.repeatType})" }
        val skillText = if (skills.isEmpty()) "暂无" else skills
            .take(20)
            .joinToString("\n") { "- ${it.name}: ${it.triggerKeywords}" }

        val webSearchHint = if (enableWebSearch) {
            "当前轮已开启联网查询能力：请你自行判断是否需要联网。只有在涉及最新信息、外部事实核验、价格/新闻/政策等场景时才调用联网工具。"
        } else {
            "当前轮未开启联网查询，请仅基于已有信息回复。"
        }

        return """
            你是 App 内置的 ActMe Agent，必须用中文回复。
            目标：帮助用户行动、整理记忆、安排日程、维护技能。
            记忆分类只能使用：${MemoryCategories.all.joinToString("、")}。
            你可以在每次对话中判断是否需要写入 memory_updates 或 schedule_updates。
            若用户提出可复用策略，可以写入 skill_updates。
            $webSearchHint
            当前用户本地时区：$localZone。
            时间字段一律输出 Unix 毫秒时间戳（不是秒），并严格按本地时区理解“今天/明天/每天12点”等表达。
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
              "skill_updates": [{"name":"...","description":"...","trigger_keywords":["..."],"action_template":"..."}]
            }
            说明：
            - 一次性提醒：repeat_type 用 NONE，并给出 reminder_at。
            - 每天提醒：repeat_type 用 DAILY，并给出 reminder_time(如 12:00)。
            - 每周提醒：repeat_type 用 WEEKLY，并给出 repeat_days_of_week(1=周一...7=周日) 与 reminder_time。
            - 每月提醒：repeat_type 用 MONTHLY，并给出 repeat_day_of_month 与 reminder_time。

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
    }
}
