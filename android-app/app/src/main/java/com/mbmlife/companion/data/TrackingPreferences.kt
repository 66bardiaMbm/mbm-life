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
}
