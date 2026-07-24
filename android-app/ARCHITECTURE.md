# MBM Life Android Companion — Phase 1 Architecture

## Scope

This project does not replace the MBM Life PWA. `MainActivity` hosts the
existing production PWA in a `WebView`. Native Android owns only the signed-in
user's tracking producer:

```text
FusedLocationProviderClient
        │
        ▼
TrackingService (foreground, START_STICKY)
        │
        ├── ActivityRecognitionClient
        ├── DrivingDetector
        ├── Room transaction
        │     ├── accepted/rejected samples
        │     ├── active/completed trip
        │     ├── diagnostic log
        │     └── idempotent Firestore outbox
        ├── persistent notification
        ├── WorkManager → Firebase Auth → Firestore acknowledgement
        └── display-only WebView bridge
```

## Single-producer contract

- The companion WebView disables HTML geolocation.
- Native writes `source: "companion"`, `nativeTrackingActive: true`, and a
  heartbeat into `families/{familyId}/locations/{uid}`.
- The small matching PWA coordination patch observes only those lease fields.
  It stops PWA `watchPosition` and rejects racing PWA fixes while the heartbeat
  is fresh.
- A standalone PWA may display companion Firestore coordinates, but may not
  produce location or driving samples during the lease.
- In the native WebView, delayed Firestore echoes are not used for marker
  movement. The display-only Android bridge supplies the latest native fix.
- The bridge writes only the PWA render cache. It does not call
  `writeMyFix()` or `FamilyDriving.ingestFix()`.

## Firestore paths

The Android companion uses the existing model:

```text
families/{familyId}/locations/{uid}
families/{familyId}/drivingSessions/{sessionId}
families/{familyId}/drivingSessions/{sessionId}/samples/chunk_{index}
families/{familyId}/drivingEvents/{eventId}
families/{familyId}/roadReports/{reportId}    # displayed/managed by PWA only
```

Samples are rewritten in deterministic chunks of at most 250 points. Outbox
rows use the full Firestore document path as the Room primary key, so repeated
writes replace pending state rather than generating duplicates.

## Driving state machine

- Provider speed is preferred when it is finite and no more than 55 m/s.
- Distance/time speed is calculated when provider speed is absent.
- An exponential filter smooths the chosen speed.
- Driving enters at 2.8 m/s after 15 seconds of evidence, tolerating an
  8-second evidence gap.
- A high-confidence `IN_VEHICLE` hint can support entry only when movement is
  also present.
- A trip does not end until speed is at or below 1.0 m/s, net movement over a
  30-second window is at most 25 m, and that evidence persists for 90 seconds.
- Active state and every accepted sample are restored from Room after service
  recreation.

## Honest boundary

Compilation and deterministic tests do not verify Android background behavior.
Release acceptance requires a physical phone test with the screen locked,
network loss/recovery, a short traffic stop, and a final sustained stop.
