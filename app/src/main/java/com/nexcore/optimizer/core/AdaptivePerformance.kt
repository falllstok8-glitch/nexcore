package com.nexcore.optimizer.core

/**
 * Adaptive Performance — CPU governor and GPU frequency management via Shizuku shell.
 *
 * Supported governors on most SoCs:
 *   performance  → all cores at max freq (best FPS, highest heat)
 *   schedutil    → kernel-managed, good balance for LTPO displays
 *   powersave    → minimum freq (cool-down mode)
 *
 * GPU devfreq governors (Adreno/Mali):
 *   performance  → GPU at peak clock
 *   msm-adreno-tz → Qualcomm's thermal-aware default
 *   simple_ondemand → generic on-demand
 */
class AdaptivePerformance(private val shizuku: ShizukuManager) {

    enum class Profile {
        PERFORMANCE,   // all cores locked to max, GPU at peak
        BALANCED,      // schedutil + msm-adreno-tz
        POWERSAVE      // cool-down — trim freqs for thermal recovery
    }

    private val cpuGovPath = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor"
    private val gpuGovPath = "/sys/class/devfreq/kgsl-3d0/governor"       // Adreno
    private val gpuGovPathMali = "/sys/class/devfreq/13000000.mali/governor"

    fun apply(profile: Profile): Boolean {
        val (cpuGov, gpuGov) = when (profile) {
            Profile.PERFORMANCE -> "performance" to "performance"
            Profile.BALANCED    -> "schedutil"   to "msm-adreno-tz"
            Profile.POWERSAVE   -> "powersave"   to "simple_ondemand"
        }
        var ok = setCpuGovernor(cpuGov)
        ok = setGpuGovernor(gpuGov) && ok
        Blackbox.log("PERF", "Profile ${profile.name} → cpu=$cpuGov gpu=$gpuGov (ok=$ok)")
        return ok
    }

    private fun setCpuGovernor(gov: String): Boolean {
        val cores = Runtime.getRuntime().availableProcessors()
        var allOk = true
        for (i in 0 until cores) {
            val path = cpuGovPath.format(i)
            val out = shizuku.shell("echo $gov > $path 2>&1")
            if (out.contains("Permission denied") || out.contains("No such file")) {
                Blackbox.warn("PERF", "cpu$i governor write failed")
                allOk = false
            }
        }
        return allOk
    }

    private fun setGpuGovernor(gov: String): Boolean {
        // Try Adreno path first, then Mali
        for (path in listOf(gpuGovPath, gpuGovPathMali)) {
            val test = shizuku.shell("test -f $path && echo 1").trim()
            if (test == "1") {
                val out = shizuku.shell("echo $gov > $path 2>&1")
                return !out.contains("error", ignoreCase = true)
            }
        }
        Blackbox.warn("PERF", "No known GPU devfreq governor path found")
        return false
    }

    /** Lock CPU max frequency per cluster (prime/big/little) for a game profile. */
    fun lockCluster(cluster: Cluster, freqKhz: Int) {
        val cores = cluster.coreIndices
        for (i in cores) {
            shizuku.shell(
                "echo $freqKhz > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq"
            )
        }
        Blackbox.log("PERF", "Cluster ${cluster.name} locked @ ${freqKhz/1000} MHz (cores=$cores)")
    }

    enum class Cluster(val coreIndices: List<Int>) {
        PRIME(listOf(7)),
        BIG(listOf(4, 5, 6)),
        LITTLE(listOf(0, 1, 2, 3))
    }

    fun getCurrentGovernors(): Map<String, String> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).associate { i ->
            val gov = try {
                shizuku.shell("cat ${cpuGovPath.format(i)}").trim()
            } catch (_: Exception) { "unknown" }
            "cpu$i" to gov
        }
    }
}