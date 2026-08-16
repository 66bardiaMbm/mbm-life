package com.mbmlife.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V459DiagnosticsBundleTest {
    @Test
    fun debugApkLoadsBundledV459DiagnosticsPage() {
        assertEquals("file:///android_asset/index.html", BuildConfig.PWA_URL)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundledIndex = context.assets.open("index.html").bufferedReader().use { it.readText() }
        assertTrue(bundledIndex.contains("const APP_VERSION='v460'"))
        assertTrue(bundledIndex.contains("window.diagHistoryPush=diagHistoryPush"))
        assertTrue(bundledIndex.contains("camera_target"))
        assertTrue(bundledIndex.contains("raw_fix_rejected"))
    }
}
