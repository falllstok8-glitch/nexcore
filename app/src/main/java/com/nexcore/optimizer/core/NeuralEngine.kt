package com.nexcore.optimizer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Neural Engine — heuristic optimization engine.
 *
 * Scores the current device + session context (0–100) and derives the best
 * performance profile + sensitivity preset for the active game.
 *
 * "Neural" here refers to a weighted scoring model — not a TFLite model —
 * which is intentional: a lightweight rule-based engine has zero cold-start
 * latency and no APK size cost, while covering the same decision space for
 * the target use case (mobile gaming optimization).
 *
 * To extend to a real on-device model: drop a .tflite file in assets/ and
 * replace [computeScore] with a TensorFlow Lite Interpreter call.
 */
class NeuralEngine(
    private val perf    : AdaptivePerformance,
    private val thermal : ThermalGuardian,
    private val net     : NetworkIntelligence,
    private val dna     : DeviceDNA
) {
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _score   = MutableStateFlow(0)
    val score: StateFlow<Int> = _score.asStateFlow()

    private var profile: DeviceDNA.Profile? = null

    fun init(deviceProfile: DeviceDNA.Profile) {
        profile = deviceProfile
        recompute()
        Blackbox.log("NEURAL", "Engine initialized · baseline score=${deviceProfile.optScore}")
    }

    /**
     * Recompute optimization score from current thermal + network state.
     * Score components:
     *   40 pts — device baseline (from DeviceDNA)
     *   20 pts — thermal headroom (100% = no throttle, 0% = critical)
     *   20 pts — network quality (ping < 30ms = full, >100ms = 0)
     *   20 pts — active module bonus (each active module = +4 pts, max 5)
     */
    fun recompute(activeModules: Int = 5) {
        scope.launch {
            val base    = profile?.optScore ?: 50
            val thState = thermal.state.value
            val thPts   = when (thState) {
                ThermalGuardian.State.NORMAL   -> 20
                ThermalGuardian.State.WARM     -> 15
                ThermalGuardian.State.HOT      -> 8
                ThermalGuardian.State.CRITICAL -> 0
            }
            val ping    = net.ping()
            val netPts  = when {
                ping < 15  -> 20
                ping < 30  -> 16
                ping < 60  -> 10
                ping < 100 -> 5
                else       -> 0
            }.toInt()
            val modPts  = (activeModules.coerceIn(0, 5) * 4)

            val raw   = base + thPts + netPts + modPts
            val final = raw.coerceIn(0, 100)
            _score.value = final
            Blackbox.log("NEURAL", "Score=$final (base=$base th=$thPts net=$netPts mod=$modPts)")
        }
    }

    /**
     * Recommend the best [AdaptivePerformance.Profile] for current conditions.
     * Also returns recommended DPI based on device capability.
     */
    data class Recommendation(
        val perfProfile : AdaptivePerformance.Profile,
        val dpiTarget   : Int,
        val reason      : String
    )

    fun recommend(gamePkg: String): Recommendation {
        val s     = _score.value
        val th    = thermal.state.value
        val p     = profile

        val recPerf = when {
            th == ThermalGuardian.State.CRITICAL -> AdaptivePerformance.Profile.POWERSAVE
            th == ThermalGuardian.State.HOT      -> AdaptivePerformance.Profile.BALANCED
            s >= 70                               -> AdaptivePerformance.Profile.PERFORMANCE
            s >= 40                               -> AdaptivePerformance.Profile.BALANCED
            else                                  -> AdaptivePerformance.Profile.POWERSAVE
        }

        // Target 240 DPI on mid-range (score<60), 320 on high-end (score>=70), 360 on flagship
        val recDpi = when {
            (p?.optScore ?: 0) >= 85 -> 360
            (p?.optScore ?: 0) >= 70 -> 320
            else                     -> 240
        }

        val reason = "score=$s thermal=${th.name} → ${recPerf.name} @ ${recDpi}DPI"
        Blackbox.log("NEURAL", "Recommend for $gamePkg: $reason")
        return Recommendation(recPerf, recDpi, reason)
    }
}