package com.actme.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.ui.chat.ChatViewModel
import com.actme.app.ui.memory.MemoryViewModel
import com.actme.app.ui.schedule.ScheduleViewModel

class AppViewModelFactory(private val repository: ActMeRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(repository) as T
            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(repository) as T
            modelClass.isAssignableFrom(ScheduleViewModel::class.java) -> ScheduleViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
