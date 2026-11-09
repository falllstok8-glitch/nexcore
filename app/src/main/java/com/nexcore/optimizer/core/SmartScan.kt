package com.nexcore.optimizer.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import java.io.File

/** Reads hardware capabilities via /proc, /sys, ActivityManager, and WindowManager. */
class SmartScan(
    private val context: Context,
    private val shizuku: ShizukuManager
) {
    data class Result(
        val cpuCores     : Int,
        val cpuMaxFreqMhz: Int,            // big-core maximum frequency
        val gpuRenderer  : String,
        val ramTotalMb   : Long,
        val ramFreeMb    : Long,
        val displayHz    : Int,
        val touchHzEst   : Int,            // best-guess touch sampling rate
        val kernelVersion: String,
        val soC          : String
    )

    fun scan(): Result {
        val cpuInfo   = readCpuInfo()
        val memInfo   = readMemInfo()
        val displayHz = getDisplayHz()
        val r = Result(
            cpuCores      = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqMhz = cpuMaxFreq() / 1000,
            gpuRenderer   = gpuRenderer(),
            ramTotalMb    = memInfo.first,
            ramFreeMb     = memInfo.second,
            displayHz     = displayHz,
            touchHzEst    = estimateTouchHz(displayHz),
            kernelVersion = System.getProperty("os.version") ?: "unknown",
            soC           = detectSoC(cpuInfo)
        )
        Blackbox.log("SCAN", "CPU: ${r.cpuCores} cores @ ${r.cpuMaxFreqMhz} MHz max")
        Blackbox.log("SCAN", "RAM: ${r.ramFreeMb} MB free / ${r.ramTotalMb} MB total")
        Blackbox.log("SCAN", "Display: ${r.displayHz} Hz · Touch ~${r.touchHzEst} Hz")
        Blackbox.log("SCAN", "SoC: ${r.soC} · GPU: ${r.gpuRenderer}")
        return r
    }

    private fun readCpuInfo(): String = try {
        File("/proc/cpuinfo").readText()
    } catch (_: Exception) { "" }

    private fun cpuMaxFreq(): Int {
        // /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq — one line per core
        val maxFreqs = (0..7).mapNotNull { i ->
            try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toIntOrNull()
            } catch (_: Exception) { null }
        }
        return maxFreqs.maxOrNull() ?: 0
    }

    private fun gpuRenderer(): String {
        // /sys/class/kgsl/kgsl-3d0/gpu_model is available on Snapdragon
        val kgsl = try { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim() } catch (_: Exception) { "" }
        if (kgsl.isNotEmpty()) return kgsl
        // Mali: /sys/class/misc/mali0/device/gpuinfo
        val mali = try { File("/sys/class/misc/mali0/device/gpuinfo").readText().trim() } catch (_: Exception) { "" }
        return mali.ifEmpty { Build.HARDWARE }
    }

    private fun readMemInfo(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Pair(info.totalMem / 1024 / 1024, info.availMem / 1024 / 1024)
    }

    private fun getDisplayHz(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics   // placeholder — real call below
            context.display?.refreshRate?.toInt() ?: 60
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate.toInt()
        }
    }

    private fun estimateTouchHz(displayHz: Int): Int = when {
        displayHz >= 144 -> 240
        displayHz >= 120 -> 180
        displayHz >= 90  -> 120
        else             -> 60
    }

    private fun detectSoC(cpuInfo: String): String {
        val board = Build.BOARD.uppercase()
        return when {
            "TARO"   in board -> "Snapdragon 8 Gen 1"
            "KALAMA" in board -> "Snapdragon 8 Gen 2"
            "PINEAPPLE" in board -> "Snapdragon 8 Gen 3"
            "TENSOR" in Build.HARDWARE.uppercase() -> "Google Tensor"
            cpuInfo.contains("Dimensity", ignoreCase = true) -> "MediaTek Dimensity"
            else -> "${Build.HARDWARE} / ${Build.BOARD}"
        }
    }
}