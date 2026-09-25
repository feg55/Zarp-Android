package io.github.feg55.zarp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log shown on the Logs screen (and mirrored to logcat by the app). */
class LogBus(private val capacity: Int = 2000, private val sink: (String) -> Unit = {}) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun write(message: String) {
        val line = fmt.get()!!.format(Date()) + "  " + message
        sink(message)
        _lines.update { old -> if (old.size >= capacity) old.drop(old.size - capacity + 1) + line else old + line }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
