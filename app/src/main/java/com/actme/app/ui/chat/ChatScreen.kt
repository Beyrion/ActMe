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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import com.actme.app.audio.AsrManager
import com.actme.app.ui.theme.MarqueeBorder
import com.actme.app.audio.AudioRecorderManager
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.mnn.DownloadState
import com.actme.app.mnn.ModelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessageEntity>,
    onSend: (String, String?, String?) -> Unit,
    sendingConversationId: Long? = null,
    isRecording: Boolean = false,
    onStartRecording: (() -> Unit)? = null,
    onStopRecording: (() -> Unit)? = null,
    availableModels: List<String> = emptyList(),
    selectedModel: String = "",
    onSelectModel: (String) -> Unit = {},
    onNavigateToMenu: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMimeType by remember { mutableStateOf<String?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val sending = sendingConversationId != null

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

    var isTranscribing by remember { mutableStateOf(false) }

    val modelManager = remember { ModelManager(context) }
    val downloadState by modelManager.downloadState.collectAsState()
    val modelInfo by modelManager.modelInfo.collectAsState()
    var isModelReady by remember { mutableStateOf(modelManager.isModelReady) }
    var showModelDialog by remember { mutableStateOf(false) }

    val asrManager = remember(isModelReady) {
        if (isModelReady) AsrManager(AsrManager.getDefaultModelPath(context)) else null
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

    LaunchedEffect(recordingFile) {
        val file = recordingFile ?: return@LaunchedEffect


        val manager = asrManager
        if (manager == null) {
            showModelDialog = true
            recordingFile = null
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
            Log.i("ChatScreen", "ASR transcribed: $text")
            if (text.isNotBlank()) {
                input = text
                // Auto-send the transcribed text to the LLM
                onSend(text, null, null)
            } else {
                Log.w("ChatScreen", "ASR returned blank text")
                Toast.makeText(context, "未识别到语音内容", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ChatScreen", "ASR error", e)
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

    MarqueeBorder(
        isActive = sending,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // ---- Top bar ----
            Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateToMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "菜单")
            }
                Text("ActMe Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

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
                if (sending) {
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
                        // Small X button
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

                        // Full-screen preview
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
                    val info = modelInfo
                    if (info != null) {
                        Text("模型: ${info.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "文件数: ${info.fileCount} · 总大小: ${modelManager.formatSize(info.totalSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else {
                        Text("Qwen3-ASR 端侧语音识别模型", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("约 1.3 GB，下载后无需联网即可使用语音输入", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (val state = downloadState) {
                        is DownloadState.NotStarted -> {
                            Button(onClick = {
                                scope.launch { try { modelManager.downloadModel() } catch (_: Exception) {} }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("下载模型")
                            }
                        }
                        is DownloadState.Checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("正在获取模型信息...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is DownloadState.Downloading -> {
                            Column {
                                Text("${state.currentFile} (${state.currentFileIndex}/${state.totalFiles})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(progress = state.currentFileProgress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${(state.currentFileProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text("${modelManager.formatSize(state.totalBytesDownloaded)} / ${modelManager.formatSize(state.totalBytes)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = if (state.totalBytes > 0) (state.totalBytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("整体进度: ${((state.totalBytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                        is DownloadState.Error -> {
                            Column {
                                Text("下载失败: ${state.message}", style = MaterialTheme.typography.bodySmall, color = Color.Red)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = {
                                    scope.launch { try { modelManager.downloadModel() } catch (_: Exception) {} }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("重试")
                                }
                            }
                        }
                        is DownloadState.Completed -> {
                            Text("模型已下载完成", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                        }
                    }
                }
            },
            confirmButton = {
                if (downloadState is DownloadState.Completed) {
                    TextButton(onClick = { showModelDialog = false }) { Text("确定") }
                } else if (downloadState !is DownloadState.Downloading) {
                    TextButton(onClick = { showModelDialog = false }) { Text("取消") }
                }
            },
            dismissButton = null
        )
    }
}

// ---- Message bubble ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageBubble(msg: ChatMessageEntity) {
    val isUser = msg.role == "user"
    val hasImage = !msg.imageBase64.isNullOrBlank() && !msg.imageMimeType.isNullOrBlank()
    var showFullImage by remember { mutableStateOf(false) }

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
                Text(msg.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
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
