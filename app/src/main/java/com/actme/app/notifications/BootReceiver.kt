package com.actme.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actme.app.util.AppLogger
import com.actme.app.ActMeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLogger.i(TAG, "boot completed, start reschedule")
        val app = context.applicationContext as? ActMeApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            app.container.repository.rescheduleAllReminders()
            app.container.pluginAlarmManager.rescheduleAll()
            AppLogger.i(TAG, "reschedule finished")
        }
    }

    companion object {
        private const val TAG = "ActMeBootReceiver"
    }
}
