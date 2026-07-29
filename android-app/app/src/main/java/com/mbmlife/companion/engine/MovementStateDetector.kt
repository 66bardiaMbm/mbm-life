package com.mbmlife.companion.engine

import com.mbmlife.companion.data.LocationSampleEntity

enum class MovementState(val wireValue: String) {
    STATIONARY("stationary"),
    WALKING("walking"),
    CYCLING("bicycling"),
    DRIVING("driving");

    companion object {
        fun fromWireValue(value: String?): MovementState =
            entries.firstOrNull { it.wireValue == value?.lowercase() } ?: STATIONARY
    }
}

data class MovementDecision(
    val state: MovementState,
    val stateStartedAtMs: Long,
    val changed: Boolean,
    val evidenceAccepted: Boolean,
    val reason: String
)

/**
 * Converts accepted native location samples into one persisted movement state.
 *
 * Trip state is authoritative for driving. Walking/stationary transitions need
 * multiple distinct, monotonic GPS samples and separate enter/exit windows.
 * Rejected, inaccurate, stale, missing, or ambiguous samples preserve the
 * current state and never reset a candidate transition.
 */
class MovementStateDetector(
    initialState: MovementState = MovementState.STATIONARY,
    initialStateStartedAtMs: Long = 0L
) {
    companion object {
        const val MAX_DECISION_ACCURACY_M = 35f
        const val WALK_ENTER_SPEED_MPS = 0.65
        const val WALK_KEEP_SPEED_MPS = 0.35
        const val DRIVE_SPEED_MPS = 2.8
        const val WALK_CONFIRM_SAMPLES = 3
        const val WALK_CONFIRM_MS = 8_000L
        const val STATIONARY_CONFIRM_SAMPLES = 4
        const val STATIONARY_CONFIRM_MS = 20_000L
        const val ACTIVITY_HINT_CONFIDENCE = 60
        const val FALLBACK_WINDOW_MS = 20_000L
        const val FALLBACK_MIN_NET_DISTANCE_M = 8.0
        const val FALLBACK_MIN_DIRECTION_RATIO = 0.55
    }

    private var current = initialState
    private var currentStartedAtMs = initialStateStartedAtMs
    private var lastSampleAtMs = 0L
    private var candidate: MovementState? = null
    private var candidateSinceMs = 0L
    private var candidateSamples = 0
    private val credibleHistory = ArrayDeque<LocationSampleEntity>()

    fun ingest(
        sample: LocationSampleEntity,
        verifiedTripActive: Boolean,
        verifiedTripEnded: Boolean = false
    ): MovementDecision {
        if (!sample.accepted) return unchanged(false, "location_rejected")
        if (sample.capturedAtMs <= lastSampleAtMs) return unchanged(false, "non_monotonic_sample")
        lastSampleAtMs = sample.capturedAtMs

        if (verifiedTripActive) {
            return commit(MovementState.DRIVING, sample.capturedAtMs, "verified_trip_active")
        }
        if (verifiedTripEnded && current == MovementState.DRIVING) {
            return commit(MovementState.STATIONARY, sample.capturedAtMs, "verified_trip_ended")
        }
        if (sample.accuracyM == null || sample.accuracyM > MAX_DECISION_ACCURACY_M) {
            return unchanged(false, "accuracy_not_credible")
        }

        credibleHistory.addLast(sample)
        while (
            credibleHistory.firstOrNull()?.capturedAtMs?.let {
                sample.capturedAtMs - it > FALLBACK_WINDOW_MS
            } == true
        ) {
            credibleHistory.removeFirst()
        }

        val observedSpeed = sample.rawSpeedMps?.toDouble()
            ?: sample.fallbackSpeedMps
            ?: sample.filteredSpeedMps
            ?: return unchanged(false, "speed_unavailable")
        val walkingHint = sample.activityConfidence >= ACTIVITY_HINT_CONFIDENCE &&
            sample.activityType in setOf("WALKING", "ON_FOOT", "RUNNING")
        val cyclingHint = sample.activityConfidence >= ACTIVITY_HINT_CONFIDENCE &&
            sample.activityType == "ON_BICYCLE"
        val stillHint = sample.activityConfidence >= ACTIVITY_HINT_CONFIDENCE &&
            sample.activityType == "STILL"
        val rawSpeedAvailable = sample.rawSpeedMps != null
        val fallbackMovementCredible = fallbackMovementIsCredible()
        val walkingSpeedEvidence =
            observedSpeed >= WALK_ENTER_SPEED_MPS &&
                (rawSpeedAvailable || fallbackMovementCredible)
        val stationaryEvidence = observedSpeed <= WALK_KEEP_SPEED_MPS ||
            (!rawSpeedAvailable && !fallbackMovementCredible && !walkingHint)

        val proposed = when {
            observedSpeed >= DRIVE_SPEED_MPS ->
                // Speed alone does not start a trip or publish Driving. The
                // DrivingDetector must first verify and open a real trip.
                if (cyclingHint) MovementState.CYCLING else null
            cyclingHint && observedSpeed >= WALK_KEEP_SPEED_MPS -> MovementState.CYCLING
            walkingSpeedEvidence -> MovementState.WALKING
            walkingHint && observedSpeed >= WALK_KEEP_SPEED_MPS -> MovementState.WALKING
            stationaryEvidence && !walkingHint -> MovementState.STATIONARY
            stillHint && observedSpeed < WALK_ENTER_SPEED_MPS -> MovementState.STATIONARY
            else -> null
        } ?: return unchanged(true, "ambiguous_evidence")

        if (proposed == current) {
            clearCandidate()
            return commit(current, sample.capturedAtMs, "state_confirmed")
        }

        if (candidate != proposed) {
            candidate = proposed
            candidateSinceMs = sample.capturedAtMs
            candidateSamples = 1
            return unchanged(true, "candidate_started")
        }
        candidateSamples += 1

        val movingHumanPowered =
            proposed == MovementState.WALKING || proposed == MovementState.CYCLING
        val requiredSamples =
            if (movingHumanPowered) WALK_CONFIRM_SAMPLES else STATIONARY_CONFIRM_SAMPLES
        val requiredMs =
            if (movingHumanPowered) WALK_CONFIRM_MS else STATIONARY_CONFIRM_MS
        return if (
            candidateSamples >= requiredSamples &&
            sample.capturedAtMs - candidateSinceMs >= requiredMs
        ) {
            commit(proposed, sample.capturedAtMs, "candidate_confirmed")
        } else {
            unchanged(true, "candidate_pending")
        }
    }

    fun confirmVerifiedTripEnded(arrivalAtMs: Long): MovementDecision =
        commit(
            MovementState.STATIONARY,
            arrivalAtMs.coerceAtLeast(0L),
            "verified_trip_ended_by_activity_timer"
        )

    private fun commit(
        next: MovementState,
        atMs: Long,
        reason: String
    ): MovementDecision {
        val changed = current != next
        if (changed) {
            current = next
            currentStartedAtMs = atMs
        } else if (currentStartedAtMs <= 0L) {
            currentStartedAtMs = atMs
        }
        clearCandidate()
        return MovementDecision(current, currentStartedAtMs, changed, true, reason)
    }

    private fun unchanged(evidenceAccepted: Boolean, reason: String) =
        MovementDecision(current, currentStartedAtMs, false, evidenceAccepted, reason)

    private fun clearCandidate() {
        candidate = null
        candidateSinceMs = 0L
        candidateSamples = 0
    }

    private fun fallbackMovementIsCredible(): Boolean {
        if (credibleHistory.size < 3) return false
        val first = credibleHistory.first()
        val last = credibleHistory.last()
        val net = Geo.distanceM(
            first.latitude,
            first.longitude,
            last.latitude,
            last.longitude
        )
        if (net < FALLBACK_MIN_NET_DISTANCE_M) return false
        val path = credibleHistory.zipWithNext().sumOf { (a, b) ->
            Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return path > 0.0 && net / path >= FALLBACK_MIN_DIRECTION_RATIO
    }
}
