package com.nexcore.optimizer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nexcore.optimizer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GameDetectorService — foreground service that polls UsageStats every [POLL_MS] ms
 * to detect when a monitored game enters the foreground.
 *
 * On game launch:
 *   1. Apply the game's SensitivityManager profile
 *   2. Apply the NeuralEngine-recommended AdaptivePerformance profile
 *   3. Apply the recommended SmartDPI
 *   4. Acquire WiFi lock (NetworkIntelligence)
 *   5. Start Aftermatch session
 *
 * On game exit:
 *   1. End Aftermatch session
 *   2. Release WiFi lock
 *   3. Reset to BALANCED performance
 *   4. Optionally reset DPI (configurable)
 *
 * Requires PACKAGE_USAGE_STATS permission — user must grant via Settings.
 * This permission cannot be granted via Shizuku; it needs Settings → Apps → Special access.
 */
class GameDetectorService : Service() {

    companion object {
        private const val POLL_MS         = 500L
        private const val CHANNEL_ID      = "nexcore_fg"
        private const val NOTIF_ID        = 1
        private const val SAMPLE_INTERVAL = 1000L   // ms between Aftermatch samples

        /** Package names to watch. Extend via Settings in the app. */
        val MONITORED_PACKAGES = setOf(
            "com.mobile.legends",
            "com.dts.freefireth",
            "com.tencent.ig",
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.pearlabyss.blackdesertm"
        )

        val GAME_LABELS = mapOf(
            "com.mobile.legends"                -> "Mobile Legends",
            "com.dts.freefireth"               -> "Free Fire",
            "com.tencent.ig"                   -> "PUBG Mobile",
            "com.activision.callofduty.shooter" -> "COD Mobile",
            "com.garena.game.codm"             -> "COD Mobile (Garena)",
            "com.pearlabyss.blackdesertm"       -> "Black Desert Mobile"
        )

        private val _foregroundGame = MutableStateFlow<String?>(null)
        val foregroundGame: StateFlow<String?> = _foregroundGame.asStateFlow()
    }

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shizuku   : ShizukuManager
    private lateinit var sensi     : SensitivityManager
    private lateinit var dpi       : SmartDPI
    private lateinit var perf      : AdaptivePerformance
    private lateinit var neural    : NeuralEngine
    private lateinit var aftermatch: Aftermatch
    private lateinit var net       : NetworkIntelligence
    private lateinit var thermal   : ThermalGuardian

    private var activeGame: String? = null

    override fun onCreate() {
        super.onCreate()
        shizuku    = ShizukuManager(this).also   { it.init() }
        perf       = AdaptivePerformance(shizuku)
        sensi      = SensitivityManager(this, shizuku)
        dpi        = SmartDPI(this, shizuku)
        net        = NetworkIntelligence(this, shizuku)
        thermal    = ThermalGuardian(this, shizuku, perf).also { it.start() }
        aftermatch = Aftermatch()

        // NeuralEngine needs DeviceDNA — build it once
        val scan   = SmartScan(this, shizuku)
        val dnaBld = DeviceDNA(scan)
        val dnaProfile = dnaBld.build()
        neural = NeuralEngine(perf, thermal, net, dnaBld).also { it.init(dnaProfile) }

        startForeground(NOTIF_ID, buildNotification("Watching for games…"))
        Blackbox.log("SERVICE", "GameDetectorService created · watching ${MONITORED_PACKAGES.size} packages")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startDetectionLoop()
        return START_STICKY
    }

    private fun startDetectionLoop() {
        scope.launch {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            var sampleTick = 0L

            while (isActive) {
                val now    = System.currentTimeMillis()
                val stats  = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 5_000L, now
                )
                val current = stats
                    ?.filter { it.packageName in MONITORED_PACKAGES }
                    ?.maxByOrNull  { it.lastTimeUsed }
                    ?.takeIf       { now - it.lastTimeUsed < POLL_MS * 3 }
                    ?.packageName

                when {
                    current != null && current != activeGame -> onGameStart(current)
                    current == null && activeGame != null    -> onGameStop()
                }

                // Aftermatch sampling
                if (activeGame != null && now - sampleTick >= SAMPLE_INTERVAL) {
                    sampleTick = now
                    val tempC = thermal.readings.value
                        .firstOrNull { "cpu" in it.zoneName.lowercase() }?.tempC ?: 0f
                    aftermatch.sample(fps = 60f, tempC = tempC, pingMs = net.ping())
                }

                delay(POLL_MS)
            }
        }
    }

    private suspend fun onGameStart(pkg: String) {
        activeGame = pkg
        _foregroundGame.value = pkg
        val label = GAME_LABELS[pkg] ?: pkg

        Blackbox.log("GAME", "$label detected foreground")
        updateNotification("▶ $label")

        // 1. Sensitivity
        sensi.applyForGame(pkg)

        // 2. Performance + DPI from NeuralEngine
        val rec = neural.recommend(pkg)
        perf.apply(rec.perfProfile)
        dpi.apply(rec.dpiTarget)

        // 3. Network
        net.acquireWifiLock("NexCore:$pkg")
        net.setGameDns()

        // 4. Session
        aftermatch.startSession(pkg, label, rec.perfProfile.name, rec.dpiTarget)

        // 5. Neural rescore with all modules active
        neural.recompute(activeModules = 5)
    }

    private fun onGameStop() {
        val pkg   = activeGame ?: return
        val label = GAME_LABELS[pkg] ?: pkg
        activeGame = null
        _foregroundGame.value = null

        Blackbox.log("GAME", "$label left foreground")
        updateNotification("Watching for games…")

        aftermatch.endSession()
        net.releaseWifiLock()
        perf.apply(AdaptivePerformance.Profile.BALANCED)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NexCore", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NexCore")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        shizuku.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}