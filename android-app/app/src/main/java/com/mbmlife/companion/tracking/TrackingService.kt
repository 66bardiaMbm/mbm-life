package com.mbmlife.companion.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.mbmlife.companion.MainActivity
import com.mbmlife.companion.MbmApplication
import com.mbmlife.companion.R
import com.mbmlife.companion.data.TrackingRepository
import com.mbmlife.companion.engine.DrivingDetector
import com.mbmlife.companion.engine.MovementState
import com.mbmlife.companion.engine.MovementStateDetector
import com.mbmlife.companion.engine.RawLocationFix
import com.mbmlife.companion.engine.TripTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TrackingService : Service() {
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "com.mbmlife.companion.action.START_TRACKING"
        const val ACTION_STOP = "com.mbmlife.companion.action.STOP_TRACKING"
        const val ACTION_ACTIVITY_UPDATE = "com.mbmlife.companion.action.ACTIVITY_UPDATE"
        const val ACTION_LOCATION_UPDATE = "com.mbmlife.companion.action.LOCATION_UPDATE"
        const val ACTION_NATIVE_FIX = "com.mbmlife.companion.action.NATIVE_FIX"
        const val EXTRA_FIX_JSON = "fix_json"
        private const val CHANNEL_ID = "mbm_native_tracking"
        private const val NOTIFICATION_ID = 4101
        private const val ACTIVITY_REQUEST_CODE = 4102
        private const val LOCATION_REQUEST_CODE = 4103
        private const val STOP_REEVALUATION_INTERVAL_MS = 10_000L
        private const val ACTIVITY_FRESH_MS = 30_000L
        // v425: the v424 fast/slow GPS mode switch (SLOW_INTERVAL_MS etc.)
        // has been reverted — untested, and the phone got worse ("Updated
        // 1 day ago") after it shipped. GPS is unconditionally fast again,
        // exactly as it was when the (separately confirmed) nativeSequence
        // fix was verified. Not reintroducing this until it can be proven
        // safe before shipping, not after.

        fun startIntent(context: android.content.Context) =
            Intent(context, TrackingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: android.content.Context) =
            Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class QueuedLocation(
        val location: Location,
        val fixId: String,
        val sequence: Long,
        val nativeCallbackAtMs: Long
    )

    // Live telemetry must never replay a backlog of old coordinates.  The
    // detector receives the newest callback as soon as the previous fix has
    // finished; an obsolete queued fix is replaced rather than rendered late.
    private val locationChannel = Channel<QueuedLocation>(Channel.CONFLATED)
    private val fixSequence = AtomicLong(0L)
    private val diagnosticSequence = AtomicLong(0L)
    private val diagnosticSessionId = UUID.randomUUID().toString()
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var activityRecognition: ActivityRecognitionClient
    private lateinit var repository: TrackingRepository
    private lateinit var app: MbmApplication
    private var detector: DrivingDetector? = null
    private var movementDetector: MovementStateDetector? = null
    private var currentSpeedKph = 0
    private var currentTripActive = false
    private var trackingStartRequested = false
    private var locationUpdatesRequested = false
    private var locationRequestFastMode: Boolean? = null
    private var activityUpdatesRequested = false
    private var stopReevaluationJob: Job? = null
    private var initializationJob: Job? = null
    private var explicitStopRequested = false
    private val detectorMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        app = application as MbmApplication
        repository = TrackingRepository(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        activityRecognition = ActivityRecognition.getClient(this)
        createNotificationChannel()
        logLifecycle("service_created")
        serviceScope.launch {
            for (queued in locationChannel) processLocation(queued)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logLifecycle(
            "start_command",
            JSONObject()
                .put("action", intent?.action ?: "sticky_restart")
                .put("flags", flags)
                .put("startId", startId)
        )
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_LOCATION_UPDATE -> {
                if (!app.preferences.trackingEnabled) {
                    logLifecycle("location_delivery_ignored_tracking_disabled")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForegroundSafely(buildNotification())
                startTracking("location_pending_intent", enableTracking = false)
                acceptLocationIntent(intent)
            }
            ACTION_ACTIVITY_UPDATE -> {
                // v424: a fresh Activity Recognition result arrived
                // (ActivityRecognitionReceiver). Re-evaluate GPS mode only —
                // does not start/stop tracking, does not touch trip state.
                // requestLocationUpdates() no-ops if the mode hasn't
                // actually changed.
                if (app.preferences.trackingEnabled) {
                    startForegroundSafely(buildNotification())
                    startTracking("activity_recognition_wakeup", enableTracking = false)
                }
                if (trackingStartRequested) requestLocationUpdates()
                logActivityDiagnostic()
            }
            ACTION_START -> startTracking("explicit_start", enableTracking = true)
            null -> {
                if (app.preferences.trackingEnabled) {
                    startTracking("sticky_restart", enableTracking = false)
                } else {
                    logLifecycle("sticky_restart_ignored_tracking_disabled")
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun startTracking(reason: String, enableTracking: Boolean) {
        if (enableTracking) app.preferences.trackingEnabled = true
        if (!app.preferences.trackingEnabled) return
        if (trackingStartRequested) {
            logLifecycle("duplicate_start_ignored", JSONObject().put("reason", reason))
            return
        }
        trackingStartRequested = true
        explicitStopRequested = false
        startForegroundSafely(buildNotification())
        if (!hasForegroundLocationPermission()) {
            repository.logger().error("Service", "Tracking refused: location permission missing")
            stopTracking()
            return
        }

        app.preferences.serviceStartedAtMs = System.currentTimeMillis()
        requestLocationUpdates()
        requestActivityUpdates()
        initializationJob?.cancel()
        initializationJob = serviceScope.launch {
            var identity: TrackingIdentity? = null
            for (attempt in 0 until 30) {
                val authUid = FirebaseAuth.getInstance().currentUser?.uid
                if (!authUid.isNullOrBlank()) app.preferences.webUid = authUid
                identity = TrackingRecoveryPolicy.resolveIdentity(
                    authUid,
                    app.preferences.webUid,
                    app.preferences.familyId
                )
                if (identity != null) break
                if (attempt == 0 || attempt == 9 || attempt == 29) {
                    logLifecycle(
                        "tracking_context_wait",
                        JSONObject()
                            .put("attempt", attempt + 1)
                            .put("hasPersistedUid", !app.preferences.webUid.isNullOrBlank())
                            .put("hasFamilyId", !app.preferences.familyId.isNullOrBlank())
                    )
                }
                delay(1_000L)
            }
            val resolved = identity
            if (resolved == null) {
                trackingStartRequested = false
                logLifecycle("tracking_context_unavailable_background_delivery_kept")
                return@launch
            }
            val active = repository.activeTrip(resolved.uid)
            val recent = repository.recentSamples(resolved.uid)
            detector = DrivingDetector(active, recent)
            movementDetector = MovementStateDetector(
                MovementState.fromWireValue(app.preferences.movementState),
                app.preferences.movementStateStartedAtMs
            )
            currentTripActive = active != null
            repository.logger().info(
                "Service",
                "Foreground tracking started",
                JSONObject()
                    .put("uid", resolved.uid)
                    .put("familyId", resolved.familyId)
                    .put("recoveredTripId", active?.id)
                    .put("startReason", reason)
                    .toString()
            )
            logLifecycle("tracking_ready", JSONObject().put("startReason", reason))
            startStopReevaluation()
        }
    }

    private fun requestLocationUpdates() {
        if (!hasForegroundLocationPermission()) return
        if (
            locationUpdatesRequested &&
            locationRequestFastMode == true
        ) return
        if (locationUpdatesRequested) {
            try { fused.removeLocationUpdates(locationPendingIntent()) } catch (_: Exception) {}
            locationUpdatesRequested = false
        }
        val builder = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2_000L
        )
            .setMinUpdateIntervalMillis(1_000L)
            // Do not enable max-update-delay/batching: it produced the exact
            // repeated 10–15 second marker/speed freezes seen on the phone.
        // v402: never gate stationary callbacks behind a distance threshold.
        // That gate could suppress fixes for minutes while the service was
        // healthy, producing "Location stale" and starving arrival/movement
        // decisions. Periodic fixes are the authoritative freshness signal.
        val request = builder.build()
        locationUpdatesRequested = true
        locationRequestFastMode = true
        try {
            // PendingIntent delivery is intentionally used here instead of a
            // process-bound LocationCallback. Google Play services can wake
            // this service and deliver fixes after Android has killed the app
            // process, which is the required closed-app tracking path.
            fused.requestLocationUpdates(request, locationPendingIntent())
                .addOnSuccessListener {
                    logLifecycle("location_pending_intent_registered")
                }
                .addOnFailureListener { error ->
                    locationUpdatesRequested = false
                    locationRequestFastMode = null
                    repository.logger().error(
                        "Location",
                        "requestLocationUpdates failed",
                        error.toString()
                    )
                }
        } catch (error: SecurityException) {
            locationUpdatesRequested = false
            locationRequestFastMode = null
            repository.logger().error("Location", "SecurityException", error.toString())
            stopTracking()
        }
    }

    private fun acceptLocationIntent(intent: Intent) {
        val result = LocationResult.extractResult(intent)
        if (result == null) {
            logLifecycle("location_pending_intent_missing_result")
            return
        }
        val callbackAt = System.currentTimeMillis()
        logLifecycle(
            "location_pending_intent_received",
            JSONObject().put("locationCount", result.locations.size)
        )
        result.locations.sortedBy { it.time }.forEach { location ->
            val sequence = fixSequence.incrementAndGet()
            val fixId = "native-${location.elapsedRealtimeNanos}"
            repository.logger().info(
                "LocationTiming",
                "Native location pending-intent delivery",
                JSONObject()
                    .put("fixId", fixId)
                    .put("sequence", sequence)
                    .put("capturedAtMs", location.time)
                    .put("nativeCallbackAtMs", callbackAt)
                    .put("lat", location.latitude)
                    .put("lng", location.longitude)
                    .put("accuracyM", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                    .put("rawSpeedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                    .toString()
            )
            locationChannel.trySend(QueuedLocation(location, fixId, sequence, callbackAt))
        }
    }

    private fun requestActivityUpdates() {
        if (activityUpdatesRequested) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            repository.logger().warn("Activity", "ACTIVITY_RECOGNITION not granted")
            return
        }
        activityUpdatesRequested = true
        try {
            activityRecognition.requestActivityUpdates(5_000L, activityPendingIntent())
                .addOnFailureListener { error ->
                    activityUpdatesRequested = false
                    repository.logger().warn(
                        "Activity",
                        "requestActivityUpdates failed",
                        error.toString()
                    )
                }
        } catch (error: SecurityException) {
            activityUpdatesRequested = false
            repository.logger().warn("Activity", "SecurityException", error.toString())
        }
    }

    private fun startStopReevaluation() {
        stopReevaluationJob?.cancel()
        stopReevaluationJob = serviceScope.launch {
            while (true) {
                delay(STOP_REEVALUATION_INTERVAL_MS)
                reevaluateActiveTripStop()
            }
        }
    }

    private suspend fun reevaluateActiveTripStop() {
        if (!currentTripActive) return
        val now = System.currentTimeMillis()
        val activityAt = app.preferences.lastActivityAtMs
        if (activityAt <= 0L || now - activityAt !in 0..ACTIVITY_FRESH_MS) return
        val drivingEngine = detector ?: return
        val timerCycle = detectorMutex.withLock {
            val before = drivingEngine.diagnosticSnapshot()
            val result = drivingEngine.reevaluateStop(
                nowMs = now,
                activityType = app.preferences.lastActivityType,
                activityConfidence = app.preferences.lastActivityConfidence
            )
            Triple(before, result, drivingEngine.diagnosticSnapshot())
        }
        val driveBefore = timerCycle.first
        val closure = timerCycle.second ?: return
        val driveAfter = timerCycle.third

        val movementEngine = movementDetector ?: MovementStateDetector(
            MovementState.fromWireValue(app.preferences.movementState),
            app.preferences.movementStateStartedAtMs
        ).also { movementDetector = it }
        val movementBefore = movementEngine.diagnosticSnapshot()
        val movement = movementEngine.confirmVerifiedTripEnded(closure.arrivalAtMs)
        val movementAfter = movementEngine.diagnosticSnapshot()
        app.preferences.movementState = movement.state.wireValue
        app.preferences.movementStateStartedAtMs = closure.arrivalAtMs
        app.preferences.movementDecisionAtMs = now
        app.preferences.stayStartAtMs = closure.arrivalAtMs

        val stationarySample = closure.lastSample.copy(
            rawSpeedMps = 0f,
            fallbackSpeedMps = 0.0,
            filteredSpeedMps = 0.0,
            displayedSpeedKph = 0,
            activityType = MovementState.STATIONARY.wireValue,
            activityConfidence = 100
        )
        repository.persistTimedStop(closure.trip, stationarySample)
        currentTripActive = false
        currentSpeedKph = 0
        updateNotification()
        withContext(Dispatchers.Main) { requestLocationUpdates() }
        broadcastForWebView(
            com.mbmlife.companion.engine.DrivingOutput(
                sample = stationarySample,
                transition = TripTransition.ENDED,
                trip = closure.trip,
                arrivalAtMs = closure.arrivalAtMs
            ),
            reportedAtMs = closure.lastSample.capturedAtMs
        )
        repository.logger().info(
            "Trip",
            "Trip ended by sustained stop activity timer",
            JSONObject()
                .put("sessionId", closure.trip.id)
                .put("arrivalAtMs", closure.arrivalAtMs)
                .put("lastAcceptedFixAtMs", closure.lastSample.capturedAtMs)
                .put("activityType", app.preferences.lastActivityType)
                .put("activityConfidence", app.preferences.lastActivityConfidence)
                .toString()
        )
        repository.logger().info(
            "NativeDecision",
            "timer_transition",
            diagnosticBase("timer_transition")
                .put("fixId", JSONObject.NULL)
                .put("lastAcceptedFixId", driveBefore.lastAcceptedFixId ?: JSONObject.NULL)
                .put("elapsedSinceTriggerFixMs", driveBefore.lastAcceptedAtMs?.let { now - it } ?: JSONObject.NULL)
                .put("activityHint", app.preferences.lastActivityType)
                .put("activityConfidence", app.preferences.lastActivityConfidence)
                .put("movementStateBefore", movementBefore.currentState.wireValue)
                .put("movementStateAfter", movementAfter.currentState.wireValue)
                .put("activeTripIdBefore", driveBefore.activeTripId ?: JSONObject.NULL)
                .put("activeTripIdAfter", driveAfter.activeTripId ?: JSONObject.NULL)
                .put("tripTransition", TripTransition.ENDED.name)
                .put("decisionReason", movement.reason)
                .put("transitionReason", closure.trip.closeReason ?: "sustained_stop_activity_timer")
                .toString()
        )
    }

    private suspend fun processLocation(queued: QueuedLocation) {
        val location = queued.location
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (!authUid.isNullOrBlank()) app.preferences.webUid = authUid
        val identity = TrackingRecoveryPolicy.resolveIdentity(
            authUid,
            app.preferences.webUid,
            app.preferences.familyId
        )
        if (identity == null) {
            logLifecycle(
                "location_deferred_missing_identity",
                JSONObject()
                    .put("capturedAtMs", location.time)
                    .put("hasPersistedUid", !app.preferences.webUid.isNullOrBlank())
                    .put("hasFamilyId", !app.preferences.familyId.isNullOrBlank())
            )
            return
        }
        val uid = identity.uid
        val familyId = identity.familyId
        val engine = detector ?: run {
            val active = repository.activeTrip(uid)
            DrivingDetector(active, repository.recentSamples(uid)).also { detector = it }
        }
        val capturedAt = if (location.time > 0) location.time else System.currentTimeMillis()
        val activityIsFresh =
            app.preferences.lastActivityAtMs > 0L &&
                System.currentTimeMillis() - app.preferences.lastActivityAtMs <= 30_000L
        val fix = RawLocationFix(
            uid = uid,
            familyId = familyId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            capturedAtMs = capturedAt,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            isMock = LocationCompat.isMock(location),
            activityType = if (activityIsFresh) app.preferences.lastActivityType else "UNKNOWN",
            activityConfidence = if (activityIsFresh) app.preferences.lastActivityConfidence else 0
        )
        val acceptedAtMs = System.currentTimeMillis()
        val driveCycle = detectorMutex.withLock {
            val before = engine.diagnosticSnapshot()
            val result = engine.ingest(fix)
            Triple(before, result, engine.diagnosticSnapshot())
        }
        val driveBefore = driveCycle.first
        val output = driveCycle.second
        val driveAfter = driveCycle.third
        readBatteryState()
        val nativeDriving = output.trip?.status == "active"
        val movementEngine = movementDetector ?: MovementStateDetector(
            MovementState.fromWireValue(app.preferences.movementState),
            app.preferences.movementStateStartedAtMs
        ).also { movementDetector = it }
        val movementBefore = movementEngine.diagnosticSnapshot()
        val movement = movementEngine.ingest(
            output.sample,
            verifiedTripActive = nativeDriving,
            verifiedTripEnded = output.transition == TripTransition.ENDED
        )
        val movementAfter = movementEngine.diagnosticSnapshot()
        val movementStartedAt =
            output.arrivalAtMs?.takeIf { movement.state == MovementState.STATIONARY }
                ?: movement.stateStartedAtMs
        app.preferences.movementState = movement.state.wireValue
        app.preferences.movementStateStartedAtMs = movementStartedAt
        if (output.sample.accepted) app.preferences.movementDecisionAtMs = capturedAt
        val stableOutput = output.copy(
            sample = output.sample.copy(activityType = movement.state.wireValue)
        )
        app.preferences.stayStartAtMs = TrackingRecoveryPolicy.nextStayStartAtMs(
            currentStayStartAtMs = app.preferences.stayStartAtMs,
            stationary = movement.state == MovementState.STATIONARY,
            arrivalAtMs = output.arrivalAtMs,
            movementStartedAtMs = movement.stateStartedAtMs
        )
        repository.logSpeed(stableOutput.sample)
        repository.logger().info(
            "Movement",
            "Movement state decision",
            JSONObject()
                .put("state", movement.state.wireValue)
                .put("stateStartedAtMs", movement.stateStartedAtMs)
                .put("changed", movement.changed)
                .put("evidenceAccepted", movement.evidenceAccepted)
                .put("reason", movement.reason)
                .put("sampleAtMs", capturedAt)
                .toString()
        )
        val distanceFromLastValidFixM = if (
            driveBefore.lastAcceptedLat != null && driveBefore.lastAcceptedLng != null
        ) {
            com.mbmlife.companion.engine.Geo.distanceM(
                driveBefore.lastAcceptedLat,
                driveBefore.lastAcceptedLng,
                fix.latitude,
                fix.longitude
            )
        } else null
        repository.logger().info(
            "NativeDecision",
            "fix_decision",
            diagnosticBase("fix_decision")
                .put("fixId", queued.fixId)
                .put("nativeSequence", queued.sequence)
                .put("provider", location.provider ?: JSONObject.NULL)
                .put("capturedAtMs", capturedAt)
                .put("elapsedRealtimeNanos", location.elapsedRealtimeNanos)
                .put("accuracyM", fix.accuracyM ?: JSONObject.NULL)
                .put("rawSpeedMps", output.sample.rawSpeedMps ?: JSONObject.NULL)
                .put("filteredSpeedMps", output.sample.filteredSpeedMps ?: JSONObject.NULL)
                .put("fallbackSpeedMps", output.sample.fallbackSpeedMps ?: JSONObject.NULL)
                .put("distanceFromLastValidFixM", distanceFromLastValidFixM ?: JSONObject.NULL)
                .put("dtFromLastValidFixMs", driveBefore.lastAcceptedAtMs?.let { capturedAt - it } ?: JSONObject.NULL)
                .put("accepted", output.sample.accepted)
                .put("rejectReason", output.sample.rejectionReason ?: JSONObject.NULL)
                .put("candidateStateBefore", movementBefore.candidateState?.wireValue ?: JSONObject.NULL)
                .put("candidateStateAfter", movementAfter.candidateState?.wireValue ?: JSONObject.NULL)
                .put("candidateCountBefore", movementBefore.candidateSamples)
                .put("candidateCountAfter", movementAfter.candidateSamples)
                .put("movementStateBefore", movementBefore.currentState.wireValue)
                .put("movementStateAfter", movementAfter.currentState.wireValue)
                .put("decisionReason", movement.reason)
                .put("evidenceAccepted", movement.evidenceAccepted)
                .put("activeTripIdBefore", driveBefore.activeTripId ?: JSONObject.NULL)
                .put("activeTripIdAfter", driveAfter.activeTripId ?: JSONObject.NULL)
                .put("tripTransition", output.transition.name)
                .put("transitionReason", output.trip?.closeReason ?: JSONObject.NULL)
                .put("driveCandidateSinceMsBefore", driveBefore.driveCandidateSinceMs ?: JSONObject.NULL)
                .put("driveCandidateSinceMsAfter", driveAfter.driveCandidateSinceMs ?: JSONObject.NULL)
                .put("stopCandidateSinceMsBefore", driveBefore.stopCandidateSinceMs ?: JSONObject.NULL)
                .put("stopCandidateSinceMsAfter", driveAfter.stopCandidateSinceMs ?: JSONObject.NULL)
                .put("activityHint", fix.activityType)
                .put("activityConfidence", fix.activityConfidence)
                .toString()
        )
        app.preferences.lastFixAtMs = capturedAt
        currentSpeedKph = stableOutput.sample.displayedSpeedKph
        val localStateUpdatedAtMs = System.currentTimeMillis()
        val tripModeChanged = currentTripActive != nativeDriving
        currentTripActive = nativeDriving
        if (tripModeChanged) {
            withContext(Dispatchers.Main) { requestLocationUpdates() }
        }
        updateNotification()
        // The signed-in user's UI is updated before Room/Firestore work. Cloud
        // persistence is deliberately downstream and can never gate the local
        // marker or speed.
        broadcastForWebView(
            stableOutput,
            fixId = queued.fixId,
            sequence = queued.sequence,
            nativeCallbackAtMs = queued.nativeCallbackAtMs,
            acceptedAtMs = acceptedAtMs,
            localStateUpdatedAtMs = localStateUpdatedAtMs
        )
        repository.persist(stableOutput)
        repository.logger().info(
            "LocationTiming",
            "Fix queued for persistence",
            JSONObject()
                .put("fixId", queued.fixId)
                .put("sequence", queued.sequence)
                .put("firebaseWriteQueuedAtMs", System.currentTimeMillis())
                .put("accepted", stableOutput.sample.accepted)
                .put("rejectionReason", stableOutput.sample.rejectionReason ?: JSONObject.NULL)
                .toString()
        )
        if (output.transition != TripTransition.NONE) {
            repository.logger().info(
                "Trip",
                "Trip transition ${output.transition}",
                JSONObject()
                    .put("sessionId", output.trip?.id)
                    .put("status", output.trip?.status)
                    .put("distanceM", output.trip?.distanceM)
                    .put("durationSec", output.trip?.durationSec)
                    .toString()
            )
        }
    }

    private fun broadcastForWebView(
        output: com.mbmlife.companion.engine.DrivingOutput,
        reportedAtMs: Long = System.currentTimeMillis(),
        fixId: String = "native-${output.sample.elapsedRealtimeNanos}",
        sequence: Long = fixSequence.incrementAndGet(),
        nativeCallbackAtMs: Long = System.currentTimeMillis(),
        acceptedAtMs: Long = System.currentTimeMillis(),
        localStateUpdatedAtMs: Long = System.currentTimeMillis()
    ) {
        if (!output.sample.accepted) return
        val sample = output.sample
        val bridgeSendAtMs = System.currentTimeMillis()
        val payload = JSONObject()
            .put("fixId", fixId)
            .put("sequence", sequence)
            .put("uid", sample.uid)
            .put("lat", sample.latitude)
            .put("lng", sample.longitude)
            .put("accuracy", sample.accuracyM ?: JSONObject.NULL)
            .put("speed", sample.filteredSpeedMps ?: JSONObject.NULL)
            .put("rawSpeedMps", sample.rawSpeedMps ?: JSONObject.NULL)
            .put("fallbackSpeedMps", sample.fallbackSpeedMps ?: JSONObject.NULL)
            .put("filteredSpeedMps", sample.filteredSpeedMps ?: JSONObject.NULL)
            .put("displayedSpeedKph", sample.displayedSpeedKph)
            .put("battery", app.preferences.batteryPct ?: JSONObject.NULL)
            .put("batteryCharging", app.preferences.batteryCharging)
            .put("heading", sample.bearingDeg ?: JSONObject.NULL)
            .put("capturedAt", java.time.Instant.ofEpochMilli(sample.capturedAtMs).toString())
            .put(
                "stayStart",
                app.preferences.stayStartAtMs.takeIf { it > 0L }
                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    ?: JSONObject.NULL
            )
            .put("reportedAt", java.time.Instant.ofEpochMilli(reportedAtMs).toString())
            .put("source", "companion")
            .put("moving", sample.activityType != MovementState.STATIONARY.wireValue)
            .put("activityType", sample.activityType)
            .put("movementState", sample.activityType)
            .put(
                "activityStartedAt",
                app.preferences.movementStateStartedAtMs.takeIf { it > 0L }
                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    ?: JSONObject.NULL
            )
            .put(
                "movementDecisionAt",
                app.preferences.movementDecisionAtMs.takeIf { it > 0L }
                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    ?: JSONObject.NULL
            )
            .put("nativeTrackingActive", true)
            .put("nativeAppVersion", com.mbmlife.companion.BuildConfig.VERSION_NAME)
            .put("nativeCallbackAtMs", nativeCallbackAtMs)
            .put("acceptedAtMs", acceptedAtMs)
            .put("localStateUpdatedAtMs", localStateUpdatedAtMs)
            .put("bridgeSendAtMs", bridgeSendAtMs)
        repository.logger().info("LocationTiming", "Bridge send", payload.toString())
        sendBroadcast(
            Intent(ACTION_NATIVE_FIX)
                .setPackage(packageName)
                .putExtra(EXTRA_FIX_JSON, payload.toString())
        )
    }

    private fun readBatteryPercentage(): Int? {
        val manager = getSystemService(BatteryManager::class.java) ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
    }

    private fun diagnosticBase(eventType: String) = JSONObject()
        .put("diagnosticSessionId", diagnosticSessionId)
        .put("seq", diagnosticSequence.incrementAndGet())
        .put("wallTimestampMs", System.currentTimeMillis())
        .put("monoTimestampMs", SystemClock.elapsedRealtime())
        .put("nativeAppVersion", com.mbmlife.companion.BuildConfig.VERSION_NAME)
        .put("eventType", eventType)

    private fun logLifecycle(event: String, details: JSONObject = JSONObject()) {
        val payload = diagnosticBase("lifecycle")
            .put("lifecycleEvent", event)
            .put("trackingEnabled", app.preferences.trackingEnabled)
            .put("isRunning", isRunning)
            .put("locationPendingIntentRegistered", locationUpdatesRequested)
        details.keys().forEach { key -> payload.put(key, details.opt(key)) }
        repository.logger().info("NativeDecision", "lifecycle", payload.toString())
    }

    private fun logActivityDiagnostic() {
        val drive = detector?.diagnosticSnapshot()
        val movement = movementDetector?.diagnosticSnapshot()
        repository.logger().info(
            "NativeDecision",
            "activity_update",
            diagnosticBase("activity_update")
                .put("fixId", JSONObject.NULL)
                .put("lastAcceptedFixId", drive?.lastAcceptedFixId ?: JSONObject.NULL)
                .put("activityHint", app.preferences.lastActivityType)
                .put("activityConfidence", app.preferences.lastActivityConfidence)
                .put("movementStateBefore", movement?.currentState?.wireValue ?: JSONObject.NULL)
                .put("movementStateAfter", movement?.currentState?.wireValue ?: JSONObject.NULL)
                .put("activeTripIdBefore", drive?.activeTripId ?: JSONObject.NULL)
                .put("activeTripIdAfter", drive?.activeTripId ?: JSONObject.NULL)
                .put("tripTransition", TripTransition.NONE.name)
                .put("decisionReason", "activity_observed_no_state_decision")
                .put("transitionReason", JSONObject.NULL)
                .toString()
        )
    }

    private fun readBatteryState() {
        readBatteryPercentage()?.let { app.preferences.batteryPct = it }
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        app.preferences.batteryCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun stopTracking() {
        explicitStopRequested = true
        initializationJob?.cancel()
        initializationJob = null
        stopReevaluationJob?.cancel()
        stopReevaluationJob = null
        try { fused.removeLocationUpdates(locationPendingIntent()) } catch (_: Exception) {}
        try { activityRecognition.removeActivityUpdates(activityPendingIntent()) } catch (_: Exception) {}
        locationUpdatesRequested = false
        locationRequestFastMode = null
        activityUpdatesRequested = false
        trackingStartRequested = false
        app.preferences.trackingEnabled = false
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            runBlocking(Dispatchers.IO) { repository.markTrackingStopped(uid) }
        }
        repository.logger().info("Service", "Foreground tracking stopped by user/system")
        logLifecycle("explicit_stop")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun locationPendingIntent(): PendingIntent {
        val intent = Intent(this, TrackingService::class.java)
            .setAction(ACTION_LOCATION_UPDATE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getService(this, LOCATION_REQUEST_CODE, intent, flags)
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, ACTIVITY_REQUEST_CODE, intent, flags)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = if (currentTripActive) {
            getString(R.string.tracking_notification_driving, currentSpeedKph)
        } else {
            getString(R.string.tracking_notification_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.tracking_stop), stop)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tracking_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        logLifecycle("task_removed")
        if (TrackingRecoveryPolicy.shouldKeepBackgroundDelivery(
                app.preferences.trackingEnabled,
                explicitStopRequested
            )
        ) {
            // Keep the already-registered PendingIntent delivery alive. This
            // is a no-op while Play services still holds the same request.
            requestLocationUpdates()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val keepBackgroundDelivery = TrackingRecoveryPolicy.shouldKeepBackgroundDelivery(
            app.preferences.trackingEnabled,
            explicitStopRequested
        )
        logLifecycle(
            if (keepBackgroundDelivery) "service_destroyed_delivery_preserved"
            else "service_destroyed_tracking_stopped"
        )
        isRunning = false
        initializationJob?.cancel()
        initializationJob = null
        stopReevaluationJob?.cancel()
        stopReevaluationJob = null
        if (!keepBackgroundDelivery) {
            try { fused.removeLocationUpdates(locationPendingIntent()) } catch (_: Exception) {}
            try { activityRecognition.removeActivityUpdates(activityPendingIntent()) } catch (_: Exception) {}
            locationUpdatesRequested = false
            locationRequestFastMode = null
            activityUpdatesRequested = false
        }
        trackingStartRequested = false
        locationChannel.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
