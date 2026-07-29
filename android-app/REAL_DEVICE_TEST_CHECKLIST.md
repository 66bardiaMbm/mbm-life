# Phase 1 Real-Device Acceptance Checklist

Record the Android version, device model, battery mode, build SHA, Firebase
UID, and family ID before starting.

## Permission and service

- [ ] Sign in natively with the same Firebase account as the PWA.
- [ ] Grant precise foreground location.
- [ ] Grant activity recognition and notifications.
- [ ] In Android app settings, select **Location → Allow all the time**.
- [ ] Grant the Android runtime permissions when the system requests them.
- [ ] Confirm the foreground-service notification appears after Family linking.
- [ ] Confirm the persistent “MBM Life tracking is active” notification.
- [ ] Lock the screen for at least five minutes.
- [ ] Confirm `adb logcat -s MBM/Service MBM/Location MBM/Speed` continues.

## Stationary baseline

- [ ] Leave the phone stationary for five minutes.
- [ ] No trip starts.
- [ ] Firestore location documents show `source = companion`.
- [ ] `nativeTrackingActive = true` and heartbeat advances.
- [ ] Only one marker writer is active; the PWA watcher is stopped.

## Real drive

- [ ] Start driving and keep the screen locked.
- [ ] `coordsSpeedMps`, fallback, filtered, and displayed km/h are logged.
- [ ] Displayed speed rises above 0 km/h.
- [ ] A `drivingSessions` document enters `status = active`.
- [ ] Sample chunks appear below the same session.
- [ ] A short traffic-light stop does not end the session.
- [ ] Restore network after an offline section; Room outbox drains in order.
- [ ] Firestore logs contain no `permission-denied` or `failed-precondition`.

## Final stop and PWA display

- [ ] Park and remain stopped for more than 90 seconds.
- [ ] The same session changes to `status = ended`.
- [ ] End time, distance, duration, moving duration and max speed are nonfake.
- [ ] The PWA Driving History loads that Firestore session.
- [ ] Weekly totals update.
- [ ] Stop tracking and confirm the persistent notification disappears.
- [ ] Firestore `nativeTrackingActive` becomes false.

No item may be marked PASS from an emulator or simulated location alone.
