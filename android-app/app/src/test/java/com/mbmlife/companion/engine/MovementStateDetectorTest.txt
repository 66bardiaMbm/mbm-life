package com.mbmlife.companion.engine

import com.mbmlife.companion.data.LocationSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementStateDetectorTest {
    @Test
    fun diagnosticSnapshotReportsCandidateWithoutChangingDecision() {
        val detector = MovementStateDetector()
        detector.ingest(sample(1_000L, speed = 1.1), false)

        val snapshot = detector.diagnosticSnapshot()

        assertEquals(MovementState.STATIONARY, snapshot.currentState)
        assertEquals(MovementState.WALKING, snapshot.candidateState)
        assertEquals(1, snapshot.candidateSamples)
        assertEquals(1_000L, snapshot.lastSampleAtMs)
    }

    @Test
    fun oneWalkingOutlierDoesNotChangeStationaryState() {
        val detector = MovementStateDetector()
        val decision = detector.ingest(sample(5_000L, speed = 1.2), false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertFalse(decision.changed)
    }

    @Test
    fun walkingNeedsConsecutiveDistinctSamplesAndElapsedTime() {
        val detector = MovementStateDetector()
        detector.ingest(sample(1_000L, speed = 1.1), false)
        detector.ingest(sample(5_000L, speed = 1.0), false)
        val decision = detector.ingest(sample(10_000L, speed = 1.2), false)

        assertEquals(MovementState.WALKING, decision.state)
        assertTrue(decision.changed)
    }

    @Test
    fun oneLowSpeedFixDoesNotEndWalking() {
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        val decision = detector.ingest(sample(5_000L, speed = 0.0), false)

        assertEquals(MovementState.WALKING, decision.state)
        assertFalse(decision.changed)
    }

    @Test
    fun stationaryNeedsSustainedLowSpeedEvidence() {
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        listOf(5_000L, 12_000L, 19_000L).forEach {
            detector.ingest(sample(it, speed = 0.1), false)
        }
        val decision = detector.ingest(sample(26_000L, speed = 0.0), false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertTrue(decision.changed)
    }

    @Test
    fun inaccurateAndOutOfOrderSamplesCannotChangeState() {
        val detector = MovementStateDetector()
        detector.ingest(sample(10_000L, speed = 0.0), false)
        val inaccurate = detector.ingest(sample(20_000L, speed = 1.2, accuracy = 80f), false)
        val outOfOrder = detector.ingest(sample(9_000L, speed = 1.2), false)

        assertEquals(MovementState.STATIONARY, inaccurate.state)
        assertEquals("accuracy_not_credible", inaccurate.reason)
        assertEquals(MovementState.STATIONARY, outOfOrder.state)
        assertEquals("non_monotonic_sample", outOfOrder.reason)
    }

    @Test
    fun verifiedTripOwnsDrivingAndVerifiedEndOwnsExit() {
        val detector = MovementStateDetector()
        val driving = detector.ingest(sample(5_000L, speed = 0.0), verifiedTripActive = true)
        val trafficLight = detector.ingest(sample(10_000L, speed = 0.0), verifiedTripActive = true)
        val ended = detector.ingest(
            sample(100_000L, speed = 0.0),
            verifiedTripActive = false,
            verifiedTripEnded = true
        )

        assertEquals(MovementState.DRIVING, driving.state)
        assertEquals(MovementState.DRIVING, trafficLight.state)
        assertEquals(MovementState.STATIONARY, ended.state)
    }

    @Test
    fun activityTimerVerifiedEndUsesPersistedArrivalTime() {
        val detector = MovementStateDetector()
        detector.ingest(sample(5_000L, speed = 8.0), verifiedTripActive = true)

        val ended = detector.confirmVerifiedTripEnded(arrivalAtMs = 25_000L)

        assertEquals(MovementState.STATIONARY, ended.state)
        assertEquals(25_000L, ended.stateStartedAtMs)
        assertEquals("verified_trip_ended_by_activity_timer", ended.reason)
    }

    @Test
    fun missingOrRejectedFixDoesNotResetCandidate() {
        val detector = MovementStateDetector()
        detector.ingest(sample(1_000L, speed = 1.0), false)
        detector.ingest(sample(3_000L, speed = 0.0, accepted = false), false)
        detector.ingest(sample(5_000L, speed = 1.0), false)
        val decision = detector.ingest(sample(10_000L, speed = 1.0), false)

        assertEquals(MovementState.WALKING, decision.state)
    }

    @Test
    fun fallbackSpeedFromStationaryNoiseDoesNotCreateWalking() {
        val detector = MovementStateDetector()
        listOf(1_000L, 5_000L, 10_000L, 15_000L).forEachIndexed { index, at ->
            detector.ingest(
                sample(
                    atMs = at,
                    speed = 1.0,
                    rawSpeed = null,
                    lat = -42.7 + (index % 2) * 0.00001
                ),
                false
            )
        }

        val decision = detector.ingest(
            sample(22_000L, speed = 1.0, rawSpeed = null, lat = -42.7),
            false
        )
        assertEquals(MovementState.STATIONARY, decision.state)
    }

    @Test
    fun directionallyConsistentFallbackMovementCanConfirmWalking() {
        val detector = MovementStateDetector()
        var decision: MovementDecision? = null
        listOf(1_000L, 5_000L, 10_000L, 15_000L, 20_000L).forEachIndexed { index, at ->
            decision = detector.ingest(
                sample(
                    atMs = at,
                    speed = 1.0,
                    rawSpeed = null,
                    lat = -42.7 + index * 0.00004
                ),
                false
            )
        }

        assertEquals(MovementState.WALKING, decision?.state)
    }

    @Test
    fun confidentBicycleActivityPublishesCyclingNotDriving() {
        val detector = MovementStateDetector()
        var decision: MovementDecision? = null
        listOf(1_000L, 5_000L, 10_000L).forEach { at ->
            decision = detector.ingest(
                sample(
                    atMs = at,
                    speed = 5.0,
                    activityType = "ON_BICYCLE",
                    activityConfidence = 90
                ),
                verifiedTripActive = false
            )
        }

        assertEquals(MovementState.CYCLING, decision?.state)
    }

    // ───────────────────────────────────────────────────────────────
    // v461: candidate_confirmed must record the FIRST continuous evidence
    // (candidateSinceMs), not the sample that happened to cross the
    // confirmation threshold. Real report: a native no-trip stationary
    // arrival was recorded up to STATIONARY_CONFIRM_MS (20s) later than the
    // person actually stopped, compounding with a separate TrackingService
    // bug (see TrackingService.kt processLocation()) into a much larger
    // real-world gap between the true arrival and what the app displayed.
    // ───────────────────────────────────────────────────────────────

    @Test
    fun noTripStationaryConfirmationRecordsFirstEvidenceNotConfirmingSample() {
        // Starting the detector already-STATIONARY would take the
        // state_confirmed path (proposed==current) on every sample, never
        // candidate_confirmed — the exact branch this fix targets. Start
        // from WALKING so confirming STATIONARY is a genuine transition.
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        val firstEvidenceAt = 100_000L
        detector.ingest(sample(firstEvidenceAt, speed = 0.1), verifiedTripActive = false)
        detector.ingest(sample(firstEvidenceAt + 6_000, speed = 0.1), verifiedTripActive = false)
        detector.ingest(sample(firstEvidenceAt + 13_000, speed = 0.1), verifiedTripActive = false)
        val decision = detector.ingest(sample(firstEvidenceAt + 21_000, speed = 0.1), verifiedTripActive = false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals("candidate_confirmed", decision.reason)
        assertEquals(firstEvidenceAt, decision.stateStartedAtMs)
    }

    @Test
    fun walkingToStationaryRecordsFirstStationaryEvidenceNotConfirmingSample() {
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        val firstEvidenceAt = 5_000L
        detector.ingest(sample(firstEvidenceAt, speed = 0.1), verifiedTripActive = false)
        detector.ingest(sample(12_000L, speed = 0.0), verifiedTripActive = false)
        detector.ingest(sample(19_000L, speed = 0.0), verifiedTripActive = false)
        val decision = detector.ingest(sample(26_000L, speed = 0.0), verifiedTripActive = false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals(firstEvidenceAt, decision.stateStartedAtMs)
        assertTrue(
            "recorded start must NOT equal the confirming sample's own timestamp",
            decision.stateStartedAtMs != 26_000L
        )
    }

    @Test
    fun restartMidCandidateDoesNotFabricateStationaryArrival() {
        // A fresh detector, seeded exactly like TrackingService re-seeds one
        // from persisted (committed) state after a process restart — the
        // in-flight, never-confirmed candidate from before the restart is
        // legitimately gone, by design (nothing persists an uncommitted
        // candidate). It must NOT be treated as if it had confirmed.
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        val decision = detector.ingest(sample(5_500L, speed = 0.1), verifiedTripActive = false)

        assertEquals(MovementState.WALKING, decision.state)
        assertFalse(decision.changed)
    }

    @Test
    fun restartLoseCandidateButNewCandidateStillConfirmsCorrectlyAfterward() {
        val detector = MovementStateDetector(MovementState.WALKING, 1_000L)
        // Post-restart: a brand new candidate must still be able to confirm
        // normally, using ITS OWN first-evidence sample as the start time.
        val firstEvidenceAt = 5_500L
        detector.ingest(sample(firstEvidenceAt, speed = 0.1), verifiedTripActive = false)
        detector.ingest(sample(13_000L, speed = 0.0), verifiedTripActive = false)
        detector.ingest(sample(20_000L, speed = 0.0), verifiedTripActive = false)
        val decision = detector.ingest(sample(27_000L, speed = 0.0), verifiedTripActive = false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals(firstEvidenceAt, decision.stateStartedAtMs)
    }

    @Test
    fun badFixesBetweenGoodEvidenceDoNotShiftTheRecordedArrivalTime() {
        val detector = MovementStateDetector()
        val firstEvidenceAt = 200_000L
        detector.ingest(sample(firstEvidenceAt, speed = 0.1), verifiedTripActive = false)
        // rejected (not accepted)
        detector.ingest(sample(firstEvidenceAt + 3_000, speed = 5.0, accepted = false), verifiedTripActive = false)
        // stale / non-monotonic
        detector.ingest(sample(firstEvidenceAt - 500, speed = 0.1), verifiedTripActive = false)
        // inaccurate
        detector.ingest(sample(firstEvidenceAt + 6_000, speed = 0.1, accuracy = 200f), verifiedTripActive = false)
        // good evidence resumes
        detector.ingest(sample(firstEvidenceAt + 13_000, speed = 0.1), verifiedTripActive = false)
        val decision = detector.ingest(sample(firstEvidenceAt + 21_000, speed = 0.1), verifiedTripActive = false)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals(
            "bad fixes in between must not shift the recorded arrival away from the true first good evidence",
            firstEvidenceAt,
            decision.stateStartedAtMs
        )
    }

    @Test
    fun tripEndedArrivalTimeIsStillPreservedExactlyAsBefore() {
        // requirement #3: the Driving Trip path must be completely
        // unaffected by the candidateSinceMs fix — it never goes through
        // the candidate_confirmed branch at all.
        val detector = MovementStateDetector()
        detector.ingest(sample(1_000L, speed = 10.0), verifiedTripActive = true)
        val arrivalAtMs = 50_000L

        val decision = detector.confirmVerifiedTripEnded(arrivalAtMs)

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals(arrivalAtMs, decision.stateStartedAtMs)
    }

    @Test
    fun verifiedTripEndedInternalTimestampIsTheClosingFixNotTheRealArrival() {
        // This is the exact fact that makes TrackingService's stayStartAtMs
        // fallback dangerous if unguarded (real bug found on review, fixed
        // by only filling a genuinely UNSET stayStartAtMs — see
        // TrackingService.kt processLocation()). ingest()'s own
        // "verified_trip_ended" branch (as opposed to
        // confirmVerifiedTripEnded(), the timer path) records the CLOSING
        // FIX's own capturedAtMs as stateStartedAtMs — NOT the trip's real
        // arrival time, which TrackingService gets separately from
        // DrivingOutput.arrivalAtMs and which can genuinely differ.
        val detector = MovementStateDetector()
        detector.ingest(sample(1_000L, speed = 10.0), verifiedTripActive = true)
        val closingFixAtMs = 55_000L
        val realArrivalAtMs = 50_000L // what DrivingOutput.arrivalAtMs would actually be — earlier than the closing fix

        val decision = detector.ingest(
            sample(closingFixAtMs, speed = 0.0),
            verifiedTripActive = false,
            verifiedTripEnded = true
        )

        assertEquals(MovementState.STATIONARY, decision.state)
        assertEquals("verified_trip_ended", decision.reason)
        assertEquals(closingFixAtMs, decision.stateStartedAtMs)
        assertTrue(
            "the detector's own internal timestamp must NOT be assumed equal to the trip's real arrivalAtMs " +
                "(they differ here: $closingFixAtMs vs $realArrivalAtMs) — a caller must use DrivingOutput.arrivalAtMs " +
                "directly for the true arrival time, never movement.stateStartedAtMs from this particular path",
            decision.stateStartedAtMs != realArrivalAtMs
        )
    }

    private fun sample(
        atMs: Long,
        speed: Double,
        accuracy: Float = 4f,
        accepted: Boolean = true,
        rawSpeed: Double? = speed,
        lat: Double = -42.7,
        lng: Double = 147.25,
        activityType: String = "UNKNOWN",
        activityConfidence: Int = 0
    ) = LocationSampleEntity(
        id = "sample-$atMs",
        sessionId = null,
        uid = "user",
        familyId = "family",
        latitude = lat,
        longitude = lng,
        accuracyM = accuracy,
        rawSpeedMps = rawSpeed?.toFloat(),
        fallbackSpeedMps = speed,
        filteredSpeedMps = speed,
        displayedSpeedKph = (speed * 3.6).toInt(),
        bearingDeg = null,
        altitudeM = null,
        capturedAtMs = atMs,
        elapsedRealtimeNanos = atMs * 1_000_000,
        activityType = activityType,
        activityConfidence = activityConfidence,
        isMock = false,
        accepted = accepted,
        rejectionReason = if (accepted) null else "test"
    )
}
