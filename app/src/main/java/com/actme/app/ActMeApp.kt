package com.actme.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.actme.app.util.AppLogger
import com.actme.app.di.AppContainer
import com.actme.app.skills.MemorySeeder
import com.actme.app.skills.SkillSeeder
import java.lang.ref.WeakReference

class ActMeApp : Application() {
    lateinit var container: AppContainer
        private set
    private var resumedActivityRef: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "application onCreate")
        AppLogger.i(TAG, "log encoding policy: plain fields ASCII, user text UTF-8 Base64 (suffix=B64)")
        registerActivityLifecycleCallbacks(activityTracker)
        container = AppContainer(this)
        createNotificationChannel()
        SkillSeeder.seedIfNeeded(this, container.database.skillDao())
        MemorySeeder.seedIfNeeded(this, container.database.memoryDao())
        AppLogger.i(TAG, "application initialized")
    }

    fun currentActivity(): Activity? = resumedActivityRef?.get()

    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivityRef = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedActivityRef?.get() === activity) {
                resumedActivityRef = null
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (resumedActivityRef?.get() === activity) {
                resumedActivityRef = null
            }
        }
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
        AppLogger.i(TAG, "notification channel ready: $REMINDER_CHANNEL_ID")
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "actme_reminder_channel"
        private const val TAG = "ActMeApp"
    }
}
