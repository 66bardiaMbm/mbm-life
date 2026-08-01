package com.mbmlife.companion.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mbmlife.companion.MbmApplication
import com.mbmlife.companion.engine.DrivingOutput
import com.mbmlife.companion.engine.TripTransition
import com.mbmlife.companion.sync.SyncWorker
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class TrackingRepository(private val context: Context) {
    private val app = context.applicationContext as MbmApplication
    private val db = app.database
    private val dao = db.trackingDao()
    private val logger = DiagnosticLogger(dao)

    suspend fun activeTrip(uid: String) = dao.activeTrip(uid)
    suspend fun recentSamples(uid: String, limit: Int = 120) =
        dao.recentSamples(uid, limit).reversed()
    suspend fun latestAccepted(uid: String) = dao.latestAcceptedSample(uid)

    suspend fun persist(output: DrivingOutput) {
        val previous = dao.latestAcceptedSample(output.sample.uid)
        val detectedEvent = detectDrivingEvent(previous, output.sample, output.trip)
        val tripForStorage = output.trip
        db.withTransaction {
            if (output.startWindow.isNotEmpty()) dao.upsertSamples(output.startWindow)
            dao.upsertSample(output.sample)
            tripForStorage?.let { trip ->
                dao.upsertTrip(trip)
                dao.upsertOutbox(outboxForTrip(trip))
            }
            detectedEvent?.let { dao.upsertOutbox(it) }
            if (output.sample.accepted) {
                dao.upsertOutbox(outboxForLocation(output.sample, tripForStorage, true))
            }
        }

        tripForStorage?.let { trip ->
            // Persist route chunks during the drive as well as at completion.
            // Chunk documents are deterministic and merged, so this safely
            // replaces the current partial chunk without duplicating points.
            // A crash/restart therefore loses at most the latest 25 accepted
            // samples instead of the entire route.
            if (
                output.transition != TripTransition.NONE ||
                (output.sample.accepted && trip.status == "active" && trip.sampleCount % 25 == 0)
            ) {
                queueSampleChunks(trip.id)
            }
        }
        dao.deleteOldPreTripSamples(System.currentTimeMillis() - 10 * 60_000L)
        scheduleSync()
    }

    suspend fun persistTimedStop(
        trip: TripEntity,
        lastAcceptedSample: LocationSampleEntity
    ) {
        val stationaryLocation = lastAcceptedSample.copy(
            rawSpeedMps = 0f,
            fallbackSpeedMps = 0.0,
            filteredSpeedMps = 0.0,
            displayedSpeedKph = 0,
            activityType = "stationary",
            activityConfidence = 100
        )
        db.withTransaction {
            dao.upsertTrip(trip)
            dao.upsertOutbox(outboxForTrip(trip))
            dao.upsertOutbox(outboxForLocation(stationaryLocation, trip, true))
        }
        queueSampleChunks(trip.id)
        scheduleSync()
    }

    private fun outboxForLocation(
        sample: LocationSampleEntity,
        trip: TripEntity?,
        trackingActive: Boolean
    ): OutboxEntity {
        val now = System.currentTimeMillis()
        val movementState =
            if (trip?.status == "active") "driving" else sample.activityType.lowercase()
        val moving = movementState in setOf(
            "walking",
            "bicycling",
            "motorcycle",
            "driving"
        )
        val payload = JSONObject()
            .put("nativeFixId", "native-${sample.elapsedRealtimeNanos}")
            .put("uid", sample.uid)
            .put("lat", sample.latitude)
            .put("lng", sample.longitude)
            .putNullable("accuracy", sample.accuracyM)
            .put("capturedAt", Instant.ofEpochMilli(sample.capturedAtMs).toString())
            .putNullable(
                "stayStart",
                app.preferences.stayStartAtMs.takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).toString() }
            )
            .put("source", "companion")
            .putNullable("heading", sample.bearingDeg)
            .putNullable("speed", sample.filteredSpeedMps)
            .putNullable("battery", app.preferences.batteryPct)
            .put("batteryCharging", app.preferences.batteryCharging)
            .put("moving", moving)
            .put("activityType", movementState)
            .put("movementState", movementState)
            .putNullable(
                "activityStartedAt",
                app.preferences.movementStateStartedAtMs.takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).toString() }
            )
            .putNullable(
                "movementDecisionAt",
                app.preferences.movementDecisionAtMs.takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).toString() }
            )
            .put("nativeTrackingActive", trackingActive)
            .put("nativeProducerUid", sample.uid)
            .put("nativeHeartbeatAt", Instant.ofEpochMilli(now).toString())
            .put("reportedAt", Instant.ofEpochMilli(now).toString())
        return OutboxEntity(
            documentPath = "families/${sample.familyId}/locations/${sample.uid}",
            payloadJson = payload.toString(),
            updatedAtMs = now,
            createdAtMs = now
        )
    }

    suspend fun markTrackingStopped(uid: String) {
        val sample = dao.latestAcceptedSample(uid) ?: return
        val active = dao.activeTrip(uid)
        dao.upsertOutbox(outboxForLocation(sample, active, false))
        scheduleSync()
    }

    private fun outboxForTrip(trip: TripEntity): OutboxEntity {
        val payload = JSONObject()
            .put("id", trip.id)
            .put("memberId", trip.memberId)
            .put("familyId", trip.familyId)
            .put("startedAt", Instant.ofEpochMilli(trip.startedAtMs).toString())
            .putNullable("endedAt", trip.endedAtMs?.let { Instant.ofEpochMilli(it).toString() })
            .put("status", trip.status)
            .put("startLat", trip.startLat)
            .put("startLng", trip.startLng)
            .put("endLat", trip.endLat)
            .put("endLng", trip.endLng)
            .put("distanceM", trip.distanceM.round2())
            .put("durationSec", trip.durationSec)
            .put("movingDurationSec", trip.movingDurationSec)
            .put("avgSpeedMps", trip.avgSpeedMps.round2())
            .put("maxSpeedMps", trip.maxSpeedMps.round2())
            .put("sampleCount", trip.sampleCount)
            .put("eventCount", trip.eventCount)
            .put("schemaVersion", trip.schemaVersion)
            .put("producer", "android-companion")
            .putNullable("closeReason", trip.closeReason)
            .put("createdAt", Instant.ofEpochMilli(trip.createdAtMs).toString())
            .put("updatedAt", Instant.ofEpochMilli(trip.updatedAtMs).toString())
        return OutboxEntity(
            documentPath = "families/${trip.familyId}/drivingSessions/${trip.id}",
            payloadJson = payload.toString(),
            createdAtMs = trip.createdAtMs,
            updatedAtMs = trip.updatedAtMs
        )
    }

    private suspend fun queueSampleChunks(sessionId: String) {
        val samples = dao.samplesForSession(sessionId)
        samples.chunked(250).forEachIndexed { chunkIndex, points ->
            val payload = JSONObject()
                .put("sessionId", sessionId)
                .put("chunkIndex", chunkIndex)
                .put("updatedAt", Instant.now().toString())
                .put("points", JSONArray().apply {
                    points.forEachIndexed { pointIndex, p ->
                        put(
                            JSONObject()
                                .put("lat", p.latitude)
                                .put("lng", p.longitude)
                                .put("t", Instant.ofEpochMilli(p.capturedAtMs).toString())
                                .put("seq", pointIndex + chunkIndex * 250)
                                .putNullable("speedMps", p.filteredSpeedMps)
                                .putNullable("rawSpeedMps", p.rawSpeedMps)
                                .putNullable("calculatedSpeedMps", p.fallbackSpeedMps)
                                .putNullable("accuracyM", p.accuracyM)
                                .putNullable("headingDeg", p.bearingDeg)
                                .putNullable("altitudeM", p.altitudeM)
                                .put("accepted", p.accepted)
                                .put("activityType", p.activityType)
                                .put("capturedAtMs", p.capturedAtMs)
                                .put("source", "companion")
                        )
                    }
                })
            val familyId = samples.firstOrNull()?.familyId ?: return
            val now = System.currentTimeMillis()
            dao.upsertOutbox(
                OutboxEntity(
                    documentPath = "families/$familyId/drivingSessions/$sessionId/samples/chunk_$chunkIndex",
                    payloadJson = payload.toString(),
                    createdAtMs = now,
                    updatedAtMs = now
                )
            )
        }
    }

    private fun detectDrivingEvent(
        previous: LocationSampleEntity?,
        current: LocationSampleEntity,
        trip: TripEntity?
    ): OutboxEntity? {
        if (previous == null || trip == null || trip.status != "active") return null
        if (previous.sessionId != trip.id || current.sessionId != trip.id) return null
        val previousSpeed = previous.filteredSpeedMps ?: return null
        val currentSpeed = current.filteredSpeedMps ?: return null
        val dt = (current.capturedAtMs - previous.capturedAtMs) / 1000.0
        if (dt <= 0 || dt > 30) return null
        val acceleration = (currentSpeed - previousSpeed) / dt
        val type = when {
            acceleration <= -3.5 -> "HardBrakeCandidate"
            acceleration >= 3.0 -> "RapidAccelCandidate"
            else -> return null
        }
        val eventId = "native_${type.lowercase()}_${trip.id}_${current.capturedAtMs}"
        val payload = JSONObject()
            .put("id", eventId)
            .put("type", type)
            .put("sessionId", trip.id)
            .put("memberUid", trip.memberId)
            .put("at", Instant.ofEpochMilli(current.capturedAtMs).toString())
            .put("lat", current.latitude)
            .put("lng", current.longitude)
            .put("prevSpeedMps", previousSpeed)
            .put("currSpeedMps", currentSpeed)
            .put("deltaTimeS", dt)
            .put("thresholdUsed", if (type == "HardBrakeCandidate") -3.5 else 3.0)
            .put("confidence", if (dt <= 3) 0.7 else 0.45)
            .put("method", "native_speed_delta")
            .put("producer", "android-companion")
            .put("createdAt", Instant.now().toString())
        val now = System.currentTimeMillis()
        return OutboxEntity(
            documentPath = "families/${trip.familyId}/drivingEvents/$eventId",
            payloadJson = payload.toString(),
            createdAtMs = now,
            updatedAtMs = now
        )
    }

    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun logSpeed(sample: LocationSampleEntity) {
        logger.info(
            "Speed",
            "GPS speed pipeline",
            JSONObject()
                .putNullable("coordsSpeedMps", sample.rawSpeedMps)
                .putNullable("fallbackSpeedMps", sample.fallbackSpeedMps)
                .putNullable("filteredSpeedMps", sample.filteredSpeedMps)
                .put("displayedSpeedKph", sample.displayedSpeedKph)
                .putNullable("accuracyM", sample.accuracyM)
                .put("accepted", sample.accepted)
                .putNullable("rejectionReason", sample.rejectionReason)
                .toString()
        )
    }

    fun logger() = logger

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun Double.round2() = kotlin.math.round(this * 100.0) / 100.0
}
