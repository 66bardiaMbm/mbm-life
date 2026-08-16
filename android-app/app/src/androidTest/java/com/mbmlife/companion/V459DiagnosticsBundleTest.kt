package com.mbmlife.companion

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun bundledV460PageActuallyLeavesSplashScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var booted = false
            for (attempt in 0 until 45) {
                val result = AtomicReference<String>()
                val callback = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webView).evaluateJavascript(
                        """
                        (() => {
                          const splash = document.getElementById('splash');
                          const auth = document.getElementById('auth-screen');
                          const app = document.getElementById('app');
                          return !splash && (
                            (auth && auth.style.display !== 'none') ||
                            (app && app.style.display !== 'none')
                          );
                        })()
                        """.trimIndent()
                    ) { value ->
                        result.set(value)
                        callback.countDown()
                    }
                }
                callback.await(1, TimeUnit.SECONDS)
                if (result.get() == "true") {
                    booted = true
                    break
                }
                Thread.sleep(1_000)
            }
            assertTrue("Bundled v460 page stayed on the splash screen", booted)
        }
    }
}
