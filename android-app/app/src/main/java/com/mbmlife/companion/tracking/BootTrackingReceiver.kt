package com.mbmlife.companion.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mbmlife.companion.MbmApplication

/**
 * v433: restores GPS tracking after a device reboot or app update — but
 * ONLY if tracking was actually active before the restart, using the same
 * `trackingEnabled` preference flag TrackingService itself already sets in
 * startTracking() and clears in stopTracking(). This deliberately does NOT
 * start tracking for every user on every boot, only resumes an
 * already-running session. It does not touch GPS/consent/movement logic —
 * it only re-issues the same startForegroundService() call MainActivity
 * already makes on app launch.
 *
 * Legal to start a `location`-type foreground service from BOOT_COMPLETED
 * at targetSdk 35: confirmed against Android's documented restricted list
 * for BOOT_COMPLETED-launched foreground services (camera, dataSync,
 * mediaPlayback, mediaProjection, microphone, phoneCall) — `location` is
 * not on that list. Still requires ACCESS_BACKGROUND_LOCATION to actually
 * be granted at runtime (declared in the manifest; TrackingService's own
 * permission check inside startTracking() already guards this and will
 * safely refuse + log if the grant is missing, same as it does today when
 * launched from MainActivity).
 */
class BootTrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as? MbmApplication ?: return
        if (!app.preferences.trackingEnabled) return

        ContextCompat.startForegroundService(context, TrackingService.startIntent(context))
    }
}
