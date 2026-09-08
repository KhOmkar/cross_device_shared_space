package com.anonymous.fileshare.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe live in-memory logger for real-time diagnostics and ADB logcat.
 */
object AppLogger {
    private const val TAG = "AnonymousShare"
    private const val MAX_LOGS = 100
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        appendLog("[$tag] $msg")
    }

    fun i(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        appendLog("[$tag] $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
        appendLog("[$tag] WARN: $msg")
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG, "[$tag] ERROR: $msg", tr)
        val errStr = if (tr != null) "$msg (${tr.javaClass.simpleName}: ${tr.message})" else msg
        appendLog("[$tag] ERROR: $errStr")
    }

    private fun appendLog(line: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "$timestamp $line"
        _logs.update { list ->
            (list + formatted).takeLast(MAX_LOGS)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
