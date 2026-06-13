package com.actme.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.sp
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
import kotlin.math.sin
import kotlin.math.cos

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
    onNavigateToMenu: () -> Unit = {},
    onCreateConversation: () -> Unit = {},
    presetQuestions: List<String> = emptyList(),
    errorMessage: String? = null,
    onErrorDismiss: () -> Unit = {},
    isRefreshingModels: Boolean = false,
    onRefreshModels: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }

    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMimeType by remember { mutableStateOf<String?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedWorkbookPath by remember { mutableStateOf<String?>(null) }
    var selectedWorkbookName by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var scheduleCandidate by remember { mutableStateOf<List<ScheduleSubAgentPlan>>(emptyList()) }
    var scheduleOcrText by remember { mutableStateOf("") }
    var showScheduleOcrText by remember { mutableStateOf(false) }
    var todoCandidate by remember { mutableStateOf<TodoImportPlan?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            onRefreshModels()
        }
    }

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
            focusManager.clearFocus()
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

    // ---- Callback definitions for input area ----
    val onPickImage: () -> Unit = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val onPickWorkbook: () -> Unit = {
        workbookPicker.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel.sheet.macroEnabled.12",
                "application/vnd.ms-excel"
            )
        )
    }
    val onClearImage: () -> Unit = {
        selectedImageBase64 = null
        selectedImageMimeType = null
        selectedImageBytes = null
    }
    val onClearWorkbook: () -> Unit = {
        selectedWorkbookPath = null
        selectedWorkbookName = null
    }
    val onImportScheduleCb: () -> Unit = { importScheduleFromImage() }
    val onImportTodosCb: () -> Unit = { importTodosFromImage() }
    val onVoiceRecordClick = lambda@ {
        if (!isModelReady) {
            Toast.makeText(context, "请先在设置中下载语音识别模型", Toast.LENGTH_SHORT).show()
        } else {
            val act = activity ?: return@lambda
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                audioRecorder?.startRecording(context.cacheDir)
                isVoiceRecording = true
                onStartRecording?.invoke()
                startPreloadingModel()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val onStopRecordingClick: () -> Unit = {
        audioRecorder?.stopRecording()
        isVoiceRecording = false
        onStopRecording?.invoke()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeBorder(
            isActive = sending,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 36.dp
        ) {
            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left: message list
                    ChatMessageListPanel(
                        messages = messages,
                        sending = sending,
                        isInputFocused = isInputFocused,
                        presetQuestions = presetQuestions,
                        onSendPreset = { onSend(it, null, null) },
                        modifier = Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
                    )
                    // Right (40%): title bar + composer
                    Column(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                    ) {
                        ChatTitleBar(
                            focusManager = focusManager,
                            onNavigateToMenu = onNavigateToMenu,
                            onCreateConversation = onCreateConversation
                        )
                        ChatInputArea(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 12.dp, bottom = 12.dp),
                            isLandscape = true,
                            input = input,
                            onInputChange = { input = it },
                            focusRequester = focusRequester,
                            onFocusChanged = { isInputFocused = it },
                            doSend = { doSend() },
                            selectedImageBase64 = selectedImageBase64,
                            selectedImageMimeType = selectedImageMimeType,
                            selectedImageBytes = selectedImageBytes,
                            onClearImage = onClearImage,
                            onImportSchedule = onImportScheduleCb,
                            onImportTodos = onImportTodosCb,
                            importBusy = importBusy,
                            selectedWorkbookPath = selectedWorkbookPath,
                            selectedWorkbookName = selectedWorkbookName,
                            onClearWorkbook = onClearWorkbook,
                            isVoiceRecording = isVoiceRecording,
                            isTranscribing = isTranscribing,
                            onVoiceRecordClick = onVoiceRecordClick,
                            onStopRecordingClick = onStopRecordingClick,
                            onPickImage = onPickImage,
                            onPickWorkbook = onPickWorkbook,
                            availableModels = availableModels,
                            selectedModel = selectedModel,
                            isRefreshingModels = isRefreshingModels,
                            onSelectModel = onSelectModel,
                            sending = sending,
                            onStopSending = onStopSending
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ChatTitleBar(
                        focusManager = focusManager,
                        onNavigateToMenu = onNavigateToMenu,
                        onCreateConversation = onCreateConversation
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    ) {
                        ChatMessageListPanel(
                            messages = messages,
                            sending = sending,
                            isInputFocused = isInputFocused,
                            presetQuestions = presetQuestions,
                            onSendPreset = { onSend(it, null, null) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ChatInputArea(
                            modifier = Modifier.fillMaxWidth(),
                            isLandscape = false,
                            input = input,
                            onInputChange = { input = it },
                            focusRequester = focusRequester,
                            onFocusChanged = { isInputFocused = it },
                            doSend = { doSend() },
                            selectedImageBase64 = selectedImageBase64,
                            selectedImageMimeType = selectedImageMimeType,
                            selectedImageBytes = selectedImageBytes,
                            onClearImage = onClearImage,
                            onImportSchedule = onImportScheduleCb,
                            onImportTodos = onImportTodosCb,
                            importBusy = importBusy,
                            selectedWorkbookPath = selectedWorkbookPath,
                            selectedWorkbookName = selectedWorkbookName,
                            onClearWorkbook = onClearWorkbook,
                            isVoiceRecording = isVoiceRecording,
                            isTranscribing = isTranscribing,
                            onVoiceRecordClick = onVoiceRecordClick,
                            onStopRecordingClick = onStopRecordingClick,
                            onPickImage = onPickImage,
                            onPickWorkbook = onPickWorkbook,
                            availableModels = availableModels,
                            selectedModel = selectedModel,
                            isRefreshingModels = isRefreshingModels,
                            onSelectModel = onSelectModel,
                            sending = sending,
                            onStopSending = onStopSending
                        )
                    }
                }
            }
        }

        // Centered error alert
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF424242).copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier.clickable { onErrorDismiss() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        errorMessage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE0E0E0),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---- Tool execution bubble ----

@Composable
private fun ToolExecutionBubble(msg: ChatMessageEntity) {
    if (msg.content.isBlank()) return

    val isDone = msg.content == "执行完成" || msg.content == "已中止"
    val isCancelled = msg.content == "已中止"
    val hasLog = !msg.searchResult.isNullOrBlank()
    var expanded by remember(msg.id) { mutableStateOf(false) }

    if (expanded && hasLog) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("执行过程", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item {
                        SelectionContainer {
                            Text(
                                text = msg.searchResult!!,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("关闭") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = 4.dp, bottomEnd = 12.dp
                    )
                )
                .then(if (isDone && hasLog) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ActMe label
            Text(
                "ActMe",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Line 1: status title (bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isDone) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = when {
                        isCancelled -> "命令执行中止"
                        isDone -> "命令执行成功"
                        else -> "命令执行中"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isCancelled -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            // Line 2: animated log line or "点击展开" (labelSmall)
            if (isDone) {
                if (hasLog) {
                    Text(
                        "点击展开执行过程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            } else {
                AnimatedContent(
                    targetState = msg.content,
                    transitionSpec = {
                        (slideInVertically { h -> h } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically { h -> -h } + fadeOut(tween(120)))
                    },
                    label = "log-line"
                ) { logLine ->
                    Text(
                        text = logLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
        val generatedFilePaths = if (isUser) {
            emptyList()
        } else {
            extractWorkspaceFilePaths(context, msg.content + "\n" + msg.searchResult.orEmpty())
        }
        LaunchedEffect(msg.id, generatedFilePaths.joinToString("|")) {
            if (!isUser) {
                AppLogger.i(
                    "AgentFile",
                    "bubble msg=${msg.id}, contentChars=${msg.content.length}, searchChars=${msg.searchResult?.length ?: 0}, files=${generatedFilePaths.size}, paths=${generatedFilePaths.joinToString("|")}"
                )
            }
        }
        if (generatedFilePaths.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .padding(top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                generatedFilePaths.take(3).forEach { path ->
                    FileCard(
                        fileName = File(path).name,
                        filePath = path,
                        onClick = { openWorkspaceFile(context, path) }
                    )
                }
            }
        }

        // Info tags row (search result + tokens, same line)
        val tokenLabel = messageTokenLabel(msg)
        if (hasSearchResult || tokenLabel != null) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (hasSearchResult) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFBCBCBC),
                        modifier = Modifier.clickable { showSearchResult = true }
                    ) {
                        Text(
                            resultExpandLabel(msg.searchResult),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                        )
                    }
                }
                if (tokenLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFBCBCBC),
                    ) {
                        Text(
                            tokenLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                        )
                    }
                }
            }
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

private fun extractWorkspaceFilePaths(context: android.content.Context, text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val refs = mutableListOf<String>()
    val absoluteRegex = Regex("""(?:[A-Za-z]:)?[/\\][^\s"'`,;]+agent_workspace[/\\][^\s"'`,;]+""")
    refs += absoluteRegex.findAll(text).map { it.value.trimEnd('.', ',', ';') }
    val outputBlockRegex = Regex("""(?m)^output_files:\s*\n((?:-\s+.+\n?)+)""")
    outputBlockRegex.findAll(text).forEach { match ->
        Regex("""(?m)^-\s+(.+)$""").findAll(match.groupValues[1]).forEach { item ->
            refs += item.groupValues[1].trim().trimEnd('.', ',', ';')
        }
    }
    val workspace = runCatching { PythonSkillEngine.workspaceDir(context).canonicalFile }.getOrNull()
    return refs
        .mapNotNull { normalizeWorkspaceFilePath(it, workspace) }
        .distinct()
        .toList()
}

private fun normalizeWorkspaceFilePath(raw: String, workspace: File?): String? {
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

private fun openWorkspaceFile(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在: ${file.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mimeType = when (file.extension.lowercase()) {
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        "xls" -> "application/vnd.ms-excel"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "json" -> "application/json"
        "txt", "md", "log" -> "text/plain"
        else -> "*/*"
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        Toast.makeText(context, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show()
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
        return "$total tokens: ↑ $input ↓ $output"
    }
    val estimated = estimateMessageTokens(msg)
    return if (estimated > 0) "~$estimated tokens" else null
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

// ---- Welcome card (empty state) ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeCard(
    presetQuestions: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val timeGreeting = when {
        hour < 6 -> "晚上好"; hour < 9 -> "早上好"; hour < 11 -> "上午好"
        hour < 13 -> "中午好"; hour < 18 -> "下午好"; else -> "晚上好"
    }
    val isDark = isSystemInDarkTheme()

    // Three independent linear phases → converted to sin/cos in draw → organic Lissajous paths
    val anim = rememberInfiniteTransition(label = "aura")
    val p1 by anim.animateFloat(0f, 1f,
        infiniteRepeatable(tween(5300, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), "p1")
    val p2 by anim.animateFloat(0f, 1f,
        infiniteRepeatable(tween(7900, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), "p2")
    val p3 by anim.animateFloat(0f, 1f,
        infiniteRepeatable(tween(6100, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), "p3")

    val textPrimary   = if (isDark) Color(0xFFE0E8FF) else Color(0xFF172050)
    val textSecondary = if (isDark) Color(0xFF9AAABF) else Color(0xFF4A5B8A)
    val tagBg   = if (isDark) Color(0xFF1C3A62) else Color(0xFFE8F3FF)
    val tagText = if (isDark) Color(0xFFAAD4FF) else Color(0xFF1A5BAE)

    val questions = presetQuestions.ifEmpty {
        listOf("帮我创建一个日程提醒", "查一查最近的科技新闻", "讲一个有趣的地理知识")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .drawBehind { drawAuraBackground(isDark, p1, p2, p3) }
            .padding(20.dp)
    ) {
        Column {
            Text("Hi，${timeGreeting}～",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = textPrimary)
            Spacer(Modifier.height(6.dp))
            Text("你可以问我任何问题、或者告诉我现在想做什么。",
                style = MaterialTheme.typography.bodyMedium, color = textSecondary)
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                questions.forEach { q ->
                    Surface(onClick = { onTagClick(q) },
                        shape = RoundedCornerShape(20.dp), color = tagBg) {
                        Text(q,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = tagText)
                    }
                }
            }
        }
    }
}

// Radial aura blobs + linear fade-to-white mask at bottom
private fun DrawScope.drawAuraBackground(isDark: Boolean, p1: Float, p2: Float, p3: Float) {
    val w = size.width
    val h = size.height
    val TAU = (2.0 * Math.PI).toFloat()

    // Base
    drawRect(if (isDark) Color(0xFF090E1E) else Color(0xFFFFFFFF))

    // Each blob's center moves on a Lissajous-like path using different phase combos.
    // Using sin/cos of independent linear phases → organic, non-looping feel.

    // ── Blue (dominant) ──
    val bx = w * (0.18f + 0.13f * sin(p1 * TAU))
    val by = h * (0.22f + 0.11f * cos(p2 * TAU + 0.8f))
    val br = w * (0.60f + 0.09f * sin(p3 * TAU + 1.1f))
    val ba = (if (isDark) 0.30f else 0.20f) + 0.07f * sin(p1 * TAU + 0.3f)
    drawRect(Brush.radialGradient(
        listOf(Color(0xFF1F82FF).copy(alpha = ba.coerceIn(0.05f, 1f)), Color.Transparent),
        center = Offset(bx, by), radius = br
    ))

    // ── Purple ──
    val px = w * (0.78f + 0.10f * cos(p2 * TAU + 1.5f))
    val py = h * (0.18f + 0.09f * sin(p3 * TAU + 0.5f))
    val pr = w * (0.50f + 0.08f * cos(p1 * TAU + 2.0f))
    val pa = (if (isDark) 0.24f else 0.16f) + 0.06f * cos(p2 * TAU + 1.0f)
    drawRect(Brush.radialGradient(
        listOf(Color(0xFF9050EE).copy(alpha = pa.coerceIn(0.05f, 1f)), Color.Transparent),
        center = Offset(px, py), radius = pr
    ))

    // ── Orange accent ──
    val ox = w * (0.52f + 0.09f * sin(p3 * TAU + 2.3f))
    val oy = h * (0.30f + 0.10f * cos(p1 * TAU + 1.7f))
    val or_ = w * (0.38f + 0.07f * sin(p2 * TAU + 0.9f))
    val oa = (if (isDark) 0.18f else 0.12f) + 0.05f * sin(p3 * TAU + 0.4f)
    drawRect(Brush.radialGradient(
        listOf(Color(0xFFFF7A30).copy(alpha = oa.coerceIn(0.05f, 1f)), Color.Transparent),
        center = Offset(ox, oy), radius = or_
    ))

    // ── Linear fade-to-white mask over bottom 70% ──
    val maskColor = if (isDark) Color(0xFF090E1E) else Color.White
    drawRect(Brush.verticalGradient(
        colors = listOf(Color.Transparent, maskColor),
        startY = h * 0.30f,
        endY = h
    ))
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

// ---- Shared message list items (used by both portrait and landscape) ----

private fun LazyListScope.ChatMessageItems(
    messages: List<ChatMessageEntity>,
    sending: Boolean,
    presetQuestions: List<String>,
    onSendPreset: (String) -> Unit
) {
    if (messages.isEmpty() && !sending) {
        item(key = "welcome") {
            WelcomeCard(
                presetQuestions = presetQuestions,
                onTagClick = onSendPreset
            )
        }
    }
    items(messages, key = { it.id }) { msg ->
        when (msg.role) {
            "tool_execution" -> ToolExecutionBubble(msg)
            else -> MessageBubble(msg)
        }
    }
    val lastAssistantMsg = messages.lastOrNull { it.role == "assistant" }
    val hasActiveToolFeedback = messages.any { it.role == "tool_execution" && it.content.isNotBlank() }
    val showSkeleton = sending && !hasActiveToolFeedback &&
        (lastAssistantMsg == null || lastAssistantMsg.content.isBlank())
    if (showSkeleton) {
        item(key = "skeleton") {
            SkeletonBubble()
        }
    }
}

// ---- Message list panel ----

@Composable
private fun ChatMessageListPanel(
    messages: List<ChatMessageEntity>,
    sending: Boolean,
    isInputFocused: Boolean,
    presetQuestions: List<String>,
    onSendPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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

    val activeConversationId = messages.firstOrNull()?.conversationId

    LaunchedEffect(activeConversationId) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        val targetIndex = listState.layoutInfo.totalItemsCount - 1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(messages.size, sending) {
        if (sending || isAtBottom) {
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    val lastMsgContentLen = messages.lastOrNull { it.role == "assistant" || it.role == "tool_execution" }?.content?.length ?: 0
    LaunchedEffect(lastMsgContentLen) {
        if (sending && isAtBottom) {
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(isInputFocused) {
        if (isInputFocused) {
            kotlinx.coroutines.delay(200)
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatMessageItems(
            messages = messages,
            sending = sending,
            presetQuestions = presetQuestions,
            onSendPreset = onSendPreset
        )
    }
}

// ---- Title bar ----

@Composable
private fun ChatTitleBar(
    focusManager: androidx.compose.ui.focus.FocusManager,
    onNavigateToMenu: () -> Unit,
    onCreateConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
        IconButton(
            onClick = {
                focusManager.clearFocus()
                onCreateConversation()
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话")
        }
    }
}

// ---- Input area panel ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    input: String,
    onInputChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    doSend: () -> Unit,
    selectedImageBase64: String?,
    selectedImageMimeType: String?,
    selectedImageBytes: ByteArray?,
    onClearImage: () -> Unit,
    onImportSchedule: () -> Unit,
    onImportTodos: () -> Unit,
    importBusy: Boolean,
    selectedWorkbookPath: String?,
    selectedWorkbookName: String?,
    onClearWorkbook: () -> Unit,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    onVoiceRecordClick: () -> Unit,
    onStopRecordingClick: () -> Unit,
    onPickImage: () -> Unit,
    onPickWorkbook: () -> Unit,
    availableModels: List<String>,
    selectedModel: String,
    isRefreshingModels: Boolean,
    onSelectModel: (String) -> Unit,
    sending: Boolean,
    onStopSending: () -> Unit
) {
    var showModelMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Selected image preview
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
                            .clickable { onClearImage() },
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
                        onClick = onImportSchedule,
                        enabled = !importBusy
                    ) {
                        Text(if (importBusy) "处理中..." else "转日程")
                    }
                    TextButton(
                        onClick = onImportTodos,
                        enabled = !importBusy
                    ) {
                        Text(if (importBusy) "处理中..." else "转待办")
                    }
                }
            }
        }

        // Workbook attachment
        if (selectedWorkbookPath != null) {
            val wbPath = selectedWorkbookPath!!
            val wbName = selectedWorkbookName ?: "workbook.xlsx"
            val wbFile = remember(wbPath) { File(wbPath) }
            val wbSize = remember(wbPath) { if (wbFile.exists()) wbFile.length() else 0L }
            val wbExt = remember(wbPath) { wbFile.extension.lowercase() }
            val wbStyle = remember(wbExt) { fileTypeStyle(wbExt) }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = wbStyle.icon,
                        contentDescription = wbExt,
                        tint = wbStyle.tint,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            wbName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                        if (wbSize > 0) {
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                formatFileSize(wbSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(
                        onClick = onClearWorkbook,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除文件",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Composer surface
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().then(if (isLandscape) Modifier.fillMaxHeight() else Modifier)
        ) {
            Column(modifier = if (isLandscape) Modifier.fillMaxHeight() else Modifier) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isLandscape) Modifier.weight(1f) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { onInputChange(input + "\n") }
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
                    onValueChange = onInputChange,
                    placeholder = { Text("告诉 ActMe 你现在想做什么...") },
                    minLines = if (isLandscape) 3 else 1,
                    maxLines = if (isLandscape) 20 else 6,
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPickImage) {
                        Icon(Icons.Filled.Image, contentDescription = "选择图片")
                    }
                    IconButton(onClick = onPickWorkbook) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "选择 Excel")
                    }
                    when {
                        isVoiceRecording -> {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onStopRecordingClick() }
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
                            IconButton(onClick = onVoiceRecordClick) {
                                Icon(Icons.Filled.Mic, contentDescription = "语音输入")
                            }
                        }
                    }
                    // Model selector
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(
                            onClick = { showModelMenu = true }
                        ) {
                            if (isRefreshingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                selectedModel.ifBlank { "模型" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
