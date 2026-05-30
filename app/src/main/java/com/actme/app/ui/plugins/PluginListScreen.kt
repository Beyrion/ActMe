package com.actme.app.ui.plugins

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actme.app.data.local.PluginDao
import com.actme.app.plugins.Plugin
import com.actme.app.plugins.PluginAlarmManager
import com.actme.app.plugins.PluginSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginListScreen(
    plugins: List<Plugin>,
    pluginDao: PluginDao,
    pluginAlarmManager: PluginAlarmManager,
    onBack: () -> Unit,
    onReloadPlugin: (pluginId: String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectedPlugin by remember { mutableStateOf<Plugin?>(null) }

    val filtered = remember(query, plugins) {
        if (query.isBlank()) plugins
        else plugins.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "插件",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // ---- Search bar ----
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            placeholder = { Text("搜索插件", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索", modifier = Modifier.size(20.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (query.isBlank()) "暂无插件" else "没有匹配「$query」的插件",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                items(filtered, key = { it.id }) { plugin ->
                    PluginRow(
                        plugin = plugin,
                        pluginDao = pluginDao,
                        pluginAlarmManager = pluginAlarmManager,
                        onClick = { selectedPlugin = plugin },
                        onReloadPlugin = onReloadPlugin
                    )
                }
            }
        }
    }

    // PluginSheet — shared bottom sheet
    if (selectedPlugin != null) {
        PluginSheet(
            plugin = selectedPlugin!!,
            pluginDao = pluginDao,
            pluginAlarmManager = pluginAlarmManager,
            onDismiss = { selectedPlugin = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginRow(
    plugin: Plugin,
    pluginDao: PluginDao,
    pluginAlarmManager: PluginAlarmManager,
    onClick: () -> Unit,
    onReloadPlugin: (pluginId: String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showReinstallDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (plugin.isBuiltin) showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(plugin.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Long-press menu for builtin plugins
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("清除插件数据") },
                    onClick = {
                        showMenu = false
                        showClearDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("重新安装") },
                    onClick = {
                        showMenu = false
                        showReinstallDialog = true
                    }
                )
            }
        }
    }

    // Confirm clear data dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除插件数据") },
            text = { Text("将删除「${plugin.name}」的所有本地数据和提醒。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PluginSeeder.clearPluginData(pluginDao, pluginAlarmManager, plugin.id)
                        }
                    }
                }) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    // Confirm reinstall dialog
    if (showReinstallDialog) {
        AlertDialog(
            onDismissRequest = { showReinstallDialog = false },
            title = { Text("重新安装插件") },
            text = { Text("将从内置资源重新加载「${plugin.name}」，并清除现有数据。确认继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showReinstallDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PluginSeeder.clearPluginData(pluginDao, pluginAlarmManager, plugin.id)
                            PluginSeeder.seedBuiltin(context, pluginDao, plugin.id)
                        }
                        onReloadPlugin(plugin.id)
                    }
                }) { Text("确认重新安装") }
            },
            dismissButton = {
                TextButton(onClick = { showReinstallDialog = false }) { Text("取消") }
            }
        )
    }
}
