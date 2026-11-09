package com.nexcore.optimizer.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque

/** Thread-safe singleton logger. All modules write here; UI observes [flow]. */
object Blackbox {

    private const val TAG     = "NexCore"
    private const val MAX     = 500
    private val sdf            = SimpleDateFormat("HH:mm:ss", Locale.US)

    enum class Level { OK, WARN, ERR }

    data class Entry(
        val ts   : String,
        val tag  : String,
        val msg  : String,
        val level: Level
    )

    private val buf   = LinkedBlockingDeque<Entry>(MAX)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow.asStateFlow()

    fun log(tag: String, msg: String, level: Level = Level.OK) {
        val e = Entry(sdf.format(Date()), tag, msg, level)
        if (buf.size >= MAX) buf.pollFirst()
        buf.addLast(e)
        _flow.value = buf.toList()
        when (level) {
            Level.OK   -> Log.d(TAG, "[$tag] $msg")
            Level.WARN -> Log.w(TAG, "[$tag] $msg")
            Level.ERR  -> Log.e(TAG, "[$tag] $msg")
        }
    }

    fun warn(tag: String, msg: String) = log(tag, msg, Level.WARN)
    fun err(tag: String, msg: String)  = log(tag, msg, Level.ERR)

    fun exportTo(file: File) {
        file.bufferedWriter().use { w ->
            buf.forEach { w.write("[${it.ts}] [${it.tag}] ${it.msg}\n") }
        }
        log("BLACKBOX", "Exported ${buf.size} entries → ${file.name}")
    }

    fun clear() {
        buf.clear()
        _flow.value = emptyList()
    }
}