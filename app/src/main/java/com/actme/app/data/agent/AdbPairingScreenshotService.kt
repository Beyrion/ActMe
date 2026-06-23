package com.actme.app.data.agent

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.actme.app.ActMeApp
import com.actme.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdbPairingScreenshotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        AppLogger.i(TAG, "ADB-SCREENSHOT-SERVICE start: timeoutMs=$timeoutMs")
        startForeground(NOTIFICATION_ID, buildRunningNotification(timeoutMs))

        watchJob?.cancel()
        watchJob = scope.launch {
            try {
                AdbPairingScreenshotWatcher.runWatch(applicationContext, timeoutMs)
            } catch (error: Throwable) {
                AppLogger.e(TAG, "ADB-SCREENSHOT-SERVICE failed", error)
            } finally {
                AppLogger.i(TAG, "ADB-SCREENSHOT-SERVICE stop")
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildRunningNotification(timeoutMs: Long): Notification {
        val seconds = (timeoutMs / 1000L).coerceAtLeast(1L)
        return NotificationCompat.Builder(this, ActMeApp.ADB_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("正在监听 ADB 配对截图")
            .setContentText("请在无线调试页面截图，ActMe 会自动读取新增图片。")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("正在监听 ADB 配对截图，最长 ${seconds} 秒。\n请切到系统无线调试页面并截图。")
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val TAG = "AdbScreenshotService"
        private const val NOTIFICATION_ID = 9300
    }
}
