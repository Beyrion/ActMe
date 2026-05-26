package com.actme.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.actme.app.ActMeApp
import com.actme.app.ui.NotificationDetailActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId <= 0) {
            Log.i(TAG, "receive reminder ignored: invalid scheduleId=$scheduleId")
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as? ActMeApp
        if (app == null) {
            Log.i(TAG, "receive reminder ignored: app is null")
            pendingResult.finish()
            return
        }
        Log.i(TAG, "receive reminder: scheduleId=$scheduleId")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schedule = app.container.repository.getScheduleById(scheduleId)
                if (schedule == null) {
                    Log.i(TAG, "schedule not found: id=$scheduleId")
                    return@launch
                }
                val detailIntent = Intent(context, NotificationDetailActivity::class.java).apply {
                    putExtra(NotificationDetailActivity.EXTRA_SCHEDULE_ID, schedule.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val tapIntent = PendingIntent.getActivity(
                    context,
                    schedule.id.toInt(),
                    detailIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, ActMeApp.REMINDER_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("ActMe 提醒：${schedule.title}")
                    .setContentText(schedule.detail.ifBlank { "点击查看完整提醒与建议" })
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("提醒内容：${schedule.detail}\n\nActMe 建议：${schedule.insight}")
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(tapIntent)
                    .setFullScreenIntent(tapIntent, true)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(schedule.id.toInt(), notification)
                Log.i(TAG, "notification shown: scheduleId=${schedule.id}")

                app.container.repository.onReminderTriggered(schedule.id)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ActMeReminderReceiver"
    }
}
