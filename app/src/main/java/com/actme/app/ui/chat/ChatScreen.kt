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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Spacer
import com.actme.app.audio.AsrManager
import com.actme.app.audio.AudioRecorderManager
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.ChatSessionEntity
import com.actme.app.mnn.DownloadState
import com.actme.app.mnn.ModelManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun ChatScreen(
    sessions: List<ChatSessionEntity>,
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
    onStopRecording: (() -> Unit)? = null
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("聊天列表", modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                Button(
                    onClick = {
                        onCreateConversation()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text("新聊天")
                }
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    items(sessions) { session ->
                        Column(modifier = Modifier.padding(bottom = 4.dp)) {
                            NavigationDrawerItem(
                                label = { Text(session.title) },
                                selected = session.id == currentConversationId,
                                onClick = {
                                    onSwitchConversation(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Row(modifier = Modifier.padding(start = 14.dp, end = 14.dp)) {
                                TextButton(
                                    onClick = {
                                        renameTarget = session
                                        renameInput = session.title
                                    }
                                ) { Text("重命名") }
                                TextButton(onClick = { deleteTarget = session }) { Text("删除") }
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
                TextButton(onClick = { scope.launch { drawerState.open() } }) {
                    Text("会话")
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
                            Text("✕")
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("告诉 ActMe 你现在想做什么...") }
                    )
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else {
                        Button(onClick = {
                            val text = input.trim()
                            if (text.isNotBlank() || selectedImageBase64 != null) {
                                onSend(text, selectedImageBase64, selectedImageMimeType)
                                input = ""
                                selectedImageBase64 = null
                                selectedImageMimeType = null
                                selectedImageBytes = null
                            }
                        }) {
                            Text("发送")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Text("🖼", modifier = Modifier.padding(4.dp))
                        }
                        Text("图片", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(start = 24.dp)
                    ) {
                        if (isVoiceRecording) {
                            val pulseScale by animateFloatAsState(
                                targetValue = if (isVoiceRecording) 1.3f else 1.0f,
                                animationSpec = tween(600),
                                label = "pulse"
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .scale(pulseScale),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(onClick = {
                                    audioRecorder?.stopRecording()
                                    isVoiceRecording = false
                                    onStopRecording?.invoke()
                                }) {
                                    Text("🔴", modifier = Modifier.padding(4.dp))
                                }
                            }
                            Text("停止", style = MaterialTheme.typography.labelSmall, color = Color.Red)
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
                                Text("🎤", modifier = Modifier.padding(4.dp))
                            }
                            Text("语音", style = MaterialTheme.typography.labelSmall)
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
