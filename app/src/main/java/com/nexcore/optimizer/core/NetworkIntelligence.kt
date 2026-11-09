package com.nexcore.optimizer.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Network Intelligence — measures latency, sets WiFi lock, and optimizes DNS.
 *
 * DNS servers used in-game: 8.8.8.8 (Google) and 1.1.1.1 (Cloudflare).
 * WiFi lock prevents the radio from dropping to 20 MHz bandwidth while gaming.
 */
class NetworkIntelligence(
    private val context: Context,
    private val shizuku: ShizukuManager
) {
    data class Stats(
        val pingMs      : Long,
        val jitterMs    : Float,
        val packetLoss  : Float,   // 0.0–1.0
        val wifiRssi    : Int,     // dBm
        val wifiChannel : Int
    )

    private var wifiLock: WifiManager.WifiLock? = null

    /** Ping [host] [count] times; returns median RTT in ms. */
    suspend fun ping(host: String = "8.8.8.8", count: Int = 5): Long =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<Long>()
            repeat(count) {
                val start = System.currentTimeMillis()
                try {
                    InetAddress.getByName(host).isReachable(1000)
                    results += System.currentTimeMillis() - start
                } catch (_: Exception) { }
            }
            results.sorted().getOrElse(results.size / 2) { 999L }
        }

    suspend fun measure(): Stats = withContext(Dispatchers.IO) {
        val samples = (1..5).map { i ->
            val t = System.currentTimeMillis()
            try { InetAddress.getByName("8.8.8.8").isReachable(800) } catch (_: Exception) { }
            System.currentTimeMillis() - t
        }
        val median  = samples.sorted()[2].toLong()
        val jitter  = samples.map { (it - median).toFloat() }.map { kotlin.math.abs(it) }.average().toFloat()
        val loss    = samples.count { it >= 800 }.toFloat() / samples.size

        val wm      = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wm.connectionInfo
        Stats(
            pingMs     = median,
            jitterMs   = jitter,
            packetLoss = loss,
            wifiRssi   = wifiInfo?.rssi ?: -99,
            wifiChannel = wifiInfo?.frequency?.let { freqToChannel(it) } ?: 0
        ).also { Blackbox.log("NET", "ping=${it.pingMs}ms jitter=${String.format("%.1f",it.jitterMs)}ms loss=${(it.packetLoss*100).toInt()}%") }
    }

    /** Acquire high-performance WiFi lock — prevents radio from downclocking during game. */
    fun acquireWifiLock(tag: String = "NexCore:gaming") {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock?.release()
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag).also {
            it.setReferenceCounted(false)
            it.acquire()
            Blackbox.log("NET", "WiFi lock acquired ($tag)")
        }
    }

    fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        Blackbox.log("NET", "WiFi lock released")
    }

    /** Override DNS to 8.8.8.8 on the active network via ndc (needs shell uid). */
    fun setGameDns() {
        shizuku.shell("ndc resolver setnetdns \$(dumpsys connectivity | grep 'default network' | grep -oP '\\d+' | head -1) '' 8.8.8.8 1.1.1.1")
        Blackbox.log("NET", "DNS set to 8.8.8.8 / 1.1.1.1")
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun freqToChannel(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2412) / 5 + 1
        freq in 5170..5825 -> (freq - 5170) / 5 + 34
        freq in 5945..7125 -> (freq - 5945) / 5 + 1   // 6 GHz (WiFi 6E)
        else -> 0
    }
}