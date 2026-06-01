package com.actme.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        MemoryItemEntity::class,
        ScheduleEntity::class,
        SkillEntity::class,
        ProviderEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class ActMeDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun skillDao(): SkillDao
    abstract fun providerDao(): ProviderDao

    companion object {
        @Volatile
        private var INSTANCE: ActMeDatabase? = null

        fun getInstance(context: Context): ActMeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context,
                    ActMeDatabase::class.java,
                    "actme.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO chat_sessions (id, title, createdAt, updatedAt)
                    VALUES (1, '默认聊天', CAST(strftime('%s','now') AS INTEGER) * 1000, CAST(strftime('%s','now') AS INTEGER) * 1000)
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN conversationId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE schedules ADD COLUMN repeatType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE schedules ADD COLUMN repeatDaysOfWeek TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE schedules ADD COLUMN repeatDayOfMonth INTEGER")
                db.execSQL("ALTER TABLE schedules ADD COLUMN reminderTimeMinutes INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE schedules ADD COLUMN timezoneId TEXT NOT NULL DEFAULT 'Asia/Shanghai'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imageBase64 TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imageMimeType TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `providers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `providerFormat` TEXT NOT NULL,
                        `endpoint` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN searchResult TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN tokenInput INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN tokenOutput INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN tokenTotal INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN tokenSource TEXT")
            }
        }
    }
}
