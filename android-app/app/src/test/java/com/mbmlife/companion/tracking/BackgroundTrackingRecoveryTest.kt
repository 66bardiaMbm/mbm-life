package com.mbmlife.companion.tracking

import com.mbmlife.companion.engine.DrivingDetector
import com.mbmlife.companion.engine.RawLocationFix
import com.mbmlife.companion.engine.TripTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTrackingRecoveryTest {
    @Test
    fun appClosedThenMoveThenStopThenReopenPreservesLocationAndRealArrival() {
        assertTrue(
            TrackingRecoveryPolicy.shouldKeepBackgroundDelivery(
                trackingEnabled = true,
                explicitStopRequested = false
            )
        )
        val identity = TrackingRecoveryPolicy.resolveIdentity(
            authenticatedUid = null,
            persistedUid = "test-user",
            familyId = "test-family"
        )
        assertNotNull(identity)

        // The Activity/WebView is absent for this entire movement and stop.
        val detector = DrivingDetector()
        var output = detector.ingest(fix(1_000L, -42.7000, 10f, "IN_VEHICLE"))
        output = detector.ingest(fix(6_000L, -42.6995, 10f, "IN_VEHICLE"))
        output = detector.ingest(fix(11_000L, -42.6990, 10f, "IN_VEHICLE"))
        output = detector.ingest(fix(16_000L, -42.6985, 10f, "IN_VEHICLE"))
        assertEquals(TripTransition.STARTED, output.transition)

        for (seconds in 25..180 step 5) {
            output = detector.ingest(
                fix(1_000L + seconds * 1_000L, -42.6980, 0f, "STILL")
            )
            if (output.transition == TripTransition.ENDED) break
        }
        assertEquals(TripTransition.ENDED, output.transition)
        val arrivalAtMs = output.arrivalAtMs
        assertNotNull(arrivalAtMs)

        var persistedStayStart = TrackingRecoveryPolicy.nextStayStartAtMs(
            currentStayStartAtMs = 0L,
            stationary = true,
            arrivalAtMs = arrivalAtMs,
            movementStartedAtMs = output.sample.capturedAtMs
        )
        // A later stationary fix after service/process recovery must not move
        // the already-correct arrival forward to the recovery/open time.
        persistedStayStart = TrackingRecoveryPolicy.nextStayStartAtMs(
            currentStayStartAtMs = persistedStayStart,
            stationary = true,
            arrivalAtMs = null,
            movementStartedAtMs = output.sample.capturedAtMs + 60_000L
        )

        // Values read when the Activity is reopened.
        assertEquals(arrivalAtMs, persistedStayStart)
        assertEquals(-42.6980, output.sample.latitude, 0.000001)
        assertEquals("ended", output.trip?.status)
    }

    @Test
    fun explicitStopCancelsBackgroundDelivery() {
        assertEquals(
            false,
            TrackingRecoveryPolicy.shouldKeepBackgroundDelivery(
                trackingEnabled = true,
                explicitStopRequested = true
            )
        )
    }

    private fun fix(
        timeMs: Long,
        lat: Double,
        speed: Float,
        activity: String
    ) = RawLocationFix(
        uid = "test-user",
        familyId = "test-family",
        latitude = lat,
        longitude = 147.25,
        accuracyM = 4f,
        speedMps = speed,
        bearingDeg = null,
        altitudeM = null,
        capturedAtMs = timeMs,
        elapsedRealtimeNanos = timeMs * 1_000_000L,
        isMock = false,
        activityType = activity,
        activityConfidence = 95
    )
}
