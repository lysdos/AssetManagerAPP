# Smoke Test Plan - 2026-06-10

This document describes how to manually smoke-test the huaxiaapp Android RFID asset-inventory app. The test requires a real Android device (or emulator) and ideally a UROVO RFID PDA for full hardware testing.

## Pre-requisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34 installed
- JDK 11 or later
- A real Android device with Android 7.0+ (or emulator) for app-level testing
- A UROVO RFID PDA for full RFID testing
- The `固定资产测试.xlsx` sample file in the project root
- Git identity configured (since the user previously set `git init` but deferred commit setup)

## Setup

1. Generate the Gradle wrapper (one-time):
   ```bash
   cd /path/to/huaxiaapp
   gradle wrapper --gradle-version 8.2
   ```
   (This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/`.)

2. Create `local.properties` with the path to your Android SDK:
   ```
   sdk.dir=/path/to/Android/sdk
   ```

3. Open the project in Android Studio and let Gradle sync.

## Smoke Test Steps

### Test 1: Project builds

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

### Test 2: Unit tests pass

```bash
./gradlew :app:testDebugUnitTest
```
Expected: All unit tests pass (EpcNormalizer, HeaderSpec, ExcelImporter, ExcelExporter, StatusDerivation, RfidController, AssetDao, ScanRecordDao).

### Test 3: Three tabs render

Install on a device:
```bash
./gradlew :app:installDebug
adb shell am start -n com.huaxia.inventory/.MainActivity
```

- Tap each of the 3 bottom-nav tabs. All three Fragments should render without crash.

### Test 4: Import flow (no RFID)

- Tap "选择 Excel 文件" → grant permission → pick the test xlsx
- Verify file info card shows correct count
- Verify preview list shows up to 10 items
- Tap "开始盘点" → tab should switch to Scan

### Test 5: Scan page (no real RFID)

- On a non-PDA device/emulator, the RfidController will be in ERROR state. The scan button should be disabled. Confirm via toast/text.

### Test 6: Result page (no real RFID)

- Switch to Result tab. All 4 sub-tabs should be present.
- The "导出 Excel" button should be enabled only if a task is in session.

### Test 7: Full RFID flow (UROVO PDA only)

- Deploy to a UROVO PDA with `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Press the physical scan button (typically F1 or KEYCODE_BUTTON_R1 - check PDA docs)
- Verify the EPC appears in the "最近扫描" list
- Verify the stats counter increments (已扫 N)
- Verify duplicate scans don't re-add to DB (EPC dedup works)

## Triage Template

When issues are found, document them in this file under "Findings" with:
- Issue title
- Severity (Critical/Important/Minor)
- Steps to reproduce
- Suggested fix

## Findings

(To be filled in during actual smoke test execution.)

## Notes

- Build verification was skipped during implementation (no Android SDK configured locally).
- Unit tests were not run during implementation (no Gradle wrapper).
- All code was verified by reading the actual files for spec compliance.
- The Gradle wrapper, AAR file, and SDK integration need to be confirmed when running on a real device.
