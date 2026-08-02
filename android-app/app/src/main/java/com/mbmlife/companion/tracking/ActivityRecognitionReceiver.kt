package com.mbmlife.companion.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.mbmlife.companion.MbmApplication
import com.mbmlife.companion.data.DiagnosticLogger
import org.json.JSONObject

class ActivityRecognitionReceiver : BroadcastReceiver() {
    // v424: kept in sync with TrackingService.MOVEMENT_ACTIVITIES — the set
    // of labels that count as "moving" for the fast/slow GPS mode switch.
    // Duplicated here (not shared as a constant) because this receiver and
    // TrackingService are separate Android components; the values must
    // match TrackingService's own list exactly.
    private val movementActivities = setOf(
        "IN_VEHICLE", "ON_BICYCLE", "ON_FOOT", "RUNNING", "WALKING"
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val probable = result.probableActivities.maxByOrNull { it.confidence } ?: return
        val app = context.applicationContext as MbmApplication
        if (result.time <= app.preferences.lastActivityAtMs) {
            DiagnosticLogger(app.database.trackingDao()).warn(
                "Activity",
                "Out-of-order activity update ignored",
                JSONObject()
                    .put("eventTimeMs", result.time)
                    .put("lastAcceptedEventTimeMs", app.preferences.lastActivityAtMs)
                    .toString()
            )
            return
        }
        val previousType = app.preferences.lastActivityType
        val newType = probable.type.asLabel()
        app.preferences.lastActivityType = newType
        app.preferences.lastActivityConfidence = probable.confidence
        app.preferences.lastActivityAtMs = result.time
        DiagnosticLogger(app.database.trackingDao()).info(
            "Activity",
            "Activity recognition update",
            JSONObject()
                .put("type", newType)
                .put("confidence", probable.confidence)
                .put("eventTimeMs", result.time)
                .toString()
        )
        // v424: TrackingService's GPS fast/slow mode depends on this value
        // (see TrackingService.hasFreshMovementActivity()), but writing to
        // preferences alone doesn't wake anything up — nothing was polling
        // this before. Only ping the service when the movement/still
        // CATEGORY actually changes, not on every ~5s reading, so this
        // stays a cheap, occasional wake-up rather than a constant one.
        val wasMoving = previousType in movementActivities
        val isMoving = newType in movementActivities
        if (wasMoving != isMoving) {
            val serviceIntent = Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_ACTIVITY_UPDATE)
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                DiagnosticLogger(app.database.trackingDao()).warn(
                    "Activity",
                    "Could not notify TrackingService of activity change",
                    JSONObject().put("error", e.message ?: e.toString()).toString()
                )
            }
        }
    }

    private fun Int.asLabel(): String = when (this) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.WALKING -> "WALKING"
        else -> "UNKNOWN"
    }
}
