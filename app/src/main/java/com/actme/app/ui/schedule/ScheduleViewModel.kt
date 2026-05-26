package com.actme.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.actme.app.data.local.RepeatType
import com.actme.app.data.local.ScheduleEntity
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.util.LogCodec
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduleViewModel(private val repository: ActMeRepository) : ViewModel() {
    val schedules: StateFlow<List<ScheduleEntity>> = repository.schedules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun addManualSchedule(
        title: String,
        detail: String,
        repeatType: RepeatType,
        oneTimeDateText: String,
        timeText: String,
        weeklyDays: List<Int>,
        monthlyDayText: String
    ): Result<Unit> {
        return runCatching {
            val nowMillis = System.currentTimeMillis()
            val time = LocalTime.parse(timeText.trim(), timeFormatter)
            val timeMinutes = time.hour * 60 + time.minute

            val startAt: Long
            val reminderAt: Long
            val repeatDays: List<Int>
            val repeatDayOfMonth: Int?

            when (repeatType) {
                RepeatType.NONE -> {
                    val date = LocalDate.parse(oneTimeDateText.trim(), dateFormatter)
                    val dateTime = date.atTime(time)
                    reminderAt = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    startAt = reminderAt
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.DAILY -> {
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = emptyList()
                    repeatDayOfMonth = null
                }
                RepeatType.WEEKLY -> {
                    val parsed = weeklyDays.filter { it in 1..7 }.distinct().sorted()
                    require(parsed.isNotEmpty()) { "每周重复时请至少选择一个周几" }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = parsed
                    repeatDayOfMonth = null
                }
                RepeatType.MONTHLY -> {
                    val day = monthlyDayText.trim().toIntOrNull()
                    require(day != null && day in 1..31) { "每月重复时请填写 1-31 之间的日期" }
                    startAt = nowMillis
                    reminderAt = nowMillis
                    repeatDays = emptyList()
                    repeatDayOfMonth = day
                }
            }

            viewModelScope.launch {
                repository.addManualSchedule(
                    title = title.trim(),
                    detail = detail.trim(),
                    startAt = startAt,
                    reminderAt = reminderAt,
                    repeatType = repeatType,
                    repeatDaysOfWeek = repeatDays,
                    repeatDayOfMonth = repeatDayOfMonth,
                    reminderTimeMinutes = timeMinutes
                )
            }
        }
    }

    fun deleteSchedule(id: Long) {
        if (id <= 0L) return
        viewModelScope.launch {
            repository.deleteSchedule(id)
        }
    }

    fun addScheduleBySubAgent(rawRequest: String, onResult: (Result<Unit>) -> Unit) {
        if (rawRequest.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("请先输入日程描述")))
            return
        }
        viewModelScope.launch {
            val result = repository.addScheduleBySubAgent(rawRequest.trim())
            if (result.isFailure) {
                Log.i(
                    TAG,
                    "add schedule by sub-agent failed: messageB64=${LogCodec.utf8Base64(result.exceptionOrNull()?.message)}"
                )
            } else {
                Log.i(TAG, "add schedule by sub-agent success")
            }
            onResult(result)
        }
    }

    companion object {
        private const val TAG = "ActMeScheduleVM"
    }
}
