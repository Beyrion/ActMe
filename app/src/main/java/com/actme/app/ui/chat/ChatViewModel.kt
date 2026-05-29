package com.actme.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actme.app.data.local.ChatMessageEntity
import com.actme.app.data.local.ChatSessionInfo
import com.actme.app.data.repo.ActMeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ActMeRepository) : ViewModel() {
    private val currentConversationIdMutable = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = currentConversationIdMutable

    val sessionInfos: StateFlow<List<ChatSessionInfo>> = repository.chatSessions
        .flatMapLatest { sessions ->
            flow {
                val infos = coroutineScope {
                    sessions.map { session ->
                        async {
                            ChatSessionInfo(
                                session = session,
                                messageCount = repository.getMessageCount(session.id)
                            )
                        }
                    }.awaitAll()
                }
                emit(infos)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<ChatMessageEntity>> = currentConversationIdMutable
        .filterNotNull()
        .flatMapLatest { repository.observeChatMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sending = MutableStateFlow(false)
    val isRecording = MutableStateFlow(false)
    val transcribedText = MutableStateFlow<String?>(null)

    fun setRecording(recording: Boolean) {
        isRecording.value = recording
    }

    fun onVoiceTranscribed(text: String) {
        transcribedText.value = text
    }

    fun clearTranscribedText() {
        transcribedText.value = null
    }

    init {
        viewModelScope.launch {
            currentConversationIdMutable.value = repository.ensureActiveConversationId()
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val id = repository.createConversation()
            currentConversationIdMutable.value = id
        }
    }

    fun switchConversation(id: Long) {
        currentConversationIdMutable.value = id
    }

    fun renameConversation(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.renameConversation(id, title)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val fallbackId = repository.deleteConversation(id)
            if (currentConversationIdMutable.value == id) {
                currentConversationIdMutable.value = fallbackId
            }
        }
    }

    fun sendMessage(input: String, imageBase64: String? = null, imageMimeType: String? = null) {
        val conversationId = currentConversationIdMutable.value ?: return
        if (input.isBlank() && imageBase64 == null || sending.value) return
        viewModelScope.launch {
            sending.value = true
            runCatching { repository.sendMessage(conversationId, input.trim(), imageBase64, imageMimeType) }
            sending.value = false
        }
    }
}
