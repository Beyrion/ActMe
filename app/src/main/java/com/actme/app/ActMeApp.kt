package com.actme.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.actme.app.di.AppContainer
import com.actme.app.skills.SkillSeeder

class ActMeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "application onCreate")
        Log.i(TAG, "log encoding policy: plain fields ASCII, user text UTF-8 Base64 (suffix=B64)")
        container = AppContainer(this)
        createNotificationChannel()
        SkillSeeder.seedIfNeeded(this, container.database.skillDao())
        Log.i(TAG, "application initialized")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "ActMe 定时提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ActMe Agent 的计划提醒与弹窗通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.i(TAG, "notification channel ready: $REMINDER_CHANNEL_ID")
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "actme_reminder_channel"
        private const val TAG = "ActMeApp"
    }
}
