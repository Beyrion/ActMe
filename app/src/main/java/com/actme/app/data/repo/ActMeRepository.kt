package com.actme.app.data.repo

import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.local.ChatDao
import com.actme.app.data.local.ProviderEntity
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.ChatSessionEntity
import com.actme.app.data.local.MemoryDao
import com.actme.app.data.local.MemoryItemEntity
import com.actme.app.data.local.RecurrenceCalculator
import com.actme.app.data.local.RepeatType
import com.actme.app.data.local.ScheduleDao
import com.actme.app.data.local.ScheduleEntity
import com.actme.app.data.local.SkillDao
import com.actme.app.data.local.SkillEntity
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderConfig
import com.actme.app.data.remote.ProviderManager
import com.actme.app.notifications.ReminderScheduler
import com.actme.app.plugins.PluginRegistry
import com.actme.app.plugins.SystemToolRegistry
import com.actme.app.util.AppLogger
import com.actme.app.util.LogCodec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

class ActMeRepository(
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao,
    private val scheduleDao: ScheduleDao,
    private val skillDao: SkillDao,
    private val agent: ActMeAgent,
    private val reminderScheduler: ReminderScheduler,
    private val providerManager: ProviderManager,
    private val openAiClient: OpenAiResponsesClient,
    val pluginRegistry: PluginRegistry = PluginRegistry(),
    val systemToolRegistry: SystemToolRegistry = SystemToolRegistry()
) {
    val chatSessions: Flow<List<ChatSessionEntity>> = chatDao.observeSessions()

    suspend fun getMessageCount(conversationId: Long): Int = withContext(Dispatchers.IO) {
        chatDao.getMessageCount(conversationId)
    }
    val schedules: Flow<List<ScheduleEntity>> = scheduleDao.observeAll()
    val skills: Flow<List<SkillEntity>> = skillDao.observeAll()

    fun observeChatMessages(conversationId: Long): Flow<List<ChatMessageEntity>> {
        return chatDao.observeByConversation(conversationId)
    }

    fun observeMemory(category: String): Flow<List<MemoryItemEntity>> = memoryDao.observeByCategory(category)
    fun observeMemoryItem(id: Long): Flow<MemoryItemEntity?> = memoryDao.observeById(id)

    suspend fun ensureActiveConversationId(): Long = withContext(Dispatchers.IO) {
        val id = chatDao.getLatestSession()?.id ?: createConversation("新聊天")
        AppLogger.i(TAG, "ensure active conversation: id=$id")
        id
    }

    suspend fun createConversation(title: String = "新聊天"): Long = withContext(Dispatchers.IO) {
        val id = chatDao.insertSession(
            ChatSessionEntity(
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        AppLogger.i(TAG, "create conversation: id=$id, titleB64=${LogCodec.utf8Base64(title)}")
        id
    }

    suspend fun renameConversation(conversationId: Long, title: String) = withContext(Dispatchers.IO) {
        val session = chatDao.getSessionById(conversationId) ?: return@withContext
        val sanitized = title.trim().ifBlank { session.title }.take(24)
        chatDao.updateSession(session.copy(title = sanitized, updatedAt = System.currentTimeMillis()))
        AppLogger.i(TAG, "rename conversation: id=$conversationId, titleB64=${LogCodec.utf8Base64(sanitized)}")
    }

    suspend fun deleteConversation(conversationId: Long): Long = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesByConversation(conversationId)
        chatDao.deleteSessionById(conversationId)
        val fallbackId = chatDao.getLatestSession()?.id ?: createConversation("新聊天")
        AppLogger.i(TAG, "delete conversation: id=$conversationId, fallbackId=$fallbackId")
        fallbackId
    }

    suspend fun sendMessage(
        conversationId: Long,
        userInput: String,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        AppLogger.i(TAG, "sendMessage: conv=$conversationId hasImage=${imageBase64 != null} input=${userInput.take(80)}")
        chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "user",
                content = userInput,
                imageBase64 = imageBase64,
                imageMimeType = imageMimeType,
                createdAt = now
            )
        )
        touchConversation(conversationId, titleCandidate = userInput)

        val memories = memoryDao.getAllNow()
        val allSchedules = scheduleDao.getAllNow()
        val enabledSkills = skillDao.getEnabledNow()
        // Exclude tool_call placeholder messages from LLM history — they're UI-only.
        val historyMessages = chatDao.getByConversation(conversationId)
            .filter { it.createdAt < now && it.role != "tool_call" }
        AppLogger.d(TAG, "context: memories=${memories.size} schedules=${allSchedules.size} skills=${enabledSkills.size} history=${historyMessages.size}")

        val config = buildProviderConfig()

        val result = try {
            agent.runTurn(
                userInput = userInput,
                memories = memories,
                schedules = allSchedules,
                skills = enabledSkills,
                pluginRegistry = pluginRegistry,
                systemToolRegistry = systemToolRegistry,
                config = config,
                enableWebSearch = true,
                imageBase64 = imageBase64,
                imageMimeType = imageMimeType,
                historyMessages = historyMessages,
                onToolCallStarted = { pluginId, toolName ->
                    val displayName = if (pluginId == "system") {
                        systemToolRegistry.getDisplayName()
                    } else {
                        pluginRegistry.get(pluginId)?.name ?: pluginId
                    }
                    chatDao.insert(ChatMessageEntity(
                        conversationId = conversationId,
                        role = "tool_call",
                        content = "⏳ $displayName · $toolName",
                        createdAt = System.currentTimeMillis()
                    ))
                },
                onToolCallFinished = { msgId, pluginId, toolName, result ->
                    if (msgId > 0) {
                        chatDao.updateContent(msgId, if (result.success) "✓ ${result.message}" else "✗ ${result.message}")
                        if (result.success && pluginId != "system") {
                            val plugin = pluginRegistry.get(pluginId)
                            val cardHtml = plugin?.getCardHtml(toolName, result.data)
                            if (cardHtml != null) {
                                val navRoute = plugin.composeRoute ?: "plugin/$pluginId"
                                val meta = org.json.JSONObject()
                                    .put("cardHtml", cardHtml)
                                    .put("cardData", org.json.JSONObject(result.data as Map<*, *>))
                                    .put("navRoute", navRoute)
                                    .put("pluginName", plugin.name)
                                    .toString()
                                chatDao.updateMetadata(msgId, meta)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "agent.runTurn failed: ${e.message}", e)
            com.actme.app.data.agent.AgentResult(reply = "出错了，请稍后再试。")
        }

        AppLogger.i(TAG, "agent result: memoryUpdates=${result.memoryUpdates.size} toolCalls=${result.toolCalls.size} pluginQueries=${result.pluginQueries.size} replyLen=${result.reply.length}")

        val localSkillHints = runLocalSkills(userInput, enabledSkills)
        val finalReply = if (localSkillHints.isBlank()) result.reply else "${result.reply}\n\n$localSkillHints"
        // Insert reply AFTER any tool_call cards so ordering in the chat is correct.
        chatDao.insert(ChatMessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = finalReply,
            createdAt = System.currentTimeMillis()
        ))
        touchConversation(conversationId)

        result.memoryUpdates.forEach { update ->
            memoryDao.upsert(
                MemoryItemEntity(
                    category = update.category,
                    content = update.content,
                    source = "agent"
                )
            )
        }

        result.skillUpdates.forEach { update ->
            skillDao.upsert(
                SkillEntity(
                    name = update.name,
                    description = update.description,
                    triggerKeywords = Json.encodeToString(update.triggerKeywords),
                    actionTemplate = update.actionTemplate,
                    enabled = true
                )
            )
        }
        AppLogger.i(TAG, "sendMessage complete: conv=$conversationId")
    }

    suspend fun updateCardHeight(msgId: Long, heightDp: Float) = withContext(Dispatchers.IO) {
        val msg = chatDao.getMessageById(msgId) ?: return@withContext
        val meta = runCatching {
            val obj = org.json.JSONObject(msg.metadata ?: return@withContext)
            obj.put("cardHeightDp", heightDp)
            obj.toString()
        }.getOrNull() ?: return@withContext
        chatDao.updateMetadata(msgId, meta)
    }

    suspend fun addOrUpdateMemory(item: MemoryItemEntity) = withContext(Dispatchers.IO) {
        if (item.id == 0L) {
            memoryDao.upsert(item)
            AppLogger.i(TAG, "memory created: categoryB64=${LogCodec.utf8Base64(item.category)}")
        } else {
            memoryDao.update(item)
            AppLogger.i(TAG, "memory updated: id=${item.id}, categoryB64=${LogCodec.utf8Base64(item.category)}")
        }
    }

    suspend fun deleteMemoryItem(id: Long) = withContext(Dispatchers.IO) {
        memoryDao.deleteById(id)
        AppLogger.i(TAG, "memory deleted: id=$id")
    }

    suspend fun addManualSchedule(
        title: String,
        detail: String,
        startAt: Long,
        reminderAt: Long,
        repeatType: RepeatType,
        repeatDaysOfWeek: List<Int>,
        repeatDayOfMonth: Int?,
        reminderTimeMinutes: Int
    ) = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault().id
        val nowMillis = System.currentTimeMillis()
        val normalizedReminderAt = RecurrenceCalculator.normalizeEpochMillis(reminderAt)
        val nextReminder = if (repeatType == RepeatType.NONE) {
            normalizeOneTimeReminder(normalizedReminderAt, nowMillis)
        } else {
            RecurrenceCalculator.computeNextRecurringReminder(
                repeatType = repeatType,
                reminderTimeMinutes = reminderTimeMinutes,
                repeatDaysOfWeek = repeatDaysOfWeek,
                repeatDayOfMonth = repeatDayOfMonth,
                timezoneId = zoneId,
                fromMillis = nowMillis
            ) ?: normalizeOneTimeReminder(normalizedReminderAt, nowMillis)
        }

        val insight = runCatching {
            agent.generateReminderInsight(title, detail, buildProviderConfig())
        }.getOrElse { fallbackInsight(title, detail) }

        val id = scheduleDao.upsert(
            ScheduleEntity(
                title = title,
                detail = detail,
                startAt = startAt,
                reminderAt = nextReminder,
                repeatType = repeatType.name,
                repeatDaysOfWeek = RecurrenceCalculator.encodeWeekdays(repeatDaysOfWeek),
                repeatDayOfMonth = repeatDayOfMonth,
                reminderTimeMinutes = reminderTimeMinutes,
                timezoneId = zoneId,
                insight = insight,
                source = "manual"
            )
        )
        scheduleDao.getById(id)?.let { reminderScheduler.schedule(it) }
        AppLogger.i(
            TAG,
            "manual schedule created: id=$id, repeatType=${repeatType.name}, nextReminder=$nextReminder"
        )
    }

    suspend fun addScheduleBySubAgent(rawRequest: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zone = ZoneId.systemDefault()
            val zoneId = zone.id
            val nowMillis = System.currentTimeMillis()
            val nowLocalIso = Instant.ofEpochMilli(nowMillis).atZone(zone).toString()
            AppLogger.d(TAG, "sub-agent schedule: rawRequest=${rawRequest.take(200)} zone=$zoneId now=$nowLocalIso")

            val plan = agent.runScheduleSubAgent(rawRequest, zoneId, nowLocalIso, buildProviderConfig())
            if (plan == null) {
                AppLogger.e(TAG, "sub-agent returned null plan — giving up")
                error("子Agent未返回有效日程结构")
            }
            AppLogger.d(TAG, "sub-agent plan received: repeatType=${plan.repeatType} " +
                "reminderTime=${plan.reminderTime} oneTimeDate=${plan.oneTimeDate} " +
                "weeklyDays=${plan.weeklyDays} monthlyDay=${plan.monthlyDay} " +
                "title=${plan.title}")

            require(
                plan.repeatType == "NONE" ||
                    plan.repeatType == "DAILY" ||
                    plan.repeatType == "WEEKLY" ||
                    plan.repeatType == "MONTHLY"
            ) { "子Agent返回的 repeat_type 非法：${plan.repeatType}" }
            val repeatType = RepeatType.fromRaw(plan.repeatType)
            AppLogger.d(TAG, "sub-agent repeatType parsed: $repeatType")

            val reminderTimeMinutes = parseTimeTextToMinutes(plan.reminderTime)
            if (reminderTimeMinutes == null) {
                AppLogger.e(TAG, "sub-agent reminderTime parse failed: raw=${plan.reminderTime}")
                error("子Agent返回的 reminder_time 无效")
            }
            AppLogger.d(TAG, "sub-agent reminderTimeMinutes=$reminderTimeMinutes (${reminderTimeMinutes/60}:${String.format("%02d", reminderTimeMinutes%60)})")
            val localTime = LocalTime.of(reminderTimeMinutes / 60, reminderTimeMinutes % 60)

            val normalizedTitle = plan.title.trim().ifBlank {
                rawRequest.trim().take(16).ifBlank { "新日程" }
            }
            val normalizedDetail = plan.detail?.trim().orEmpty()

            val startAt: Long
            val reminderAt: Long
            val repeatDays: List<Int>
            val repeatDayOfMonth: Int?

            when (repeatType) {
                RepeatType.NONE -> {
                    val dateText = plan.oneTimeDate?.trim().orEmpty()
                    AppLogger.d(TAG, "sub-agent NONE: oneTimeDate=${plan.oneTimeDate}")
                    require(dateText.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
                        AppLogger.e(TAG, "sub-agent NONE: invalid date format: '$dateText'")
                        "子Agent返回的 one_time_date 无效"
                    }
                    val date = LocalDate.parse(dateText, DATE_FORMATTER)
                    val dateTime = date.atTime(localTime)
                    val millis = dateTime.atZone(zone).toInstant().toEpochMilli()
                    AppLogger.d(TAG, "sub-agent NONE: resolved millis=$millis ($dateTime)")
                    startAt = millis
                    reminderAt = millis
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.DAILY -> {
                    AppLogger.d(TAG, "sub-agent DAILY: reminderTime=$localTime")
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.WEEKLY -> {
                    val days = plan.weeklyDays.orEmpty().filter { it in 1..7 }.distinct().sorted()
                    AppLogger.d(TAG, "sub-agent WEEKLY: rawDays=${plan.weeklyDays} parsedDays=$days reminderTime=$localTime")
                    require(days.isNotEmpty()) {
                        AppLogger.e(TAG, "sub-agent WEEKLY: weekly_days empty after filter")
                        "子Agent返回的 weekly_days 为空"
                    }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = days
                    repeatDayOfMonth = null
                }
                RepeatType.MONTHLY -> {
                    val monthDay = plan.monthlyDay?.coerceIn(1, 31)
                    AppLogger.d(TAG, "sub-agent MONTHLY: rawMonthlyDay=${plan.monthlyDay} clamped=$monthDay reminderTime=$localTime")
                    if (monthDay == null) {
                        AppLogger.e(TAG, "sub-agent MONTHLY: monthly_day is null")
                        error("子Agent返回的 monthly_day 无效")
                    }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = emptyList()
                    repeatDayOfMonth = monthDay
                }
            }

            addManualSchedule(
                title = normalizedTitle,
                detail = normalizedDetail,
                startAt = startAt,
                reminderAt = reminderAt,
                repeatType = repeatType,
                repeatDaysOfWeek = repeatDays,
                repeatDayOfMonth = repeatDayOfMonth,
                reminderTimeMinutes = reminderTimeMinutes
            )
            AppLogger.i(
                TAG,
                "sub-agent schedule SUCCESS: title=${normalizedTitle} repeatType=${repeatType.name} " +
                    "reminderTimeMinutes=$reminderTimeMinutes startAt=$startAt"
            )
            AppLogger.i(
                TAG,
                "sub-agent schedule created: titleB64=${LogCodec.utf8Base64(normalizedTitle)}, repeatType=${repeatType.name}, time=${plan.reminderTime}"
            )
            Unit
        }.onFailure { e ->
            AppLogger.e(TAG, "sub-agent schedule FAILED: ${e.message}", e)
        }
    }

    private suspend fun saveScheduleUpdateStrictlyFromAgent(title: String, detail: String, reminderAt: Long, repeatType: RepeatType, repeatDays: List<Int>, repeatDayOfMonth: Int?, reminderTimeMinutes: Int): Result<Unit> {
        return runCatching {
            val zoneId = ZoneId.systemDefault().id
            val nowMillis = System.currentTimeMillis()
            val nextReminder = when (repeatType) {
                RepeatType.NONE -> {
                    require(reminderAt > 0) { "一次性提醒缺少 reminder_at" }
                    if (reminderAt > nowMillis) reminderAt else nowMillis + 60_000
                }
                else -> RecurrenceCalculator.computeNextRecurringReminder(
                    repeatType, reminderTimeMinutes, repeatDays, repeatDayOfMonth, zoneId, nowMillis
                ) ?: error("无法计算下次提醒")
            }
            val id = scheduleDao.upsert(
                ScheduleEntity(
                    title = title, detail = detail,
                    startAt = nextReminder, reminderAt = nextReminder,
                    repeatType = repeatType.name,
                    repeatDaysOfWeek = RecurrenceCalculator.encodeWeekdays(repeatDays),
                    repeatDayOfMonth = repeatDayOfMonth,
                    reminderTimeMinutes = reminderTimeMinutes,
                    timezoneId = zoneId, source = "agent"
                )
            )
            scheduleDao.getById(id)?.let { reminderScheduler.schedule(it) }
        }
    }

    suspend fun getScheduleById(id: Long): ScheduleEntity? = withContext(Dispatchers.IO) {
        scheduleDao.getById(id)
    }

    suspend fun deleteSchedule(id: Long) = withContext(Dispatchers.IO) {
        reminderScheduler.cancel(id)
        scheduleDao.deleteById(id)
        AppLogger.i(TAG, "schedule deleted: id=$id")
    }

    suspend fun onReminderTriggered(id: Long) = withContext(Dispatchers.IO) {
        val schedule = scheduleDao.getById(id) ?: return@withContext
        val repeatType = RepeatType.fromRaw(schedule.repeatType)
        if (repeatType == RepeatType.NONE) return@withContext

        val nextReminder = RecurrenceCalculator.computeNextReminderFromSchedule(
            entity = schedule,
            fromMillis = System.currentTimeMillis()
        ) ?: return@withContext

        val updated = schedule.copy(reminderAt = nextReminder)
        scheduleDao.upsert(updated)
        reminderScheduler.schedule(updated)
        AppLogger.i(TAG, "reminder rolled: id=$id, nextReminder=$nextReminder")
    }

    suspend fun rescheduleAllReminders() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        scheduleDao.getAllNow().forEach { schedule ->
            val next = RecurrenceCalculator.computeNextReminderFromSchedule(schedule, now) ?: return@forEach
            val updated = if (next == schedule.reminderAt) schedule else schedule.copy(reminderAt = next)
            if (updated != schedule) {
                scheduleDao.upsert(updated)
            }
            reminderScheduler.schedule(updated)
        }
        AppLogger.i(TAG, "rescheduled all reminders")
    }

    private fun runLocalSkills(userInput: String, skills: List<SkillEntity>): String {
        if (skills.isEmpty()) return ""
        val text = userInput.lowercase()
        val outputs = mutableListOf<String>()
        for (skill in skills) {
            val keywords = runCatching {
                Json.parseToJsonElement(skill.triggerKeywords).jsonArray.map { it.jsonPrimitive.content.lowercase() }
            }.getOrDefault(emptyList())
            if (keywords.any { text.contains(it) }) {
                outputs += "【${skill.name}】${skill.actionTemplate}"
            }
        }
        return outputs.joinToString("\n")
    }

    private fun fallbackInsight(title: String, detail: String): String {
        val source = "$title $detail"
        return when {
            source.contains("面试") -> "面试提醒：提前 10 分钟准备自我介绍、岗位亮点和 2 个 STAR 案例。"
            source.contains("久坐") || source.contains("肩颈") -> "久坐提醒：现在站立活动 3-5 分钟，做肩颈与髋部拉伸各 1 组。"
            source.contains("吃饭") || source.contains("午餐") || source.contains("晚餐") -> "饮食提醒：优先蛋白质+蔬菜，再补主食，减少高糖饮料。"
            source.contains("短视频") || source.contains("刷") -> "专注提醒：先完成 20 分钟专注块，再给自己 5 分钟休息。"
            else -> "执行建议：把这件事拆成 1 个立刻可做的小动作，并在 10 分钟内开始。"
        }
    }

    private suspend fun touchConversation(conversationId: Long, titleCandidate: String? = null) {
        val session = chatDao.getSessionById(conversationId) ?: return
        val nextTitle = if (session.title == "新聊天" && !titleCandidate.isNullOrBlank()) {
            titleCandidate.trim().take(14).ifBlank { session.title }
        } else {
            session.title
        }
        chatDao.updateSession(session.copy(title = nextTitle, updatedAt = System.currentTimeMillis()))
    }

    private fun normalizeOneTimeReminder(reminderAt: Long, nowMillis: Long): Long {
        if (reminderAt > nowMillis) return reminderAt
        return nowMillis + 60_000
    }

    private fun extractReminderMinutes(reminderAt: Long, zoneId: String): Int {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        val local = Instant.ofEpochMilli(reminderAt).atZone(zone)
        return local.hour * 60 + local.minute
    }

    private fun parseTimeTextToMinutes(raw: String?): Int? {
        val content = raw?.trim().orEmpty()
        if (!content.matches(Regex("^\\d{1,2}:\\d{2}$"))) return null
        val hour = content.substringBefore(":").toIntOrNull() ?: return null
        val minute = content.substringAfter(":").toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun buildScheduleGateRequest(userInput: String, title: String, detail: String): String {
        return "用户需求：$userInput\n候选标题：$title\n候选详情：$detail"
    }

    // ---- Provider management ----

    private suspend fun buildProviderConfig(): ProviderConfig {
        val provider = providerManager.getActiveProvider()
        if (provider == null) {
            return ProviderConfig("openai", "", "", "")
        }
        val sk = providerManager.getSk(provider.id)
        val model = providerManager.getLastModel(provider.id).ifBlank { "" }
        return ProviderConfig(provider.providerFormat, provider.endpoint, sk, model)
    }

    val providers = providerManager.providers
    val activeProviderIdFlow = providerManager.activeProviderIdFlow

    suspend fun addProvider(name: String, format: String, endpoint: String, sk: String): Long {
        return providerManager.addProvider(name, format, endpoint, sk)
    }

    suspend fun updateProvider(id: Long, name: String, format: String, endpoint: String, sk: String) {
        providerManager.updateProvider(id, name, format, endpoint, sk)
    }

    suspend fun deleteProvider(id: Long) {
        providerManager.deleteProvider(id)
    }

    fun setActiveProvider(id: Long) {
        providerManager.setActiveProviderId(id)
    }

    fun getActiveProviderId(): Long {
        return providerManager.getActiveProviderId()
    }

    suspend fun getActiveProvider(): ProviderEntity? {
        return providerManager.getActiveProvider()
    }

    fun getProviderSk(id: Long): String {
        return providerManager.getSk(id)
    }

    fun getLastModel(providerId: Long): String {
        return providerManager.getLastModel(providerId)
    }

    fun setLastModel(providerId: Long, model: String) {
        providerManager.setLastModel(providerId, model)
    }

    suspend fun fetchModels(): List<String> {
        val provider = providerManager.getActiveProvider() ?: return emptyList()
        val sk = providerManager.getSk(provider.id)
        return openAiClient.fetchModels(provider.endpoint, sk, provider.providerFormat)
    }

    companion object {
        private const val TAG = "ActMeRepository"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
