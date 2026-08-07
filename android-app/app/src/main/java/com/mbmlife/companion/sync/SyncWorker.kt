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
        const val LEGACY_UNIQUE_WORK = "mbm-native-firestore-outbox"
        const val UNIQUE_WORK = "mbm-native-firestore-outbox-v2"
        private const val BATCH_SIZE = 100
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
        while (true) {
            val pending = dao.pendingOutboxForPrefix(prefix, BATCH_SIZE)
                .sortedWith(compareBy({ pathPriority(it.documentPath) }, { it.updatedAtMs }))
            if (pending.isEmpty()) return Result.success()

            for (item in pending) {
                try {
                    val payload = jsonObjectToMap(JSONObject(item.payloadJson))
                    val reference = firestore.document(item.documentPath)
                    if (item.merge) reference.set(payload, SetOptions.merge()).await()
                    else reference.set(payload).await()
                    dao.deleteOutbox(item.documentPath)
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
                    dao.markOutboxFailure(
                        item.documentPath,
                        exact,
                        System.currentTimeMillis()
                    )
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
        }
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
