package com.mbmlife.companion.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
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

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "com.mbmlife.companion.action.START_TRACKING"
        const val ACTION_STOP = "com.mbmlife.companion.action.STOP_TRACKING"
        const val ACTION_NATIVE_FIX = "com.mbmlife.companion.action.NATIVE_FIX"
        const val EXTRA_FIX_JSON = "fix_json"
        private const val CHANNEL_ID = "mbm_native_tracking"
        private const val NOTIFICATION_ID = 4101
        private const val ACTIVITY_REQUEST_CODE = 4102
        private const val STOP_REEVALUATION_INTERVAL_MS = 10_000L
        private const val ACTIVITY_FRESH_MS = 30_000L

        fun startIntent(context: android.content.Context) =
            Intent(context, TrackingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: android.content.Context) =
            Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationChannel = Channel<Location>(Channel.UNLIMITED)
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
    private var locationRequestForActiveTrip: Boolean? = null
    private var activityUpdatesRequested = false
    private var stopReevaluationJob: Job? = null
    private val detectorMutex = Mutex()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { locationChannel.trySend(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as MbmApplication
        repository = TrackingRepository(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        activityRecognition = ActivityRecognition.getClient(this)
        createNotificationChannel()
        serviceScope.launch {
            for (location in locationChannel) processLocation(location)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (trackingStartRequested) {
            repository.logger().info("Service", "Duplicate start ignored")
            return
        }
        trackingStartRequested = true
        startForegroundSafely(buildNotification())
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val familyId = app.preferences.familyId
        if (uid == null || familyId.isNullOrBlank()) {
            repository.logger().error(
                "Service",
                "Tracking refused: Firebase user or family context missing"
            )
            stopTracking()
            return
        }
        if (!hasForegroundLocationPermission()) {
            repository.logger().error("Service", "Tracking refused: location permission missing")
            stopTracking()
            return
        }

        app.preferences.trackingEnabled = true
        app.preferences.serviceStartedAtMs = System.currentTimeMillis()
        serviceScope.launch {
            val active = repository.activeTrip(uid)
            val recent = repository.recentSamples(uid)
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
                    .put("uid", uid)
                    .put("familyId", familyId)
                    .put("recoveredTripId", active?.id)
                    .toString()
            )
            withContext(Dispatchers.Main) {
                requestLocationUpdates()
                requestActivityUpdates()
            }
            startStopReevaluation()
        }
    }

    private fun requestLocationUpdates() {
        if (!hasForegroundLocationPermission()) return
        if (
            locationUpdatesRequested &&
            locationRequestForActiveTrip == currentTripActive
        ) return
        if (locationUpdatesRequested) {
            try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
            locationUpdatesRequested = false
        }
        val builder = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000L
        )
            .setMinUpdateIntervalMillis(2_000L)
            .setMaxUpdateDelayMillis(10_000L)
        // Idle tracking keeps the movement-triggered battery saving. Once a
        // trip is active, interval-based callbacks remain enabled so the stop
        // state machine cannot be starved while the parked phone is motionless.
        if (!currentTripActive) builder.setMinUpdateDistanceMeters(3f)
        val request = builder.build()
        locationUpdatesRequested = true
        locationRequestForActiveTrip = currentTripActive
        try {
            fused.requestLocationUpdates(request, locationCallback, mainLooper)
                .addOnFailureListener { error ->
                    locationUpdatesRequested = false
                    locationRequestForActiveTrip = null
                    repository.logger().error(
                        "Location",
                        "requestLocationUpdates failed",
                        error.toString()
                    )
                }
        } catch (error: SecurityException) {
            locationUpdatesRequested = false
            locationRequestForActiveTrip = null
            repository.logger().error("Location", "SecurityException", error.toString())
            stopTracking()
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
        val closure = detectorMutex.withLock {
            detector?.reevaluateStop(
                nowMs = now,
                activityType = app.preferences.lastActivityType,
                activityConfidence = app.preferences.lastActivityConfidence
            )
        } ?: return

        val movementEngine = movementDetector ?: MovementStateDetector(
            MovementState.fromWireValue(app.preferences.movementState),
            app.preferences.movementStateStartedAtMs
        ).also { movementDetector = it }
        val movement = movementEngine.confirmVerifiedTripEnded(closure.arrivalAtMs)
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
    }

    private suspend fun processLocation(location: Location) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val familyId = app.preferences.familyId ?: return
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
        val output = detectorMutex.withLock { engine.ingest(fix) }
        readBatteryPercentage()?.let { app.preferences.batteryPct = it }
        val nativeDriving = output.trip?.status == "active"
        val movementEngine = movementDetector ?: MovementStateDetector(
            MovementState.fromWireValue(app.preferences.movementState),
            app.preferences.movementStateStartedAtMs
        ).also { movementDetector = it }
        val movement = movementEngine.ingest(
            output.sample,
            verifiedTripActive = nativeDriving,
            verifiedTripEnded = output.transition == TripTransition.ENDED
        )
        val movementStartedAt =
            output.arrivalAtMs?.takeIf { movement.state == MovementState.STATIONARY }
                ?: movement.stateStartedAtMs
        app.preferences.movementState = movement.state.wireValue
        app.preferences.movementStateStartedAtMs = movementStartedAt
        if (output.sample.accepted) app.preferences.movementDecisionAtMs = capturedAt
        val stableOutput = output.copy(
            sample = output.sample.copy(activityType = movement.state.wireValue)
        )
        if (movement.state != MovementState.STATIONARY) {
            app.preferences.stayStartAtMs = 0
        } else if (output.arrivalAtMs != null) {
            app.preferences.stayStartAtMs = output.arrivalAtMs
        } else if (app.preferences.stayStartAtMs == 0L) {
            app.preferences.stayStartAtMs =
                movementStartedAt.takeIf { it > 0L } ?: capturedAt
        }
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
        repository.persist(stableOutput)
        app.preferences.lastFixAtMs = capturedAt
        currentSpeedKph = stableOutput.sample.displayedSpeedKph
        val tripModeChanged = currentTripActive != nativeDriving
        currentTripActive = nativeDriving
        if (tripModeChanged) {
            withContext(Dispatchers.Main) { requestLocationUpdates() }
        }
        updateNotification()
        broadcastForWebView(stableOutput)
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
        reportedAtMs: Long = System.currentTimeMillis()
    ) {
        if (!output.sample.accepted) return
        val sample = output.sample
        val payload = JSONObject()
            .put("uid", sample.uid)
            .put("lat", sample.latitude)
            .put("lng", sample.longitude)
            .put("accuracy", sample.accuracyM ?: JSONObject.NULL)
            .put("speed", sample.filteredSpeedMps ?: JSONObject.NULL)
            .put("battery", app.preferences.batteryPct ?: JSONObject.NULL)
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

    private fun stopTracking() {
        stopReevaluationJob?.cancel()
        stopReevaluationJob = null
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { activityRecognition.removeActivityUpdates(activityPendingIntent()) } catch (_: Exception) {}
        locationUpdatesRequested = false
        locationRequestForActiveTrip = null
        activityUpdatesRequested = false
        trackingStartRequested = false
        app.preferences.trackingEnabled = false
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            runBlocking(Dispatchers.IO) { repository.markTrackingStopped(uid) }
        }
        repository.logger().info("Service", "Foreground tracking stopped by user/system")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    override fun onDestroy() {
        stopReevaluationJob?.cancel()
        stopReevaluationJob = null
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        locationUpdatesRequested = false
        locationRequestForActiveTrip = null
        activityUpdatesRequested = false
        trackingStartRequested = false
        locationChannel.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
