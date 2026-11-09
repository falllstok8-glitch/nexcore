# NexCore Gaming Optimizer

Android gaming optimizer — Shizuku-based, no root required.

## Features
SmartScan · DeviceDNA · AdaptivePerformance · ThermalGuardian ·
NetworkIntelligence · SensitivityManager (X/Y/smooth/deadzone) ·
SmartDPI · NeuralEngine · Aftermatch · Blackbox

## Build via GitHub Actions (no Android Studio needed)

### 1. Buat repository GitHub
1. Buka https://github.com → klik **New repository**
2. Nama: `nexcore` → klik **Create repository**

### 2. Upload semua file
Cara termudah — drag & drop di browser:
1. Di halaman repo kosong klik **uploading an existing file**
2. Drag seluruh folder `nexcore/` ke area upload
3. Klik **Commit changes**

Atau pakai Git CLI:
```bash
cd nexcore
git init
git remote add origin https://github.com/NAMAUSER/nexcore.git
git add .
git commit -m "init"
git push -u origin main
```

### 3. Jalankan build
- Buka tab **Actions** di repo GitHub
- Klik workflow **Build NexCore APK**
- Klik **Run workflow** → **Run workflow**
- Tunggu ~3-5 menit

### 4. Download APK
- Setelah build hijau ✓, klik nama run-nya
- Scroll ke bawah → **Artifacts**
- Download `NexCore-debug-N.zip` → extract → install `.apk`

## Setup di HP setelah install

### Izin wajib (manual)
```
Settings → Apps → Special app access → Usage access → NexCore → Allow
```
Tanpa ini GameDetectorService tidak bisa deteksi game foreground.

### Aktifkan Shizuku
Install Shizuku dari Play Store, lalu:
```bash
# PC/Mac dengan USB debugging aktif:
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```
Atau pakai Wireless debugging di Android 11+.

## Struktur project
```
app/src/main/
├── aidl/   IUserService.aidl          — Shizuku IPC interface
├── java/com/nexcore/optimizer/
│   ├── MainActivity.kt                — Compose UI entry point
│   ├── core/
│   │   ├── Blackbox.kt                — Thread-safe logger + StateFlow
│   │   ├── ShizukuManager.kt          — Shizuku binder lifecycle
│   │   ├── SmartScan.kt               — /proc + /sys device scan
│   │   ├── DeviceDNA.kt               — Device fingerprint + score
│   │   ├── AdaptivePerformance.kt     — CPU/GPU governor control
│   │   ├── ThermalGuardian.kt         — Thermal zone polling + action
│   │   ├── NetworkIntelligence.kt     — Ping, WiFi lock, DNS
│   │   ├── SensitivityManager.kt      — X/Y/smooth/deadzone/ptrspeed
│   │   ├── SmartDPI.kt                — wm density via Shizuku
│   │   ├── NeuralEngine.kt            — Scoring + recommendation
│   │   └── Aftermatch.kt              — Session telemetry recorder
│   └── service/
│       ├── NexCoreUserService.kt      — Shizuku UserService (uid=2000)
│       └── GameDetectorService.kt     — Foreground game detector
└── res/
```

## Minimum requirements
- Android 8.0 (API 26)+
- Shizuku 13.x
- PACKAGE_USAGE_STATS permission

## License
MIT