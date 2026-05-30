package com.actme.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.actme.app.data.local.ChatSessionEntity
import com.actme.app.data.local.ChatSessionInfo
import com.actme.app.util.formatRelativeTime

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    sessionInfos: List<ChatSessionInfo>,
    currentConversationId: Long?,
    sendingConversationId: Long? = null,
    onCreateConversation: () -> Unit,
    onSwitchConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredSessions = remember(searchQuery, sessionInfos) {
        if (searchQuery.isBlank()) sessionInfos
        else sessionInfos.filter { it.session.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // ---- 全部会话 header ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "全部会话",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCreateConversation) {
                Icon(Icons.Filled.Add, contentDescription = "新聊天")
            }
        }

        // ---- Search bar ----
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            placeholder = { Text("搜索会话", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索", modifier = Modifier.size(20.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        // ---- Session list ----
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            items(filteredSessions, key = { it.session.id }) { info ->
                var showMenu by remember { mutableStateOf(false) }
                val session = info.session
                val selected = session.id == currentConversationId
                val isLoading = session.id == sendingConversationId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .combinedClickable(
                            onClick = { onSwitchConversation(session.id) },
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                session.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
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

        // ---- 1x3 grid: Memory, Plugins, Settings ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GridCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Psychology,
                label = "记忆",
                onClick = onNavigateToMemory
            )
            GridCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Extension,
                label = "插件",
                onClick = onNavigateToPlugins
            )
            GridCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Settings,
                label = "设置",
                onClick = onNavigateToSettings
            )
        }
    }

    // ---- Rename dialog ----
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
                TextButton(onClick = {
                    val target = renameTarget ?: return@TextButton
                    onRenameConversation(target.id, renameInput)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // ---- Delete dialog ----
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确认删除「${deleteTarget?.title.orEmpty()}」及其全部消息吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget ?: return@TextButton
                    onDeleteConversation(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun GridCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
