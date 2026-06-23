package com.actme.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import com.actme.app.data.local.ProviderEntity
import com.actme.app.ui.AdbOverlayService
import com.actme.app.ui.GeckoDebugActivity
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.actme.app.data.agent.AdbPairingScreenshotWatcher
import com.actme.app.data.agent.AdbSkillEngine
import com.actme.app.mnn.DownloadState
import com.actme.app.mnn.ModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providers: List<ProviderEntity>,
    activeProviderId: Long,
    isModelReady: Boolean,
    downloadState: DownloadState,
    isVisionModelReady: Boolean,
    visionDownloadState: DownloadState,
    isOcrModelReady: Boolean,
    ocrDownloadState: DownloadState,
    asrLanguage: String,
    localAsrModelDir: String,
    localVisionModelDir: String,
    localOcrModelDir: String,
    onSetAsrLanguage: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onDownloadVisionModel: () -> Unit,
    onDeleteVisionModel: () -> Unit,
    onDownloadOcrModel: () -> Unit,
    onDeleteOcrModel: () -> Unit,
    onClearChatHistory: () -> Unit,
    onAddProvider: (String, String, String, String, String) -> Unit,
    onUpdateProvider: (Long, String, String, String, String, String) -> Unit,
    onDeleteProvider: (Long) -> Unit,
    onSetActiveProvider: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProviderEntity?>(null) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }
    var showDeleteVisionModelDialog by remember { mutableStateOf(false) }
    var showDeleteOcrModelDialog by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var showAdbDialog by remember { mutableStateOf(false) }
    var showAdbOverlayPermissionDialog by remember { mutableStateOf(false) }

    fun beginAdbScreenshotWatch() {
        AdbPairingScreenshotWatcher.start(context)
        Toast.makeText(context, "已开始监听图库新增截图，请在无线调试页面截图 ADB 配对信息。", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            Toast.makeText(context, "无法打开无线调试设置页：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val adbScreenshotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginAdbScreenshotWatch()
        } else {
            Toast.makeText(context, "需要图库读取权限才能检测新增截图。", Toast.LENGTH_LONG).show()
        }
    }

    if (showAdbDialog) {
        AdbPairingDialog(onDismiss = { showAdbDialog = false })
    }

    if (showAdbOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAdbOverlayPermissionDialog = false },
            title = { Text("需要悬浮窗权限") },
            text = { Text("ADB 配对窗口必须显示在系统无线调试页面上方。请允许 ActMe“显示在其他应用上层”。如果系统另外提示“允许在设置上重叠显示”，也必须打开，否则无线调试页面上看不到配对窗口。授权后返回设置页重新打开内置 ADB。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAdbOverlayPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                ) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showAdbOverlayPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Clear history dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空聊天记录") },
            text = { Text("确定要删除所有聊天记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearChatHistory()
                    showClearDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    // Delete provider confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除提供商") },
            text = { Text("确定要删除「${deleteTarget?.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { onDeleteProvider(it.id) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // Delete model confirmation
    if (showDeleteModelDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteModelDialog = false },
            title = { Text("删除语音模型") },
            text = { Text("确定要删除本地语音识别模型吗？删除后需重新下载才能使用语音输入。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteModel()
                    showDeleteModelDialog = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModelDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteVisionModelDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteVisionModelDialog = false },
            title = { Text("删除视觉模型") },
            text = { Text("确定要删除本地图片识别模型吗？删除后需重新下载才能使用图片转日程/待办。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteVisionModel()
                    showDeleteVisionModelDialog = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVisionModelDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteOcrModelDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteOcrModelDialog = false },
            title = { Text("删除 OCR 模型") },
            text = { Text("确定要删除 GLM-OCR-MNN 吗？删除后需要重新下载才能使用 ADB 截图 OCR。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteOcrModel()
                    showDeleteOcrModelDialog = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteOcrModelDialog = false }) { Text("取消") }
            }
        )
    }

    // Add/Edit provider dialog
    if (showAddDialog || editingProvider != null) {
        ProviderEditDialog(
            initialName = editingProvider?.name ?: "",
            initialFormat = editingProvider?.providerFormat ?: "openai",
            initialEndpoint = editingProvider?.endpoint ?: "",
            initialDefaultModel = editingProvider?.defaultModel ?: "",
            initialSk = "", // SK is never pre-filled for security
            isEdit = editingProvider != null,
            onDismiss = {
                showAddDialog = false
                editingProvider = null
            },
            onSave = { name, format, endpoint, defaultModel, sk ->
                val provider = editingProvider
                if (provider != null) {
                    onUpdateProvider(provider.id, name, format, endpoint, defaultModel, sk)
                } else {
                    onAddProvider(name, format, endpoint, defaultModel, sk)
                }
                showAddDialog = false
                editingProvider = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- 模型提供商 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "模型提供商",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加提供商")
            }
        }

        if (providers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                ListItem(
                    headlineContent = { Text("尚未添加提供商") },
                    supportingContent = { Text("点击 + 添加 OpenAI 或 Anthropic 兼容的 API 提供商") },
                    leadingContent = {
                        Icon(Icons.Outlined.Api, null, Modifier.size(24.dp))
                    }
                )
            }
        } else {
            for (provider in providers) {
                val isActive = provider.id == activeProviderId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSetActiveProvider(provider.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isActive,
                            onClick = { onSetActiveProvider(provider.id) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${provider.providerFormat.uppercase()} · ${provider.endpoint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                            if (provider.defaultModel.isNotBlank()) {
                                Text(
                                    "默认模型: ${provider.defaultModel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = { editingProvider = provider }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "编辑",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { deleteTarget = provider }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "删除",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- 语音 ----
        Text(
            "语音",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
            ListItem(
                headlineContent = { Text(ModelManager.MODEL_NAME) },
                supportingContent = {
                    Text("ModelScope: ${ModelManager.MODEL_OWNER}, dir: $localAsrModelDir")
                },
                trailingContent = {
                    when {
                        isModelReady -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "已下载",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = { showDeleteModelDialog = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "删除模型",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        downloadState is DownloadState.Downloading -> {
                            val state = downloadState as DownloadState.Downloading
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    "${(state.currentFileProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        downloadState is DownloadState.Checking -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        else -> {
                            TextButton(onClick = onDownloadModel) {
                                Text("下载")
                            }
                        }
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "语音输入语言偏好",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "选择首要语言，以提升本地语音识别模型的准确性",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Box {
                    TextButton(onClick = { langExpanded = true }) {
                        Text(
                            if (asrLanguage == "Chinese") "中文" else "English",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = langExpanded,
                        onDismissRequest = { langExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("中文") },
                            onClick = { onSetAsrLanguage("Chinese"); langExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("English") },
                            onClick = { onSetAsrLanguage("English"); langExpanded = false }
                        )
                    }
                }
            }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            "本地视觉模型",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ListItem(
                    headlineContent = { Text("GUI-Owl-1.5-2B-Instruct-MNN") },
                    supportingContent = {
                        Text("来源：ModelScope 社区，目录：$localVisionModelDir")
                    },
                    trailingContent = {
                        when {
                            isVisionModelReady -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "已下载",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = { showDeleteVisionModelDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "删除视觉模型",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            visionDownloadState is DownloadState.Downloading -> {
                                val state = visionDownloadState as DownloadState.Downloading
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "${(state.currentFileProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            visionDownloadState is DownloadState.Checking -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            else -> {
                                TextButton(onClick = onDownloadVisionModel) {
                                    Text("下载")
                                }
                            }
                        }
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("GLM-OCR-MNN") },
                    supportingContent = {
                        Text("来源：ModelScope 社区，专用于 ADB 截图 OCR，目录：$localOcrModelDir")
                    },
                    trailingContent = {
                        when {
                            isOcrModelReady -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "已下载",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = { showDeleteOcrModelDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "删除 OCR 模型",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            ocrDownloadState is DownloadState.Downloading -> {
                                val state = ocrDownloadState as DownloadState.Downloading
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "${(state.currentFileProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            ocrDownloadState is DownloadState.Checking -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            else -> {
                                TextButton(onClick = onDownloadOcrModel) {
                                    Text("下载")
                                }
                            }
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- 操作 ----
        Text(
            "操作",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            ListItem(
                headlineContent = { Text("清空聊天记录") },
                supportingContent = { Text("删除所有聊天会话和消息") },
                leadingContent = {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(24.dp))
                },
                modifier = Modifier.clickable { showClearDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            ListItem(
                headlineContent = { Text("内置浏览器") },
                supportingContent = { Text("使用 GeckoView 打开网页") },
                leadingContent = {
                    Icon(Icons.Outlined.Info, null, Modifier.size(24.dp))
                },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(context, GeckoDebugActivity::class.java))
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            ListItem(
                headlineContent = { Text("内置 ADB") },
                supportingContent = { Text("截图识别无线调试配对信息，并自动连接 adb shell") },
                leadingContent = {
                    Icon(Icons.Outlined.Api, null, Modifier.size(24.dp))
                },
                modifier = Modifier.clickable {
                    val permission = AdbPairingScreenshotWatcher.imageReadPermission()
                    if (permission == null ||
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    ) {
                        beginAdbScreenshotWatch()
                    } else {
                        adbScreenshotPermissionLauncher.launch(permission)
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- 关于 ----
        Text(
            "关于",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            ListItem(
                headlineContent = { Text("ActMe") },
                supportingContent = { Text("AI 生活助手") },
                leadingContent = {
                    Icon(Icons.Outlined.Info, null, Modifier.size(24.dp))
                },
                trailingContent = {
                    Text(
                        versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AdbPairingDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val saved = remember { AdbSkillEngine.getSavedConfig() }
    var host by remember { mutableStateOf(saved.host) }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf(saved.port.toString()) }
    var shellCommand by remember { mutableStateOf("echo actme_adb_ready") }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("请保持系统无线调试的配对码弹窗不关闭，在这里输入配对端口和验证码。")
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("内置 ADB 配对") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "操作流程\n" +
                        "1. 在系统无线调试中点击“使用配对码配对设备”。\n" +
                        "2. 不要关闭系统配对码弹窗，把配对端口和验证码填到这里。\n" +
                        "3. 配对成功后，把无线调试主页面显示的连接端口填入连接端口。\n" +
                        "4. 点击“测试并保存连接”，看到 actme_adb_ready 后即可让 Agent 调用 adb_shell。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("配对端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.filter(Char::isDigit).take(8) },
                        label = { Text("验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    enabled = !busy,
                    onClick = {
                        val port = pairPort.toIntOrNull()
                        if (port == null) {
                            status = "配对端口无效。"
                            return@Button
                        }
                        busy = true
                        status = "正在配对 $host:$port ..."
                        scope.launch {
                            val result = AdbSkillEngine.pair(host, port, pairCode)
                            status = if (result.ok) result.output else "配对失败：${result.error}"
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "执行中" else "配对")
                }

                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !busy,
                    onClick = {
                        val port = connectPort.toIntOrNull()
                        if (port == null) {
                            status = "连接端口无效。"
                            return@Button
                        }
                        busy = true
                        status = "正在连接 $host:$port ..."
                        scope.launch {
                            val result = AdbSkillEngine.testConnection(host, port)
                            status = if (result.ok) {
                                "连接成功，已保存 $host:$port\n${result.output.trim()}"
                            } else {
                                "连接失败：${result.error.ifBlank { result.output }}"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试并保存连接")
                }

                OutlinedTextField(
                    value = shellCommand,
                    onValueChange = { shellCommand = it },
                    label = { Text("adb shell 命令") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !busy,
                    onClick = {
                        val port = connectPort.toIntOrNull()
                        busy = true
                        status = "正在执行 shell ..."
                        scope.launch {
                            val result = AdbSkillEngine.shell(shellCommand, host, port)
                            status = buildString {
                                appendLine(if (result.ok) "执行成功" else "执行失败")
                                result.exitCode?.let { appendLine("exit_code: $it") }
                                if (result.output.isNotBlank()) appendLine(result.output.trimEnd())
                                if (result.error.isNotBlank()) appendLine("stderr: ${result.error.trimEnd()}")
                            }.trim()
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("执行 shell 测试")
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss
            ) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditDialog(
    initialName: String,
    initialFormat: String,
    initialEndpoint: String,
    initialDefaultModel: String,
    initialSk: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf(initialFormat) }
    var endpoint by remember { mutableStateOf(initialEndpoint) }
    var defaultModel by remember { mutableStateOf(initialDefaultModel) }
    var sk by remember { mutableStateOf(initialSk) }
    var formatExpanded by remember { mutableStateOf(false) }

    val formatOptions = listOf("openai" to "OpenAI", "anthropic" to "Anthropic")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑提供商" else "添加提供商") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：我的 OpenAI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = it }
                ) {
                    OutlinedTextField(
                        value = formatOptions.firstOrNull { it.first == format }?.second ?: format,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("格式") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        for ((key, label) in formatOptions) {
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    format = key
                                    formatExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("默认模型（可选）") },
                    placeholder = { Text("留空则拉取模型列表") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = sk,
                    onValueChange = { sk = it },
                    label = { Text("Secret Key") },
                    placeholder = { Text(if (isEdit) "留空则不修改" else "sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && endpoint.isNotBlank() && (isEdit || sk.isNotBlank())) {
                        onSave(name.trim(), format, endpoint.trim(), defaultModel.trim(), sk.trim())
                    }
                },
                enabled = name.isNotBlank() && endpoint.isNotBlank() && (isEdit || sk.isNotBlank())
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
