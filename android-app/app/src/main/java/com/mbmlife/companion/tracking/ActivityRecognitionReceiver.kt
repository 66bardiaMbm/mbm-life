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
        app.preferences.lastActivityType = probable.type.asLabel()
        app.preferences.lastActivityConfidence = probable.confidence
        app.preferences.lastActivityAtMs = result.time
        DiagnosticLogger(app.database.trackingDao()).info(
            "Activity",
            "Activity recognition update",
            JSONObject()
                .put("type", probable.type.asLabel())
                .put("confidence", probable.confidence)
                .put("eventTimeMs", result.time)
                .toString()
        )
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
