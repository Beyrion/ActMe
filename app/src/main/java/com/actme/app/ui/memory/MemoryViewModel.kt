package com.actme.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actme.app.data.local.MemoryCategories
import com.actme.app.data.local.MemoryItemEntity
import com.actme.app.data.repo.ActMeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MemoryViewModel(private val repository: ActMeRepository) : ViewModel() {
    val categories: List<String> = MemoryCategories.all

    fun observeCategory(category: String): Flow<List<MemoryItemEntity>> = repository.observeMemory(category)
    fun observeItem(id: Long): Flow<MemoryItemEntity?> = repository.observeMemoryItem(id)

    fun saveMemoryItem(id: Long, category: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addOrUpdateMemory(
                MemoryItemEntity(
                    id = id,
                    category = category,
                    content = content.trim(),
                    source = "manual"
                )
            )
        }
    }

    fun deleteMemoryItem(id: Long) {
        if (id <= 0L) return
        viewModelScope.launch {
            repository.deleteMemoryItem(id)
        }
    }
}
