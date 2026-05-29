package com.actme.app.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import com.actme.app.data.local.ProviderEntity
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providers: List<ProviderEntity>,
    activeProviderId: Long,
    onClearChatHistory: () -> Unit,
    onAddProvider: (String, String, String, String) -> Unit,
    onUpdateProvider: (Long, String, String, String, String) -> Unit,
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

    // Add/Edit provider dialog
    if (showAddDialog || editingProvider != null) {
        ProviderEditDialog(
            initialName = editingProvider?.name ?: "",
            initialFormat = editingProvider?.providerFormat ?: "openai",
            initialEndpoint = editingProvider?.endpoint ?: "",
            initialSk = "", // SK is never pre-filled for security
            isEdit = editingProvider != null,
            onDismiss = {
                showAddDialog = false
                editingProvider = null
            },
            onSave = { name, format, endpoint, sk ->
                val provider = editingProvider
                if (provider != null) {
                    onUpdateProvider(provider.id, name, format, endpoint, sk)
                } else {
                    onAddProvider(name, format, endpoint, sk)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditDialog(
    initialName: String,
    initialFormat: String,
    initialEndpoint: String,
    initialSk: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf(initialFormat) }
    var endpoint by remember { mutableStateOf(initialEndpoint) }
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
                        onSave(name.trim(), format, endpoint.trim(), sk.trim())
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
