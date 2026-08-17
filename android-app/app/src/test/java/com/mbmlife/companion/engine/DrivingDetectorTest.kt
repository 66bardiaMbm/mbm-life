package com.mbmlife.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class ObservedFix(
    val offsetMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val rawSpeedMps: Float
)

class DrivingDetectorTest {
    private val uid = "test-user"
    private val familyId = "test-family"

    @Test
    fun diagnosticSnapshotReportsDrivingCandidateAndLastFix() {
        val detector = DrivingDetector()
        val output = detector.ingest(
            fix(timeMs = 1_000L, lat = -42.7, lng = 147.25, speed = 10f)
        )

        val snapshot = detector.diagnosticSnapshot()

        assertEquals(output.sample.id, snapshot.lastAcceptedFixId)
        assertEquals(1_000L, snapshot.lastAcceptedAtMs)
        assertEquals(1_000L, snapshot.driveCandidateSinceMs)
        assertEquals(null, snapshot.activeTripId)
    }

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
    fun providerSpeedMissingStationaryAccuracyNoiseStaysAtZero() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null
        for (seconds in 0..120 step 5) {
            val jitter = if ((seconds / 5) % 2 == 0) 0.000025 else -0.000025
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7 + jitter,
                    lng = 147.25,
                    speed = null,
                    activity = "STILL"
                )
            )
        }

        assertEquals(0, output?.sample?.displayedSpeedKph)
        assertEquals(null, output?.trip)
        assertEquals(TripTransition.NONE, output?.transition)
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
        assertNotNull(ended?.arrivalAtMs)
        assertEquals(ended?.arrivalAtMs, ended?.trip?.endedAtMs)
    }

    @Test
    fun activityTimerEndsOnlyAnEstablishedStopCandidateWithoutNewGps() {
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

        for (seconds in listOf(25, 30, 35, 40)) {
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

        assertEquals(
            null,
            detector.reevaluateStop(
                nowMs = 115_000L,
                activityType = "IN_VEHICLE",
                activityConfidence = 95
            )
        )
        val closed = detector.reevaluateStop(
            nowMs = 116_000L,
            activityType = "STILL",
            activityConfidence = 95
        )
        assertNotNull(closed)
        assertEquals("ended", closed?.trip?.status)
        assertEquals("sustained_stop_activity_timer", closed?.trip?.closeReason)
        assertEquals(closed?.arrivalAtMs, closed?.trip?.endedAtMs)
    }

    @Test
    fun oneNoisySpeedFixDoesNotKeepStoppedTripActiveForever() {
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
        for (seconds in 25..200 step 5) {
            val noisyFix = seconds == 80
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.698 + if (noisyFix) 0.00002 else 0.0,
                    lng = 147.25,
                    speed = if (noisyFix) 3.2f else 0f,
                    activity = "STILL"
                )
            )
            if (output?.transition == TripTransition.ENDED) {
                ended = output
                break
            }
        }

        assertNotNull(ended)
        assertEquals("sustained_stop", ended?.trip?.closeReason)
    }

    @Test
    fun stationaryDeviceTraceWithRepeatedRawSpeedSpikesEventuallyEndsTrip() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null

        // Establish a real trip through the public ingest path before replaying
        // the stationary device trace captured at 02:42 on 17 August 2026.
        for (seconds in listOf(0, 5, 10, 15)) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7790 + seconds * 0.0001,
                    lng = 147.0546,
                    speed = 10f,
                    activity = "IN_VEHICLE"
                )
            )
        }
        assertEquals(TripTransition.STARTED, output?.transition)

        val observed = listOf(
            ObservedFix(0L, -42.777185, 147.054536, 24f, 1.59f),
            ObservedFix(995L, -42.777052, 147.054422, 26f, 6.10f),
            ObservedFix(1_992L, -42.777015, 147.054484, 25f, 4.77f),
            ObservedFix(2_990L, -42.777057, 147.054503, 26f, 1.55f),
            ObservedFix(3_998L, -42.777139, 147.054467, 27f, 0.47f),
            // The screenshot cuts off the raw speed at 02:42:16, so that row
            // is deliberately omitted instead of inventing a value.
            ObservedFix(6_004L, -42.777152, 147.054435, 29f, 0.76f),
            ObservedFix(6_984L, -42.777513, 147.054155, 30f, 10.23f),
            ObservedFix(7_987L, -42.777566, 147.054116, 30f, 9.39f),
            ObservedFix(8_997L, -42.777565, 147.054087, 30f, 7.41f)
        )

        var ended: DrivingOutput? = null
        val stationaryStartMs = 31_925L
        for (cycle in 0..12) {
            // Synthetic continuation: repeat the observed stationary jitter
            // pattern long enough to exercise the 90-second end hysteresis.
            for (point in observed) {
                output = detector.ingest(
                    fix(
                        timeMs = stationaryStartMs + cycle * 10_000L + point.offsetMs,
                        lat = point.lat,
                        lng = point.lng,
                        speed = point.rawSpeedMps,
                        accuracy = point.accuracyM
                    )
                )
                if (output?.transition == TripTransition.ENDED) {
                    ended = output
                    break
                }
            }
            if (ended != null) break
        }

        assertNotNull(ended)
        assertEquals("sustained_stop", ended?.trip?.closeReason)
    }

    @Test
    fun sustainedRealDisplacementDoesNotEndActiveTripUnderWeakAccuracy() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null
        for (seconds in listOf(0, 5, 10, 15)) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7790 + seconds * 0.0001,
                    lng = 147.0546,
                    speed = 10f,
                    activity = "IN_VEHICLE",
                    accuracy = 30f
                )
            )
        }
        assertEquals(TripTransition.STARTED, output?.transition)

        for (seconds in 20..160 step 5) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    // About 55 m every five seconds: sustained real movement
                    // without crossing the detector's impossible-spike guard.
                    lat = -42.7775 + ((seconds - 20) / 5) * 0.0005,
                    lng = 147.0546,
                    speed = 10f,
                    activity = "IN_VEHICLE",
                    accuracy = 30f
                )
            )
            assertEquals(TripTransition.UPDATED, output?.transition)
            assertEquals("active", output?.trip?.status)
        }
    }

    @Test
    fun bicycleActivityDoesNotOpenCarTripAtDrivingSpeed() {
        val detector = DrivingDetector()
        var output: DrivingOutput? = null
        for (seconds in listOf(0, 5, 10, 15, 20, 25)) {
            output = detector.ingest(
                fix(
                    timeMs = 1_000L + seconds * 1_000L,
                    lat = -42.7 + seconds * 0.00005,
                    lng = 147.25,
                    speed = 6f,
                    activity = "ON_BICYCLE"
                )
            )
        }

        assertEquals(null, output?.trip)
        assertEquals(TripTransition.NONE, output?.transition)
    }

    private fun fix(
        timeMs: Long,
        lat: Double,
        lng: Double,
        speed: Float?,
        activity: String = "UNKNOWN",
        accuracy: Float = 4f
    ) = RawLocationFix(
        uid = uid,
        familyId = familyId,
        latitude = lat,
        longitude = lng,
        accuracyM = accuracy,
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
