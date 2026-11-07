[中文README](https://github.com/Super-Oguri-Cap/flight-efb/main/README.md)

# FlightEFB

An offline EFB for Android: browse AIP charts, aircraft checklists, and QRH on the ground or in the air, with no network access at all. Original chart files are never modified.

> **For flight simulation only.** This app ships no official charts and must never be used for real-world flight.

## Features

| Tab | Contents |
|---|---|
| Charts | Search by ICAO code, airport name, or chart title; group by chart type; open PDF with pinch zoom, double-tap zoom, one-finger pan, colour inversion, and next-page pre-rendering |
| Checklists | 21 aircraft types, 2208 items, grouped as Airbus / Boeing / Other |
| QRH | 16 procedures covering A320 ECAM items and Boeing memory items |

A four-step first-run wizard walks through granting storage access, picking the chart library folder, scanning it, and confirming the result. Later launches go straight to the main screen.

## Requirements

| Item | Requirement |
|---|---|
| Android | 14 or newer (minSdk 34) |
| Storage | About 1.2 GB for a full chart library (3203 PDFs) |
| Permission | All files access (MANAGE_EXTERNAL_STORAGE) |
| Network | Not required |

## Chart library layout

The app identifies airports and chart types from directory names. The library must be organised as:

```
AIP航图库/
├─ ZBAA_北京_首都/
│   ├─ 01_机场图ADC/
│   └─ 05_进近图IAC/
└─ 00_ENR通用资料/
```

The first level is the airport (`ICAO_City_Name`), the second level is the chart type (`01_` through `10_`). Full rules are in [航图库结构与命名规范.md](航图库结构与命名规范.md) (Chinese). Inside the app, **Settings → Generate spec document** writes the same file to `/sdcard/Download/`.

## Building

Requires JDK 17 and Android SDK 36.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

The signing keystore is not distributed with the repository. Configure your own keystore in `app/build.gradle.kts` before building a release.

## Testing

```bash
./gradlew testDebugUnitTest
```

75 JVM unit tests cover index queries, Chinese name matching, directory scanning, chart type ordering, SAF path resolution, page geometry, checklist and QRH data structures, and spec export.

`ScannerParityTest` verifies that the on-device scanner produces exactly the same result as the PC-side `naip_index.py` across the real 3203-file library.

## Data

| File | Purpose |
|---|---|
| `assets/charts.db` | Prebuilt chart index (620 KB) |
| `assets/checklists.json` | Checklist data |
| `assets/qrh.json` | QRH data |
| `assets/library_spec.md` | Structure spec, exportable from Settings |

To use a different chart library, rescan it inside the app; no rebuild needed.

### Regenerating checklists

```bash
python tools/fetch_sources.py
python tools/build_checklists.py checklists.json
cp checklists.json app/src/main/assets/
```

### Regenerating the chart index (PC side)

```bash
python naip_index.py --root /path/to/AIP航图库 --out index.json --db charts.db
cp charts.db app/src/main/assets/
```

## Data sources and licensing

All Kotlin code is original. No third-party source code is included. Runtime dependencies are the Kotlin standard library and AndroidX, both Apache-2.0.

Checklist data comes from [simchecklists](https://github.com/rkaw92/simchecklists) under the **MIT licence**; the original copyright notice is preserved in [NOTICE.md](NOTICE.md). The QRH entries were written for this project based on publicly documented A320 ECAM structure and Boeing memory items.

This project is licensed under the MIT Licence. See [LICENSE](LICENSE).

## Disclaimer

For flight simulation only. The author makes no guarantee about the accuracy of any content and accepts no liability for any use of this software. **Not for real-world flight.**
