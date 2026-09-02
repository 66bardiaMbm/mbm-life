package com.mbmlife.companion

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mbmlife.companion.tracking.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTrackingManifestTest {
    @Test
    fun removingAppTaskDoesNotStopTrackingService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getServiceInfo(
                ComponentName(context, TrackingService::class.java),
                PackageManager.ComponentInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(
                ComponentName(context, TrackingService::class.java),
                0
            )
        }
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, info.foregroundServiceType)
    }
}
