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

    // ── Background-arrival-time fix (real report: MBM recorded an
    // app-reopen time instead of the true, hours-earlier arrival time
    // after a GPS gap while the phone was stationary and backgrounded).
    // Root cause: STOP_EVIDENCE_GAP_TOLERANCE_MS (45s) wiped the stop
    // candidate on any gap that long, discarding real arrival evidence.
    // Fix: the candidate still always restarts fresh after a gap (a
    // same-place resume is NOT trusted to mean "never left" — that would
    // hide a genuine departure-and-return) but a same-place resume is
    // flagged arrivalUncertain=true rather than asserted as a precise,
    // confident time. ──

    private val homeLat = -42.8000
    private val homeLng = 147.3000
    private val awayLat = -42.9000
    private val awayLng = 147.5000

    /** Physically continuous drive from away to home (never an instant
     * teleport, which would trip the impossible-speed-spike rejection). */
    private fun driveToHome(detector: DrivingDetector, startT: Long): Long {
        var t = startT
        var lat = awayLat
        var lng = awayLng
        val steps = 40
        val dLat = (homeLat - awayLat) / steps
        val dLng = (homeLng - awayLng) / steps
        for (i in 0 until steps) {
            t += 2000
            lat += dLat
            lng += dLng
            detector.ingest(fix(t, lat, lng, 25f, "IN_VEHICLE"))
        }
        return t
    }

    /** Feeds stationary fixes until isSustainedStopEvidence's own
     * STOP_MIN_SAMPLES/STOP_MIN_WINDOW_MS requirements are satisfied. */
    private fun establishStationary(detector: DrivingDetector, startT: Long, lat: Double, lng: Double, count: Int = 20): Long {
        var t = startT
        for (i in 0 until count) {
            t += 2000
            detector.ingest(fix(t, lat, lng, 0f, "STILL"))
        }
        return t
    }

    /** Keeps feeding stationary fixes until the trip confirms ENDED. */
    private fun runUntilEnded(detector: DrivingDetector, startT: Long, lat: Double, lng: Double, maxTries: Int = 100): Pair<Long, DrivingOutput?> {
        var t = startT
        var tries = 0
        while (tries < maxTries) {
            t += 2000
            tries++
            val out = detector.ingest(fix(t, lat, lng, 0f, "STILL"))
            if (out.transition == TripTransition.ENDED) return Pair(t, out)
        }
        return Pair(t, null)
    }

    @Test
    fun continuousTrackingConfirmsArrivalAtTrueTimeNeverUncertain() {
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val trueArrivalMs = afterDrive + 2000
        val lastEstablish = establishStationary(detector, afterDrive, homeLat, homeLng)
        val (_, out) = runUntilEnded(detector, lastEstablish, homeLat, homeLng)

        assertNotNull(out)
        assertTrue(Math.abs(out!!.arrivalAtMs!! - trueArrivalMs) <= 20_000L)
        assertEquals(false, out.arrivalUncertain)
    }

    @Test
    fun reopeningWithNoRealGapDoesNotCorruptOrDelayArrival() {
        // Simulates: a candidate already mid-accumulation, then the SAME
        // detector instance keeps receiving fixes without interruption —
        // exactly what continuous background tracking means. Reopening
        // the UI does not change what ingest() sees.
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val trueArrivalMs = afterDrive + 2000
        val lastEstablish = establishStationary(detector, afterDrive, homeLat, homeLng, count = 5)
        val (_, out) = runUntilEnded(detector, lastEstablish, homeLat, homeLng)

        assertNotNull(out)
        assertTrue(Math.abs(out!!.arrivalAtMs!! - trueArrivalMs) <= 20_000L)
        assertEquals(false, out.arrivalUncertain)
    }

    @Test
    fun gpsGapWhileStationaryIsHonestlyFlaggedUncertainNotSilentlyWrong() {
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val lastEstablish = establishStationary(detector, afterDrive, homeLat, homeLng)
        val gapMs = 51 * 60_000L
        val (_, out) = runUntilEnded(detector, lastEstablish + gapMs, homeLat, homeLng)

        assertNotNull(out)
        assertEquals(true, out!!.arrivalUncertain)
    }

    @Test
    fun genuineObservedDepartureAndReturnGetsNewArrivalNotFlaggedUncertain() {
        // Critical case: a REAL departure and return, with the drive
        // itself actually observed (only the STATIONARY periods had
        // gaps — driving generates frequent GPS activity, unlike a
        // parked phone) must still produce a NEW, confident arrival —
        // never silently reuse the old one.
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val firstStopMs = establishStationary(detector, afterDrive, homeLat, homeLng)
        val (endedAtFirst, firstOut) = runUntilEnded(detector, firstStopMs, homeLat, homeLng)
        assertNotNull(firstOut)
        val firstArrival = firstOut!!.arrivalAtMs!!

        val gapMs = 20 * 60_000L
        val departT = driveToHome(detector, endedAtFirst + gapMs)
        val secondStopMs = establishStationary(detector, departT, homeLat, homeLng)
        val (_, secondOut) = runUntilEnded(detector, secondStopMs, homeLat, homeLng)

        assertNotNull(secondOut)
        val secondArrival = secondOut!!.arrivalAtMs!!
        assertTrue(secondArrival > firstArrival + 60_000L)
        assertEquals(false, secondOut.arrivalUncertain)
    }

    @Test
    fun totalBlackoutAcrossAPossibleRoundTripFabricatesNoSecondConfirmation() {
        // The genuinely unanswerable case: zero fixes for the ENTIRE
        // possible round trip, including the drive itself. With no
        // driving evidence ever arriving, ingest() has no trip to close
        // a second time — this documents that limit rather than
        // fabricating a confirmation in either direction.
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val firstStopMs = establishStationary(detector, afterDrive, homeLat, homeLng)
        val (endedAtFirst, firstOut) = runUntilEnded(detector, firstStopMs, homeLat, homeLng)
        assertNotNull(firstOut)

        val gapMs = 90 * 60_000L
        var t = endedAtFirst + gapMs
        var sawEnded = false
        for (i in 0 until 30) {
            t += 2000
            val out = detector.ingest(fix(t, homeLat, homeLng, 0f, "STILL"))
            if (out.transition == TripTransition.ENDED) sawEnded = true
        }
        assertEquals(false, sawEnded)
    }

    @Test
    fun gapEndingAtAClearlyDifferentPlaceIsUnambiguousNotFlaggedUncertain() {
        val detector = DrivingDetector()
        val afterDrive = driveToHome(detector, 0L)
        val lastEstablish = establishStationary(detector, afterDrive, homeLat, homeLng)
        val gapMs = 51 * 60_000L
        val elsewhereLat = homeLat + 0.01 // ~1.1km away — clearly not the same spot
        val (_, out) = runUntilEnded(detector, lastEstablish + gapMs, elsewhereLat, homeLng)

        assertNotNull(out)
        assertEquals(false, out!!.arrivalUncertain)
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
