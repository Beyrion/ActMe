package com.actme.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.actme.app.util.AppLogger
import com.actme.app.data.local.RecurrenceCalculator
import com.actme.app.data.local.ScheduleEntity

class ReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(entity: ScheduleEntity) {
        val triggerAt = RecurrenceCalculator.normalizeEpochMillis(entity.reminderAt)
        if (triggerAt <= System.currentTimeMillis()) {
            AppLogger.i(TAG, "skip schedule: id=${entity.id}, triggerAt=$triggerAt is past")
            return
        }

        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, entity.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            entity.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canUseExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                AppLogger.i(TAG, "scheduled exact alarm: id=${entity.id}, triggerAt=$triggerAt")
                return
            } catch (se: SecurityException) {
                AppLogger.i(TAG, "exact alarm denied, fallback to inexact: id=${entity.id}, reason=${se.message}")
            }
        } else {
            AppLogger.i(TAG, "exact alarm permission missing, fallback to inexact: id=${entity.id}")
        }

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )
        AppLogger.i(TAG, "scheduled inexact alarm: id=${entity.id}, triggerAt=$triggerAt")
    }

    fun cancel(scheduleId: Long) {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            scheduleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        AppLogger.i(TAG, "cancelled alarm: id=$scheduleId")
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        private const val TAG = "ActMeReminderScheduler"
    }
}
