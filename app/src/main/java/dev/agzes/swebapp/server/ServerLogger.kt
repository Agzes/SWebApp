package dev.agzes.swebapp.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerLogger {
    private val _logs = MutableSharedFlow<String>(replay = 50)
    val logs = _logs.asSharedFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val time = timeFormat.format(Date())
        val formattedMsg = "[$time] $message"
        _logs.tryEmit(formattedMsg)
    }

    fun clear() {
        _logs.resetReplayCache()
    }
}
