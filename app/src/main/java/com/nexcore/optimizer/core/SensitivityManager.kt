package com.nexcore.optimizer.core

import android.content.Context
import android.provider.Settings

/**
 * Sensitivity Manager — per-axis sensitivity, smooth factor, and touch deadzone.
 *
 * Settings path: Settings.System.POINTER_SPEED — int −7 to +7.
 *   −7 = slowest (1/32× multiplier internally)
 *   0  = neutral
 *   +7 = fastest (32× multiplier)
 *
 * Smooth factor (licin): written to SoC-specific sysfs touch nodes when available:
 *   Snapdragon: /sys/devices/virtual/touchscreen/touchscreen/sensitivity
 *   OPPO/OnePlus: /proc/touchpanel/smooth_level
 *   Samsung Exynos: /sys/class/sec/tsp/cmd  (via "set_grip_data" or smooth cmd)
 *
 * X/Y are stored in-app as a game profile overlay; they feed into game-specific
 * ADB overlayConfigs where supported (some game engines read these at startup).
 */
class SensitivityManager(
    private val context: Context,
    private val shizuku: ShizukuManager
) {
    data class Profile(
        val x           : Float = 1.6f,   // 0.01 – 3.00 relative X scale
        val y           : Float = 1.6f,   // 0.01 – 3.00 relative Y scale
        val smooth      : Float = 0.75f,  // 0.00 (raw) – 1.00 (max smooth)
        val deadzone    : Int   = 2,      // pixels 0–20
        val pointerSpeed: Int   = 3       // Settings.System.POINTER_SPEED  −7…+7
    )

    companion object {
        /** Preset profiles keyed by package name. */
        val GAME_PROFILES = mapOf(
            "com.mobile.legends"                   to Profile(x=1.6f, y=1.6f, smooth=0.75f, deadzone=2, pointerSpeed=3),
            "com.dts.freefireth"                   to Profile(x=2.0f, y=2.0f, smooth=0.50f, deadzone=1, pointerSpeed=4),
            "com.tencent.ig"                       to Profile(x=1.4f, y=1.4f, smooth=0.80f, deadzone=3, pointerSpeed=2),
            "com.activision.callofduty.shooter"    to Profile(x=1.8f, y=1.8f, smooth=0.60f, deadzone=2, pointerSpeed=3),
            "com.garena.game.codm"                 to Profile(x=1.8f, y=1.8f, smooth=0.60f, deadzone=2, pointerSpeed=3),
            "com.pearlabyss.blackdesertm"          to Profile(x=1.3f, y=1.3f, smooth=0.90f, deadzone=4, pointerSpeed=1),
        )
    }

    private var active: Profile = Profile()

    fun apply(profile: Profile): Boolean {
        active = profile
        val ptrOk  = applyPointerSpeed(profile.pointerSpeed)
        val sysOk  = applyTouchSmooth(profile.smooth)
        applyDeadzone(profile.deadzone)
        Blackbox.log(
            "SENSI",
            "x=${profile.x} y=${profile.y} smooth=${profile.smooth} dead=${profile.deadzone}px ptr=${profile.pointerSpeed} → ptrOk=$ptrOk sysOk=$sysOk"
        )
        return ptrOk
    }

    fun applyForGame(pkg: String): Boolean {
        val p = GAME_PROFILES[pkg] ?: active
        return apply(p)
    }

    private fun applyPointerSpeed(speed: Int): Boolean {
        require(speed in -7..7) { "pointer_speed must be in −7..7, got $speed" }
        return shizuku.putSetting("system", "pointer_speed", speed.toString())
    }

    private fun applyTouchSmooth(smooth: Float): Boolean {
        val sysVal = (smooth * 255).toInt().coerceIn(0, 255)
        val candidates = listOf(
            "/sys/devices/virtual/touchscreen/touchscreen/sensitivity",
            "/proc/touchpanel/smooth_level",
            "/sys/class/input/input1/sensitivity",
            "/sys/class/sec/tsp/cmd"              // Samsung — needs special format
        )
        for (path in candidates) {
            val exists = shizuku.shell("test -e $path && echo 1").trim()
            if (exists == "1") {
                shizuku.shell("echo $sysVal > $path")
                Blackbox.log("SENSI", "smooth=$sysVal → $path")
                return true
            }
        }
        Blackbox.warn("SENSI", "No touch smooth sysfs path found on this device")
        return false
    }

    private fun applyDeadzone(px: Int) {
        // Some kernels expose deadzone via /proc; otherwise stored in app only
        val path = "/proc/touchpanel/dead_zone"
        val exists = shizuku.shell("test -e $path && echo 1").trim()
        if (exists == "1") {
            shizuku.shell("echo $px > $path")
            Blackbox.log("SENSI", "deadzone=${px}px → $path")
        }
    }

    fun getCurrentPointerSpeed(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.POINTER_SPEED, 0)
    } catch (_: Exception) { 0 }

    fun getActive(): Profile = active
}