# MBM Life Android Companion (Phase 1)

Native Kotlin tracking engine for the existing MBM Life PWA. The visual app
continues to load from:

```text
https://66bardiambm.github.io/mbm-life/
```

The Android companion is registered in Firebase project `mbm-life`:

```text
Package:  com.mbmlife.companion
App ID:   1:541048775101:android:155b8c6ef2078fb61c32d4
```

`app/google-services.json` is the real configuration downloaded from that
Firebase Android app. Firebase identifiers and API keys in this file identify
the project; Firestore Rules and Firebase Auth still enforce access.

## What Phase 1 contains

- Kotlin Android app with the current PWA in a WebView
- Firebase Auth using Google + Credential Manager
- staged runtime permission flow
- `FusedLocationProviderClient`
- `ActivityRecognitionClient`
- location foreground service with persistent notification
- high-accuracy fixes while the screen is off
- native speed pipeline and driving state machine
- Room samples, trips, diagnostics, and idempotent Firestore outbox
- WorkManager network retry
- existing Firestore location/session/sample/event paths
- battery-optimization status and settings link
- display-only native-fix bridge to the PWA

## Prerequisites

Install the latest stable Android Studio with:

- JDK 17 (Android Studio's embedded JDK is sufficient)
- Android SDK Platform 35
- Android SDK Build Tools 35
- Android SDK Platform Tools
- a physical Android phone with Google Play services

This Mac did not have Android Studio, a JDK, or an Android SDK installed when
the project was generated. Therefore the included Gradle project and tests were
reviewed statically here, but the APK was not compiled on this machine.

## First build

1. Open Android Studio.
2. Choose **Open** and select the `android-app` folder.
3. Let Android Studio use its embedded JDK 17.
4. Install Android SDK 35 if prompted.
5. Wait for Gradle sync to finish.
6. In Android Studio's Terminal run:

```bash
./gradlew signingReport
```

Copy the debug variant's SHA-1 fingerprint. Register it with the already-created
Firebase Android app:

```bash
firebase apps:android:sha:create \
  1:541048775101:android:155b8c6ef2078fb61c32d4 \
  YOUR_DEBUG_SHA1 \
  --project mbm-life
```

Refresh the Firebase config after adding SHA-1:

```bash
firebase apps:sdkconfig android \
  1:541048775101:android:155b8c6ef2078fb61c32d4 \
  --project mbm-life > app/google-services.json
```

The refreshed file should contain an Android OAuth client (`client_type: 1`).
Google sign-in can return `DEVELOPER_ERROR` until the installed APK's signing
SHA-1 is registered.

Confirm that **Google** is enabled in Firebase Console → Authentication →
Sign-in method.

## Build and test

From `android-app/`:

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Build without Android Studio (GitHub Actions)

The repository includes `.github/workflows/android-apk.yml`. To build entirely
on GitHub:

1. Open the repository on GitHub.
2. Select the **Actions** tab.
3. Select **Build Android APK** in the left sidebar.
4. Select **Run workflow**, choose the branch containing `android-app`, then
   select the green **Run workflow** button.
5. Open the newest workflow run and wait for every build step to turn green.
6. At the bottom of the run summary, under **Artifacts**, select
   **app-debug**.
7. GitHub downloads `app-debug.zip`. Extract it to obtain the exact file
   `app-debug.apk`.

The workflow uses JDK 17, runs `testDebugUnitTest`, assembles the debug APK,
fails if the expected APK is missing, and retains the artifact for 14 days.

The included deterministic tests cover:

- fallback speed when `Location.speed` is absent
- impossible position spike rejection
- 15-second verified trip entry
- keeping one trip through a short stop
- ending after a sustained verified stop

These tests do not prove background operation.

## Install on a phone

Enable Developer options and USB debugging, connect the phone, then run:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone, open it, and approve installation from
that file source.

## First-run permission order

1. Tap **Sign in with Google** and use the same account as the PWA.
2. Grant precise foreground location.
3. Grant activity recognition.
4. Grant notification permission.
5. Tap the permission button again.
6. On Android 11+, Android Settings opens. Select:
   **Permissions → Location → Allow all the time**.
7. Return to MBM Life.
8. Open the Family screen long enough to link its active family, or let the
   native single-family query resolve it.
9. Tap **Start native tracking**.
10. Confirm the persistent tracking notification before leaving the app.

The app never asks for a password, security code, or two-factor code in a chat.
Authentication stays inside Google's Android credential UI.

## Diagnostics

Live native logs:

```bash
adb logcat -v time \
  -s MBM/Service MBM/Location MBM/Activity MBM/Speed MBM/Trip MBM/Sync MBM/Auth MBM/Family
```

Important entries:

- `MBM/Speed`: provider, fallback, filtered and displayed speed
- `MBM/Trip`: session start/update/end transition
- `MBM/Sync`: acknowledged Firestore path or exact exception
- `MBM/Activity`: detected activity and confidence
- `MBM/Service`: service start/stop and recovery

Room also retains the last 1,000 diagnostic records in
`mbm_native_tracking.db`.

To inspect service state:

```bash
adb shell dumpsys activity services com.mbmlife.companion
adb shell dumpsys deviceidle whitelist
adb shell dumpsys package com.mbmlife.companion
```

## Required real-device validation

Use [`REAL_DEVICE_TEST_CHECKLIST.md`](REAL_DEVICE_TEST_CHECKLIST.md). At minimum:

- drive with the app backgrounded and screen locked
- confirm speed rises above zero
- stop briefly without splitting the trip
- park for more than 90 seconds
- verify the ended session and sample chunks in Firestore
- verify the PWA Driving History reads the native session
- test network loss and Room outbox recovery

Phase 1 must not be described as reliable background tracking until that test
passes on a physical Android phone.

## Known scope boundaries

- Road-report creation and UI remain in the PWA.
- Tabs, settings, and navigation remain in the PWA.
- A foreground service cannot survive a user **Force stop**. Android blocks
  restart until the user launches the app again.
- Aggressive OEM battery managers can still stop services. The app exposes
  battery settings, but it does not silently request unrestricted battery use.
- Google Play distribution of background location requires policy disclosure
  and approval.
