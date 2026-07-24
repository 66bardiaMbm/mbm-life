package com.mbmlife.companion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_samples",
    indices = [Index("sessionId"), Index("capturedAtMs")]
)
data class LocationSampleEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val uid: String,
    val familyId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val rawSpeedMps: Float?,
    val fallbackSpeedMps: Double?,
    val filteredSpeedMps: Double?,
    val displayedSpeedKph: Int,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val capturedAtMs: Long,
    val elapsedRealtimeNanos: Long,
    val activityType: String,
    val activityConfidence: Int,
    val isMock: Boolean,
    val accepted: Boolean,
    val rejectionReason: String?
)

@Entity(tableName = "trips", indices = [Index("memberId"), Index("status")])
data class TripEntity(
    @PrimaryKey val id: String,
    val memberId: String,
    val familyId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val status: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val distanceM: Double,
    val durationSec: Long,
    val movingDurationSec: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val sampleCount: Int,
    val eventCount: Int,
    val schemaVersion: Int = 1,
    val closeReason: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val documentPath: String,
    val payloadJson: String,
    val merge: Boolean = true,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "diagnostic_logs", indices = [Index("timestampMs")])
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
    val detailsJson: String?
)
