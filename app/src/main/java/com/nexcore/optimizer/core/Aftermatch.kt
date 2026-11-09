package com.nexcore.optimizer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aftermatch — records per-game session telemetry and exposes it as StateFlow.
 *
 * A session begins when [startSession] is called (triggered by GameDetectorService
 * when a monitored game reaches the foreground) and ends when [endSession] is
 * called (game goes to background or is closed).
 *
 * During the session, [sample] is called periodically (every 500ms by the service)
 * to collect FPS, temperature, and ping. At session end these are averaged and
 * the [Session] is emitted to [history].
 */
class Aftermatch {

    data class Session(
        val id         : String,
        val gamePkg    : String,
        val gameLabel  : String,
        val startTime  : String,
        val durationMs : Long,
        val avgFps     : Float,
        val peakTempC  : Float,
        val avgPingMs  : Long,
        val minPingMs  : Long,
        val maxPingMs  : Long,
        val perfProfile: String,
        val dpi        : Int
    )

    private data class Live(
        val pkg      : String,
        val label    : String,
        val startMs  : Long,
        val perfProf : String,
        val dpi      : Int
    )

    private val sdf = SimpleDateFormat("HH:mm", Locale.US)

    private var live     : Live? = null
    private val fpsSamples   = mutableListOf<Float>()
    private val tempSamples  = mutableListOf<Float>()
    private val pingSamples  = mutableListOf<Long>()

    private val _history  = MutableStateFlow<List<Session>>(emptyList())
    val history: StateFlow<List<Session>> = _history.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun startSession(pkg: String, label: String, perfProfile: String, dpi: Int) {
        if (live != null) endSession()
        live = Live(pkg, label, System.currentTimeMillis(), perfProfile, dpi)
        fpsSamples.clear(); tempSamples.clear(); pingSamples.clear()
        _active.value = true
        Blackbox.log("AFTER", "Session started: $label ($pkg)")
    }

    /** Call every ~500ms from GameDetectorService. All values are current readings. */
    fun sample(fps: Float, tempC: Float, pingMs: Long) {
        if (live == null) return
        if (fps   > 0f ) fpsSamples  += fps
        if (tempC > 0f ) tempSamples += tempC
        if (pingMs > 0L) pingSamples += pingMs
    }

    fun endSession(): Session? {
        val l = live ?: return null
        val dur = System.currentTimeMillis() - l.startMs
        val session = Session(
            id          = "${l.pkg}:${l.startMs}",
            gamePkg     = l.pkg,
            gameLabel   = l.label,
            startTime   = sdf.format(Date(l.startMs)),
            durationMs  = dur,
            avgFps      = fpsSamples.average().toFloat().takeIf { !it.isNaN() } ?: 0f,
            peakTempC   = tempSamples.maxOrNull() ?: 0f,
            avgPingMs   = pingSamples.average().toLong().takeIf { !it.isNaN() } ?: 0L,
            minPingMs   = pingSamples.minOrNull() ?: 0L,
            maxPingMs   = pingSamples.maxOrNull() ?: 0L,
            perfProfile = l.perfProf,
            dpi         = l.dpi
        )
        _history.value = listOf(session) + _history.value.take(49)  // keep last 50
        _active.value  = false
        live           = null
        Blackbox.log(
            "AFTER",
            "Session saved: ${session.gameLabel} ${dur/60000}m${(dur/1000)%60}s " +
            "avg${session.avgFps.toInt()}fps ${session.peakTempC.toInt()}°C ${session.avgPingMs}ms"
        )
        return session
    }

    fun currentDuration(): Long = live?.let { System.currentTimeMillis() - it.startMs } ?: 0L
}