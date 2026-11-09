package com.nexcore.optimizer.core

import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Thermal Guardian — polls /sys thermal zones every [intervalMs] ms.
 *
 * Thresholds (°C):
 *   NORMAL   < 42   → no action
 *   WARM     42–45  → log, standby
 *   HOT      45–50  → switch AdaptivePerformance to BALANCED
 *   CRITICAL > 50   → switch to POWERSAVE + vibrate alert
 *
 * Thermal zone paths vary by SoC:
 *   Snapdragon: /sys/class/thermal/thermal_zone[0-9]/temp (millidegrees)
 *   Dimensity : /sys/class/thermal/thermal_zone[0-9]/temp (degrees)
 */
class ThermalGuardian(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val perf   : AdaptivePerformance
) {
    enum class State { NORMAL, WARM, HOT, CRITICAL }

    data class Reading(val zoneName: String, val tempC: Float)

    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.NORMAL)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    companion object {
        const val WARM_C     = 42f
        const val HOT_C      = 45f
        const val CRITICAL_C = 50f
    }

    fun start(intervalMs: Long = 2000L) {
        scope.launch {
            while (true) {
                val zones = readAllZones()
                _readings.value = zones
                val peak = zones.maxOfOrNull { it.tempC } ?: 0f
                val newState = when {
                    peak >= CRITICAL_C -> State.CRITICAL
                    peak >= HOT_C      -> State.HOT
                    peak >= WARM_C     -> State.WARM
                    else               -> State.NORMAL
                }
                if (newState != _state.value) {
                    onStateChange(newState, peak)
                    _state.value = newState
                }
                delay(intervalMs)
            }
        }
        Blackbox.log("THERMAL", "Guardian started — polling every ${intervalMs}ms")
    }

    private fun readAllZones(): List<Reading> {
        val results = mutableListOf<Reading>()
        for (i in 0..15) {
            val typePath = "/sys/class/thermal/thermal_zone$i/type"
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            try {
                val type = File(typePath).readText().trim()
                val raw  = File(tempPath).readText().trim().toFloatOrNull() ?: continue
                // Snapdragon reports millidegrees; check if value > 1000 → divide by 1000
                val tempC = if (raw > 1000f) raw / 1000f else raw
                results += Reading(type, tempC)
            } catch (_: Exception) { }
        }
        return results
    }

    private fun onStateChange(new: State, peakC: Float) {
        Blackbox.log(
            "THERMAL",
            "State ${_state.value.name} → ${new.name} · peak=${peakC}°C",
            if (new >= State.HOT) Blackbox.Level.WARN else Blackbox.Level.OK
        )
        when (new) {
            State.HOT      -> perf.apply(AdaptivePerformance.Profile.BALANCED)
            State.CRITICAL -> {
                perf.apply(AdaptivePerformance.Profile.POWERSAVE)
                vibrate()
            }
            State.NORMAL   -> perf.apply(AdaptivePerformance.Profile.BALANCED)
            State.WARM     -> { /* log only */ }
        }
    }

    private fun vibrate() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(400)
        }
    }
}