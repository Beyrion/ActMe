package com.actme.app.skills

import android.content.Context
import com.actme.app.data.local.MemoryDao
import com.actme.app.data.local.MemoryItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MemorySeedModel(
    val category: String,
    val content: String
)

object MemorySeeder {
    private val json = Json { ignoreUnknownKeys = true }

    fun seedIfNeeded(context: Context, memoryDao: MemoryDao) {
        CoroutineScope(Dispatchers.IO).launch {
            if (memoryDao.countSystem() > 0) return@launch
            val content = runCatching {
                context.assets.open("memory/system_memories.json").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrElse { return@launch }

            val list = runCatching {
                json.decodeFromString<List<MemorySeedModel>>(content)
            }.getOrDefault(emptyList())

            list.forEach { seed ->
                if (seed.content.isBlank()) return@forEach
                memoryDao.upsert(
                    MemoryItemEntity(
                        category = seed.category.ifBlank { "系统" },
                        content = seed.content,
                        source = "system"
                    )
                )
            }
        }
    }
}
