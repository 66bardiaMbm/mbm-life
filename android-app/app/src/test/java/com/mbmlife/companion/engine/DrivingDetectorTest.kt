package com.mbmlife.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingDetectorTest {
    private val uid = "test-user"
    private val familyId = "test-family"

    @Test
    fun fallbackSpeedIsCalculatedWhenProviderSpeedIsMissing() {
        val detector = DrivingDetector()
        detector.ingest(fix(timeMs = 1_000L, lat = -42.7, lng = 147.25, speed = null))
        val result = detector.ingest(
            fix(timeMs = 6_000L, lat = -42.69955, lng = 147.25, speed = null)
        )

        assertTrue(result.sample.accepted)
        assertNotNull(result.sample.fallbackSpeedMps)
        assertTrue(result.sample.displayedSpeedKph > 0)
    }

    @Test
    fun impossiblePositionSpikeIsRejected() {
        val detector = DrivingDetector()
        detector.ingest(fix(timeMs = 1_000L, lat = -42.7, lng = 147.25, speed = 0f))
        val result = detector.ingest(
            fix(timeMs = 6_000L, lat = -41.7, lng = 147.25, speed = 0f)
        )

        assertEquals(false, result.sample.accepted)
        assertEquals("impossible_position_spike", result.sample.rejectionReason)
    }

    @Test
    fun sustainedDrivingStartsTripAndShortStopDoesNotEndIt() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null
        for (seconds in listOf(0, 5, 10, 15)) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7 + seconds * 0.0001,
                    lng = 147.25,
                    speed = 10f,
                    activity = "IN_VEHICLE"
                )
            )
        }
        assertEquals(TripTransition.STARTED, output?.transition)
        val tripId = output?.trip?.id
        assertNotNull(tripId)

        for (seconds in 25..75 step 5) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.698,
                    lng = 147.25,
                    speed = 0f,
                    activity = "STILL"
                )
            )
        }
        assertEquals("active", output?.trip?.status)

        output = detector.ingest(
            fix(
                timeMs = 81_000L,
                lat = -42.6975,
                lng = 147.25,
                speed = 10f,
                activity = "IN_VEHICLE"
            )
        )
        assertEquals(TripTransition.UPDATED, output?.transition)
        assertEquals(tripId, output?.trip?.id)
    }

    @Test
    fun sustainedFinalStopEndsTrip() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null
        for (seconds in listOf(0, 5, 10, 15)) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7 + seconds * 0.0001,
                    lng = 147.25,
                    speed = 10f,
                    activity = "IN_VEHICLE"
                )
            )
        }
        assertEquals(TripTransition.STARTED, output?.transition)

        var ended: DrivingOutput? = null
        for (seconds in 25..180 step 5) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.698,
                    lng = 147.25,
                    speed = 0f,
                    activity = "STILL"
                )
            )
            if (output?.transition == TripTransition.ENDED) {
                ended = output
                break
            }
        }
        assertNotNull(ended)
        assertEquals("ended", ended?.trip?.status)
        assertEquals("sustained_stop", ended?.trip?.closeReason)
    }

    private fun fix(
        timeMs: Long,
        lat: Double,
        lng: Double,
        speed: Float?,
        activity: String = "UNKNOWN"
    ) = RawLocationFix(
        uid = uid,
        familyId = familyId,
        latitude = lat,
        longitude = lng,
        accuracyM = 4f,
        speedMps = speed,
        bearingDeg = null,
        altitudeM = null,
        capturedAtMs = timeMs,
        elapsedRealtimeNanos = timeMs * 1_000_000,
        isMock = false,
        activityType = activity,
        activityConfidence = if (activity == "UNKNOWN") 0 else 90
    )
}
