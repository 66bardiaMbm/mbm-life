package com.mbmlife.companion.data

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v453 regression test — proves the exact race Bahman/Codex identified is
 * closed: an in-flight Firestore write for an outbox row must NOT delete a
 * newer row that replaced it (same documentPath, same PrimaryKey) while the
 * write was still awaiting.
 *
 * HONESTY NOTE (Claude): this is a real Room/AndroidX instrumented test,
 * written against the actual TrackingDao/OutboxEntity signatures from the
 * uploaded source, using the confirmed real database class (AppDatabase).
 * I cannot compile or run Android/Room code in my own environment, so this
 * has NOT been executed by me — only reasoned through against the real
 * @Query/@Entity definitions. Please run it (or have Codex run it) before
 * treating v453 as verified.
 */
@RunWith(AndroidJUnit4::class)
class OutboxRaceRegressionTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // Placeholder: swap for the app's actual RoomDatabase class if its name
    // differs (I don't have that file — only TrackingDao.kt/Entities.kt
    // were uploaded).
    private lateinit var db: AppDatabase
    private lateinit var dao: TrackingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.trackingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun outboxRow(updatedAtMs: Long, payload: String) = OutboxEntity(
        documentPath = "families/fam1/locations/uidA",
        payloadJson = payload,
        merge = true,
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs
    )

    @Test
    fun olderRowReadThenNewerRowReplaces_deleteMustNotRemoveNewerRow() = runBlocking {
        // 1. "old location read" — the row a SyncWorker pass would read first.
        val old = outboxRow(updatedAtMs = 1_000L, payload = "{\"lat\":1}")
        dao.upsertOutbox(old)
        val readForSync = dao.pendingOutbox(100).first { it.documentPath == old.documentPath }
        assertEquals(1_000L, readForSync.updatedAtMs)

        // 2. "newer same-path location inserted during Firestore await" —
        //    simulates a fresh GPS fix upserting the SAME documentPath
        //    (PrimaryKey) while the old row's Firestore write is in flight.
        val newer = outboxRow(updatedAtMs = 2_000L, payload = "{\"lat\":2}")
        dao.upsertOutbox(newer) // REPLACE — only one row for this path now, updatedAtMs=2000

        // 3. "old write finishes" — SyncWorker now deletes using the OLD
        //    updatedAtMs it read in step 1 (1_000L), NOT the current one.
        val deletedByOldAttempt = dao.deleteOutboxIfUnchanged(readForSync.documentPath, readForSync.updatedAtMs)
        assertEquals(
            "Deleting with the STALE updatedAtMs must affect 0 rows — the row moved on",
            0,
            deletedByOldAttempt
        )

        // 4. "newer row must remain and be drained next" — confirm it's
        //    still there, untouched, with the NEW payload/timestamp.
        val remaining = dao.pendingOutbox(100).filter { it.documentPath == old.documentPath }
        assertEquals(1, remaining.size)
        assertEquals(2_000L, remaining[0].updatedAtMs)
        assertEquals("{\"lat\":2}", remaining[0].payloadJson)

        // 5. Sanity check on the OLD path (regression guard): a correctly
        //    matched delete (current row's real updatedAtMs) DOES remove it.
        val deletedCorrectly = dao.deleteOutboxIfUnchanged(remaining[0].documentPath, remaining[0].updatedAtMs)
        assertEquals(1, deletedCorrectly)
        assertNull(dao.pendingOutbox(100).firstOrNull { it.documentPath == old.documentPath })
    }

    @Test
    fun deleteOutboxIfUnchanged_noRaceCase_stillDeletesNormally() = runBlocking {
        // Baseline: no race at all — read, then delete with the SAME
        // updatedAtMs immediately. Must behave exactly like the old
        // deleteOutbox(path) did in the common (non-racy) case.
        val row = outboxRow(updatedAtMs = 5_000L, payload = "{\"lat\":3}")
        dao.upsertOutbox(row)
        val deleted = dao.deleteOutboxIfUnchanged(row.documentPath, row.updatedAtMs)
        assertEquals(1, deleted)
        assertNull(dao.pendingOutbox(100).firstOrNull { it.documentPath == row.documentPath })
    }

    @Test
    fun oldDeleteOutboxStillExists_forAnyOtherCaller_unaffected() = runBlocking {
        // Confirms the original deleteOutbox(path) was left intact (not
        // removed) — v453 only ADDS the safe variant, per the "touch as
        // little as possible" constraint.
        val row = outboxRow(updatedAtMs = 9_000L, payload = "{\"lat\":4}")
        dao.upsertOutbox(row)
        dao.deleteOutbox(row.documentPath)
        assertNull(dao.pendingOutbox(100).firstOrNull { it.documentPath == row.documentPath })
    }

    @Test
    fun olderRowReadThenNewerRowReplaces_failureMustNotTouchNewerRow() = runBlocking {
        // The same race, on the FAILURE path this time: old row read →
        // newer same-path row inserted during the await → old Firestore
        // write fails → the newer row's payload/attempts/lastError/
        // updatedAtMs must remain completely untouched.
        val old = outboxRow(updatedAtMs = 1_000L, payload = "{\"lat\":10}")
        dao.upsertOutbox(old)
        val readForSync = dao.pendingOutbox(100).first { it.documentPath == old.documentPath }
        assertEquals(1_000L, readForSync.updatedAtMs)

        val newer = outboxRow(updatedAtMs = 2_000L, payload = "{\"lat\":20}")
        dao.upsertOutbox(newer) // REPLACE — same PrimaryKey (documentPath)

        // Old write now fails. SyncWorker calls markOutboxFailureIfUnchanged
        // with the STALE updatedAtMs (1_000L) it read before the replacement.
        val affected = dao.markOutboxFailureIfUnchanged(
            readForSync.documentPath,
            readForSync.updatedAtMs,
            "IOException: simulated old-write failure",
            nowMs = 9_999L
        )
        assertEquals(
            "Marking failure with the STALE updatedAtMs must affect 0 rows",
            0,
            affected
        )

        // Newer row must be byte-for-byte unchanged: same payload, same
        // updatedAtMs, attempts still 0, no lastError stamped onto it.
        val stillThere = dao.pendingOutbox(100).first { it.documentPath == old.documentPath }
        assertEquals("{\"lat\":20}", stillThere.payloadJson)
        assertEquals(2_000L, stillThere.updatedAtMs)
        assertEquals(0, stillThere.attempts)
        assertNull(stillThere.lastError)
    }

    @Test
    fun markOutboxFailureIfUnchanged_noRaceCase_stillRecordsFailure() = runBlocking {
        // Baseline: no race — failure recorded against the exact current row.
        val row = outboxRow(updatedAtMs = 3_000L, payload = "{\"lat\":30}")
        dao.upsertOutbox(row)
        val affected = dao.markOutboxFailureIfUnchanged(
            row.documentPath,
            row.updatedAtMs,
            "IOException: simulated failure",
            nowMs = 3_500L
        )
        assertEquals(1, affected)
        val updated = dao.pendingOutbox(100).first { it.documentPath == row.documentPath }
        assertEquals(1, updated.attempts)
        assertEquals("IOException: simulated failure", updated.lastError)
    }
}
