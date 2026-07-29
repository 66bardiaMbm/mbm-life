package com.mbmlife.companion.engine

import com.mbmlife.companion.data.LocationSampleEntity
import com.mbmlife.companion.data.TripEntity
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class RawLocationFix(
    val uid: String,
    val familyId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val capturedAtMs: Long,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean,
    val activityType: String,
    val activityConfidence: Int
)

enum class TripTransition { NONE, STARTED, UPDATED, ENDED }

data class DrivingOutput(
    val sample: LocationSampleEntity,
    val transition: TripTransition,
    val trip: TripEntity?,
    val startWindow: List<LocationSampleEntity> = emptyList(),
    val arrivalAtMs: Long? = null
)

data class TimedStopClosure(
    val trip: TripEntity,
    val lastSample: LocationSampleEntity,
    val arrivalAtMs: Long
)

/**
 * Native-only driving state machine. It never reads WebView state and never
 * fabricates coordinates. All timing comes from provider capture timestamps.
 */
class DrivingDetector(
    activeTrip: TripEntity? = null,
    recentSamples: List<LocationSampleEntity> = emptyList()
) {
    companion object {
        const val MAX_ACCURACY_M = 100f
        const val MAX_SPEED_MPS = 55.0
        const val DRIVE_ENTER_MPS = 2.8
        const val DRIVE_EXIT_MPS = 1.0
        const val START_HYSTERESIS_MS = 15_000L
        const val START_GAP_TOLERANCE_MS = 8_000L
        const val END_HYSTERESIS_MS = 90_000L
        const val PRE_WINDOW_MS = 30_000L
        const val STOP_WINDOW_MS = 30_000L
        const val STOP_NET_DISTANCE_M = 25.0
        const val STOP_EVIDENCE_GAP_TOLERANCE_MS = 45_000L
        const val STOP_LOW_SPEED_MPS = 1.5
        const val STOP_LOW_SPEED_RATIO = 0.75
        const val STOP_MIN_SAMPLES = 4
        const val STOP_MIN_WINDOW_MS = 15_000L
        const val TIMED_STOP_ACTIVITY_CONFIDENCE = 75
        const val TIMED_STOP_MAX_FIX_AGE_MS = 10 * 60_000L
        const val FILTER_ALPHA = 0.45
    }

    private var active: TripEntity? = activeTrip
    private var lastAccepted: LocationSampleEntity? = recentSamples.maxByOrNull { it.capturedAtMs }
    private val tail = ArrayDeque<LocationSampleEntity>().apply {
        recentSamples.sortedBy { it.capturedAtMs }.takeLast(120).forEach(::addLast)
    }
    private val preWindow = ArrayDeque<LocationSampleEntity>()
    private var filteredSpeed: Double? = lastAccepted?.filteredSpeedMps
    private var driveCandidateSince: Long? = null
    private var lastDriveEvidenceAt: Long? = null
    private var stopCandidateSince: Long? = null
    private var lastStopEvidenceAt: Long? = null

    init {
        val latest = tail.lastOrNull()
        if (active != null && latest != null && isSustainedStopEvidence(latest)) {
            stopCandidateSince = max(active!!.startedAtMs, latest.capturedAtMs - STOP_WINDOW_MS)
            lastStopEvidenceAt = latest.capturedAtMs
        }
    }

    fun ingest(fix: RawLocationFix): DrivingOutput {
        val rejection = rejectionReason(fix)
        if (rejection != null) {
            return DrivingOutput(
                sample = fix.toEntity(
                    sessionId = active?.id,
                    rawSpeed = validRawSpeed(fix.speedMps),
                    fallback = null,
                    filtered = filteredSpeed,
                    accepted = false,
                    rejectionReason = rejection
                ),
                transition = TripTransition.NONE,
                trip = active
            )
        }

        val previous = lastAccepted
        val fallback = fallbackSpeed(previous, fix)
        val raw = validRawSpeed(fix.speedMps)
        val input = raw ?: fallback
        if (input != null) {
            filteredSpeed = if (filteredSpeed == null) input
            else FILTER_ALPHA * input + (1.0 - FILTER_ALPHA) * filteredSpeed!!
        }

        var sample = fix.toEntity(
            sessionId = active?.id,
            rawSpeed = raw,
            fallback = fallback,
            filtered = filteredSpeed,
            accepted = true,
            rejectionReason = null
        )
        lastAccepted = sample
        tail.addLast(sample)
        trimTail(fix.capturedAtMs)

        if (active == null) {
            preWindow.addLast(sample)
            while (preWindow.firstOrNull()?.capturedAtMs?.let {
                    fix.capturedAtMs - it > PRE_WINDOW_MS
                } == true
            ) {
                preWindow.removeFirst()
            }

            val drivingEvidence = isDrivingEvidence(sample)
            if (drivingEvidence) {
                if (driveCandidateSince == null) driveCandidateSince = fix.capturedAtMs
                lastDriveEvidenceAt = fix.capturedAtMs
            } else if (
                lastDriveEvidenceAt == null ||
                fix.capturedAtMs - lastDriveEvidenceAt!! > START_GAP_TOLERANCE_MS
            ) {
                driveCandidateSince = null
                lastDriveEvidenceAt = null
            }

            val candidateSince = driveCandidateSince
            if (
                candidateSince != null &&
                fix.capturedAtMs - candidateSince >= START_HYSTERESIS_MS
            ) {
                val credible = preWindow.filter { it.capturedAtMs >= candidateSince }
                val startSample = credible.firstOrNull() ?: sample
                val tripId = "trip_${fix.uid}_${startSample.capturedAtMs}_${UUID.randomUUID().toString().take(8)}"
                val startWindow = credible.map { it.copy(sessionId = tripId) }
                sample = sample.copy(sessionId = tripId)
                lastAccepted = sample
                active = TripEntity(
                    id = tripId,
                    memberId = fix.uid,
                    familyId = fix.familyId,
                    startedAtMs = startSample.capturedAtMs,
                    endedAtMs = null,
                    status = "active",
                    startLat = startSample.latitude,
                    startLng = startSample.longitude,
                    endLat = sample.latitude,
                    endLng = sample.longitude,
                    distanceM = distanceAcross(startWindow),
                    durationSec = max(0, (sample.capturedAtMs - startSample.capturedAtMs) / 1000),
                    movingDurationSec = movingDuration(startWindow),
                    avgSpeedMps = averageSpeed(distanceAcross(startWindow), movingDuration(startWindow)),
                    maxSpeedMps = startWindow.maxOfOrNull { it.filteredSpeedMps ?: 0.0 } ?: 0.0,
                    sampleCount = startWindow.size,
                    eventCount = 0,
                    closeReason = null,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis()
                )
                driveCandidateSince = null
                lastDriveEvidenceAt = null
                return DrivingOutput(sample, TripTransition.STARTED, active, startWindow)
            }
            return DrivingOutput(sample, TripTransition.NONE, null)
        }

        val current = updateTrip(active!!, previous, sample)
        active = current
        val stopped = isSustainedStopEvidence(sample)
        if (stopped) {
            if (stopCandidateSince == null) {
                stopCandidateSince = max(
                    current.startedAtMs,
                    fix.capturedAtMs - STOP_MIN_WINDOW_MS
                )
            }
            lastStopEvidenceAt = fix.capturedAtMs
        } else if (
            lastStopEvidenceAt == null ||
            fix.capturedAtMs - lastStopEvidenceAt!! > STOP_EVIDENCE_GAP_TOLERANCE_MS
        ) {
            stopCandidateSince = null
            lastStopEvidenceAt = null
        }

        if (
            stopped &&
            stopCandidateSince != null &&
            fix.capturedAtMs - stopCandidateSince!! >= END_HYSTERESIS_MS
        ) {
            val arrivalAt = stopCandidateSince!!
            val ended = current.copy(
                endedAtMs = arrivalAt,
                status = "ended",
                durationSec = max(0, (arrivalAt - current.startedAtMs) / 1000),
                closeReason = "sustained_stop",
                updatedAtMs = System.currentTimeMillis()
            )
            active = null
            stopCandidateSince = null
            lastStopEvidenceAt = null
            preWindow.clear()
            return DrivingOutput(
                sample = sample,
                transition = TripTransition.ENDED,
                trip = ended,
                arrivalAtMs = arrivalAt
            )
        }
        return DrivingOutput(sample, TripTransition.UPDATED, current)
    }

    /**
     * Advances an already-established stop candidate using monotonic wall-clock
     * evidence from Activity Recognition when Fused Location pauses callbacks.
     * It never creates a coordinate or treats missing GPS as proof of stopping:
     * accepted low-speed location samples must have established the candidate
     * first, and a fresh high-confidence STILL activity must confirm it.
     */
    fun reevaluateStop(
        nowMs: Long,
        activityType: String,
        activityConfidence: Int
    ): TimedStopClosure? {
        val current = active ?: return null
        val candidateAt = stopCandidateSince ?: return null
        val lastEvidenceAt = lastStopEvidenceAt ?: return null
        val last = lastAccepted ?: return null
        if (activityType != "STILL" || activityConfidence < TIMED_STOP_ACTIVITY_CONFIDENCE) {
            return null
        }
        if (nowMs < candidateAt || nowMs - candidateAt < END_HYSTERESIS_MS) return null
        if (nowMs < lastEvidenceAt || nowMs - lastEvidenceAt > TIMED_STOP_MAX_FIX_AGE_MS) {
            return null
        }
        val ended = current.copy(
            endedAtMs = candidateAt,
            status = "ended",
            endLat = last.latitude,
            endLng = last.longitude,
            durationSec = max(0, (candidateAt - current.startedAtMs) / 1000),
            closeReason = "sustained_stop_activity_timer",
            updatedAtMs = nowMs
        )
        active = null
        stopCandidateSince = null
        lastStopEvidenceAt = null
        preWindow.clear()
        return TimedStopClosure(ended, last, candidateAt)
    }

    private fun rejectionReason(fix: RawLocationFix): String? {
        if (fix.isMock) return "mock_location"
        if (fix.accuracyM != null && fix.accuracyM > MAX_ACCURACY_M) return "accuracy"
        val previous = lastAccepted
        if (previous != null) {
            if (fix.capturedAtMs <= previous.capturedAtMs) return "non_monotonic_time"
            val dt = (fix.capturedAtMs - previous.capturedAtMs) / 1000.0
            if (dt > 0 && dt <= 30) {
                val implied = Geo.distanceM(
                    previous.latitude,
                    previous.longitude,
                    fix.latitude,
                    fix.longitude
                ) / dt
                if (implied > MAX_SPEED_MPS) return "impossible_position_spike"
            }
        }
        val raw = fix.speedMps?.toDouble()
        if (raw != null && raw > MAX_SPEED_MPS) return "impossible_speed_spike"
        return null
    }

    private fun validRawSpeed(speed: Float?): Double? =
        speed?.toDouble()?.takeIf { it >= 0.0 && it <= MAX_SPEED_MPS }

    private fun fallbackSpeed(
        previous: LocationSampleEntity?,
        fix: RawLocationFix
    ): Double? {
        if (previous == null) return null
        val dt = (fix.capturedAtMs - previous.capturedAtMs) / 1000.0
        if (dt <= 0 || dt > 30) return null
        val measuredDistance = Geo.distanceM(
            previous.latitude,
            previous.longitude,
            fix.latitude,
            fix.longitude
        )
        // When the provider has no speed, ordinary movement inside the two
        // fixes' accuracy envelope is GPS uncertainty, not real motion.
        // Subtract that uncertainty before deriving distance/time speed so a
        // stationary phone cannot keep or start a trip from coordinate noise.
        val accuracyEnvelope = max(
            5.0,
            ((previous.accuracyM?.toDouble() ?: 0.0) +
                (fix.accuracyM?.toDouble() ?: 0.0)) / 2.0
        )
        val implied = (measuredDistance - accuracyEnvelope).coerceAtLeast(0.0) / dt
        return implied.takeIf { it in 0.0..MAX_SPEED_MPS }
    }

    private fun RawLocationFix.toEntity(
        sessionId: String?,
        rawSpeed: Double?,
        fallback: Double?,
        filtered: Double?,
        accepted: Boolean,
        rejectionReason: String?
    ) = LocationSampleEntity(
        id = "${uid}_${capturedAtMs}_${elapsedRealtimeNanos}",
        sessionId = sessionId,
        uid = uid,
        familyId = familyId,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        rawSpeedMps = rawSpeed?.toFloat(),
        fallbackSpeedMps = fallback,
        filteredSpeedMps = filtered,
        displayedSpeedKph = ((filtered ?: 0.0) * 3.6).roundToInt().coerceAtLeast(0),
        bearingDeg = bearingDeg,
        altitudeM = altitudeM,
        capturedAtMs = capturedAtMs,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        activityType = activityType,
        activityConfidence = activityConfidence,
        isMock = isMock,
        accepted = accepted,
        rejectionReason = rejectionReason
    )

    private fun isDrivingEvidence(sample: LocationSampleEntity): Boolean {
        val speed = sample.filteredSpeedMps ?: return false
        val vehicleHint = sample.activityType == "IN_VEHICLE" && sample.activityConfidence >= 60
        return speed >= DRIVE_ENTER_MPS || (vehicleHint && speed >= 1.5)
    }

    private fun isSustainedStopEvidence(sample: LocationSampleEntity): Boolean {
        if ((sample.rawSpeedMps?.toDouble() ?: 0.0) >= DRIVE_ENTER_MPS) return false
        val cutoff = sample.capturedAtMs - STOP_WINDOW_MS
        val recent = tail.filter { it.capturedAtMs >= cutoff && it.accepted }
        if (recent.size < STOP_MIN_SAMPLES) return false
        val samplesWithSpeed = recent.mapNotNull { candidate ->
            (candidate.rawSpeedMps?.toDouble() ?: candidate.filteredSpeedMps)
                ?.let { speed -> candidate to speed }
        }
        val speeds = samplesWithSpeed.map { it.second }
        if (speeds.size < STOP_MIN_SAMPLES) return false
        val lowSpeedRatio = speeds.count { it <= STOP_LOW_SPEED_MPS }.toDouble() / speeds.size
        if (lowSpeedRatio < STOP_LOW_SPEED_RATIO) return false
        val lowSpeedSamples = samplesWithSpeed
            .filter { it.second <= STOP_LOW_SPEED_MPS }
            .map { it.first }
        if (lowSpeedSamples.size < STOP_MIN_SAMPLES) return false
        val first = lowSpeedSamples.first()
        if (sample.capturedAtMs - first.capturedAtMs < STOP_MIN_WINDOW_MS) return false
        val distance = Geo.distanceM(
            first.latitude,
            first.longitude,
            sample.latitude,
            sample.longitude
        )
        val accuracyAllowance =
            (first.accuracyM?.toDouble() ?: 0.0) +
                (sample.accuracyM?.toDouble() ?: 0.0) + 8.0
        return distance <= max(STOP_NET_DISTANCE_M, accuracyAllowance)
    }

    private fun updateTrip(
        trip: TripEntity,
        previous: LocationSampleEntity?,
        sample: LocationSampleEntity
    ): TripEntity {
        var distance = trip.distanceM
        var movingSec = trip.movingDurationSec
        if (previous != null && previous.sessionId == trip.id) {
            val dt = (sample.capturedAtMs - previous.capturedAtMs) / 1000.0
            if (dt in 0.0..30.0) {
                val segment = Geo.distanceM(
                    previous.latitude,
                    previous.longitude,
                    sample.latitude,
                    sample.longitude
                )
                val implied = if (dt > 0) segment / dt else 0.0
                if (implied <= MAX_SPEED_MPS) {
                    distance += segment
                    if (implied > 0.3) movingSec += dt.toLong()
                }
            }
        }
        val maxSpeed = max(trip.maxSpeedMps, sample.filteredSpeedMps ?: 0.0)
        val eventIncrement = if (previous != null) {
            val dt = (sample.capturedAtMs - previous.capturedAtMs) / 1000.0
            val previousSpeed = previous.filteredSpeedMps
            val currentSpeed = sample.filteredSpeedMps
            if (dt > 0 && dt <= 30 && previousSpeed != null && currentSpeed != null) {
                val acceleration = (currentSpeed - previousSpeed) / dt
                if (acceleration <= -3.5 || acceleration >= 3.0) 1 else 0
            } else 0
        } else 0
        return trip.copy(
            endLat = sample.latitude,
            endLng = sample.longitude,
            distanceM = distance,
            durationSec = max(0, (sample.capturedAtMs - trip.startedAtMs) / 1000),
            movingDurationSec = movingSec,
            avgSpeedMps = averageSpeed(distance, movingSec),
            maxSpeedMps = maxSpeed,
            sampleCount = trip.sampleCount + 1,
            eventCount = trip.eventCount + eventIncrement,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun trimTail(nowMs: Long) {
        while (tail.firstOrNull()?.capturedAtMs?.let { nowMs - it > 180_000L } == true) {
            tail.removeFirst()
        }
    }

    private fun distanceAcross(samples: List<LocationSampleEntity>): Double =
        samples.zipWithNext().sumOf { (a, b) ->
            Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
        }

    private fun movingDuration(samples: List<LocationSampleEntity>): Long =
        samples.zipWithNext().sumOf { (a, b) ->
            if ((b.filteredSpeedMps ?: 0.0) > 0.3)
                max(0, (b.capturedAtMs - a.capturedAtMs) / 1000)
            else 0L
        }

    private fun averageSpeed(distanceM: Double, movingSec: Long): Double =
        if (movingSec > 0) distanceM / movingSec else 0.0
}
