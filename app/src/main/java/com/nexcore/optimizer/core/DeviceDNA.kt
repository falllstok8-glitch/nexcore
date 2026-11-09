package com.nexcore.optimizer.core

import android.os.Build

/**
 * Device DNA — a stable fingerprint string derived from hardware constants.
 * Used to cache per-device optimization profiles and restore them across boots.
 *
 * Format: <SoC>-<RAM_GB>G-<DisplayHz>H
 * Example: SD8G2-12G-144H
 */
class DeviceDNA(private val scan: SmartScan) {

    data class Profile(
        val fingerprint : String,
        val socLabel    : String,
        val ramGb       : Int,
        val displayHz   : Int,
        val touchHz     : Int,
        val cpuMaxMhz   : Int,
        val gpuRenderer : String,
        val optScore    : Int       // 0–100, used by NeuralEngine as baseline
    )

    fun build(): Profile {
        val r = scan.scan()
        val fp = buildFingerprint(r)
        val score = scoreDevice(r)
        val p = Profile(
            fingerprint  = fp,
            socLabel     = r.soC,
            ramGb        = (r.ramTotalMb / 1024).toInt(),
            displayHz    = r.displayHz,
            touchHz      = r.touchHzEst,
            cpuMaxMhz    = r.cpuMaxFreqMhz,
            gpuRenderer  = r.gpuRenderer,
            optScore     = score
        )
        Blackbox.log("DNA", "Fingerprint: ${p.fingerprint} · score=${p.optScore}")
        return p
    }

    private fun buildFingerprint(r: SmartScan.Result): String {
        val soc = when {
            "8 Gen 3" in r.soC -> "SD8G3"
            "8 Gen 2" in r.soC -> "SD8G2"
            "8 Gen 1" in r.soC -> "SD8G1"
            "Tensor"  in r.soC -> "TENSOR"
            "Dimensity" in r.soC -> "DIM"
            else -> Build.BOARD.take(6).uppercase()
        }
        val ram = "${(r.ramTotalMb / 1024).toInt()}G"
        val hz  = "${r.displayHz}H"
        return "$soc-$ram-$hz"
    }

    /**
     * Device capability score (0–100):
     * 40 pts CPU max freq, 20 pts RAM, 20 pts display Hz, 20 pts touch Hz.
     */
    private fun scoreDevice(r: SmartScan.Result): Int {
        val cpu   = (r.cpuMaxFreqMhz.toFloat() / 3200f * 40).toInt().coerceIn(0, 40)
        val ram   = ((r.ramTotalMb  / 1024f) / 16f * 20).toInt().coerceIn(0, 20)
        val disp  = (r.displayHz.toFloat() / 165f * 20).toInt().coerceIn(0, 20)
        val touch = (r.touchHzEst.toFloat() / 300f * 20).toInt().coerceIn(0, 20)
        return cpu + ram + disp + touch
    }
}