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

    @Query("DELETE FROM outbox WHERE documentPath = :path")
    suspend fun deleteOutbox(path: String)

    // v453 FIX: SyncWorker used to call deleteOutbox(path) after a
    // successful Firestore write, keyed ONLY by documentPath. Since
    // documentPath is the outbox table's PrimaryKey and upsertOutbox
    // REPLACEs on conflict, a newer sample for the SAME member arriving
    // and upserting its outbox row WHILE the older row's Firestore write
    // was still in flight would get silently deleted by that plain
    // deleteOutbox(path) call — the newer, not-yet-synced row, gone,
    // with no record it ever existed. This variant only deletes the row
    // if it is STILL the exact row that was read and written (matched by
    // updatedAtMs too, not just the path) — returns the number of rows
    // actually deleted (0 or 1) so the caller can tell the two cases apart
    // instead of assuming success.
    @Query("DELETE FROM outbox WHERE documentPath = :path AND updatedAtMs = :expectedUpdatedAtMs")
    suspend fun deleteOutboxIfUnchanged(path: String, expectedUpdatedAtMs: Long): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error, updatedAtMs = :nowMs WHERE documentPath = :path")
    suspend fun markOutboxFailure(path: String, error: String, nowMs: Long)

    // v453 FIX (same race class as deleteOutboxIfUnchanged, on the failure
    // path instead of the success path): markOutboxFailure(path, ...) also
    // matched by documentPath alone. If a newer same-path row replaces the
    // old one during the Firestore await AND the old write then fails,
    // the plain version would stamp that failure's attempts/lastError onto
    // the NEWER, unrelated row — corrupting its retry bookkeeping even
    // though the newer row was never attempted yet. Conditional on
    // updatedAtMs the same way the delete variant is; returns rows affected
    // so the caller can tell whether the failure was actually recorded.
    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error, updatedAtMs = :nowMs WHERE documentPath = :path AND updatedAtMs = :expectedUpdatedAtMs")
    suspend fun markOutboxFailureIfUnchanged(path: String, expectedUpdatedAtMs: Long, error: String, nowMs: Long): Int

    @Insert
    suspend fun insertLog(log: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int = 200): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_logs WHERE tag = :tag ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    suspend fun recentLogsForTag(tag: String, limit: Int = 200): List<DiagnosticLogEntity>

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT :keep)")
    suspend fun trimLogs(keep: Int = 1000)
}
