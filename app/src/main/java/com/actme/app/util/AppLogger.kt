package com.actme.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process log buffer. Writes to android.util.Log AND keeps the last MAX_ENTRIES
 * entries in a ring buffer exposed as a StateFlow for the log viewer UI.
 *
 * Lifetime: process-scoped (resets on each app launch).
 */
object AppLogger {
    private const val PREFIX = "[ActMe]: "
    private const val LOGCAT_CHUNK_SIZE = 3000

    data class LogEntry(
        val id: Long,
        val timestamp: Long,
        val level: Char,   // 'V' 'D' 'I' 'W' 'E'
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 2000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val buffer = ArrayDeque<LogEntry>()
    private val lock = Any()
    private val idGen = AtomicLong(0)

    private fun prefixedLines(message: String): String {
        return message.lineSequence()
            .joinToString("\n") { line ->
                if (line.startsWith(PREFIX)) line else PREFIX + line
            }
    }

    private fun append(level: Char, tag: String, message: String) {
        val entry = LogEntry(idGen.getAndIncrement(), System.currentTimeMillis(), level, tag, prefixedLines(message))
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    private fun logChunked(level: Char, tag: String, message: String) {
        val lines = prefixedLines(message).lineSequence().toList().ifEmpty { listOf(PREFIX) }
        lines.forEach { line ->
            if (line.length <= LOGCAT_CHUNK_SIZE) {
                logLine(level, tag, line)
            } else {
                val body = line.removePrefix(PREFIX)
                var offset = 0
                var part = 1
                val total = (body.length + LOGCAT_CHUNK_SIZE - 1) / LOGCAT_CHUNK_SIZE
                while (offset < body.length) {
                    val end = minOf(offset + LOGCAT_CHUNK_SIZE, body.length)
                    logLine(level, tag, "$PREFIX[chunk $part/$total] ${body.substring(offset, end)}")
                    offset = end
                    part += 1
                }
            }
        }
    }

    private fun logLine(level: Char, tag: String, message: String) {
        when (level) {
            'V' -> Log.v(tag, message)
            'D' -> Log.d(tag, message)
            'I' -> Log.i(tag, message)
            'W' -> Log.w(tag, message)
            'E' -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
    }

    fun v(tag: String, msg: String) {
        val m = prefixedLines(msg)
        logChunked('V', tag, m)
        append('V', tag, m)
    }

    fun d(tag: String, msg: String) {
        val m = prefixedLines(msg)
        logChunked('D', tag, m)
        append('D', tag, m)
    }

    fun i(tag: String, msg: String) {
        val m = prefixedLines(msg)
        logChunked('I', tag, m)
        append('I', tag, m)
    }

    fun w(tag: String, msg: String) {
        val m = prefixedLines(msg)
        logChunked('W', tag, m)
        append('W', tag, m)
    }

    fun w(tag: String, msg: String, t: Throwable) {
        val m = prefixedLines("$msg\n${t.stackTraceToString()}")
        logChunked('W', tag, m)
        append('W', tag, m)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val m = if (t != null) {
            prefixedLines("$msg\n${t.stackTraceToString()}")
        } else {
            prefixedLines(msg)
        }
        logChunked('E', tag, m)
        append('E', tag, m)
    }
}
