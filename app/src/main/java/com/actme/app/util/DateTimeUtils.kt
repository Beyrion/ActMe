package com.actme.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatRelativeTime(timestampMs: Long): String {
    val now = Instant.now()
    val then = Instant.ofEpochMilli(timestampMs)
    val zone = ZoneId.systemDefault()

    val nowLocal = now.atZone(zone)
    val thenLocal = then.atZone(zone)

    val diffMs = now.toEpochMilli() - then.toEpochMilli()
    val diffMinutes = diffMs / 60_000

    return when {
        diffMs < 0 -> thenLocal.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        diffMinutes < 1 -> "刚刚"
        diffMinutes < 60 -> "${diffMinutes}分钟前"
        diffMinutes < 24 * 60 -> "${diffMinutes / 60}小时前"
        LocalDate.from(nowLocal).minusDays(1) == LocalDate.from(thenLocal) ->
            "昨天 ${thenLocal.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        else -> thenLocal.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }
}
