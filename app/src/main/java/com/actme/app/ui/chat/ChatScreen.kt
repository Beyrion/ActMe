package com.actme.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.actme.app.util.AppLogger
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.actme.app.audio.AsrManager
import com.actme.app.ui.theme.MarqueeBorder
import com.actme.app.audio.AudioRecorderManager
import com.actme.app.data.agent.ScheduleSubAgentPlan
import com.actme.app.data.agent.PythonSkillEngine
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.image.LocalImageImportManager
import com.actme.app.image.TodoImportPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessageEntity>,
    onSend: (String, String?, String?) -> Unit,
    onImportSchedules: (List<ScheduleSubAgentPlan>, (Result<Int>) -> Unit) -> Unit,
    onRefineImageSchedules: (String, (Result<List<ScheduleSubAgentPlan>>) -> Unit) -> Unit,
    onImportTodos: (List<String>, (Result<Int>) -> Unit) -> Unit,
    sendingConversationId: Long? = null,
    isRecording: Boolean = false,
    onStartRecording: (() -> Unit)? = null,
    onStopRecording: (() -> Unit)? = null,
    availableModels: List<String> = emptyList(),
    selectedModel: String = "",
    onSelectModel: (String) -> Unit = {},
    asrLanguage: String = "Chinese",
    isModelReady: Boolean = false,
    onStopSending: () -> Unit = {},
    localVisionModelDir: String = "",
    pendingWorkbook: PendingWorkbookAttachment? = null,
    onWorkbookConsumed: () -> Unit = {},
    onNavigateToMenu: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }

    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMimeType by remember { mutableStateOf<String?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedWorkbookPath by remember { mutableStateOf<String?>(null) }
    var selectedWorkbookName by remember { mutableStateOf<String?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var scheduleCandidate by remember { mutableStateOf<List<ScheduleSubAgentPlan>>(emptyList()) }
    var scheduleOcrText by remember { mutableStateOf("") }
    var showScheduleOcrText by remember { mutableStateOf(false) }
    var todoCandidate by remember { mutableStateOf<TodoImportPlan?>(null) }

    LaunchedEffect(pendingWorkbook) {
        val workbook = pendingWorkbook ?: return@LaunchedEffect
        selectedWorkbookPath = workbook.path
        selectedWorkbookName = workbook.name
        onWorkbookConsumed()
    }

    fun doSend() {
        val text = input.trim()
        val workbookPath = selectedWorkbookPath
        val workbookName = selectedWorkbookName
        if (text.isNotBlank() || selectedImageBase64 != null || workbookPath != null) {
            val finalText = if (workbookPath != null) {
                buildString {
                    if (text.isNotBlank()) appendLine(text)
                    appendLine()
                    appendLine("Excel file attached: ${workbookName ?: "workbook.xlsx"}")
                    appendLine("Workspace path: $workbookPath")
                    appendLine("Use python_exec and read_excel(\"$workbookPath\") to read the workbook, then answer the user's question.")
                    /*
                    appendLine("已上传 Excel 文件：${workbookName ?: "workbook.xlsx"}")
                    appendLine("工作区路径：$workbookPath")
                    appendLine("请使用 python_exec 调用 read_excel(\"$workbookPath\") 读取表格，然后按用户问题分析。")
                    */
                }.trim()
            } else {
                text
            }
            onSend(finalText, selectedImageBase64, selectedImageMimeType)
            input = ""
            selectedImageBase64 = null
            selectedImageMimeType = null
            selectedImageBytes = null
            selectedWorkbookPath = null
            selectedWorkbookName = null
            scope.launch {
                kotlinx.coroutines.delay(100)
                focusRequester.requestFocus()
            }
        }
    }

    val context = LocalContext.current
    val sending = sendingConversationId != null

    // Voice recording state
    var isVoiceRecording by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    val activity = context as? android.app.Activity

    val audioRecorder = remember {
        activity?.let { AudioRecorderManager(it) }
    }

    var isTranscribing by remember { mutableStateOf(false) }
    var preloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val asrManager = remember(isModelReady) {
        if (isModelReady) AsrManager(AsrManager.getDefaultModelPath(context)) else null
    }
    val imageImportManager = remember(localVisionModelDir) {
        val resolved = localVisionModelDir.trim().ifBlank { LocalImageImportManager.getDefaultModelPath(context) }
        LocalImageImportManager(resolved)
    }

    // Preload ASR model in parallel with recording
    fun startPreloadingModel() {
        val mgr = asrManager
        if (mgr != null && !mgr.isLoaded && preloadJob?.isActive != true) {
            preloadJob = scope.launch {
                AppLogger.i("ChatScreen", "Preloading ASR model in parallel with recording...")
                mgr.init()
                AppLogger.i("ChatScreen", "ASR model preload complete, loaded=${mgr.isLoaded}")
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioRecorder?.startRecording(context.cacheDir)
            isVoiceRecording = true
            onStartRecording?.invoke()
            startPreloadingModel()
        } else {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(audioRecorder) {
        audioRecorder?.onRecordingStopped = { wavFile ->
            recordingFile = wavFile
            isVoiceRecording = false
            onStopRecording?.invoke()
        }
        audioRecorder?.onError = { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            isVoiceRecording = false
        }
        onDispose { }
    }

    DisposableEffect(imageImportManager) {
        onDispose {
            imageImportManager.release()
        }
    }

    LaunchedEffect(recordingFile) {
        val file = recordingFile ?: return@LaunchedEffect


        val manager = asrManager
        if (manager == null) {
            Toast.makeText(context, "请先在设置中下载语音识别模型", Toast.LENGTH_SHORT).show()
            recordingFile = null
            return@LaunchedEffect
        }
        isTranscribing = true
        try {
            if (!manager.isLoaded) {
                // Wait for parallel preload to complete if still in flight
                preloadJob?.join()
                if (!manager.isLoaded) {
                    val ok = manager.init()
                    if (!ok) {
                        Toast.makeText(context, "ASR模型加载失败", Toast.LENGTH_SHORT).show()
                        return@LaunchedEffect
                    }
                }
            }
            val text = manager.transcribe(file, asrLanguage)
            AppLogger.i("ChatScreen", "ASR transcribed: $text")
            if (text.isNotBlank()) {
                input = input + text
            } else {
                AppLogger.w("ChatScreen", "ASR returned blank text")
                Toast.makeText(context, "未识别到语音内容", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLogger.e("ChatScreen", "ASR error", e)
            Toast.makeText(context, "语音识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isTranscribing = false
            file.delete()
            recordingFile = null  // clear trigger only after all work is done
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (bitmap != null) {
                    val normalizedBytes = if (mimeType.contains("png", ignoreCase = true)) {
                        bytes
                    } else {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 98, outputStream)
                        outputStream.toByteArray()
                    }
                    selectedImageBase64 = Base64.encodeToString(normalizedBytes, Base64.NO_WRAP)
                    selectedImageMimeType = if (mimeType.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
                    selectedImageBytes = normalizedBytes
                }
            }
        }
    }

    fun displayNameForUri(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "workbook.xlsx"
    }

    fun safeWorkbookFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "workbook.xlsx" }
        return if (cleaned.endsWith(".xlsx", true) || cleaned.endsWith(".xlsm", true)) {
            cleaned
        } else {
            "$cleaned.xlsx"
        }
    }

    val workbookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val displayName = displayNameForUri(uri)
                val targetDir = File(PythonSkillEngine.workspaceDir(context), "excel").apply { mkdirs() }
                val targetFile = File(targetDir, "${System.currentTimeMillis()}_${safeWorkbookFileName(displayName)}")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    targetFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: error("无法读取文件")
                selectedWorkbookName = displayName
                selectedWorkbookPath = targetFile.absolutePath
                Toast.makeText(context, "Excel 已添加：$displayName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLogger.e("ChatScreen", "excel import failed", e)
                Toast.makeText(context, "Excel 添加失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun persistSelectedImageToCache(): File {
        val bytes = selectedImageBytes ?: error("请先选择图片")
        val ext = when {
            selectedImageMimeType?.contains("png", ignoreCase = true) == true -> "png"
            else -> "jpg"
        }
        val file = File(context.cacheDir, "image_import_${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        return file
    }

    fun importScheduleFromImage() {
        scope.launch {
            if (selectedImageBytes == null) {
                Toast.makeText(context, "请先选择图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            importBusy = true
            val imageFile = runCatching { persistSelectedImageToCache() }.getOrElse {
                Toast.makeText(context, "保存图片失败: ${it.message}", Toast.LENGTH_SHORT).show()
                importBusy = false
                return@launch
            }
            try {
                val ocr = imageImportManager.extractText(imageFile)
                if (ocr.sourceText.isBlank()) {
                    Toast.makeText(context, "本地视觉模型未识别出文字内容", Toast.LENGTH_SHORT).show()
                } else {
                    onRefineImageSchedules(ocr.sourceText) { result ->
                        importBusy = false
                        result.onSuccess { refined ->
                            scheduleCandidate = refined
                            scheduleOcrText = ocr.sourceText
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                "云端整理日程失败: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("ChatScreen", "local image schedule import failed", e)
                Toast.makeText(context, "图片转日程失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                imageFile.delete()
                if (scheduleCandidate.isEmpty()) {
                    importBusy = false
                }
            }
        }
    }

    fun importTodosFromImage() {
        scope.launch {
            if (selectedImageBytes == null) {
                Toast.makeText(context, "请先选择图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            importBusy = true
            val imageFile = runCatching { persistSelectedImageToCache() }.getOrElse {
                Toast.makeText(context, "保存图片失败: ${it.message}", Toast.LENGTH_SHORT).show()
                importBusy = false
                return@launch
            }
            try {
                val plan = imageImportManager.parseTodos(imageFile)
                if (plan.items.isEmpty()) {
                    Toast.makeText(context, "本地视觉模型未识别出待办项", Toast.LENGTH_SHORT).show()
                } else {
                    todoCandidate = plan
                }
            } catch (e: Exception) {
                AppLogger.e("ChatScreen", "local image todo import failed", e)
                Toast.makeText(context, "图片转待办失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                imageFile.delete()
                importBusy = false
            }
        }
    }

    if (showScheduleOcrText && scheduleOcrText.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showScheduleOcrText = false },
            title = { Text("本地 OCR 原文", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        item {
                            SelectionContainer {
                                Text(
                                    text = scheduleOcrText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScheduleOcrText = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (scheduleCandidate.isNotEmpty()) {
        val schedules = scheduleCandidate.take(8)
        AlertDialog(
            onDismissRequest = {
                scheduleCandidate = emptyList()
                scheduleOcrText = ""
            },
            title = { Text("确认导入日程（${schedules.size} 条）") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(schedules) { plan ->
                        val weeklyDays = plan.weeklyDays.orEmpty()
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("标题：${plan.title.ifBlank { "未识别" }}")
                            Text("详情：${plan.detail?.ifBlank { "无" } ?: "无"}")
                            Text("重复：${plan.repeatType}")
                            Text("日期：${plan.oneTimeDate?.ifBlank { "无" } ?: "无"}")
                            Text("时间：${plan.reminderTime?.ifBlank { "无" } ?: "无"}")
                            if (weeklyDays.isNotEmpty()) {
                                Text("每周：${weeklyDays.joinToString(",")}")
                            }
                            if (plan.monthlyDay != null) {
                                Text("每月：${plan.monthlyDay} 号")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onImportSchedules(schedules) { result ->
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "已导入 ${result.getOrNull() ?: schedules.size} 条日程" else (result.exceptionOrNull()?.message ?: "导入失败"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    scheduleCandidate = emptyList()
                    scheduleOcrText = ""
                    selectedImageBase64 = null
                    selectedImageMimeType = null
                    selectedImageBytes = null
                }) { Text("导入") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scheduleOcrText.isNotBlank()) {
                        TextButton(onClick = { showScheduleOcrText = true }) {
                            Text("查看 OCR 原文")
                        }
                    }
                    TextButton(onClick = {
                        scheduleCandidate = emptyList()
                        scheduleOcrText = ""
                    }) { Text("取消") }
                }
            }
        )
    }

    if (todoCandidate != null) {
        val candidate = todoCandidate!!
        AlertDialog(
            onDismissRequest = { todoCandidate = null },
            title = { Text("确认导入待办") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(candidate.items.take(8)) { item ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("• ${item.title}")
                            if (item.detail.isNotBlank()) {
                                Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = candidate.items.map {
                        if (it.detail.isBlank()) it.title else "${it.title}：${it.detail}"
                    }
                    onImportTodos(items) { result ->
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "已导入 ${result.getOrNull() ?: items.size} 条待办到短期目标" else (result.exceptionOrNull()?.message ?: "导入失败"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    todoCandidate = null
                    selectedImageBase64 = null
                    selectedImageMimeType = null
                    selectedImageBytes = null
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { todoCandidate = null }) { Text("取消") }
            }
        )
    }

    MarqueeBorder(
        isActive = sending,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ---- Top bar ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        onNavigateToMenu()
                    },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = "菜单")
                }
                Text(
                    "ActMe",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // ---- Content area (shifts with keyboard) ----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {

            // ---- Message list ----
            val listState = rememberLazyListState()

            val isAtBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    if (info.totalItemsCount == 0) true
                    else {
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        lastVisible >= info.totalItemsCount - 1
                    }
                }
            }

            // Auto-scroll to bottom on initial load
            LaunchedEffect(Unit) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                }
            }

            // Auto-scroll to bottom when sending or already at bottom
            LaunchedEffect(messages.size, sending) {
                if (sending || isAtBottom) {
                    val targetIndex = listState.layoutInfo.totalItemsCount - 1
                    if (targetIndex >= 0) {
                        listState.animateScrollToItem(targetIndex)
                    }
                }
            }

            // Follow streaming content as last assistant message grows
            val lastMsgContentLen = messages.lastOrNull()?.content?.length ?: 0
            LaunchedEffect(lastMsgContentLen) {
                if (sending && isAtBottom) {
                    val targetIndex = listState.layoutInfo.totalItemsCount - 1
                    if (targetIndex >= 0) listState.scrollToItem(targetIndex)
                }
            }

            // Auto-scroll to bottom when keyboard appears
            LaunchedEffect(isInputFocused) {
                if (isInputFocused) {
                    kotlinx.coroutines.delay(200)
                    val targetIndex = listState.layoutInfo.totalItemsCount - 1
                    if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                val showSkeleton = sending && (messages.isEmpty() ||
                    messages.last().role != "assistant" ||
                    messages.last().content.isBlank())
                if (showSkeleton) {
                    item(key = "skeleton") {
                        SkeletonBubble()
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Input area ----
            Column(modifier = Modifier.fillMaxWidth()) {
                // Selected image preview with X overlay
                if (selectedImageBase64 != null && selectedImageBytes != null) {
                    var showPreviewFull by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.padding(bottom = 6.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .size(64.dp)
                        ) {
                            val bitmap = BitmapFactory.decodeByteArray(selectedImageBytes!!, 0, selectedImageBytes!!.size)
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "选中的图片",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showPreviewFull = true },
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .clickable {
                                        selectedImageBase64 = null
                                        selectedImageMimeType = null
                                        selectedImageBytes = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }

                            if (showPreviewFull) {
                                AlertDialog(onDismissRequest = { showPreviewFull = false }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showPreviewFull = false }
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "查看大图",
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.FillWidth
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { importScheduleFromImage() },
                                enabled = !importBusy
                            ) {
                                Text(if (importBusy) "处理中..." else "转日程")
                            }
                            TextButton(
                                onClick = { importTodosFromImage() },
                                enabled = !importBusy
                            ) {
                                Text(if (importBusy) "处理中..." else "转待办")
                            }
                        }
                    }
                }

                if (selectedWorkbookPath != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                selectedWorkbookName ?: "workbook.xlsx",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1
                            )
                            IconButton(
                                onClick = {
                                    selectedWorkbookPath = null
                                    selectedWorkbookName = null
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除 Excel",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { isInputFocused = it.isFocused }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { input += "\n" }
                                    )
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        val native = event.nativeKeyEvent
                                        if (native != null && !native.isShiftPressed && native.metaState == 0) {
                                            doSend()
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                },
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("告诉 ActMe 你现在想做什么...") },
                            minLines = 1,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Filled.Image, contentDescription = "选择图片")
                            }
                            IconButton(onClick = {
                                workbookPicker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.ms-excel.sheet.macroEnabled.12",
                                        "application/vnd.ms-excel"
                                    )
                                )
                            }) {
                                Icon(Icons.Filled.AttachFile, contentDescription = "选择 Excel")
                            }
                            when {
                                isVoiceRecording -> {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            audioRecorder?.stopRecording()
                                            isVoiceRecording = false
                                            onStopRecording?.invoke()
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Stop,
                                                contentDescription = "停止录音",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                "对话中",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                                isTranscribing -> {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White
                                            )
                                            Text(
                                                "识别中",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    IconButton(onClick = {
                                        if (!isModelReady) {
                                            Toast.makeText(context, "请先在设置中下载语音识别模型", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val act = activity ?: return@IconButton
                                            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                                                == PackageManager.PERMISSION_GRANTED) {
                                                audioRecorder?.startRecording(context.cacheDir)
                                                isVoiceRecording = true
                                                onStartRecording?.invoke()
                                                startPreloadingModel()
                                            } else {
                                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Mic, contentDescription = "语音输入")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))

                            // Model selector
                            Box {
                                TextButton(onClick = { showModelMenu = true }) {
                                    Text(
                                        selectedModel.ifBlank { "模型" },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(
                                    expanded = showModelMenu,
                                    onDismissRequest = { showModelMenu = false }
                                ) {
                                    if (availableModels.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("暂无可用模型") },
                                            onClick = { showModelMenu = false }
                                        )
                                    } else {
                                        for (model in availableModels) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        model,
                                                        fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    onSelectModel(model)
                                                    showModelMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (sending) {
                                IconButton(onClick = onStopSending) {
                                    Icon(Icons.Filled.Stop, contentDescription = "停止")
                                }
                            } else {
                                IconButton(onClick = { doSend() }) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "发送")
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

// ---- Message bubble ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageBubble(msg: ChatMessageEntity) {
    val isUser = msg.role == "user"
    val hasImage = !msg.imageBase64.isNullOrBlank() && !msg.imageMimeType.isNullOrBlank()
    val hasSearchResult = !msg.searchResult.isNullOrBlank()
    val resultLabel = resultPanelLabel(msg.searchResult)
    var showFullImage by remember { mutableStateOf(false) }
    var showSearchResult by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val customUriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                pendingUrl = uri
            }
        }
    }

    // Search result dialog
    if (showSearchResult && hasSearchResult) {
        AlertDialog(
            onDismissRequest = { showSearchResult = false },
            title = { Text(resultLabel, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Scrollable search results
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        item {
                            val cleanResult = cleanResultPanelContent(msg.searchResult!!)
                            CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
                                SelectionContainer {
                                    Markdown(
                                        content = cleanResult,
                                        colors = markdownColor(
                                            linkText = Color(0xFF1A73E8),
                                        ),
                                        typography = markdownTypography(
                                            text = MaterialTheme.typography.bodySmall,
                                            paragraph = MaterialTheme.typography.bodySmall,
                                            h1 = MaterialTheme.typography.bodyMedium,
                                            h2 = MaterialTheme.typography.bodyMedium,
                                            h3 = MaterialTheme.typography.bodySmall,
                                            code = MaterialTheme.typography.bodySmall,
                                            link = TextStyle(textDecoration = TextDecoration.Underline),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchResult = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (pendingUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open link") },
            text = {
                Text(
                    pendingUrl!!,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pendingUrl))
                    context.startActivity(intent)
                    pendingUrl = null
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Image displayed independently outside the bubble
        if (hasImage) {
            val bitmap = android.util.Base64.decode(msg.imageBase64, android.util.Base64.NO_WRAP)
                .let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "图片",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .padding(bottom = 4.dp)
                        .clickable { showFullImage = true },
                    contentScale = ContentScale.Crop
                )

                // Full-screen image viewer
                if (showFullImage) {
                    AlertDialog(
                        onDismissRequest = { showFullImage = false }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFullImage = false }
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "查看大图",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }

        // Text bubble (skip if no text content)
        if (msg.content.isNotBlank()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = if (isUser) 12.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 12.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (!isUser) {
                    Text(
                        "ActMe",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Strip search result link from displayed content
                val displayContent = stripResultExpandLink(msg.content)
                if (isUser) {
                    Text(displayContent, style = MaterialTheme.typography.bodyMedium)
                } else {
                    CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
                        SelectionContainer {
                            Markdown(
                                content = displayContent,
                                colors = markdownColor(
                                    linkText = Color(0xFF1A73E8),
                                ),
                                typography = markdownTypography(
                                    text = MaterialTheme.typography.bodyMedium,
                                    paragraph = MaterialTheme.typography.bodyMedium,
                                    h1 = MaterialTheme.typography.bodyLarge,
                                    h2 = MaterialTheme.typography.bodyLarge,
                                    h3 = MaterialTheme.typography.bodyMedium,
                                    code = MaterialTheme.typography.bodySmall,
                                    link = TextStyle(textDecoration = TextDecoration.Underline),
                                )
                            )
                        }
                    }
                }
            }
        }

        // Search result expand button
        val generatedExcelPaths = if (isUser) emptyList() else extractWorkspaceExcelPaths(msg.content)
        if (generatedExcelPaths.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                generatedExcelPaths.take(3).forEach { path ->
                    TextButton(
                        onClick = { openWorkspaceExcel(context, path) }
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            File(path).name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Search result expand button
        if (hasSearchResult) {
            TextButton(
                onClick = { showSearchResult = true },
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    resultExpandLabel(msg.searchResult),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        val tokenLabel = messageTokenLabel(msg)
        if (tokenLabel != null) {
            Text(
                tokenLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = 2.dp,
                    start = if (isUser) 0.dp else 8.dp,
                    end = if (isUser) 8.dp else 0.dp
                )
            )
        }
    }
}

private fun resultPanelLabel(result: String?): String {
    if (result.isNullOrBlank()) return "联网资料"
    val hasBrowse = result.contains("[BROWSE_RESULT]") || result.contains("[BROWSE_ERROR]")
    val hasSearch = result.contains("【联网搜索结果")
    return when {
        hasBrowse && hasSearch -> "联网资料"
        hasBrowse -> "网页阅读内容"
        else -> "搜索结果"
    }
}

private fun extractWorkspaceExcelPaths(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val regex = Regex("""(?:[A-Za-z]:)?[/\\][^\s"'`，。；）)]+agent_workspace[/\\][^\s"'`，。；）)]+\.(?:xlsx|xlsm)""")
    return regex.findAll(text)
        .map { it.value.replace('/', File.separatorChar).replace('\\', File.separatorChar) }
        .distinct()
        .filter { File(it).exists() }
        .toList()
}

private fun openWorkspaceExcel(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在: ${file.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "打开 Excel"))
    }.onFailure {
        Toast.makeText(context, "没有可打开 Excel 的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun resultExpandLabel(result: String?): String {
    val label = resultPanelLabel(result)
    return when (label) {
        "网页阅读内容" -> "📖 展开网页阅读内容"
        "联网资料" -> "🌐 展开联网资料"
        else -> "🔍 展开搜索结果"
    }
}

private fun cleanResultPanelContent(result: String): String {
    return result
        .replace("【联网搜索结果：", "")
        .replace("[BROWSE_RESULT]", "【网页阅读内容】")
        .replace("[BROWSE_ERROR]", "【网页阅读失败】")
}

private fun stripResultExpandLink(content: String): String {
    return content.replace(Regex("\\n?---\\n[🔍📖🌐] \\[展开(?:搜索结果|网页阅读内容|联网资料)]\\(search://result\\)"), "")
}

private fun messageTokenLabel(msg: ChatMessageEntity): String? {
    if (msg.role == "user") return null
    val total = msg.tokenTotal
    if (msg.tokenSource == "api" && total != null && total > 0) {
        val input = msg.tokenInput ?: 0
        val output = msg.tokenOutput ?: 0
        return "API tokens: 输入 $input / 输出 $output / 总计 $total"
    }
    val estimated = estimateMessageTokens(msg)
    return if (estimated > 0) "约 $estimated tokens" else null
}

private fun estimateMessageTokens(msg: ChatMessageEntity): Int {
    val textTokens = estimateTextTokens(msg.content)
    val imageTokens = if (!msg.imageBase64.isNullOrBlank()) 85 else 0
    return textTokens + imageTokens
}

private fun estimateTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    var cjk = 0
    var nonCjk = 0
    for (ch in text) {
        when {
            ch.isWhitespace() -> Unit
            isCjk(ch) -> cjk += 1
            else -> nonCjk += 1
        }
    }
    return cjk + ((nonCjk + 3) / 4)
}

private fun isCjk(ch: Char): Boolean {
    val code = ch.code
    return code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF
}

// ---- Skeleton placeholder ----

@Composable
private fun SkeletonBubble() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("ActMe", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp).background(Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))
            Box(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp).background(Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))
        }
    }
}
