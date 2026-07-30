# B-link Signage Player

Android TV client for Bhimavaram Digitals's digital signage network — an
Android application that pairs with a screen, receives a playlist assignment
in real time, and plays ads (video, image, or web content) in a fullscreen
kiosk loop across ~40 physical displays citywide.

## Overview

Signage Player runs on low-cost Android TV boxes and Google TV certified
devices. Each device generates a one-time pairing code on first boot, which
an operator enters into the [Signage Dashboard](https://github.com/harshacsit/digitals-signage.git) to
assign it a name and playlist. From that point on, the device listens to
Firestore in real time for playlist changes, plays each item for its
configured duration, and reports playback analytics back to Firestore.

## Key Features

- Real-time playlist sync via Firestore listeners (no polling, no manual push)
- Supports video (HLS + progressive MP4), image, and web/YouTube content types
- Per-item and per-screen rotation (0°/90°/180°/270°) for portrait displays
- Local disk caching via ExoPlayer's CacheDataSource — plays offline after
  first successful download
- Automatic boot recovery: relaunches into kiosk mode after power loss or
  reboot with no manual intervention
- Heartbeat + last-seen tracking so the dashboard can show online/offline status for every 5min(as per requirement)
- Per-ad playback analytics (play count, total seconds) logged to Firestore

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Media playback | AndroidX Media3 / ExoPlayer 1.3.1 (+ HLS extension) |
| Image loading | Coil |
| Backend | Firebase Firestore (real-time listeners) |
| Auth | Firebase Anonymous Auth |
| Video hosting | Cloudflare R2 |
| Min SDK | 23 (Android 6.0) — validated on Android 9 boxes |
| Target SDK | 35 |

## Architecture
                 ┌─────────────────────────┐
                 │     Android TV Boot     │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ SignageBootReceiver     │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ BootLaunchService       │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ MainActivity            │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ Firebase Authentication │
                 │ (Anonymous Login)       │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ Firestore               │
                 │ screens/{screenId}      │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ currentPlaylist         │
                 └────────────┬────────────┘
                              │
                              ▼
                 ┌─────────────────────────┐
                 │ playlists/{playlistId}  │
                 └────────────┬────────────┘
                              │
             ┌────────────────┼─────────────────┐
             ▼                ▼                 ▼
      ExoPlayer          Coil Image         WebView
       (Video)            Loader         (Web/YouTube)
             │                │                 │
             └────────────────┼─────────────────┘
                              ▼
                 ┌─────────────────────────┐
                 │ Playback Analytics      │
                 │ Firestore Analytics     │
                 └─────────────────────────┘


See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full data flow, caching
strategy, and OTA update design.

## Getting Started

### Prerequisites
- Android Studio (Koala or later)
- JDK 17
- A Firebase project with Firestore + Anonymous Auth enabled
- `google-services.json` for your Firebase project (not committed — see below)

### Setup
1. Clone the repo
2. Place your `google-services.json` in `app/`
3. Open in Android Studio, let Gradle sync
4. Run on an emulator (Android TV skin) or sideload to a physical device

### Building a release APK

### Steps

1. Open the project in Android Studio.
2. Wait for Gradle Sync to complete successfully.
3. Select **Build → Generate Signed Bundle / APK**.
4. Choose **APK** and click **Next**.
5. Select the release keystore.
6. Enter the keystore password and key password.
7. Select the **release** build variant.
8. Click **Finish**.
9. Wait for Gradle to complete the build.
10. The generated APK is located in:


app/build/outputs/apk/release/

### Installing the APK

Install the APK using ADB:

adb install app-release.apk

Or copy the APK to a USB drive and install it directly on the Android TV.

## Firestore Data Model

Player reads: `screens/{screenId}`, `playlists/{playlistId}`
Player writes: `screens/{screenId}.lastSeen` (heartbeat),
`analytics/{screenId}_{yyyyMMdd}/items/{urlEncoded}`

Full schema documented in the [Dashboard repo's DATA_MODEL.md](link), since
both apps share the same Firestore project.

## Deployment / Fleet Management

Devices are currently updated via ADB sideload. An OTA update system (remote
version check + silent install via Device Owner provisioning) is in progress
— see [ROADMAP](#roadmap).

## Known Limitations

- YouTube playback resolves HLS URLs via `yt-dlp` server-side; resolved URLs
  expire and are not refreshed automatically yet
- No offline-duration alerting yet — a dead screen is only visible as "last
  seen X ago" on the dashboard
- Raspberry Pi displays do not run this APK — see `signage-web-player` instead

## Roadmap

- [ ] OTA APK updates via Device Owner + PackageInstaller Session API
- [ ] Proactive pre-caching on cold boot (currently lazy/play-triggered)
- [ ] Time-based playlist scheduling
- [ ] Offline duration alerts

## Project Structure
```text
app/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── signage/
│   │   │           ├── player/
│   │   │           ├── services/
│   │   │           ├── receivers/
│   │   │           ├── models/
│   │   │           ├── utils/
│   │   │           └── firebase/
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── test/
└── build.gradle
```
### Directory Structure
```text
app/
└── src/
    └── main/
        └── java/
            └── com/
                └── signage/
                    └── player/
                        ├── MainActivity.kt
                        ├── SignageBootReceiver.kt
                        └── BootLaunchService.kt
```
 ## Team

- Android / Firebase logic/dashboard — [Harsha Vardhan Eudu]
- Ui contributed[figma] -[ch Hindrika sri]

## License

This project is the intellectual property of the project owner and is intended for educational, research, and startup development purposes.

All source code, documentation, designs, and related assets are proprietary. Unauthorized copying, modification, distribution, or commercial use of this project is prohibited without prior written permission from the project owner.

© 2026 Harsha Vardhan Eudu . All Rights Reserved.
