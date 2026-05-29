package com.actme.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getByConversation(conversationId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSession(): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ChatSessionEntity?

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_items WHERE category = :category ORDER BY updatedAt DESC")
    fun observeByCategory(category: String): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items ORDER BY updatedAt DESC")
    suspend fun getAllNow(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<MemoryItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItemEntity)

    @Update
    suspend fun update(item: MemoryItemEntity)

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY reminderAt ASC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY reminderAt ASC")
    suspend fun getAllNow(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduleEntity): Long

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: Long): ProviderEntity?

    @Insert
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabledNow(): List<SkillEntity>

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillEntity): Long
}
