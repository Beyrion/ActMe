package com.actme.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.actme.app.ActMeApp
import com.actme.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

class PluginAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: return
        val alarmKey = intent.getStringExtra(EXTRA_ALARM_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        showNotification(context, title, body)
        AppLogger.i(TAG, "alarm fired: $pluginId/$alarmKey")

        val app = context.applicationContext as? ActMeApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val mgr = app.container.pluginAlarmManager
            val entity = mgr.getEntity(pluginId, alarmKey) ?: return@launch
            val repeat = runCatching { JSONObject(entity.repeatJson) }.getOrNull() ?: return@launch
            val repeatType = repeat.optString("type", "NONE")

            if (repeatType == "NONE") {
                mgr.deleteEntity(pluginId, alarmKey)
                return@launch
            }

            val timeStr = repeat.optString("time", "09:00")
            val (h, m) = timeStr.split(":").map { it.toIntOrNull() ?: 0 }
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()

            val nextMs: Long = when (repeatType) {
                "DAILY" -> nextDailyMs(h, m, now, zone)
                "WEEKLY" -> {
                    val arr = repeat.optJSONArray("days")
                    val days = (0 until (arr?.length() ?: 0)).map { arr!!.optInt(it) }
                    nextWeeklyMs(h, m, days, now, zone)
                }
                "MONTHLY" -> nextMonthlyMs(h, m, repeat.optInt("day", 1), now, zone)
                else -> return@launch
            }
            mgr.rescheduleEntity(entity.copy(triggerMs = nextMs))
            AppLogger.i(TAG, "rescheduled recurring: $pluginId/$alarmKey nextMs=$nextMs")
        }
    }

    private fun showNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "插件提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        nm.notify(
            "$title$body".hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_ALARM_KEY = "alarm_key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val CHANNEL_ID = "plugin_alarms"
        private const val TAG = "PluginAlarmReceiver"

        fun nextDailyMs(h: Int, m: Int, now: Long, zone: ZoneId): Long {
            val t = Instant.ofEpochMilli(now).atZone(zone)
                .withHour(h).withMinute(m).withSecond(0).withNano(0)
            return if (t.toInstant().toEpochMilli() > now) t.toInstant().toEpochMilli()
            else t.plusDays(1).toInstant().toEpochMilli()
        }

        fun nextWeeklyMs(h: Int, m: Int, days: List<Int>, now: Long, zone: ZoneId): Long {
            if (days.isEmpty()) return nextDailyMs(h, m, now, zone)
            val zdt = Instant.ofEpochMilli(now).atZone(zone)
            val todayDow = zdt.dayOfWeek.value
            val sorted = days.filter { it in 1..7 }.sorted()
            for (offset in 0..13) {
                val dow = ((todayDow - 1 + offset) % 7) + 1
                if (dow in sorted) {
                    val candidate = zdt.plusDays(offset.toLong())
                        .withHour(h).withMinute(m).withSecond(0).withNano(0)
                    val ms = candidate.toInstant().toEpochMilli()
                    if (ms > now) return ms
                }
            }
            return nextDailyMs(h, m, now, zone)
        }

        fun nextMonthlyMs(h: Int, m: Int, dayOfMonth: Int, now: Long, zone: ZoneId): Long {
            val zdt = Instant.ofEpochMilli(now).atZone(zone)
            val maxDay = zdt.month.length(zdt.toLocalDate().isLeapYear)
            val t = zdt.withDayOfMonth(dayOfMonth.coerceIn(1, maxDay))
                .withHour(h).withMinute(m).withSecond(0).withNano(0)
            val ms = t.toInstant().toEpochMilli()
            return if (ms > now) ms else t.plusMonths(1).toInstant().toEpochMilli()
        }
    }
}
