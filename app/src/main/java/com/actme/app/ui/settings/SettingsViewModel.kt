package com.actme.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actme.app.data.local.ProviderEntity
import com.actme.app.data.repo.ActMeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: ActMeRepository) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeProviderId = MutableStateFlow(repository.getActiveProviderId())
    val activeProviderId: StateFlow<Long> = _activeProviderId

    fun clearAllChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = repository.chatSessions.firstOrNull() ?: return@launch
            for (session in sessions) {
                repository.deleteConversation(session.id)
            }
        }
    }

    fun addProvider(name: String, format: String, endpoint: String, sk: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addProvider(name, format, endpoint, sk)
            _activeProviderId.value = repository.getActiveProviderId()
        }
    }

    fun updateProvider(id: Long, name: String, format: String, endpoint: String, sk: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProvider(id, name, format, endpoint, sk)
        }
    }

    fun deleteProvider(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProvider(id)
        }
    }

    fun setActiveProvider(id: Long) {
        repository.setActiveProvider(id)
        _activeProviderId.value = id
    }
}
