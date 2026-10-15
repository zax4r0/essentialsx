# EssentialsX ⚡

<p align="center">
  <img src="https://raw.githubusercontent.com/zax4r0/essentialsx/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="EssentialsX Logo" />
</p>

<p align="center">
  <strong>The essential power-user toolkit for Android, reimagined with Jetpack Compose & Material 3.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square" alt="License: MIT" /></a>
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-green.svg?style=flat-square" alt="Min SDK 26" /></a>
  <a href="https://developer.android.com/about/versions/16"><img src="https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-brightgreen.svg?style=flat-square" alt="Target SDK 36" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.1.10-purple.svg?style=flat-square" alt="Kotlin 2.1" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.02-blueviolet.svg?style=flat-square" alt="Compose BOM" /></a>
  <a href="https://shizuku.rikka.app"><img src="https://img.shields.io/badge/Privilege-Shizuku%20%2F%20Porter-orange.svg?style=flat-square" alt="Shizuku / Porter" /></a>
</p>

---

## 📖 Overview

**EssentialsX** (`eu.zax4r0.essentialsx`) is a lightweight, high-performance system utility tailored for modern Android devices. Designed with the clean, elegant aesthetics of **Porter UI**, EssentialsX consolidates vital device controls—keep-awake wake locks, multi-SIM / eSIM cellular mode controls, and custom animation scaling—into a unified, reactive interface.

---

## ✨ Features

### ☕ 1. Caffeine (Keep Screen Awake)
- **Zero elevated privileges needed**: Operates entirely in userland with Android's native WakeLock API.
- **Configurable Timeouts**: Choose from 1m, 5m, 10m, 30m, 1h, or Infinite awake mode.
- **Persistent Notification & Live Countdown**: Easily monitor remaining time or cancel directly from notifications.
- **Quick Settings Tile**: Single-tap toggle or cycle timeout presets straight from the notification shade.

### 📶 2. Multi-SIM & Multi-eSIM Network Switch
- **Per-SIM Control**: Independent network mode switching for each detected SIM card.
- **Full eSIM Discovery**: Accurately queries all installed physical and embedded (eSIM) profiles via `dumpsys isub`.
- **Smart Status Handling**: Active subscriptions provide full switching; inactive or disabled eSIM profiles are gracefully dimmed with disabled controls.
- **Supported Network Modes**:
  - `5G Only` (NR Only - Force SA/NSA 5G connectivity)
  - `4G Only` (LTE Only - Lock device to reliable 4G)
  - `5G Auto` (NR / LTE / WCDMA preferred automatic switching)
- **Privileged Backend**: Executes commands seamlessly via **Shizuku** or **Porter** without needing full device root.
- **Quick Settings Tile**: Cycle network modes on the primary data SIM on the fly.

### ⚡ 3. Custom Animation Scales
- **Fluid Window & Transition Speed**: Overwrite system limits with granular precision (from `0.0x` up to `10.0x`).
- **One-Tap Presets**: Instant switches to `Off (0x)`, `0.25x`, `0.5x`, `0.75x`, `1.0x`, and `1.5x`.
- **Link All Scales**: Synchronize Window, Transition, and Animator scales with a single toggle.
- **Privilege Options**: Works with `WRITE_SECURE_SETTINGS` (via ADB) or zero-setup auto-delegation via Porter/Shizuku.
- **Quick Settings Tile**: Toggle between high-speed 0.5x and standard 1.0x animations with one tap.

### 🎨 4. Porter Design System
- Built 100% with **Jetpack Compose** and **Material 3 Dynamic Theming**.
- Consistent dark-mode optimized components: rounded cards, status badges, contextual action sheets, and custom sliders.
- Native `StatusBarManager.requestAddTileService` prompts directly within the UI to add Quick Settings tiles in one click.

---

## 📱 Screenshots

| Caffeine & Cellular Controls | Per-SIM & Multi-eSIM Details | Animator & Quick Tiles | About & Developer Info |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/01_caffeine_overview.png" width="220" /> | <img src="docs/screenshots/02_per_sim_network.png" width="220" /> | <img src="docs/screenshots/03_animator_and_tiles.png" width="220" /> | <img src="docs/screenshots/04_about_dialog.png" width="220" /> |

---

## 🛠️ Permissions & Setup

EssentialsX is designed with a tiered permission architecture:

| Feature | Required Permission | Setup Method |
|:---|:---|:---|
| **Caffeine** | None (`WAKE_LOCK`, `POST_NOTIFICATIONS`) | Ready out of the box |
| **Animation Scales** | `WRITE_SECURE_SETTINGS` | Shizuku / Porter **OR** ADB command: <br/>`adb shell pm grant eu.zax4r0.essentialsx android.permission.WRITE_SECURE_SETTINGS` |
| **Network Switch** | Privileged Shell (`cmd phone`) | Shizuku **OR** Porter daemon |

### Porter Integration
EssentialsX seamlessly links with Porter daemon. If using Porter, add `eu.zax4r0.essentialsx` to your `porter.json` configuration:

```json
{
  "packages": [
    {
      "packageName": "eu.zax4r0.essentialsx",
      "certHash": "04a325910b157c2911d27bc2e9b540569a1a72aecc9c292c68078844d6dc4ee0",
      "permissions": ["all"]
    }
  ]
}
```

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK with platform 36 (Android 16)
- Gradle 8.11+ (bundled via `./gradlew`)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/zax4r0/essentialsx.git
cd essentialsx

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install directly to connected device
./gradlew installDebug
```

---

## 👤 Author

Developed with care by **zax4r0**:
- **GitHub**: [@zax4r0](https://github.com/zax4r0)
- **Website**: [zax4r0.dev](http://zax4r0.dev/)
- **Twitter / X**: [@zax4r0](https://twitter.com/zax4r0)

---

## 📄 License

This project is open-source under the terms of the [MIT License](LICENSE).
