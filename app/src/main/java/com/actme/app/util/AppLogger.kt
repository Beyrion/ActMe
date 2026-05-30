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

    private fun append(level: Char, tag: String, message: String) {
        val entry = LogEntry(idGen.getAndIncrement(), System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    fun v(tag: String, msg: String) { Log.v(tag, msg); append('V', tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append('D', tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append('I', tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append('W', tag, msg) }
    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, msg, t)
        append('W', tag, "$msg  ↳ ${t.javaClass.simpleName}: ${t.message}")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        val full = if (t != null) "$msg  ↳ ${t.javaClass.simpleName}: ${t.message}" else msg
        append('E', tag, full)
    }
}
