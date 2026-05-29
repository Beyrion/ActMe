package com.actme.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actme.app.data.repo.ActMeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: ActMeRepository) : ViewModel() {

    fun clearAllChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = repository.chatSessions.firstOrNull() ?: return@launch
            for (session in sessions) {
                repository.deleteConversation(session.id)
            }
        }
    }
}
