package com.easytranscriber.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, timestamped in-app log, mirrored to logcat, used as evidence in hardware tests. */
class EventLog(private val capacity: Int = 300) {
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun add(message: String) {
        Log.i(TAG, message)
        val line = "${synchronized(format) { format.format(Date()) }}  $message"
        _entries.update { (it + line).takeLast(capacity) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val TAG = "EasyTranscriber"
    }
}
