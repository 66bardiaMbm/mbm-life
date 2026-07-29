package com.mbmlife.companion.engine

import com.mbmlife.companion.data.LocationSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementStateDetectorTest {
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
