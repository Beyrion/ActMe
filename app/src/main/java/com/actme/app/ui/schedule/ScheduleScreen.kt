package com.actme.app.ui.schedule

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actme.app.data.local.RecurrenceCalculator
import com.actme.app.data.local.RepeatType
import com.actme.app.data.local.ScheduleEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(
    schedules: List<ScheduleEntity>,
    onAddManual: (
        title: String,
        detail: String,
        repeatType: RepeatType,
        oneTimeDateText: String,
        timeText: String,
        weeklyDays: List<Int>,
        monthlyDayText: String
    ) -> Result<Unit>,
    onDeleteSchedule: (Long) -> Unit,
    onAddBySubAgent: (String, (Result<Unit>) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var oneTimeDate by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var monthlyDayText by remember { mutableStateOf("") }
    var subAgentRequest by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var repeatExpanded by remember { mutableStateOf(false) }
    var repeatType by remember { mutableStateOf(RepeatType.NONE) }
    var selectedWeekdays by remember { mutableStateOf(setOf<Int>()) }
    var pendingDelete by remember { mutableStateOf<ScheduleEntity?>(null) }
    val exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val weekdayOptions = remember {
        listOf(
            1 to "周一",
            2 to "周二",
            3 to "周三",
            4 to "周四",
            5 to "周五",
            6 to "周六",
            7 to "周日"
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("日历日程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("支持一次性提醒、每天、每周(周几可选)、每月(几日可选)。")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmGranted) {
                Text(
                    "当前未开启精确闹钟权限，提醒将自动降级为非精确触发。",
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        openExactAlarmSettings(context)
                        status = "已打开系统设置，请开启精确闹钟后返回。"
                    }
                ) {
                    Text("开启精确闹钟权限")
                }
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = title,
                onValueChange = { title = it },
                label = { Text("日程标题") }
            )
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = detail,
                onValueChange = { detail = it },
                label = { Text("日程描述") }
            )
        }

        item {
            Button(
                onClick = { repeatExpanded = true }
            ) {
                Text("重复：${repeatLabel(repeatType)}")
            }
            DropdownMenu(expanded = repeatExpanded, onDismissRequest = { repeatExpanded = false }) {
                RepeatType.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(repeatLabel(option)) },
                        onClick = {
                            repeatType = option
                            repeatExpanded = false
                        }
                    )
                }
            }
        }

        if (repeatType == RepeatType.NONE) {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = oneTimeDate,
                    onValueChange = { oneTimeDate = it },
                    label = { Text("日期(yyyy-MM-dd)") }
                )
            }
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("时间(HH:mm)") }
            )
        }

        if (repeatType == RepeatType.WEEKLY) {
            item {
                Text("选择每周提醒日")
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    weekdayOptions.forEach { (day, label) ->
                        val selected = day in selectedWeekdays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedWeekdays = if (selected) {
                                    selectedWeekdays - day
                                } else {
                                    selectedWeekdays + day
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
        if (repeatType == RepeatType.MONTHLY) {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = monthlyDayText,
                    onValueChange = { monthlyDayText = it },
                    label = { Text("每月几号(1-31)") }
                )
            }
        }

        item {
            Button(
                onClick = {
                    val result = onAddManual(
                        title,
                        detail,
                        repeatType,
                        oneTimeDate,
                        timeText,
                        selectedWeekdays.toList().sorted(),
                        monthlyDayText
                    )
                    status = if (result.isSuccess) {
                        title = ""
                        detail = ""
                        oneTimeDate = ""
                        timeText = ""
                        monthlyDayText = ""
                        selectedWeekdays = emptySet()
                        "已添加提醒，ActMe 会按你的重复规则自动续排。"
                    } else {
                        result.exceptionOrNull()?.message ?: "输入有误，请检查日期/时间/重复规则。"
                    }
                }
            ) {
                Text("添加日程")
            }
        }
        if (status.isNotBlank()) {
            item {
                Text(status)
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = subAgentRequest,
                onValueChange = { subAgentRequest = it },
                label = { Text("让子Agent创建日程（自然语言）") },
                placeholder = { Text("例如：每周一三五上午9点提醒我背单词") }
            )
        }
        item {
            Button(
                onClick = {
                    onAddBySubAgent(subAgentRequest) { result ->
                        status = if (result.isSuccess) {
                            subAgentRequest = ""
                            "子Agent 已创建日程。"
                        } else {
                            result.exceptionOrNull()?.message ?: "子Agent 创建失败。"
                        }
                    }
                }
            ) {
                Text("子Agent智能建日程")
            }
        }

        items(schedules) { schedule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(schedule.title, style = MaterialTheme.typography.titleMedium)
                    Text("下一次提醒：${formatTime(schedule.reminderAt, schedule.timezoneId, formatter)}")
                    Text("重复规则：${repeatDescription(schedule)}")
                    if (schedule.detail.isNotBlank()) Text("内容：${schedule.detail}")
                    if (schedule.insight.isNotBlank()) Text("ActMe 推送：${schedule.insight}")
                    Button(
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = { pendingDelete = schedule }
                    ) {
                        Text("删除日程")
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定删除日程「${pendingDelete?.title.orEmpty()}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pendingDelete ?: return@TextButton
                        onDeleteSchedule(target.id)
                        pendingDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val appIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(appIntent)
    }
}

private fun repeatLabel(type: RepeatType): String {
    return when (type) {
        RepeatType.NONE -> "一次性"
        RepeatType.DAILY -> "每天"
        RepeatType.WEEKLY -> "每周"
        RepeatType.MONTHLY -> "每月"
    }
}

private fun repeatDescription(schedule: ScheduleEntity): String {
    return when (RepeatType.fromRaw(schedule.repeatType)) {
        RepeatType.NONE -> "一次性"
        RepeatType.DAILY -> "每天"
        RepeatType.WEEKLY -> {
            val days = RecurrenceCalculator.parseWeekdays(schedule.repeatDaysOfWeek)
            "每周 ${days.joinToString("、") { weekdayToCn(it) }}"
        }
        RepeatType.MONTHLY -> "每月 ${schedule.repeatDayOfMonth ?: 1} 日"
    }
}

private fun weekdayToCn(day: Int): String {
    return when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
}

private fun formatTime(timeMillis: Long, timezoneId: String, formatter: DateTimeFormatter): String {
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
    val normalized = RecurrenceCalculator.normalizeEpochMillis(timeMillis)
    return Instant.ofEpochMilli(normalized).atZone(zone).format(formatter)
}
