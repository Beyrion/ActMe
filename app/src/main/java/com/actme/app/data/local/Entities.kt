package com.actme.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新聊天",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatSessionInfo(
    val session: ChatSessionEntity,
    val messageCount: Int
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long = 1,
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_items")
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val source: String = "manual",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String,
    val startAt: Long,
    // 对重复提醒来说，此字段始终表示“下一次触发时间”。
    val reminderAt: Long,
    val repeatType: String = RepeatType.NONE.name,
    val repeatDaysOfWeek: String = "",
    val repeatDayOfMonth: Int? = null,
    val reminderTimeMinutes: Int = -1,
    val timezoneId: String = "Asia/Shanghai",
    val insight: String = "",
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val providerFormat: String, // "openai" or "anthropic"
    val endpoint: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val triggerKeywords: String,
    val actionTemplate: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

enum class RepeatType {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY;

    companion object {
        fun fromRaw(raw: String?): RepeatType {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: NONE
        }
    }
}

object MemoryCategories {
    val all = listOf(
        "短期目标",
        "长期目标",
        "个人焦虑",
        "近期烦恼",
        "个人喜好",
        "人际关系",
        "健康状态",
        "学习工作"
    )
}
