package com.actme.app.data.repo

import com.actme.app.util.AppLogger
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.agent.AgentResult
import com.actme.app.data.agent.PythonSkillEngine
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
import com.actme.app.data.remote.MessagePayload
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderConfig
import com.actme.app.data.remote.ProviderManager
import com.actme.app.data.remote.TokenUsage
import com.actme.app.notifications.ReminderScheduler
import com.actme.app.util.LogCodec
import java.io.File
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
        val maxPasses: Int = 12,
        val maxToolCalls: Int = 24,
        val maxSearchCalls: Int = 8,
        val maxBrowseCalls: Int = 14,
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
            .takeLastUserRounds(10)
        AppLogger.i(
            TAG,
            "history context: messages=${historyMessages.size}, userRounds=${historyMessages.count { it.role == "user" }}, toolMessages=${historyMessages.count { it.role == "tool_execution" }}"
        )

        val config = buildProviderConfig()

        // Insert initial placeholder. If no tools run it stays as the reply bubble;
        // if tools are detected it gets promoted to role="tool_execution" and a new reply bubble is inserted.
        val initialMsgId = chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis()
            )
        )
        var replyMsgId = initialMsgId
        var toolMsgId = -1L   // set on first pass that has tool calls

        val runSteps = mutableListOf<AgentRunStep>()
        var nextStepIndex = 1
        val budget = ToolBudget()
        val searchedQueries = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()
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

        fun buildStepLog(): String = runSteps.joinToString("\n") { step ->
            val m = when (step.status) {
                StepStatus.DONE -> "[OK]"
                StepStatus.FAILED -> "[FAIL]"
                StepStatus.SKIPPED -> "[SKIP]"
                StepStatus.RUNNING -> "[...]"
            }
            if (step.detail.isBlank()) "$m ${step.title}" else "$m ${step.title} - ${step.detail.take(200)}"
        }

        fun modelExecutionErrorText(e: Exception): String {
            return "模型请求失败：${e.message?.take(120) ?: e::class.java.simpleName}"
        }

        suspend fun updateChatContent(messageId: Long, content: String, reason: String) {
            AppLogger.i(
                "ChatOutput",
                "CHAT_CONTENT id=$messageId reason=$reason chars=${content.length}\n$content"
            )
            chatDao.updateContent(messageId, content)
        }

        // Only update the tool bubble content when a step is actively running
        suspend fun addRunStep(title: String, detail: String = "", status: StepStatus = StepStatus.RUNNING): Int {
            val index = nextStepIndex++
            runSteps.add(AgentRunStep(index, title, detail, status))
            if (toolMsgId != -1L && status == StepStatus.RUNNING) {
                val text = if (detail.isBlank()) title else "$title · ${detail.take(60)}"
                updateChatContent(toolMsgId, text, "tool_step_running")
            }
            return index
        }

        suspend fun updateRunStep(index: Int, status: StepStatus, detail: String? = null) {
            val pos = runSteps.indexOfFirst { it.index == index }
            if (pos >= 0) {
                val old = runSteps[pos]
                runSteps[pos] = old.copy(status = status, detail = detail ?: old.detail)
            }
        }

        // ── Pass 1: stream the initial reply ──
        if (config.model.isBlank()) {
            updateChatContent(
                replyMsgId,
                "模型未设置。请在模型提供商里填写默认模型，或先拉取并选择一个可用模型。",
                "missing_model"
            )
            touchConversation(conversationId)
            return@withContext
        }

        val extractor = ReplyExtractor()
        val displayBuilder = StringBuilder()
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
                    val visible = agent.sanitizeUserVisibleReply(displayBuilder.toString())
                    if (visible.isNotBlank()) {
                        updateChatContent(replyMsgId, visible, "initial_stream")
                    }
                }
            }
            addApiUsage(streamingUsage)
        } catch (e: CancellationException) {
            if (toolMsgId != -1L) updateChatContent(toolMsgId, "已中止", "cancel_tool")
            updateChatContent(replyMsgId, displayBuilder.toString().ifBlank { "已中止。" }, "cancel_reply")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "streaming error", e)
            updateChatContent(replyMsgId, modelExecutionErrorText(e), "initial_stream_error")
            touchConversation(conversationId)
            return@withContext
        }

        val rawText = extractor.getRaw()
        var result = agent.parseRaw(rawText)
        AppLogger.i(
            TAG,
            "agent result: replyLen=${result.reply.length}, memoryUpdates=${result.memoryUpdates.size}, scheduleUpdates=${result.scheduleUpdates.size}, skillUpdates=${result.skillUpdates.size}, systemCalls=${result.systemCalls.size}"
        )

        // ── Tool execution loop ──
        var searchResults: String? = null
        val generatedFileRefs = linkedSetOf<String>()
        var searchSucceeded = false
        var searchFailed = false
        var toolLoopPausedReason: String? = null
        val maxEmptyCompletionRecoveries = 4
        var emptyCompletionRecoveries = 0
        suspend fun recoverEmptyCompletion(raw: String): AgentResult {
            emptyCompletionRecoveries += 1
            val callLens = result.systemCalls.joinToString(",") { call ->
                "${call.type}:code=${call.code.length}:query=${call.query.length}:url=${call.url.length}:files=${call.outputFiles.size + call.generatedFiles.size + call.expectedOutputs.size + call.files.size}"
            }.ifBlank { "none" }
            AppLogger.w(
                TAG,
                "empty completion blocked; attempt=$emptyCompletionRecoveries, files=${generatedFileRefs.size}, rawLen=${raw.length}, systemCalls=${result.systemCalls.size}, callLens=$callLens, rawHeadB64=${LogCodec.utf8Base64(raw.take(320))}, rawTailB64=${LogCodec.utf8Base64(raw.takeLast(320))}"
            )
            addRunStep(
                title = "Agent continues empty result",
                detail = "reply and output files are empty; requesting completion",
                status = StepStatus.RUNNING
            )
            val toolContext = searchResults
                ?.takeIf { it.isNotBlank() }
                ?: "No tool result is available yet. Decide whether to call tools or directly provide a non-empty reply."
            val recoveryInput = """
                User request:
                $userInput

                Existing tool/intermediate results:
                $toolContext

                The previous model output had an empty reply and no output file, so it is not a valid stop.
                Continue now:
                - If enough information is available, put the complete final answer/report in reply.
                - If the user asked for a file, produce the needed result file and list it in output_files.
                - Do not return empty reply with empty system_calls.
            """.trimIndent()
            val recoveryExtractor = ReplyExtractor()
            val recoveryBuilder = StringBuilder()
            var recoveryUsage: TokenUsage? = null
            agent.runTurnStreamingWithUsage(
                userInput = recoveryInput,
                memories = userMemories,
                systemMemories = systemMemories,
                schedules = allSchedules,
                skills = enabledSkills,
                config = config,
                enableWebSearch = true,
                historyMessages = historyMessages,
                webSearchResults = searchResults
            ).collect { chunk ->
                recoveryUsage = mergeStreamingUsage(recoveryUsage, chunk.usage)
                val display = recoveryExtractor.consume(chunk.text)
                if (display != null) {
                    recoveryBuilder.append(display)
                    val visible = agent.sanitizeUserVisibleReply(recoveryBuilder.toString())
                    if (visible.isNotBlank()) {
                        updateChatContent(replyMsgId, visible, "empty_recovery_stream")
                    }
                }
            }
            addApiUsage(recoveryUsage)
            val recoveryRaw = recoveryExtractor.getRaw()
            val recovered = agent.parseRaw(recoveryRaw)
            generatedFileRefs += extractWorkspaceFileRefs(recoveryRaw)
            AppLogger.i(
                TAG,
                "empty completion recovery result: replyLen=${recovered.reply.length}, systemCalls=${recovered.systemCalls.size}, files=${generatedFileRefs.size}"
            )
            return recovered
        }
        for (pass in 1..budget.maxPasses) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result.systemCalls.isEmpty()) {
                generatedFileRefs += extractWorkspaceFileRefs(result.reply)
                if (result.reply.isBlank() && generatedFileRefs.isEmpty() && emptyCompletionRecoveries < maxEmptyCompletionRecoveries) {
                    result = recoverEmptyCompletion(rawText)
                    continue
                }
                break
            }

            // First pass needing tools: convert initialMsgId → tool_execution, open fresh reply bubble
            if (toolMsgId == -1L) {
                chatDao.updateRole(initialMsgId, "tool_execution")
                updateChatContent(initialMsgId, "", "promote_initial_to_tool")
                toolMsgId = initialMsgId
                replyMsgId = chatDao.insert(
                    ChatMessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = "",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            addRunStep(
                title = "Agent planning pass $pass",
                detail = "${result.systemCalls.size} tool call(s) requested",
                status = StepStatus.DONE
            )

            val uniqueCalls = mutableListOf<SystemCall>()
            for (call in result.systemCalls) {
                val type = call.type
                val isSearch = type == "web_search"
                val isBrowse = type == "browse_url" || type == "browser_url" || type == "web_browse" || type == "open_url"
                val isPython = type == "python_exec" || type == "run_python" || type == "python"
                val isHtmlToPdf = type == "html_to_pdf" || type == "render_html_pdf" || type == "webview_pdf"
                val isAdb = type == "adb_shell" || type == "adb" || type == "run_adb"
                val key = when {
                    isSearch -> call.query.trim().lowercase()
                    isBrowse -> call.url.ifBlank { call.query }.trim().lowercase()
                    isPython -> type + ":" + call.code.take(500) + ":" + call.input.take(500)
                    isHtmlToPdf -> type + ":" + call.url.ifBlank { call.query }.ifBlank { call.input }.trim().lowercase() + ":" + call.outputFiles.joinToString(",").lowercase()
                    isAdb -> type + ":" + call.command.ifBlank { call.code }.ifBlank { call.query }.take(500)
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
                updateChatContent(toolMsgId, "已中止", "cancel_tool_execution")
                updateChatContent(replyMsgId, "已中止。", "cancel_reply_during_tool")
                throw e
            }
            searchResults = if (searchResults != null) "$searchResults\n$executedResults" else executedResults
            generatedFileRefs += extractWorkspaceFileRefs(executedResults)
            AppLogger.i(
                "AgentFile",
                "pass=$pass collectedAfterTool=${generatedFileRefs.size}, files=${generatedFileRefs.joinToString("|")}"
            )

            if (executedResults.contains("[BROWSE_RESULT]")) searchSucceeded = true
            else if (executedResults.contains("[BROWSE_ERROR]")) searchFailed = true
            if (executedResults.contains("【联网搜索结果")) searchSucceeded = true
            else if (executedResults.contains("【搜索错误】")) searchFailed = true

            // Continuation: stream reply to replyMsgId
            val thinkingStep = addRunStep(
                title = "Agent observes results",
                detail = "remaining tools=${budget.maxToolCalls - budget.toolCalls}",
                status = StepStatus.RUNNING
            )
            val continuationInput = "用户问题：$userInput\n\nsystem_calls 执行结果：\n$executedResults\n\n请基于以上结果，回答用户问题。"
            val remainingAfterExecution = budget.maxToolCalls - budget.toolCalls
            val continuationWithBudget = continuationInput + if (remainingAfterExecution > 0) {
                "\n\nTool budget: remaining=$remainingAfterExecution. You may continue calling get_current_time, web_search, browse_url, python_exec, html_to_pdf, and adb_shell if useful. Decide freely whether more searching, browsing, Python processing, HTML-to-PDF rendering, or ADB inspection/control is needed based on the task, uncertainty, source quality, and user intent. Prefer primary or authoritative sources when they matter, and use multiple independent pages when helpful. If a search result only shows a breadcrumb URL such as https://www.boc.cn › fimarkets, you may convert it to https://www.boc.cn/fimarkets and browse it. Do not repeat the same query, URL, code, ADB command, or HTML-to-PDF render unless there is a clear reason. Return final reply when the answer is sufficiently supported or the user likely wants a quick answer."
            } else {
                "\n\nTool budget: remaining=0. Do not call more tools in this turn. Return the best final reply from the available results."
            }
            val continuationExtractor = ReplyExtractor()
            val continuationBuilder = StringBuilder()
            var continuationUsage: TokenUsage? = null
            try {
                agent.runTurnStreamingWithUsage(
                    userInput = continuationWithBudget,
                    memories = userMemories,
                    systemMemories = systemMemories,
                    schedules = allSchedules,
                    skills = enabledSkills,
                    config = config,
                    enableWebSearch = true,
                    historyMessages = historyMessages,
                    webSearchResults = searchResults
                ).collect { chunk ->
                    continuationUsage = mergeStreamingUsage(continuationUsage, chunk.usage)
                    val display = continuationExtractor.consume(chunk.text)
                    if (display != null) {
                        continuationBuilder.append(display)
                        val visible = agent.sanitizeUserVisibleReply(continuationBuilder.toString())
                        if (visible.isNotBlank()) {
                            updateChatContent(replyMsgId, visible, "continuation_stream")
                        }
                    }
                }
            } catch (e: CancellationException) {
                updateRunStep(thinkingStep, StepStatus.FAILED, "cancelled")
                updateChatContent(toolMsgId, "已中止", "cancel_tool_continuation")
                updateChatContent(replyMsgId, "已中止。", "cancel_reply_continuation")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "continuation streaming error", e)
                updateRunStep(thinkingStep, StepStatus.FAILED, e.message?.take(120) ?: e::class.java.simpleName)
                if (toolMsgId != -1L) {
                    updateChatContent(toolMsgId, "执行失败", "continuation_error_tool")
                    chatDao.updateSearchResult(toolMsgId, buildStepLog())
                }
                result = AgentResult(reply = modelExecutionErrorText(e))
                break
            }
            addApiUsage(continuationUsage)
            val continuationRaw = continuationExtractor.getRaw()
            result = agent.parseRaw(continuationRaw)
            generatedFileRefs += extractWorkspaceFileRefs(continuationRaw)
            if (result.reply.isBlank() && result.systemCalls.isEmpty() && generatedFileRefs.isEmpty() && emptyCompletionRecoveries < maxEmptyCompletionRecoveries) {
                result = recoverEmptyCompletion(continuationRaw)
            }
            updateRunStep(thinkingStep, StepStatus.DONE, "reply=${result.reply.length} chars, next tools=${result.systemCalls.size}")
            AppLogger.i(TAG, "pass $pass result: replyLen=${result.reply.length}, systemCalls=${result.systemCalls.size}")
        }

        if (toolLoopPausedReason == null && result.systemCalls.isNotEmpty()) {
            toolLoopPausedReason = "pass limit reached"
            addRunStep("Pause tool loop", "$toolLoopPausedReason; send 继续 to run more", StepStatus.SKIPPED)
        }

        // Finalize tool execution bubble: collapsed "执行完成" + expandable step log
        if (toolMsgId != -1L) {
            updateChatContent(toolMsgId, "执行完成", "tool_execution_complete")
            chatDao.updateSearchResult(toolMsgId, buildStepLog())
        }

        // Build and persist final reply
        val visibleReply = agent.sanitizeUserVisibleReply(result.reply)
        var finalReply = visibleReply
        if (toolLoopPausedReason != null && result.systemCalls.isNotEmpty()) {
            finalReply += "\n\n---\n执行已暂停：$toolLoopPausedReason。可以发送【继续】让我接着执行后续步骤。"
        }
        generatedFileRefs += extractWorkspaceFileRefs(searchResults.orEmpty())
        generatedFileRefs += extractWorkspaceFileRefs(result.reply)
        val generatedFiles = generatedFileRefs.toList()
        if (finalReply.isBlank() && generatedFiles.isEmpty()) {
            AppLogger.w(TAG, "final empty reply blocked after recoveries=$emptyCompletionRecoveries")
            finalReply = "模型连续返回空结果，未生成可展示回复或结果文件。本轮已自动重试，但仍未得到有效输出。"
        }
        val missingGeneratedFiles = generatedFiles.filterNot { finalReply.contains(it) }
        AppLogger.i(
            "AgentFile",
            "final collected=${generatedFiles.size}, missing=${missingGeneratedFiles.size}, files=${generatedFiles.joinToString("|")}"
        )
        if (missingGeneratedFiles.isNotEmpty()) {
            finalReply += "\n\n---\nGenerated files:\n" + missingGeneratedFiles.joinToString("\n")
        }
        if (searchFailed) {
            finalReply += "\n\n---\n⚠️ 联网搜索失败，以上回复基于已有知识，可能不是最新信息。"
        } else if (searchSucceeded) {
            finalReply += "\n\n---\n${resultExpandLinkText(searchResults)}"
        }
        updateChatContent(replyMsgId, finalReply, "final_reply")
        totalApiUsage?.let { usage ->
            chatDao.updateTokenUsage(
                replyMsgId,
                input = usage.inputTokens,
                output = usage.outputTokens,
                total = usage.totalTokens,
                source = "api"
            )
        }
        if (!searchResults.isNullOrBlank()) {
            chatDao.updateSearchResult(replyMsgId, searchResults)
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

    private fun List<ChatMessageEntity>.takeLastUserRounds(rounds: Int): List<ChatMessageEntity> {
        if (rounds <= 0) return emptyList()
        var userSeen = 0
        var startIndex = 0
        for (index in indices.reversed()) {
            if (this[index].role == "user") {
                userSeen += 1
                if (userSeen == rounds) {
                    startIndex = index
                    break
                }
            }
        }
        return if (userSeen < rounds) this else subList(startIndex, size)
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

    private fun extractWorkspaceFileRefs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val refs = mutableListOf<String>()
        val workspace = runCatching { PythonSkillEngine.workspaceDir().canonicalFile }.getOrNull()
        val absoluteRegex = Regex("""(?:[A-Za-z]:)?[/\\][^\s"'`,;]+agent_workspace[/\\][^\s"'`,;]+""")
        refs += absoluteRegex.findAll(text).map { it.value.trimEnd('.', ',', ';') }
        val outputBlockRegex = Regex("""(?m)^output_files:\s*\n((?:-\s+.+\n?)+)""")
        outputBlockRegex.findAll(text).forEach { match ->
            Regex("""(?m)^-\s+(.+)$""").findAll(match.groupValues[1]).forEach { item ->
                refs += item.groupValues[1].trim().trimEnd('.', ',', ';')
            }
        }
        return refs.mapNotNull { ref -> normalizeWorkspaceFileRef(ref, workspace) }.distinct()
    }

    private fun normalizeWorkspaceFileRef(raw: String, workspace: File?): String? {
        val clean = raw.removePrefix("file://").trim()
        if (clean.isBlank()) return null
        val candidate = if (workspace != null && !File(clean).isAbsolute) {
            File(workspace, clean)
        } else {
            File(clean)
        }
        val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        if (workspace != null && !file.path.startsWith(workspace.path + File.separator)) return null
        return file.absolutePath
    }
    // ---- Provider management ----

    private suspend fun buildProviderConfig(): ProviderConfig {
        val provider = providerManager.getActiveProvider()
        if (provider == null) {
            return ProviderConfig("openai", "", "", "")
        }
        val sk = providerManager.getSk(provider.id)
        val model = provider.defaultModel.trim().ifBlank { providerManager.getLastModel(provider.id).trim().ifBlank { "" } }
        return ProviderConfig(provider.providerFormat, provider.endpoint, sk, model)
    }

    val providers = providerManager.providers
    val activeProviderIdFlow = providerManager.activeProviderIdFlow

    suspend fun addProvider(name: String, format: String, endpoint: String, defaultModel: String, sk: String): Long {
        return providerManager.addProvider(name, format, endpoint, defaultModel, sk)
    }

    suspend fun updateProvider(id: Long, name: String, format: String, endpoint: String, defaultModel: String, sk: String) {
        providerManager.updateProvider(id, name, format, endpoint, defaultModel, sk)
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
        provider.defaultModel.trim().takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val sk = providerManager.getSk(provider.id)
        return openAiClient.fetchModels(provider.endpoint, sk, provider.providerFormat)
    }

    suspend fun generatePresetQuestions(): List<String> = withContext(Dispatchers.IO) {
        val config = buildProviderConfig()
        if (config.sk.isBlank()) return@withContext emptyList()
        val memories = memoryDao.getUserMemories().take(10)
        val lastSession = chatDao.getLatestSession()
        val recentMessages = lastSession?.let { chatDao.getByConversation(it.id).takeLast(4) } ?: emptyList()
        val memorySummary = memories.joinToString("\n") { "- ${it.category}: ${it.content.take(60)}" }
        val recentSummary = recentMessages.joinToString("\n") { "${it.role}: ${it.content.take(80)}" }
        val systemPrompt = """你的任务是生成3条用户会对AI说的对话开场白，以JSON字符串数组返回，只输出JSON，不要任何其他文字。
每条是用户的提问或请求（≤18字），语气是用户问AI的口吻，绝对不能是AI说的话。
示例输出：["今天天气怎么样？","帮我规划明天的行程","讲一个火星探测的故事"]
规则：第1条与最近对话内容相关（若无历史则结合用户记忆）；第2条挖掘用户潜在需求（结合记忆）；第3条是探索性提问（科学/历史/地理/新闻/角色扮演等）。"""
        val userContent = buildString {
            if (memorySummary.isNotBlank()) appendLine("用户记忆：\n$memorySummary")
            if (recentSummary.isNotBlank()) appendLine("近期对话：\n$recentSummary")
        }.ifBlank { "新用户，无历史记录" }
        try {
            val result = openAiClient.runWithUsage(
                messages = listOf(
                    MessagePayload("system", systemPrompt),
                    MessagePayload("user", userContent)
                ),
                config = config
            )
            val raw = result.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val badPatterns = listOf("我会", "我将", "我可以", "以下是", "好的", "当然", "回复", "如下")
            Json.parseToJsonElement(raw).jsonArray
                .mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } }
                .filter { q -> badPatterns.none { q.contains(it) } }
                .take(3)
        } catch (e: Exception) {
            AppLogger.w(TAG, "generatePresetQuestions failed: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ActMeRepository"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
