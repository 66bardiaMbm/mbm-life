package com.mbmlife.companion.data

import android.content.Context
import android.content.SharedPreferences

class TrackingPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("native_tracking", Context.MODE_PRIVATE)

    var familyId: String?
        get() = prefs.getString("family_id", null)
        set(value) = prefs.edit().putString("family_id", value).apply()

    var webUid: String?
        get() = prefs.getString("web_uid", null)
        set(value) = prefs.edit().putString("web_uid", value).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking_enabled", false)
        set(value) = prefs.edit().putBoolean("tracking_enabled", value).apply()

    var lastActivityType: String
        get() = prefs.getString("activity_type", "UNKNOWN") ?: "UNKNOWN"
        set(value) = prefs.edit().putString("activity_type", value).apply()

    var lastActivityConfidence: Int
        get() = prefs.getInt("activity_confidence", 0)
        set(value) = prefs.edit().putInt("activity_confidence", value).apply()

    var lastActivityAtMs: Long
        get() = prefs.getLong("activity_at", 0L)
        set(value) = prefs.edit().putLong("activity_at", value).apply()

    var movementState: String
        get() = prefs.getString("movement_state", "stationary") ?: "stationary"
        set(value) = prefs.edit().putString("movement_state", value).apply()

    var movementStateStartedAtMs: Long
        get() = prefs.getLong("movement_state_started_at", 0L)
        set(value) = prefs.edit().putLong("movement_state_started_at", value).apply()

    var movementDecisionAtMs: Long
        get() = prefs.getLong("movement_decision_at", 0L)
        set(value) = prefs.edit().putLong("movement_decision_at", value).apply()

    var serviceStartedAtMs: Long
        get() = prefs.getLong("service_started_at", 0)
        set(value) = prefs.edit().putLong("service_started_at", value).apply()

    var lastFixAtMs: Long
        get() = prefs.getLong("last_fix_at", 0)
        set(value) = prefs.edit().putLong("last_fix_at", value).apply()

    var lastSyncAtMs: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(value) = prefs.edit().putLong("last_sync_at", value).apply()

    var stayStartAtMs: Long
        get() = prefs.getLong("stay_start_at", 0)
        set(value) = prefs.edit().putLong("stay_start_at", value).apply()

    var batteryPct: Int?
        get() = if (prefs.contains("battery_pct")) prefs.getInt("battery_pct", -1)
            .takeIf { it in 0..100 } else null
        set(value) {
            if (value == null) prefs.edit().remove("battery_pct").apply()
            else prefs.edit().putInt("battery_pct", value.coerceIn(0, 100)).apply()
        }
}
