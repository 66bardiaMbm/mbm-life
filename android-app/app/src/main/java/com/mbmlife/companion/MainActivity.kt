package com.mbmlife.companion

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.mbmlife.companion.data.DiagnosticLogger
import com.mbmlife.companion.data.FamilyResolver
import com.mbmlife.companion.databinding.ActivityMainBinding
import com.mbmlife.companion.tracking.TrackingService
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONTokener
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var app: MbmApplication
    private lateinit var auth: FirebaseAuth
    private lateinit var logger: DiagnosticLogger
    private val handler = Handler(Looper.getMainLooper())
    private var pendingGoogleIdToken: String? = null
    private var receiverRegistered = false
    private var foregroundPermissionRequested = false
    private var secondaryPermissionsRequested = false
    private var backgroundPermissionRequested = false
    private var familyResolutionInFlight = false

    private val foregroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            advanceTrackingSetup()
        }

    private val secondaryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            advanceTrackingSetup()
        }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            advanceTrackingSetup()
        }

    // Holds the WebView's callback while the system photo/file picker is
    // open; resolved with the chosen file (or null on cancel) when the
    // picker activity returns. Required for <input type="file"> inside the
    // WebView to work at all — without a WebChromeClient.onShowFileChooser
    // override paired with this launcher, WebView silently drops the click.
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooserCallback
            pendingFileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val nativeFixReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(TrackingService.EXTRA_FIX_JSON) ?: return
            injectNativeFix(json)
        }
    }

    private val contextPoll = object : Runnable {
        override fun run() {
            readFamilyContextFromPwa()
            handler.postDelayed(this, 4_000L)
        }
    }

    private inner class NativeAuthBridge {
        @JavascriptInterface
        fun requestGoogleSignIn() {
            runOnUiThread { signInWithGoogle() }
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun appVersionName(): String = BuildConfig.VERSION_NAME

        /**
         * Opens Android's real share sheet for WebView actions such as Family
         * invitations. WebView may expose navigator.share but reject the call,
         * so the PWA cannot treat the browser API as proof that sharing works.
         */
        @JavascriptInterface
        fun shareText(title: String?, text: String?, url: String?) {
            runOnUiThread {
                val payload = listOfNotNull(
                    text?.trim()?.takeIf { it.isNotEmpty() },
                    url?.trim()?.takeIf { it.isNotEmpty() }
                ).joinToString("\n")
                if (payload.isEmpty()) return@runOnUiThread
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title?.takeIf { it.isNotBlank() } ?: "MBM Life")
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                startActivity(Intent.createChooser(sendIntent, title ?: "Share"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as MbmApplication
        auth = FirebaseAuth.getInstance()
        logger = DiagnosticLogger(app.database.trackingDao())
        if (auth.currentUser != null && app.preferences.familyId.isNullOrBlank()) {
            resolveFamilyNatively()
        }

        configureWebView()
        advanceTrackingSetup()
    }

    /**
     * Production setup coordinator. There is deliberately no corresponding
     * Android view: the WebView remains the complete app UI and Android shows
     * only its own runtime-permission dialogs. Each permission is requested at
     * most once per activity lifetime so a denial never creates a prompt loop.
     */
    private fun advanceTrackingSetup() {
        when {
            auth.currentUser == null -> return
            !hasForegroundLocation() -> {
                if (foregroundPermissionRequested) return
                foregroundPermissionRequested = true
                foregroundPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            missingSecondaryPermissions().isNotEmpty() -> {
                if (secondaryPermissionsRequested) return
                secondaryPermissionsRequested = true
                secondaryPermissionLauncher.launch(missingSecondaryPermissions().toTypedArray())
            }
            !hasBackgroundLocation() -> {
                if (backgroundPermissionRequested) return
                backgroundPermissionRequested = true
                requestBackgroundLocation()
            }
            app.preferences.familyId.isNullOrBlank() -> resolveFamilyNatively()
            else -> {
                // Installing/updating an APK stops the old process and its
                // foreground service, but SharedPreferences survives.  The
                // previous code treated trackingEnabled=true as proof that
                // the service was still alive and returned here, leaving the
                // app on a hours-old fix until Android happened to restart it.
                //
                // Starting an already-running service is safe: TrackingService
                // rejects duplicate starts itself.  Always reconcile the real
                // service on app launch instead of trusting a persisted flag.
                if (!TrackingService.isRunning) {
                    ContextCompat.startForegroundService(this, TrackingService.startIntent(this))
                }
                app.preferences.trackingEnabled = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                nativeFixReceiver,
                IntentFilter(TrackingService.ACTION_NATIVE_FIX),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        handler.post(contextPoll)
    }

    override fun onStop() {
        handler.removeCallbacks(contextPoll)
        if (receiverRegistered) {
            unregisterReceiver(nativeFixReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        advanceTrackingSetup()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "$userAgentString MBMLifeNative/${BuildConfig.VERSION_NAME}"
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // The native foreground service is the sole location producer.
                callback?.invoke(origin, false, false)
                logger.info("WebView", "PWA geolocation request denied; native service is authoritative")
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                // Cancel any prior pending chooser rather than leaking it —
                // only one file input can be actively awaiting a result.
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    logger.error("WebView", "File chooser launch failed", e.toString())
                    pendingFileChooserCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return if (uri.host == Uri.parse(BuildConfig.PWA_URL).host) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installNativeAuthBridgeInPwa()
                deliverPendingGoogleCredentialToPwa()
                readFamilyContextFromPwa()
                lifecycleScope.launch {
                    val uid = auth.currentUser?.uid ?: return@launch
                    app.database.trackingDao().latestAcceptedSample(uid)?.let { sample ->
                        val json = JSONObject()
                            .put("uid", sample.uid)
                            .put("lat", sample.latitude)
                            .put("lng", sample.longitude)
                            .put("accuracy", sample.accuracyM ?: JSONObject.NULL)
                            .put("speed", sample.filteredSpeedMps ?: JSONObject.NULL)
                            .put("battery", app.preferences.batteryPct ?: JSONObject.NULL)
                            .put("heading", sample.bearingDeg ?: JSONObject.NULL)
                            .put("capturedAt", java.time.Instant.ofEpochMilli(sample.capturedAtMs).toString())
                            .put(
                                "stayStart",
                                app.preferences.stayStartAtMs.takeIf { it > 0L }
                                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                                    ?: JSONObject.NULL
                            )
                            // This is a replay of Room's last accepted sample,
                            // not a new GPS callback.  Giving it "now" made an
                            // hours-old coordinate look live after every page
                            // load.  Freshness must remain tied to the sample.
                            .put(
                                "reportedAt",
                                java.time.Instant.ofEpochMilli(sample.capturedAtMs).toString()
                            )
                            .put("source", "companion")
                            .put("moving", app.preferences.movementState != "stationary")
                            .put("activityType", app.preferences.movementState)
                            .put("movementState", app.preferences.movementState)
                            .put(
                                "activityStartedAt",
                                app.preferences.movementStateStartedAtMs.takeIf { it > 0L }
                                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                                    ?: JSONObject.NULL
                            )
                            .put(
                                "movementDecisionAt",
                                app.preferences.movementDecisionAtMs.takeIf { it > 0L }
                                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                                    ?: JSONObject.NULL
                            )
                            .put("nativeTrackingActive", app.preferences.trackingEnabled)
                        injectNativeFix(json.toString())
                    }
                }
            }
        }
        binding.webView.addJavascriptInterface(NativeAuthBridge(), "MbmNativeAuth")
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        binding.webView.clearCache(true)
        binding.webView.loadUrl(BuildConfig.PWA_URL)
    }

    private fun installNativeAuthBridgeInPwa() {
        val js = """
            (function(){
              if (window.__mbmNativeAuthInstalled) return;
              window.__mbmNativeAuthInstalled = true;
              window.mbmNativeGoogleSignInFailed = function(message) {
                var btn = document.getElementById('auth-google');
                if (btn) { btn.disabled = false; btn.style.opacity = '1'; }
                if (typeof authError === 'function') {
                  authError('Sign-in failed: ' + (message || 'unknown'));
                }
              };
              window.mbmCompleteNativeGoogleSignIn = function(idToken) {
                if (typeof FB === 'undefined' || !FB.ready || !idToken) {
                  window.mbmNativeGoogleSignInFailed('Firebase authentication is not ready.');
                  return;
                }
                var credential = firebase.auth.GoogleAuthProvider.credential(idToken);
                FB.auth.signInWithCredential(credential).catch(function(error) {
                  window.mbmNativeGoogleSignInFailed(
                    error && error.message ? error.message : 'Native sign-in failed'
                  );
                });
              };
              window.__mbmOriginalSignInWithGoogle = window.signInWithGoogle;
              window.signInWithGoogle = function() {
                if (typeof authClearError === 'function') authClearError();
                var btn = document.getElementById('auth-google');
                if (btn) { btn.disabled = true; btn.style.opacity = '.6'; }
                try {
                  window.MbmNativeAuth.requestGoogleSignIn();
                } catch (error) {
                  window.mbmNativeGoogleSignInFailed(
                    error && error.message ? error.message : 'Native sign-in bridge unavailable'
                  );
                }
              };
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun deliverGoogleCredentialToPwa(idToken: String) {
        pendingGoogleIdToken = idToken
        deliverPendingGoogleCredentialToPwa()
    }

    private fun deliverPendingGoogleCredentialToPwa() {
        val idToken = pendingGoogleIdToken ?: return
        val js = """
            (function(token){
              try {
                if (typeof window.mbmCompleteNativeGoogleSignIn !== 'function') return false;
                window.mbmCompleteNativeGoogleSignIn(token);
                return true;
              } catch(e) {
                return false;
              }
            })(${JSONObject.quote(idToken)})
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { delivered ->
            if (delivered == "true") {
                pendingGoogleIdToken = null
                logger.info("Auth", "Google credential delivered to PWA without redirect")
            }
        }
    }

    private fun notifyPwaGoogleSignInFailed(message: String) {
        val js = """
            (function(message){
              if (typeof window.mbmNativeGoogleSignInFailed === 'function') {
                window.mbmNativeGoogleSignInFailed(message);
              }
            })(${JSONObject.quote(message)})
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun readFamilyContextFromPwa() {
        val js = """
            (function(){
              try {
                var uid=(typeof FB!=='undefined'&&FB.user)?FB.user.uid:null;
                var familyId=(typeof FamilyBackend!=='undefined'&&FamilyBackend.familyId)
                  ?FamilyBackend.familyId():null;
                return JSON.stringify({uid:uid,familyId:familyId});
              } catch(e) { return JSON.stringify({error:String(e)}); }
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { encoded ->
            try {
                val decoded = JSONTokener(encoded).nextValue() as? String ?: return@evaluateJavascript
                val context = JSONObject(decoded)
                val webUid = context.optString("uid").takeIf { it.isNotBlank() && it != "null" }
                val familyId = context.optString("familyId").takeIf { it.isNotBlank() && it != "null" }
                val nativeUid = auth.currentUser?.uid
                when {
                    webUid == null || familyId == null -> {
                        return@evaluateJavascript
                    }
                    nativeUid == null -> {
                        return@evaluateJavascript
                    }
                    nativeUid != webUid -> {
                        logger.error(
                            "Auth",
                            "Native/PWA UID mismatch",
                            JSONObject().put("nativeUid", nativeUid).put("webUid", webUid).toString()
                        )
                    }
                    else -> {
                        val changed = app.preferences.familyId != familyId
                        app.preferences.familyId = familyId
                        app.preferences.webUid = webUid
                        if (changed) logger.info(
                            "Family",
                            "Family context linked from WebView",
                            JSONObject().put("familyId", familyId).put("uid", webUid).toString()
                        )
                    }
                }
                advanceTrackingSetup()
            } catch (error: Exception) {
                logger.warn("Family", "Waiting for valid WebView family context", error.toString())
            }
        }
    }

    private fun injectNativeFix(json: String) {
        val js = """
            (function(nativeFix){
              try {
                var uid=(typeof FB!=='undefined'&&FB.user)?FB.user.uid:null;
                if(!uid || uid!==nativeFix.uid || typeof DB==='undefined') return false;
                DB.entities=DB.entities||{};
                DB.entities.famLocations=DB.entities.famLocations||{};
                var previous=DB.entities.famLocations[uid]||{};
                var nextAt=Date.parse(nativeFix.capturedAt||nativeFix.reportedAt||'');
                var previousAt=Date.parse(previous.capturedAt||previous.reportedAt||'');
                if(!isNaN(previousAt) && !isNaN(nextAt) && nextAt<=previousAt) return false;
                var next=Object.assign({},previous,nativeFix);
                if(!Object.prototype.hasOwnProperty.call(nativeFix,'stayStart') && !next.stayStart)
                  next.stayStart=nativeFix.capturedAt;
                DB.entities.famLocations[uid]=next;
                if(typeof famLiveApply==='function' &&
                   typeof screen!=='undefined' && screen==='module' &&
                   typeof currentModule!=='undefined' && currentModule==='family'){
                  famLiveApply();
                }
                return true;
              } catch(e) { return false; }
            })($json)
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun signInWithGoogle() {
        lifecycleScope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val result = CredentialManager.create(this@MainActivity)
                    .getCredential(this@MainActivity, request)
                val credential = result.credential
                require(
                    credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) { "Unexpected credential type" }
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(googleCredential.idToken, null)
                ).await()
                logger.info("Auth", "Firebase Google sign-in succeeded", auth.currentUser?.uid)
                deliverGoogleCredentialToPwa(googleCredential.idToken)
                resolveFamilyNatively()
                readFamilyContextFromPwa()
                advanceTrackingSetup()
            } catch (error: Exception) {
                logger.error("Auth", "Google/Firebase sign-in failed", error.toString())
                notifyPwaGoogleSignInFailed(error.message ?: "Native Google sign-in failed")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sign-in failed")
                    .setMessage(error.message ?: error.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            advanceTrackingSetup()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAppSettings()
        } else {
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun resolveFamilyNatively() {
        val uid = auth.currentUser?.uid ?: return
        if (familyResolutionInFlight) return
        familyResolutionInFlight = true
        lifecycleScope.launch {
            try {
                val familyId = FamilyResolver(
                    com.google.firebase.firestore.FirebaseFirestore.getInstance(),
                    logger
                ).resolveSingleFamily(uid)
                if (familyId != null) {
                    app.preferences.familyId = familyId
                    advanceTrackingSetup()
                }
            } finally {
                familyResolutionInFlight = false
            }
        }
    }

    private fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun missingSecondaryPermissions(): List<String> = buildList {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }
}
