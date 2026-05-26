package com.actme.app.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.ChatSessionEntity
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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
    sending: Boolean
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

    val context = androidx.compose.ui.platform.LocalContext.current

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
                        IconButton(onClick = { /* TODO: 语音输入 */ }) {
                            Text("🎤", modifier = Modifier.padding(4.dp))
                        }
                        Text("语音", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
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
