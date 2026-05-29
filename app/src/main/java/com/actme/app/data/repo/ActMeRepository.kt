package com.actme.app.data.repo

import android.util.Log
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.agent.ScheduleUpdate
import com.actme.app.data.local.ChatDao
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
import com.actme.app.notifications.ReminderScheduler
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

class ActMeRepository(
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao,
    private val scheduleDao: ScheduleDao,
    private val skillDao: SkillDao,
    private val agent: ActMeAgent,
    private val reminderScheduler: ReminderScheduler
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
        Log.i(TAG, "ensure active conversation: id=$id")
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
        Log.i(TAG, "create conversation: id=$id, titleB64=${LogCodec.utf8Base64(title)}")
        id
    }

    suspend fun renameConversation(conversationId: Long, title: String) = withContext(Dispatchers.IO) {
        val session = chatDao.getSessionById(conversationId) ?: return@withContext
        val sanitized = title.trim().ifBlank { session.title }.take(24)
        chatDao.updateSession(session.copy(title = sanitized, updatedAt = System.currentTimeMillis()))
        Log.i(TAG, "rename conversation: id=$conversationId, titleB64=${LogCodec.utf8Base64(sanitized)}")
    }

    suspend fun deleteConversation(conversationId: Long): Long = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesByConversation(conversationId)
        chatDao.deleteSessionById(conversationId)
        val fallbackId = chatDao.getLatestSession()?.id ?: createConversation("新聊天")
        Log.i(TAG, "delete conversation: id=$conversationId, fallbackId=$fallbackId")
        fallbackId
    }

    suspend fun sendMessage(
        conversationId: Long,
        userInput: String,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        Log.i(TAG, "send message begin: conversationId=$conversationId, webSearch=agent_decides, hasImage=${imageBase64 != null}")
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
        val historyMessages = chatDao.getByConversation(conversationId)
            .filter { it.createdAt < now } // 排除刚插入的当前消息

        val result = agent.runTurn(
            userInput = userInput,
            memories = memories,
            schedules = allSchedules,
            skills = enabledSkills,
            enableWebSearch = true,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType,
            historyMessages = historyMessages
        )
        Log.i(
            TAG,
            "agent result: memoryUpdates=${result.memoryUpdates.size}, scheduleUpdates=${result.scheduleUpdates.size}, skillUpdates=${result.skillUpdates.size}"
        )

        val localSkillHints = runLocalSkills(userInput, enabledSkills)
        val finalReply = if (localSkillHints.isBlank()) result.reply else "${result.reply}\n\n$localSkillHints"
        chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = finalReply,
                createdAt = System.currentTimeMillis()
            )
        )
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

        result.scheduleUpdates.forEach { update ->
            // 聊天新增日程走子Agent门卫：先二次结构化，再入库。
            val gateRequest = buildScheduleGateRequest(userInput, update)
            val gateResult = addScheduleBySubAgent(gateRequest)
            if (gateResult.isFailure) {
                Log.i(
                    TAG,
                    "chat schedule gate failed, fallback strict-agent-parse: reasonB64=${
                        LogCodec.utf8Base64(gateResult.exceptionOrNull()?.message)
                    }"
                )
                val strictResult = saveScheduleUpdateStrictlyFromAgent(update)
                if (strictResult.isFailure) {
                    Log.i(
                        TAG,
                        "chat schedule dropped: reasonB64=${LogCodec.utf8Base64(strictResult.exceptionOrNull()?.message)}"
                    )
                }
            } else {
                Log.i(TAG, "chat schedule gated by sub-agent: titleB64=${LogCodec.utf8Base64(update.title)}")
            }
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
        Log.i(TAG, "send message complete: conversationId=$conversationId")
    }

    suspend fun addOrUpdateMemory(item: MemoryItemEntity) = withContext(Dispatchers.IO) {
        if (item.id == 0L) {
            memoryDao.upsert(item)
            Log.i(TAG, "memory created: categoryB64=${LogCodec.utf8Base64(item.category)}")
        } else {
            memoryDao.update(item)
            Log.i(TAG, "memory updated: id=${item.id}, categoryB64=${LogCodec.utf8Base64(item.category)}")
        }
    }

    suspend fun deleteMemoryItem(id: Long) = withContext(Dispatchers.IO) {
        memoryDao.deleteById(id)
        Log.i(TAG, "memory deleted: id=$id")
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
            agent.generateReminderInsight(title, detail)
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
        Log.i(
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
            val plan = agent.runScheduleSubAgent(rawRequest, zoneId, nowLocalIso)
                ?: error("子Agent未返回有效日程结构")

            require(
                plan.repeatType == "NONE" ||
                    plan.repeatType == "DAILY" ||
                    plan.repeatType == "WEEKLY" ||
                    plan.repeatType == "MONTHLY"
            ) { "子Agent返回的 repeat_type 非法：${plan.repeatType}" }
            val repeatType = RepeatType.fromRaw(plan.repeatType)
            val reminderTimeMinutes = parseTimeTextToMinutes(plan.reminderTime)
                ?: error("子Agent返回的 reminder_time 无效")
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
                    require(dateText.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) { "子Agent返回的 one_time_date 无效" }
                    val date = LocalDate.parse(dateText, DATE_FORMATTER)
                    val dateTime = date.atTime(localTime)
                    val millis = dateTime.atZone(zone).toInstant().toEpochMilli()
                    startAt = millis
                    reminderAt = millis
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.DAILY -> {
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.WEEKLY -> {
                    val days = plan.weeklyDays.orEmpty().filter { it in 1..7 }.distinct().sorted()
                    require(days.isNotEmpty()) { "子Agent返回的 weekly_days 为空" }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = days
                    repeatDayOfMonth = null
                }
                RepeatType.MONTHLY -> {
                    val monthDay = plan.monthlyDay?.coerceIn(1, 31)
                        ?: error("子Agent返回的 monthly_day 无效")
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
            Log.i(
                TAG,
                "sub-agent schedule created: titleB64=${LogCodec.utf8Base64(normalizedTitle)}, repeatType=${repeatType.name}, time=${plan.reminderTime}"
            )
            Unit
        }
    }

    private suspend fun saveScheduleUpdateStrictlyFromAgent(update: ScheduleUpdate): Result<Unit> {
        return runCatching {
            val repeatType = RepeatType.fromRaw(update.repeatType)
            val zoneId = ZoneId.systemDefault().id
            val nowMillis = System.currentTimeMillis()
            val reminderRaw = RecurrenceCalculator.normalizeEpochMillis(update.reminderAt)
            val startRaw = RecurrenceCalculator.normalizeEpochMillis(update.startAt)
            val reminderTimeMinutes = parseTimeTextToMinutes(update.reminderTime)
                ?: if (reminderRaw > 0) extractReminderMinutes(reminderRaw, zoneId) else null
                ?: error("主Agent未给出有效提醒时间")

            val repeatDaysFromAgent = update.repeatDaysOfWeek.filter { it in 1..7 }.distinct().sorted()
            val inferredWeekday = if (reminderRaw > 0) {
                Instant.ofEpochMilli(reminderRaw).atZone(ZoneId.of(zoneId)).dayOfWeek.value
            } else null

            val repeatDays = when (repeatType) {
                RepeatType.WEEKLY -> {
                    repeatDaysFromAgent.ifEmpty {
                        inferredWeekday?.let { listOf(it) }
                            ?: error("主Agent未给出 weekly_days")
                    }
                }
                else -> emptyList()
            }

            val repeatDayOfMonth = when (repeatType) {
                RepeatType.MONTHLY -> {
                    update.repeatDayOfMonth?.coerceIn(1, 31)
                        ?: if (reminderRaw > 0) {
                            Instant.ofEpochMilli(reminderRaw).atZone(ZoneId.of(zoneId)).dayOfMonth
                        } else {
                            null
                        }
                        ?: error("主Agent未给出 monthly_day")
                }
                else -> null
            }

            val nextReminder = when (repeatType) {
                RepeatType.NONE -> {
                    require(reminderRaw > 0) { "主Agent未给出一次性 reminder_at" }
                    normalizeOneTimeReminder(reminderRaw, nowMillis)
                }
                else -> {
                    RecurrenceCalculator.computeNextRecurringReminder(
                        repeatType = repeatType,
                        reminderTimeMinutes = reminderTimeMinutes,
                        repeatDaysOfWeek = repeatDays,
                        repeatDayOfMonth = repeatDayOfMonth,
                        timezoneId = zoneId,
                        fromMillis = nowMillis
                    ) ?: error("无法根据主Agent配置计算下一次提醒")
                }
            }

            val insight = runCatching {
                agent.generateReminderInsight(update.title, update.detail)
            }.getOrElse { fallbackInsight(update.title, update.detail) }

            val id = scheduleDao.upsert(
                ScheduleEntity(
                    title = update.title,
                    detail = update.detail,
                    startAt = if (startRaw > 0) startRaw else nextReminder,
                    reminderAt = nextReminder,
                    repeatType = repeatType.name,
                    repeatDaysOfWeek = RecurrenceCalculator.encodeWeekdays(repeatDays),
                    repeatDayOfMonth = repeatDayOfMonth,
                    reminderTimeMinutes = reminderTimeMinutes,
                    timezoneId = zoneId,
                    insight = insight,
                    source = "agent"
                )
            )
            scheduleDao.getById(id)?.let { reminderScheduler.schedule(it) }
            Unit
        }
    }

    suspend fun getScheduleById(id: Long): ScheduleEntity? = withContext(Dispatchers.IO) {
        scheduleDao.getById(id)
    }

    suspend fun deleteSchedule(id: Long) = withContext(Dispatchers.IO) {
        reminderScheduler.cancel(id)
        scheduleDao.deleteById(id)
        Log.i(TAG, "schedule deleted: id=$id")
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
        Log.i(TAG, "reminder rolled: id=$id, nextReminder=$nextReminder")
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
        Log.i(TAG, "rescheduled all reminders")
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

    private fun buildScheduleGateRequest(userInput: String, update: ScheduleUpdate): String {
        val reminderRaw = RecurrenceCalculator.normalizeEpochMillis(update.reminderAt)
        val startRaw = RecurrenceCalculator.normalizeEpochMillis(update.startAt)
        return """
            以下是聊天中待创建的日程候选，请转成最终结构化配置：
            用户原始需求：$userInput
            候选标题：${update.title}
            候选详情：${update.detail}
            候选 repeat_type：${update.repeatType ?: "NONE"}
            候选 reminder_time：${update.reminderTime ?: ""}
            候选 weekly_days：${update.repeatDaysOfWeek.joinToString(",")}
            候选 monthly_day：${update.repeatDayOfMonth ?: ""}
            候选 start_at(ms)：$startRaw
            候选 reminder_at(ms)：$reminderRaw
        """.trimIndent()
    }

    companion object {
        private const val TAG = "ActMeRepository"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
