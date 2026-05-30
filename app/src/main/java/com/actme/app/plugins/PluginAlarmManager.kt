package com.actme.app.plugins

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.actme.app.data.local.PluginAlarmDao
import com.actme.app.data.local.PluginAlarmEntity
import com.actme.app.notifications.PluginAlarmReceiver
import com.actme.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PluginAlarmManager(
    private val context: Context,
    private val pluginAlarmDao: PluginAlarmDao
) {
    private val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun schedule(pluginId: String, alarmKey: String, triggerMs: Long, title: String, body: String, repeatJson: String) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (triggerMs <= now) {
                AppLogger.w(TAG, "skip past alarm: $pluginId/$alarmKey triggerMs=$triggerMs")
                return@withContext
            }
            pluginAlarmDao.upsert(PluginAlarmEntity(pluginId, alarmKey, triggerMs, title, body, repeatJson))
            setAlarm(pluginId, alarmKey, triggerMs, title, body)
        }

    suspend fun cancel(pluginId: String, alarmKey: String) = withContext(Dispatchers.IO) {
        pluginAlarmDao.delete(pluginId, alarmKey)
        cancelAlarm(pluginId, alarmKey)
    }

    suspend fun getEntity(pluginId: String, alarmKey: String): PluginAlarmEntity? =
        withContext(Dispatchers.IO) { pluginAlarmDao.get(pluginId, alarmKey) }

    suspend fun deleteEntity(pluginId: String, alarmKey: String) = withContext(Dispatchers.IO) {
        pluginAlarmDao.delete(pluginId, alarmKey)
    }

    suspend fun rescheduleEntity(entity: PluginAlarmEntity) = withContext(Dispatchers.IO) {
        pluginAlarmDao.upsert(entity)
        setAlarm(entity.pluginId, entity.alarmKey, entity.triggerMs, entity.title, entity.body)
    }

    suspend fun cancelAll(pluginId: String) = withContext(Dispatchers.IO) {
        pluginAlarmDao.getByPlugin(pluginId).forEach { e -> cancelAlarm(e.pluginId, e.alarmKey) }
        pluginAlarmDao.deleteByPlugin(pluginId)
        AppLogger.i(TAG, "cancelled all alarms: $pluginId")
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        pluginAlarmDao.getAll().filter { it.triggerMs > now }.forEach { e ->
            setAlarm(e.pluginId, e.alarmKey, e.triggerMs, e.title, e.body)
        }
    }

    private fun setAlarm(pluginId: String, alarmKey: String, triggerMs: Long, title: String, body: String) {
        val intent = Intent(context, PluginAlarmReceiver::class.java).apply {
            putExtra(PluginAlarmReceiver.EXTRA_PLUGIN_ID, pluginId)
            putExtra(PluginAlarmReceiver.EXTRA_ALARM_KEY, alarmKey)
            putExtra(PluginAlarmReceiver.EXTRA_TITLE, title)
            putExtra(PluginAlarmReceiver.EXTRA_BODY, body)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode(pluginId, alarmKey), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
        if (canExact) {
            try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi); return }
            catch (_: SecurityException) {}
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        AppLogger.i(TAG, "scheduled: $pluginId/$alarmKey at $triggerMs")
    }

    private fun cancelAlarm(pluginId: String, alarmKey: String) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode(pluginId, alarmKey),
            Intent(context, PluginAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi); pi.cancel()
        AppLogger.i(TAG, "cancelled: $pluginId/$alarmKey")
    }

    private fun requestCode(pluginId: String, alarmKey: String) = "$pluginId::$alarmKey".hashCode()

    companion object {
        private const val TAG = "PluginAlarmManager"
    }
}
