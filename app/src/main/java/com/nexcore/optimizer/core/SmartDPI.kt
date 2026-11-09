package com.nexcore.optimizer.core

import android.content.Context
import android.util.DisplayMetrics

/**
 * Smart DPI — applies display density via `wm density <dpi>` through Shizuku.
 *
 * Lower DPI = more screen space per logical pixel = less GPU work per frame.
 * Useful for older/mid-range devices to gain FPS headroom in games that
 * render at display density.
 *
 * Safe range: 160–640 dp. Below 160 the launcher becomes unusable.
 * Above the native DPI there is no visual benefit and wastes GPU bandwidth.
 *
 * Example:
 *   smartDPI.apply(240)       // wm density 240
 *   smartDPI.reset()          // wm density reset  (back to native)
 *   smartDPI.optimalFor(1.0f) // compute best DPI for a 1× sensitivity factor
 */
class SmartDPI(
    private val context: Context,
    private val shizuku: ShizukuManager
) {
    companion object {
        /** Common DPI presets used in gaming optimizer tools. */
        val PRESETS = listOf(240, 300, 360, 400, 480, 540, 560, 640)

        const val MIN_DPI = 160
        const val MAX_DPI = 640
    }

    /** Physical (native) display DPI from DisplayMetrics. */
    fun nativeDpi(): Int = context.resources.displayMetrics.densityDpi

    /**
     * Compute the optimal preset for a desired sensitivity scaling factor.
     * Base is 320 dp; factor 1.0 → 320, factor 0.75 → 240, factor 1.25 → 400.
     * Result snapped to nearest value in [PRESETS].
     */
    fun optimalFor(sensitivityFactor: Float = 1.0f): Int {
        val target = (320 * sensitivityFactor).toInt().coerceIn(MIN_DPI, MAX_DPI)
        return PRESETS.minByOrNull { kotlin.math.abs(it - target) } ?: 320
    }

    /**
     * Apply [dpi] via `wm density <dpi>`.
     * Requires shell UID (Shizuku) — WRITE_SECURE_SETTINGS is insufficient for wm.
     * Returns true when stdout is empty (success) or false on error.
     */
    fun apply(dpi: Int): Boolean {
        require(dpi in MIN_DPI..MAX_DPI) { "DPI $dpi out of safe range $MIN_DPI–$MAX_DPI" }
        val out = shizuku.shell("wm density $dpi")
        val ok  = out.isBlank() || !out.contains("Error", ignoreCase = true)
        Blackbox.log("DPI", "wm density $dpi → ${if (ok) "applied" else "failed: $out"}")
        return ok
    }

    /**
     * Reset to native DPI.
     * Equivalent to: adb shell wm density reset
     */
    fun reset(): Boolean {
        val out = shizuku.shell("wm density reset")
        val ok  = out.isBlank()
        Blackbox.log("DPI", "wm density reset → ${if (ok) "ok" else "failed: $out"}")
        return ok
    }

    /**
     * Read current override DPI from `wm density` output.
     * Output format:
     *   Physical density: 420
     *   Override density: 240   ← present only when overridden
     * Returns override if set, else physical density.
     */
    fun current(): Int {
        val out   = shizuku.shell("wm density")
        val ovrd  = Regex("Override density:\s*(\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val phys  = Regex("Physical density:\s*(\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return ovrd ?: phys ?: nativeDpi()
    }

    fun isOverridden(): Boolean {
        val out = shizuku.shell("wm density")
        return out.contains("Override density", ignoreCase = true)
    }
}