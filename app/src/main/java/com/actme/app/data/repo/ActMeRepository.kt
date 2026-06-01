package com.actme.app.data.repo

import com.actme.app.util.AppLogger
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.agent.ReplyExtractor
import com.actme.app.data.agent.SystemCall
import com.actme.app.data.agent.SystemSkillExecutor
import com.actme.app.data.agent.ScheduleUpdate
import com.actme.app.data.agent.ScheduleSubAgentPlan
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
import com.actme.app.data.remote.TokenUsage
import com.actme.app.notifications.ReminderScheduler
import com.actme.app.util.LogCodec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private val reminderScheduler: ReminderScheduler,
    private val providerManager: ProviderManager,
    private val openAiClient: OpenAiResponsesClient
) {
    private enum class StepStatus { RUNNING, DONE, FAILED, SKIPPED }

    private data class AgentRunStep(
        val index: Int,
        val title: String,
        val detail: String = "",
        val status: StepStatus = StepStatus.RUNNING
    )

    private data class ToolBudget(
        val maxPasses: Int = 6,
        val maxToolCalls: Int = 12,
        val maxSearchCalls: Int = 4,
        val maxBrowseCalls: Int = 8,
        var toolCalls: Int = 0,
        var searchCalls: Int = 0,
        var browseCalls: Int = 0
    )

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
        AppLogger.i(TAG, "send message begin: conversationId=$conversationId, hasImage=${imageBase64 != null}")
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

        val userMemories = memoryDao.getUserMemories()
        val systemMemories = memoryDao.getSystemMemories()
        val allSchedules = scheduleDao.getAllNow()
        val enabledSkills = skillDao.getEnabledNow()
        val historyMessages = chatDao.getByConversation(conversationId)
            .filter { it.createdAt < now }

        val config = buildProviderConfig()

        // Insert placeholder assistant message immediately so the bubble appears during streaming
        val streamingMsgId = chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis()
            )
        )

        val extractor = ReplyExtractor()
        val displayBuilder = StringBuilder()
        val runSteps = mutableListOf<AgentRunStep>()
        val budget = ToolBudget()
        val searchedQueries = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()
        var nextStepIndex = 1
        var totalApiUsage: TokenUsage? = null

        fun addApiUsage(usage: TokenUsage?) {
            if (usage == null) return
            totalApiUsage = totalApiUsage?.plus(usage) ?: usage
        }

        fun mergeStreamingUsage(current: TokenUsage?, incoming: TokenUsage?): TokenUsage? {
            if (incoming == null) return current
            if (current == null) return incoming
            val input = maxOf(current.inputTokens, incoming.inputTokens)
            val output = maxOf(current.outputTokens, incoming.outputTokens)
            return TokenUsage(
                inputTokens = input,
                outputTokens = output,
                totalTokens = maxOf(current.totalTokens, incoming.totalTokens, input + output)
            )
        }

        fun renderRunProgress(finalReply: String? = null): String {
            val sb = StringBuilder()
            if (runSteps.isNotEmpty()) {
                sb.appendLine("执行过程：")
                for (step in runSteps) {
                    val marker = when (step.status) {
                        StepStatus.RUNNING -> "..."
                        StepStatus.DONE -> "OK"
                        StepStatus.FAILED -> "FAIL"
                        StepStatus.SKIPPED -> "SKIP"
                    }
                    sb.append(step.index).append(". [").append(marker).append("] ").append(step.title)
                    if (step.detail.isNotBlank()) sb.append(" - ").append(step.detail.take(220))
                    sb.appendLine()
                }
                sb.appendLine()
            }
            if (!finalReply.isNullOrBlank()) {
                sb.appendLine("---")
                sb.append(finalReply)
            } else if (displayBuilder.isNotBlank() && runSteps.isEmpty()) {
                sb.append(displayBuilder)
            }
            return sb.toString().trim()
        }

        suspend fun addRunStep(title: String, detail: String = "", status: StepStatus = StepStatus.RUNNING): Int {
            val index = nextStepIndex++
            runSteps.add(AgentRunStep(index, title, detail, status))
            chatDao.updateContent(streamingMsgId, renderRunProgress())
            return index
        }

        suspend fun updateRunStep(index: Int, status: StepStatus, detail: String? = null) {
            val pos = runSteps.indexOfFirst { it.index == index }
            if (pos >= 0) {
                val old = runSteps[pos]
                runSteps[pos] = old.copy(status = status, detail = detail ?: old.detail)
                chatDao.updateContent(streamingMsgId, renderRunProgress())
            }
        }

        try {
            var streamingUsage: TokenUsage? = null
            agent.runTurnStreamingWithUsage(
                userInput = userInput,
                memories = userMemories,
                systemMemories = systemMemories,
                schedules = allSchedules,
                skills = enabledSkills,
                config = config,
                enableWebSearch = true,
                imageBase64 = imageBase64,
                imageMimeType = imageMimeType,
                historyMessages = historyMessages
            ).collect { chunk ->
                streamingUsage = mergeStreamingUsage(streamingUsage, chunk.usage)
                val display = extractor.consume(chunk.text)
                if (display != null) {
                    displayBuilder.append(display)
                    chatDao.updateContent(streamingMsgId, displayBuilder.toString())
                }
            }
            addApiUsage(streamingUsage)
        } catch (e: CancellationException) {
            chatDao.updateContent(streamingMsgId, renderRunProgress("已中止。"))
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "streaming error: ${e.message}")
        }

        val rawText = extractor.getRaw()
        var result = agent.parseRaw(rawText)
        AppLogger.i(
            TAG,
            "agent result: replyLen=${result.reply.length}, memoryUpdates=${result.memoryUpdates.size}, scheduleUpdates=${result.scheduleUpdates.size}, skillUpdates=${result.skillUpdates.size}, systemCalls=${result.systemCalls.size}"
        )

        // Multi-pass system call loop: execute system_calls → feed back → re-call agent
        var searchResults: String? = null
        var searchSucceeded = false
        var searchFailed = false
        var toolLoopPausedReason: String? = null
        for (pass in 1..budget.maxPasses) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result.systemCalls.isEmpty()) break

            addRunStep(
                title = "Agent planning pass $pass",
                detail = "${result.systemCalls.size} tool call(s) requested",
                status = StepStatus.DONE
            )

            val hasWebSearch = result.systemCalls.any { it.type == "web_search" }
            val hasBrowseUrl = result.systemCalls.any {
                it.type == "browse_url" || it.type == "browser_url" || it.type == "web_browse" || it.type == "open_url"
            }
            val hasNetworkCall = hasWebSearch || hasBrowseUrl
            if (hasNetworkCall) {
                chatDao.updateContent(streamingMsgId, renderRunProgress())
            }

            val uniqueCalls = mutableListOf<SystemCall>()
            for (call in result.systemCalls) {
                val type = call.type
                val isSearch = type == "web_search"
                val isBrowse = type == "browse_url" || type == "browser_url" || type == "web_browse" || type == "open_url"
                val key = when {
                    isSearch -> call.query.trim().lowercase()
                    isBrowse -> call.url.ifBlank { call.query }.trim().lowercase()
                    else -> type + ":" + call.query + ":" + call.url
                }
                val duplicate = (isSearch && key in searchedQueries) || (isBrowse && key in visitedUrls)
                val budgetBlocked = (isSearch && budget.searchCalls >= budget.maxSearchCalls) ||
                    (isBrowse && budget.browseCalls >= budget.maxBrowseCalls)
                if (duplicate) {
                    addRunStep("Skip duplicate ${call.type}", key, StepStatus.SKIPPED)
                } else if (budgetBlocked) {
                    addRunStep("Skip ${call.type}", "type budget exhausted: $key", StepStatus.SKIPPED)
                } else {
                    if (isSearch) searchedQueries += key
                    if (isBrowse) visitedUrls += key
                    uniqueCalls += call
                }
            }
            val remainingToolCalls = budget.maxToolCalls - budget.toolCalls
            if (remainingToolCalls <= 0) {
                toolLoopPausedReason = "tool budget exhausted"
                addRunStep("Pause tool loop", "$toolLoopPausedReason; send 继续 to run more", StepStatus.SKIPPED)
                break
            }
            val callsToExecute = uniqueCalls.take(remainingToolCalls)
            if (callsToExecute.size < uniqueCalls.size) {
                addRunStep("Skip extra tools", "tool budget allows $remainingToolCalls more call(s)", StepStatus.SKIPPED)
            }
            if (callsToExecute.isEmpty()) {
                toolLoopPausedReason = "no new executable tool calls"
                addRunStep("Pause tool loop", "$toolLoopPausedReason; send 继续 to run more", StepStatus.SKIPPED)
                break
            }
            budget.toolCalls += callsToExecute.size
            budget.searchCalls += callsToExecute.count { it.type == "web_search" }
            budget.browseCalls += callsToExecute.count { it.type == "browse_url" || it.type == "browser_url" || it.type == "web_browse" || it.type == "open_url" }

            AppLogger.i(TAG, "executing ${callsToExecute.size}/${result.systemCalls.size} system calls (pass $pass)")
            var activeToolStep: Int? = null
            val executedResults = try {
                SystemSkillExecutor.execute(callsToExecute) { event ->
                    when (event.type) {
                        SystemSkillExecutor.ToolStepEvent.Type.STARTED -> {
                            activeToolStep = addRunStep(event.title, event.detail, StepStatus.RUNNING)
                        }
                        SystemSkillExecutor.ToolStepEvent.Type.FINISHED -> {
                            activeToolStep?.let { updateRunStep(it, StepStatus.DONE, event.detail) }
                            activeToolStep = null
                        }
                        SystemSkillExecutor.ToolStepEvent.Type.FAILED -> {
                            activeToolStep?.let { updateRunStep(it, StepStatus.FAILED, event.detail) }
                            activeToolStep = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                activeToolStep?.let { updateRunStep(it, StepStatus.FAILED, "cancelled") }
                chatDao.updateContent(streamingMsgId, renderRunProgress("已中止。"))
                throw e
            }
            searchResults = if (searchResults != null) "$searchResults\n$executedResults" else executedResults

            // Check if search actually returned useful results
            if (hasBrowseUrl && executedResults.contains("[BROWSE_RESULT]")) {
                searchSucceeded = true
            } else if (hasBrowseUrl && executedResults.contains("[BROWSE_ERROR]")) {
                searchFailed = true
            }
            if (hasWebSearch) {
                if (executedResults.contains("【联网搜索结果")) {
                    searchSucceeded = true
                } else if (executedResults.contains("【搜索错误】")) {
                    searchFailed = true
                }
            }

            // Re-call agent with search results (non-streaming for intermediate passes)
            val thinkingStep = addRunStep(
                title = "Agent observes results",
                detail = "remaining tools=${budget.maxToolCalls - budget.toolCalls}",
                status = StepStatus.RUNNING
            )
            val continuationInput = "system_calls 执行结果：\n$executedResults\n\n请基于以上结果继续生成回复。"
            val remainingAfterExecution = budget.maxToolCalls - budget.toolCalls
            val continuationWithBudget = continuationInput + if (remainingAfterExecution > 0) {
                "\n\nTool budget: remaining=$remainingAfterExecution. You may continue calling get_current_time, web_search, and browse_url if useful. Decide freely whether more searching or browsing is needed based on the task, uncertainty, source quality, and user intent. Prefer primary or authoritative sources when they matter, and use multiple independent pages when helpful. If a search result only shows a breadcrumb URL such as https://www.boc.cn › fimarkets, you may convert it to https://www.boc.cn/fimarkets and browse it. Do not repeat the same query or URL unless there is a clear reason. Return final reply when the answer is sufficiently supported or the user likely wants a quick answer."
            } else {
                "\n\nTool budget: remaining=0. Do not call more tools in this turn. Return the best final reply from the available results."
            }
            result = try {
                val turn = agent.runTurnWithUsage(
                    userInput = continuationWithBudget,
                    memories = userMemories,
                    systemMemories = systemMemories,
                    schedules = allSchedules,
                    skills = enabledSkills,
                    config = config,
                    enableWebSearch = true,
                    historyMessages = historyMessages,
                    webSearchResults = searchResults
                )
                addApiUsage(turn.usage)
                turn.result
            } catch (e: CancellationException) {
                updateRunStep(thinkingStep, StepStatus.FAILED, "cancelled")
                chatDao.updateContent(streamingMsgId, renderRunProgress("已中止。"))
                throw e
            }
            updateRunStep(thinkingStep, StepStatus.DONE, "reply=${result.reply.length} chars, next tools=${result.systemCalls.size}")
            AppLogger.i(TAG, "pass $pass result: replyLen=${result.reply.length}, systemCalls=${result.systemCalls.size}")
        }
        if (toolLoopPausedReason == null && result.systemCalls.isNotEmpty()) {
            toolLoopPausedReason = "pass limit reached"
            addRunStep("Pause tool loop", "$toolLoopPausedReason; send 继续 to run more", StepStatus.SKIPPED)
        }

        val localSkillHints = runLocalSkills(userInput, enabledSkills)
        var finalReply = if (localSkillHints.isBlank()) result.reply else "${result.reply}\n\n$localSkillHints"
        if (toolLoopPausedReason != null && result.systemCalls.isNotEmpty()) {
            finalReply += "\n\n---\n执行已暂停：$toolLoopPausedReason。可以发送“继续”让我接着执行后续步骤。"
        }
        // Append search status footer if search was attempted
        if (searchFailed) {
            finalReply += "\n\n---\n⚠️ 联网搜索失败，以上回复基于已有知识，可能不是最新信息。"
        } else if (searchSucceeded) {
            finalReply += "\n\n---\n${resultExpandLinkText(searchResults)}"
        }
        chatDao.updateContent(streamingMsgId, renderRunProgress(finalReply))
        totalApiUsage?.let { usage ->
            chatDao.updateTokenUsage(
                streamingMsgId,
                input = usage.inputTokens,
                output = usage.outputTokens,
                total = usage.totalTokens,
                source = "api"
            )
        }

        // Persist search results so the UI can display them
        if (!searchResults.isNullOrBlank()) {
            chatDao.updateSearchResult(streamingMsgId, searchResults)
        }
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
            val gateRequest = buildScheduleGateRequest(userInput, update)
            val gateResult = addScheduleBySubAgent(gateRequest)
            if (gateResult.isFailure) {
                AppLogger.i(TAG, "chat schedule gate failed, fallback strict-agent-parse")
                val strictResult = saveScheduleUpdateStrictlyFromAgent(update)
                if (strictResult.isFailure) {
                    AppLogger.i(TAG, "chat schedule dropped")
                }
            } else {
                AppLogger.i(TAG, "chat schedule gated by sub-agent: titleB64=${LogCodec.utf8Base64(update.title)}")
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
        AppLogger.i(TAG, "send message complete: conversationId=$conversationId")
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
            val plan = agent.runScheduleSubAgent(rawRequest, zoneId, nowLocalIso, buildProviderConfig())
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
            AppLogger.i(
                TAG,
                "sub-agent schedule created: titleB64=${LogCodec.utf8Base64(normalizedTitle)}, repeatType=${repeatType.name}, time=${plan.reminderTime}"
            )
            Unit
        }
    }

    suspend fun refineImageSchedulesFromLocal(
        sourceText: String
    ): Result<List<ScheduleSubAgentPlan>> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceText.isNotBlank()) { "本地模型没有提取出文字内容" }
            val zone = ZoneId.systemDefault()
            val zoneId = zone.id
            val nowLocalIso = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toString()
            val batch = agent.refineImageScheduleCandidates(
                sourceText = sourceText,
                timezoneId = zoneId,
                nowLocalIso = nowLocalIso,
                config = buildProviderConfig()
            ) ?: error("云端未返回有效日程结果")
            val schedules = batch.schedules.filter {
                it.title.isNotBlank() && !it.reminderTime.isNullOrBlank()
            }
            require(schedules.isNotEmpty()) { "云端未生成可导入的日程" }
            AppLogger.i(TAG, "image schedule refined by cloud: count=${schedules.size}")
            schedules
        }
    }

    suspend fun addScheduleFromStructuredPlan(plan: ScheduleSubAgentPlan): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(
                plan.repeatType == "NONE" ||
                    plan.repeatType == "DAILY" ||
                    plan.repeatType == "WEEKLY" ||
                    plan.repeatType == "MONTHLY"
            ) { "本地视觉模型返回的 repeat_type 非法：${plan.repeatType}" }

            val zone = ZoneId.systemDefault()
            val nowMillis = System.currentTimeMillis()
            val repeatType = RepeatType.fromRaw(plan.repeatType)
            val reminderTimeMinutes = parseTimeTextToMinutes(plan.reminderTime)
                ?: error("本地视觉模型未识别出有效提醒时间")
            val localTime = LocalTime.of(reminderTimeMinutes / 60, reminderTimeMinutes % 60)

            val normalizedTitle = plan.title.trim().ifBlank { "图片导入日程" }
            val normalizedDetail = plan.detail?.trim().orEmpty()

            val startAt: Long
            val reminderAt: Long
            val repeatDays: List<Int>
            val repeatDayOfMonth: Int?

            when (repeatType) {
                RepeatType.NONE -> {
                    val dateText = plan.oneTimeDate?.trim().orEmpty()
                    require(dateText.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) { "本地视觉模型未识别出有效日期" }
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
                    require(days.isNotEmpty()) { "本地视觉模型未识别出 weekly_days" }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = days
                    repeatDayOfMonth = null
                }
                RepeatType.MONTHLY -> {
                    val monthDay = plan.monthlyDay?.coerceIn(1, 31)
                        ?: error("本地视觉模型未识别出 monthly_day")
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
                "local-image schedule created: titleB64=${LogCodec.utf8Base64(normalizedTitle)}, repeatType=${repeatType.name}"
            )
        }
    }

    suspend fun addSchedulesFromStructuredPlans(plans: List<ScheduleSubAgentPlan>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = plans.filter {
                it.title.isNotBlank() && !it.reminderTime.isNullOrBlank()
            }
            require(normalized.isNotEmpty()) { "没有可导入的日程内容" }
            normalized.forEach { plan ->
                addScheduleFromStructuredPlan(plan).getOrThrow()
            }
            AppLogger.i(TAG, "local-image schedules created: count=${normalized.size}")
            normalized.size
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
                agent.generateReminderInsight(update.title, update.detail, buildProviderConfig())
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

    suspend fun addTodoItems(items: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = items.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            require(normalized.isNotEmpty()) { "没有可导入的待办内容" }
            normalized.forEach { item ->
                memoryDao.upsert(
                    MemoryItemEntity(
                        category = "短期目标",
                        content = item,
                        source = "image_import"
                    )
                )
            }
            AppLogger.i(TAG, "image todos imported: count=${normalized.size}")
            normalized.size
        }
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

    private fun resultExpandLinkText(result: String?): String {
        if (result.isNullOrBlank()) return "🔍 [展开搜索结果](search://result)"
        val hasBrowse = result.contains("[BROWSE_RESULT]") || result.contains("[BROWSE_ERROR]")
        val hasSearch = result.contains("【联网搜索结果")
        return when {
            hasBrowse && hasSearch -> "🌐 [展开联网资料](search://result)"
            hasBrowse -> "📖 [展开网页阅读内容](search://result)"
            else -> "🔍 [展开搜索结果](search://result)"
        }
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
