package com.actme.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.actme.app.ActMeApp
import com.actme.app.data.local.RecurrenceCalculator
import com.actme.app.data.local.ScheduleEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class NotificationDetailActivity : ComponentActivity() {
    private var schedule by mutableStateOf<ScheduleEntity?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheduleId = intent?.getLongExtra(EXTRA_SCHEDULE_ID, -1L) ?: -1L
        if (scheduleId > 0) {
            val app = application as ActMeApp
            lifecycleScope.launch {
                schedule = app.container.repository.getScheduleById(scheduleId)
            }
        }

        setContent {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("ActMe 推送详情", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (schedule == null) {
                    Text("正在加载提醒内容...")
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(schedule!!.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "提醒时间：${
                                    Instant.ofEpochMilli(RecurrenceCalculator.normalizeEpochMillis(schedule!!.reminderAt))
                                        .atZone(ZoneId.systemDefault())
                                        .format(formatter)
                                }"
                            )
                            if (schedule!!.detail.isNotBlank()) {
                                Text("完整内容：${schedule!!.detail}", modifier = Modifier.padding(top = 8.dp))
                            }
                            Text("相关信息：${schedule!!.insight}", modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }
}
