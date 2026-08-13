package com.mbmlife.companion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mbmlife.companion.MbmApplication
import com.mbmlife.companion.data.DiagnosticLogger
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        const val UNIQUE_WORK = "mbm-native-firestore-outbox"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as MbmApplication
        val dao = app.database.trackingDao()
        val logger = DiagnosticLogger(dao)
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return Result.retry().also {
                logger.warn("Sync", "Firebase Auth user unavailable")
            }

        val familyId = app.preferences.familyId
            ?: return Result.retry().also {
                logger.warn("Sync", "Family context unavailable")
            }

        val prefix = "families/$familyId/"
        val firestore = FirebaseFirestore.getInstance()

        // v453 FIX (battery/CPU root cause, part 2 of 2 — pairs with the
        // ExistingWorkPolicy.KEEP change in TrackingRepository.scheduleSync()).
        // The old version read pendingOutbox(100) exactly ONCE per doWork()
        // call. With KEEP now silently absorbing every scheduleSync() call
        // that arrives while this Worker is already RUNNING, any outbox item
        // written by persist() during that window would never be seen by
        // THIS run — and, being silently dropped by KEEP, nothing else would
        // trigger a new run either, until some later, unrelated fix happened
        // to call scheduleSync() again after this run finished. That gap is
        // the stranded-outbox race. Fixed by re-querying after each batch
        // and only returning once a query genuinely comes back empty, capped
        // so a pathological non-stop stream can't run forever in one
        // Worker execution.
        var iterations = 0
        val maxIterations = 20 // 20 * 100 = up to 2000 outbox items drained in one run
        while (iterations < maxIterations) {
            val pending = dao.pendingOutbox(100)
                .filter { it.documentPath.startsWith(prefix) }
                .sortedWith(compareBy({ pathPriority(it.documentPath) }, { it.updatedAtMs }))

            if (pending.isEmpty()) return Result.success()

            for (item in pending) {
                try {
                    val payload = jsonObjectToMap(JSONObject(item.payloadJson))
                    val reference = firestore.document(item.documentPath)
                    if (item.merge) reference.set(payload, SetOptions.merge()).await()
                    else reference.set(payload).await()
                    // v453 FIX: was deleteOutbox(item.documentPath) — deleted
                    // by path alone, which could silently discard a NEWER
                    // row for the same path that arrived while this Firestore
                    // write was in flight (documentPath is the outbox
                    // table's PrimaryKey, so a newer upsert REPLACEs it
                    // in-place; a bare path-delete can't tell old from new).
                    // Now conditional on updatedAtMs still matching the
                    // exact row that was just written. If 0 rows were
                    // deleted, a newer row already replaced this one before
                    // the write finished — that write's payload is now
                    // stale/superseded, but nothing was lost: the newer row
                    // is untouched in the outbox and this same while-loop's
                    // next iteration will read and sync it normally.
                    val deleted = dao.deleteOutboxIfUnchanged(item.documentPath, item.updatedAtMs)
                    if (deleted == 0) {
                        logger.info(
                            "LocationTiming",
                            "Outbox row superseded during Firestore write — newer row will sync next pass",
                            JSONObject()
                                .put("uid", authUid)
                                .put("path", item.documentPath)
                                .toString()
                        )
                    }
                    app.preferences.lastSyncAtMs = System.currentTimeMillis()
                    logger.info(
                        "LocationTiming",
                        "Firestore write acknowledged",
                        JSONObject()
                            .put("uid", authUid)
                            .put("path", item.documentPath)
                            .put("fixId", payload["nativeFixId"])
                            .put("firebaseWriteAcknowledgedAtMs", System.currentTimeMillis())
                            .toString()
                    )
                } catch (error: Exception) {
                    val exact = "${error::class.java.simpleName}: ${error.message}"
                    // v453 FIX: was markOutboxFailure(path, ...) — matched by
                    // documentPath alone, same race as the delete above but
                    // on the failure path. If a newer same-path row replaced
                    // this one during the Firestore await and THIS (now
                    // stale) write then failed, a plain path-match would
                    // stamp attempts/lastError onto the newer row — which
                    // was never actually attempted. Conditional on
                    // updatedAtMs so a superseded row's failure never
                    // touches its replacement's bookkeeping.
                    val marked = dao.markOutboxFailureIfUnchanged(
                        item.documentPath,
                        item.updatedAtMs,
                        exact,
                        System.currentTimeMillis()
                    )
                    if (marked == 0) {
                        logger.info(
                            "LocationTiming",
                            "Outbox row superseded before failure could be recorded — newer row unaffected",
                            JSONObject()
                                .put("path", item.documentPath)
                                .toString()
                        )
                    }
                    logger.error(
                        "LocationTiming",
                        "Firestore write failed",
                        JSONObject()
                            .put("path", item.documentPath)
                            .put("firebaseWriteFailedAtMs", System.currentTimeMillis())
                            .put("error", exact)
                            .toString()
                    )
                    return Result.retry()
                }
            }
            iterations++
        }
        // Cap reached with items still arriving faster than they can be
        // drained — hand back to WorkManager as a retry rather than silently
        // stopping; KEEP means the next real scheduleSync() call (the very
        // next accepted fix) will pick this straight back up once this run
        // ends, since this Worker is no longer RUNNING by then.
        logger.warn(
            "Sync",
            "Outbox drain hit iteration cap, deferring remainder to next run",
            JSONObject().put("maxIterations", maxIterations).toString()
        )
        return Result.retry()
    }

    private fun pathPriority(path: String): Int = when {
        "/locations/" in path -> 0
        "/drivingSessions/" in path && "/samples/" !in path -> 1
        "/samples/" in path -> 2
        "/drivingEvents/" in path -> 3
        else -> 4
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key -> jsonValue(json.get(key)) }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
}
