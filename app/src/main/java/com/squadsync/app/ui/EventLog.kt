package com.squadsync.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app rolling event log. Master-only — feeds the bottom "Recent activity"
 * panel so the user can see what's been sent / acknowledged.
 */
object EventLog {

    data class Entry(
        val ts: Long,
        val level: Level,
        val text: String
    )

    enum class Level { Send, Ack, Info, Error }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun log(level: Level, text: String) {
        val entry = Entry(System.currentTimeMillis(), level, text)
        // Cap at 200 entries so the UI doesn't grow unbounded.
        _entries.update { prev -> (prev + entry).takeLast(200) }
    }

    fun send(text: String) = log(Level.Send, text)
    fun ack(text: String) = log(Level.Ack, text)
    fun info(text: String) = log(Level.Info, text)
    fun error(text: String) = log(Level.Error, text)

    fun format(entry: Entry): String = "${fmt.format(Date(entry.ts))} ${entry.text}"
}