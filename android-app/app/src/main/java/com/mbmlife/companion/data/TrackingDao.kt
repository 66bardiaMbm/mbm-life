package com.mbmlife.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSample(sample: LocationSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSamples(samples: List<LocationSampleEntity>)

    @Query("SELECT * FROM location_samples WHERE uid = :uid AND accepted = 1 ORDER BY capturedAtMs DESC LIMIT 1")
    suspend fun latestAcceptedSample(uid: String): LocationSampleEntity?

    @Query("SELECT * FROM location_samples WHERE sessionId = :sessionId AND accepted = 1 ORDER BY capturedAtMs ASC")
    suspend fun samplesForSession(sessionId: String): List<LocationSampleEntity>

    @Query("SELECT * FROM location_samples WHERE uid = :uid AND accepted = 1 ORDER BY capturedAtMs DESC LIMIT :limit")
    suspend fun recentSamples(uid: String, limit: Int): List<LocationSampleEntity>

    @Query("DELETE FROM location_samples WHERE sessionId IS NULL AND capturedAtMs < :beforeMs")
    suspend fun deleteOldPreTripSamples(beforeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE memberId = :uid AND status = 'active' ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun activeTrip(uid: String): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    suspend fun trip(id: String): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY updatedAtMs ASC LIMIT :limit")
    suspend fun pendingOutbox(limit: Int = 50): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE documentPath LIKE :prefix || '%' ORDER BY updatedAtMs ASC LIMIT :limit")
    suspend fun pendingOutboxForPrefix(prefix: String, limit: Int = 100): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE documentPath = :path")
    suspend fun deleteOutbox(path: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error, updatedAtMs = :nowMs WHERE documentPath = :path")
    suspend fun markOutboxFailure(path: String, error: String, nowMs: Long)

    @Insert
    suspend fun insertLog(log: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int = 200): List<DiagnosticLogEntity>

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT :keep)")
    suspend fun trimLogs(keep: Int = 1000)
}
