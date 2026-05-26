package com.actme.app.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actme.app.data.local.MemoryItemEntity

@Composable
fun MemoryCategoryScreen(
    categories: List<String>,
    onOpenCategory: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text("个人记忆", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("按类别管理记忆；Agent 对话中也会自动整理落库。")
        LazyColumn(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCategory(category) }
                ) {
                    Text(
                        text = category,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
fun MemoryListScreen(
    category: String,
    items: List<MemoryItemEntity>,
    onBack: () -> Unit,
    onSaveNew: (String) -> Unit,
    onOpenItem: (Long) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row {
            TextButton(onClick = onBack) { Text("返回") }
            Text(category, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("点击条目可进入详情编辑，并可返回。")

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            value = input,
            onValueChange = { input = it },
            label = { Text("新增条目") }
        )
        Button(
            onClick = {
                if (input.isBlank()) return@Button
                onSaveNew(input.trim())
                input = ""
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("保存新增")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenItem(item.id) }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.content)
                        Text("点我进入详情", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryItemScreen(
    category: String,
    item: MemoryItemEntity?,
    onBack: () -> Unit,
    onSave: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var input by remember(item?.id) { mutableStateOf(item?.content.orEmpty()) }
    val itemId = item?.id ?: 0L
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row {
            TextButton(onClick = onBack) { Text("返回") }
            Text("条目详情", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("分类：$category")

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            value = input,
            onValueChange = { input = it },
            label = { Text("条目内容") }
        )
        Button(
            modifier = Modifier.padding(top = 8.dp),
            onClick = {
                if (input.isBlank()) return@Button
                onSave(itemId, input.trim())
                onBack()
            }
        ) {
            Text("保存并返回")
        }
        if (itemId > 0L) {
            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                    showDeleteConfirm = true
                }
            ) {
                Text("删除条目")
            }
        }
    }

    if (showDeleteConfirm && itemId > 0L) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("删除后将无法恢复，确定删除这个记忆条目吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(itemId)
                        onBack()
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}
