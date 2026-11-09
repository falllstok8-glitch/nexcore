package com.nexcore.optimizer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexcore.optimizer.core.*
import com.nexcore.optimizer.service.GameDetectorService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var shizuku   : ShizukuManager
    private lateinit var scan      : SmartScan
    private lateinit var dnaBld    : DeviceDNA
    private lateinit var perf      : AdaptivePerformance
    private lateinit var thermal   : ThermalGuardian
    private lateinit var sensi     : SensitivityManager
    private lateinit var smartDpi  : SmartDPI
    private lateinit var net       : NetworkIntelligence
    private lateinit var aftermatch: Aftermatch
    private lateinit var neural    : NeuralEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init core layer
        shizuku    = ShizukuManager(this).also { it.init() }
        perf       = AdaptivePerformance(shizuku)
        scan       = SmartScan(this, shizuku)
        dnaBld     = DeviceDNA(scan)
        thermal    = ThermalGuardian(this, shizuku, perf).also { it.start() }
        net        = NetworkIntelligence(this, shizuku)
        sensi      = SensitivityManager(this, shizuku)
        smartDpi   = SmartDPI(this, shizuku)
        aftermatch = Aftermatch()
        neural     = NeuralEngine(perf, thermal, net, dnaBld).also {
            it.init(dnaBld.build())
        }

        // Start background game detector
        startForegroundService(Intent(this, GameDetectorService::class.java))

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary        = Color(0xFF00E5A0),
                    background     = Color(0xFF080810),
                    surface        = Color(0xFF10121E),
                    onBackground   = Color(0xFFE8EAF6),
                    onSurface      = Color(0xFFE8EAF6),
                )
            ) {
                NexCoreApp(
                    shizuku    = shizuku,
                    perf       = perf,
                    thermal    = thermal,
                    sensi      = sensi,
                    smartDpi   = smartDpi,
                    net        = net,
                    aftermatch = aftermatch,
                    neural     = neural
                )
            }
        }
    }

    override fun onDestroy() {
        shizuku.destroy()
        super.onDestroy()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NexCoreApp(
    shizuku   : ShizukuManager,
    perf      : AdaptivePerformance,
    thermal   : ThermalGuardian,
    sensi     : SensitivityManager,
    smartDpi  : SmartDPI,
    net       : NetworkIntelligence,
    aftermatch: Aftermatch,
    neural    : NeuralEngine
) {
    val tabs = listOf("Dashboard", "Sensi & DPI", "Games", "Aftermatch", "Blackbox")
    var selectedTab by remember { mutableIntStateOf(0) }

    val shizukuOk   by shizuku.connected.collectAsStateWithLifecycle()
    val thermalState by thermal.state.collectAsStateWithLifecycle()
    val neuralScore  by neural.score.collectAsStateWithLifecycle()
    val logEntries   by Blackbox.flow.collectAsStateWithLifecycle()
    val sessions     by aftermatch.history.collectAsStateWithLifecycle()

    val accent = Color(0xFF00E5A0)
    val warn   = Color(0xFFFFB800)
    val danger = Color(0xFFFF4B6E)
    val muted  = Color(0xFF6B7290)
    val card   = Color(0xFF141628)

    Scaffold(
        containerColor = Color(0xFF080810),
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("NexCore", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text("Gaming Optimizer v2.4", fontSize = 11.sp, color = muted)
                    }
                    // Shizuku badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (shizukuOk) Color(0x2200E5A0) else Color(0x22FF4B6E),
                        border = ButtonDefaults.outlinedButtonBorder
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape)
                                    .background(if (shizukuOk) accent else danger)
                            )
                            Text(
                                if (shizukuOk) "Shizuku active" else "Shizuku offline",
                                fontSize = 11.sp,
                                color = if (shizukuOk) accent else danger
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor   = Color.Transparent,
                    contentColor     = accent,
                    edgePadding      = 0.dp
                ) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = selectedTab == i,
                            onClick  = { selectedTab = i },
                            text     = { Text(t, fontSize = 13.sp, fontWeight = if (selectedTab==i) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (selectedTab) {
                0 -> DashboardTab(thermal, thermalState, neuralScore, accent, warn, danger, muted, card, perf, shizukuOk)
                1 -> SensiDpiTab(sensi, smartDpi, accent, muted, card)
                2 -> GamesTab(accent, muted, card)
                3 -> AftermatchTab(sessions, accent, warn, danger, muted, card)
                4 -> BlackboxTab(logEntries, accent, warn, danger, muted)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dashboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DashboardTab(
    thermal     : ThermalGuardian,
    thermalState: ThermalGuardian.State,
    neuralScore : Int,
    accent: Color, warn: Color, danger: Color, muted: Color, card: Color,
    perf        : AdaptivePerformance,
    shizukuOk   : Boolean
) {
    val scope = rememberCoroutineScope()
    val readings by thermal.readings.collectAsStateWithLifecycle()
    val cpuTemp = readings.firstOrNull { "cpu" in it.zoneName.lowercase() }?.tempC ?: 0f

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Neural score
        item {
            NexCard(card) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Neural score", fontSize = 11.sp, color = muted)
                        Text("$neuralScore / 100", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent)
                    }
                    Text(thermalState.name, fontSize = 12.sp, color = when (thermalState) {
                        ThermalGuardian.State.NORMAL   -> accent
                        ThermalGuardian.State.WARM     -> warn
                        ThermalGuardian.State.HOT      -> warn
                        ThermalGuardian.State.CRITICAL -> danger
                    })
                }
            }
        }

        // Thermal readings
        item {
            NexCard(card) {
                Text("Thermal Guardian", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                Spacer(Modifier.height(10.dp))
                readings.take(4).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.zoneName, fontSize = 12.sp, color = muted)
                        Text("%.1f°C".format(r.tempC), fontSize = 12.sp, color = if (r.tempC > 50f) danger else if (r.tempC > 42f) warn else accent)
                    }
                    LinearProgressIndicator(
                        progress = { (r.tempC / 60f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color    = if (r.tempC > 50f) danger else if (r.tempC > 42f) warn else accent,
                        trackColor = Color(0x1AFFFFFF)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Performance buttons
        item {
            NexCard(card) {
                Text("Adaptive Performance", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdaptivePerformance.Profile.entries.forEach { p ->
                        OutlinedButton(
                            onClick = { scope.launch { perf.apply(p) } },
                            modifier = Modifier.weight(1f),
                            enabled  = shizukuOk,
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                        ) { Text(p.name.take(4), fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sensi & DPI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SensiDpiTab(sensi: SensitivityManager, smartDpi: SmartDPI, accent: Color, muted: Color, card: Color) {
    val scope = rememberCoroutineScope()
    var sensiX    by remember { mutableFloatStateOf(1.6f) }
    var sensiY    by remember { mutableFloatStateOf(1.6f) }
    var smooth    by remember { mutableFloatStateOf(0.75f) }
    var deadzone  by remember { mutableIntStateOf(2) }
    var ptrSpeed  by remember { mutableIntStateOf(3) }
    var dpiValue  by remember { mutableIntStateOf(240) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            NexCard(card) {
                Text("Sensitivity X / Y", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                Spacer(Modifier.height(12.dp))
                SliderRow("X (horizontal)", "%.2f".format(sensiX), accent, muted) {
                    sensiX = it * 2.99f + 0.01f
                }
                SliderRow("Y (vertical)", "%.2f".format(sensiY), accent, muted) {
                    sensiY = it * 2.99f + 0.01f
                }
                SliderRow("Smooth / licin", "%.2f".format(smooth), accent, muted) {
                    smooth = it
                }
                SliderRow("Touch deadzone", "${deadzone}px", accent, muted) {
                    deadzone = (it * 20).toInt()
                }
                SliderRow("Pointer speed (−7…+7)", if (ptrSpeed >= 0) "+$ptrSpeed" else "$ptrSpeed", accent, muted) {
                    ptrSpeed = (it * 14 - 7).toInt()
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            sensi.apply(SensitivityManager.Profile(sensiX, sensiY, smooth, deadzone, ptrSpeed))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5A0), contentColor = Color(0xFF080810))
                ) { Text("Apply via Shizuku", fontWeight = FontWeight.Bold) }
            }
        }

        item {
            NexCard(card) {
                Text("Smart DPI", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartDPI.PRESETS.take(4).forEach { preset ->
                        Surface(
                            modifier = Modifier.weight(1f).clickable { dpiValue = preset },
                            shape    = RoundedCornerShape(8.dp),
                            color    = if (dpiValue == preset) Color(0x2200E5A0) else Color(0x1AFFFFFF),
                            border   = if (dpiValue == preset) ButtonDefaults.outlinedButtonBorder else null
                        ) {
                            Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$preset", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (dpiValue==preset) accent else Color(0xFFE8EAF6))
                                Text("DPI", fontSize = 10.sp, color = Color(0xFF6B7290))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("wm density $dpiValue", fontSize = 11.sp, color = Color(0xFF6B7290), fontFamily = FontFamily.Monospace)
                    Text("native: ${smartDpi.nativeDpi()}dpi", fontSize = 11.sp, color = Color(0xFF6B7290))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { scope.launch { smartDpi.apply(dpiValue) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5A0), contentColor = Color(0xFF080810))
                ) { Text("Apply DPI", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: String, accent: Color, muted: Color, onChange: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(0.5f) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = muted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
    }
    Slider(value = v, onValueChange = { v = it; onChange(it) }, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
    Spacer(Modifier.height(4.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Games tab (static preview — wired to GameDetectorService in production)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GamesTab(accent: Color, muted: Color, card: Color) {
    val games = listOf(
        Triple("Mobile Legends",  "com.mobile.legends",                   true),
        Triple("Free Fire",       "com.dts.freefireth",                   false),
        Triple("PUBG Mobile",     "com.tencent.ig",                        false),
        Triple("COD Mobile",      "com.activision.callofduty.shooter",     false),
    )
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { NexCard(card) {
            games.forEach { (label, pkg, live) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                        Text(pkg, fontSize = 10.sp, color = muted, fontFamily = FontFamily.Monospace)
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = if (live) Color(0x2200E5A0) else Color(0x1AFFFFFF)) {
                        Text(if (live) "● Live" else "Standby", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = if (live) accent else muted)
                    }
                }
                if (label != games.last().first) HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 0.5.dp)
            }
        }}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Aftermatch
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AftermatchTab(sessions: List<com.nexcore.optimizer.core.Aftermatch.Session>, accent: Color, warn: Color, danger: Color, muted: Color, card: Color) {
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No sessions yet — start a game to record stats", fontSize = 13.sp, color = muted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(sessions) { s ->
            NexCard(card) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s.gameLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF6))
                        Text("${s.startTime} · ${s.durationMs/60000}m${(s.durationMs/1000)%60}s", fontSize = 11.sp, color = muted)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatChip("${s.avgFps.toInt()} FPS", "avg", accent)
                    StatChip("%.0f°C".format(s.peakTempC), "peak temp", warn)
                    StatChip("${s.avgPingMs}ms", "avg ping", accent)
                }
            }
        }
    }
}

@Composable
fun StatChip(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = Color(0xFF6B7290))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Blackbox
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BlackboxTab(entries: List<Blackbox.Entry>, accent: Color, warn: Color, danger: Color, muted: Color) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            items(entries.reversed()) { e ->
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text("[${e.ts}]", fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Text(" [${e.tag}] ", fontSize = 10.sp, color = when (e.level) {
                        Blackbox.Level.OK   -> Color(0xFF00E5A0)
                        Blackbox.Level.WARN -> warn
                        Blackbox.Level.ERR  -> danger
                    }, fontFamily = FontFamily.Monospace)
                    Text(e.msg, fontSize = 10.sp, color = Color(0xFF8899BB), fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Blackbox.clear() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = danger)) {
                Text("Clear", fontSize = 13.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared component
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NexCard(bg: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), content = content)
    }
}