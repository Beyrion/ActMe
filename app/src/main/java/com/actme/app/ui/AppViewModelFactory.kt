package com.actme.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.ui.chat.ChatViewModel
import com.actme.app.ui.memory.MemoryViewModel
import com.actme.app.ui.schedule.ScheduleViewModel
import com.actme.app.ui.settings.SettingsViewModel

class AppViewModelFactory(
    private val application: Application,
    private val repository: ActMeRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(repository, application) as T
            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(repository) as T
            modelClass.isAssignableFrom(ScheduleViewModel::class.java) -> ScheduleViewModel(repository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(application, repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
