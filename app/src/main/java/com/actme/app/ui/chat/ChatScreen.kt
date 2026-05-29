package com.actme.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import com.actme.app.audio.AsrManager
import com.actme.app.audio.AudioRecorderManager
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.ChatSessionEntity
import com.actme.app.data.local.ChatSessionInfo
import com.actme.app.util.formatRelativeTime
import com.actme.app.mnn.DownloadState
import com.actme.app.mnn.ModelManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    sessionInfos: List<ChatSessionInfo>,
    currentConversationId: Long?,
    messages: List<ChatMessageEntity>,
    onCreateConversation: () -> Unit,
    onSwitchConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onSend: (String, String?, String?) -> Unit,
    sending: Boolean,
    isRecording: Boolean = false,
    onStartRecording: (() -> Unit)? = null,
    onStopRecording: (() -> Unit)? = null,
    availableModels: List<String> = emptyList(),
    selectedModel: String = "",
    onSelectModel: (String) -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }

    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMimeType by remember { mutableStateOf<String?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Voice recording state
    var isVoiceRecording by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    val activity = context as? android.app.Activity

    val audioRecorder = remember {
        activity?.let { AudioRecorderManager(it) }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioRecorder?.startRecording(context.cacheDir)
            isVoiceRecording = true
            onStartRecording?.invoke()
        } else {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // Transcribing state
    var isTranscribing by remember { mutableStateOf(false) }

    // Model manager for download
    val modelManager = remember { ModelManager(context) }
    val downloadState by modelManager.downloadState.collectAsState()
    val modelInfo by modelManager.modelInfo.collectAsState()
    var isModelReady by remember { mutableStateOf(modelManager.isModelReady) }
    var showModelDialog by remember { mutableStateOf(false) }

    // Lazy ASR manager (re-created when model becomes ready)
    val asrManager = remember(isModelReady) {
        if (isModelReady) {
            AsrManager(AsrManager.getDefaultModelPath(context))
        } else {
            null
        }
    }

    // Handle recording callbacks
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

    // Trigger ASR when recording file is ready
    LaunchedEffect(recordingFile) {
        val file = recordingFile ?: return@LaunchedEffect
        recordingFile = null

        val manager = asrManager
        if (manager == null) {
            showModelDialog = true
            return@LaunchedEffect
        }

        isTranscribing = true
        try {
            if (!manager.isLoaded) {
                val ok = manager.init()
                if (!ok) {
                    Toast.makeText(context, "ASR模型加载失败", Toast.LENGTH_SHORT).show()
                    return@LaunchedEffect
                }
            }
            val text = manager.transcribe(file)
            if (text.isNotBlank()) {
                input = if (input.isBlank()) text else "$input $text"
            } else {
                Toast.makeText(context, "未识别到语音内容", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ChatScreen", "ASR error", e)
            Toast.makeText(context, "语音识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isTranscribing = false
            file.delete()
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
                    val outputStream = ByteArrayOutputStream()
                    val format = if (mimeType.contains("png")) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
                    bitmap.compress(format, 85, outputStream)
                    val compressed = outputStream.toByteArray()
                    selectedImageBase64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                    selectedImageMimeType = mimeType
                    selectedImageBytes = compressed
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "所有会话",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onCreateConversation()
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新聊天")
                    }
                }
                LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
                    items(sessionInfos, key = { it.session.id }) { info ->
                        var showMenu by remember { mutableStateOf(false) }
                        val session = info.session
                        val selected = session.id == currentConversationId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        onSwitchConversation(session.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    onLongClick = { showMenu = true }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${info.messageCount} 轮对话",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        formatRelativeTime(session.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = {
                                        showMenu = false
                                        renameTarget = session
                                        renameInput = session.title
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        deleteTarget = session
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Filled.Forum, contentDescription = "会话列表")
                }
                Text("ActMe Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text("在这里和你的行动助手对话，Agent 会自动判断是否联网查询。")
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isUser) Color(0xFFD7F3D8) else Color(0xFFEAEFFB),
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(10.dp)
                    ) {
                        Text(if (isUser) "我" else "ActMe", fontWeight = FontWeight.SemiBold)
                        if (!msg.imageBase64.isNullOrBlank() && !msg.imageMimeType.isNullOrBlank()) {
                            val bitmap = android.util.Base64.decode(msg.imageBase64, android.util.Base64.NO_WRAP)
                                .let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "发送的图片",
                                    modifier = Modifier
                                        .height(120.dp)
                                        .padding(bottom = 4.dp)
                                )
                            }
                        }
                        Text(msg.content)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (selectedImageBase64 != null) {
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        selectedImageBytes?.let { bytes ->
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "选中的图片",
                                modifier = Modifier
                                    .height(60.dp)
                                    .padding(end = 8.dp)
                            )
                        }
                        IconButton(onClick = {
                            selectedImageBase64 = null
                            selectedImageMimeType = null
                            selectedImageBytes = null
                        }) {
                            Icon(Icons.Filled.Image, contentDescription = "移除图片")
                        }
                    }
                }
                if (isTranscribing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在识别语音...", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                            modifier = Modifier.fillMaxWidth(),
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("告诉 ActMe 你现在想做什么...") },
                            minLines = 2,
                            maxLines = 6,
                            singleLine = false,
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
                            if (isVoiceRecording) {
                                IconButton(onClick = {
                                    audioRecorder?.stopRecording()
                                    isVoiceRecording = false
                                    onStopRecording?.invoke()
                                }) {
                                    Icon(Icons.Filled.MicOff, contentDescription = "停止录音", tint = Color.Red)
                                }
                            } else {
                                IconButton(onClick = {
                                    if (!isModelReady) {
                                        showModelDialog = true
                                    } else {
                                        val act = activity ?: return@IconButton
                                        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                                            == PackageManager.PERMISSION_GRANTED) {
                                            audioRecorder?.startRecording(context.cacheDir)
                                            isVoiceRecording = true
                                            onStartRecording?.invoke()
                                        } else {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Mic, contentDescription = "语音输入")
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
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    val text = input.trim()
                                    if (text.isNotBlank() || selectedImageBase64 != null) {
                                        onSend(text, selectedImageBase64, selectedImageMimeType)
                                        input = ""
                                        selectedImageBase64 = null
                                        selectedImageMimeType = null
                                        selectedImageBytes = null
                                    }
                                }) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "发送")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // React to download completion
    LaunchedEffect(downloadState) {
        when (val state = downloadState) {
            is DownloadState.Completed -> {
                isModelReady = true
                showModelDialog = false
                Toast.makeText(context, "ASR模型下载完成，可以使用语音输入了", Toast.LENGTH_SHORT).show()
            }
            is DownloadState.Error -> {
                Toast.makeText(context, "模型下载失败: ${state.message}", Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    // Model download dialog
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = {
                if (downloadState !is DownloadState.Downloading) {
                    showModelDialog = false
                }
            },
            title = { Text("语音识别模型") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Model info
                    val info = modelInfo
                    if (info != null) {
                        Text(
                            "模型: ${info.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "文件数: ${info.fileCount} · 总大小: ${modelManager.formatSize(info.totalSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else {
                        Text(
                            "Qwen3-ASR 端侧语音识别模型",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "约 1.3 GB，下载后无需联网即可使用语音输入",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Download progress
                    when (val state = downloadState) {
                        is DownloadState.NotStarted -> {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            modelManager.downloadModel()
                                        } catch (_: Exception) {}
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("下载模型")
                            }
                        }

                        is DownloadState.Checking -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("正在获取模型信息...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        is DownloadState.Downloading -> {
                            Column {
                                Text(
                                    "${state.currentFile} (${state.currentFileIndex}/${state.totalFiles})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Per-file progress
                                LinearProgressIndicator(
                                    progress = state.currentFileProgress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${(state.currentFileProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                    Text(
                                        "${modelManager.formatSize(state.totalBytesDownloaded)} / ${modelManager.formatSize(state.totalBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                // Overall progress
                                LinearProgressIndicator(
                                    progress = if (state.totalBytes > 0)
                                        (state.totalBytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                                    else 0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "整体进度: ${((state.totalBytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f) * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        is DownloadState.Error -> {
                            Column {
                                Text(
                                    "下载失败: ${state.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Red
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            try { modelManager.downloadModel() } catch (_: Exception) {}
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("重试")
                                }
                            }
                        }

                        is DownloadState.Completed -> {
                            Text(
                                "模型已下载完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (downloadState is DownloadState.Completed) {
                    TextButton(onClick = { showModelDialog = false }) {
                        Text("确定")
                    }
                } else if (downloadState !is DownloadState.Downloading) {
                    TextButton(onClick = { showModelDialog = false }) {
                        Text("取消")
                    }
                }
            },
            dismissButton = null
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("会话名") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameTarget ?: return@TextButton
                        onRenameConversation(target.id, renameInput)
                        renameTarget = null
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确认删除「${deleteTarget?.title.orEmpty()}」及其全部消息吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget ?: return@TextButton
                        onDeleteConversation(target.id)
                        deleteTarget = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}
