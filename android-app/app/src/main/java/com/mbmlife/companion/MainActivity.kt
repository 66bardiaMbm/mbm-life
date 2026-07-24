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
import android.os.PowerManager
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
    private var webContextMessage: String? = null
    private var receiverRegistered = false

    private val foregroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetupUi()
        }

    private val secondaryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetupUi()
        }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshSetupUi()
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
        binding.primaryButton.setOnClickListener { performNextSetupAction() }
        binding.batteryButton.setOnClickListener { openBatterySettings() }
        binding.hideButton.setOnClickListener {
            binding.setupCard.isVisible = false
            binding.trackingChip.isVisible = true
        }
        binding.trackingChip.setOnClickListener {
            binding.setupCard.isVisible = true
            binding.trackingChip.isVisible = false
            refreshSetupUi()
        }
        refreshSetupUi()
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
        refreshSetupUi()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            geolocationEnabled = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
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
                            .put("heading", sample.bearingDeg ?: JSONObject.NULL)
                            .put("capturedAt", java.time.Instant.ofEpochMilli(sample.capturedAtMs).toString())
                            .put(
                                "stayStart",
                                app.preferences.stayStartAtMs.takeIf { it > 0L }
                                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                                    ?: JSONObject.NULL
                            )
                            .put("reportedAt", java.time.Instant.now().toString())
                            .put("source", "companion")
                            .put("moving", app.preferences.trackingEnabled)
                            .put("nativeTrackingActive", app.preferences.trackingEnabled)
                        injectNativeFix(json.toString())
                    }
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        binding.webView.loadUrl(BuildConfig.PWA_URL)
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
                        webContextMessage = "Open/sign in to the Family screen so the native service can link the family."
                    }
                    nativeUid == null -> {
                        webContextMessage = "Sign in natively with the same Google account used in the PWA."
                    }
                    nativeUid != webUid -> {
                        webContextMessage = "UID mismatch: native and PWA accounts must be the same."
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
                        webContextMessage = null
                        if (changed) logger.info(
                            "Family",
                            "Family context linked from WebView",
                            JSONObject().put("familyId", familyId).put("uid", webUid).toString()
                        )
                    }
                }
                refreshSetupUi()
            } catch (error: Exception) {
                webContextMessage = "Waiting for Family context: ${error.message}"
                refreshSetupUi()
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

    private fun performNextSetupAction() {
        when {
            auth.currentUser == null -> signInWithGoogle()
            !hasForegroundLocation() -> foregroundPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            missingSecondaryPermissions().isNotEmpty() ->
                secondaryPermissionLauncher.launch(missingSecondaryPermissions().toTypedArray())
            !hasBackgroundLocation() -> requestBackgroundLocation()
            app.preferences.familyId.isNullOrBlank() -> {
                binding.webView.loadUrl(BuildConfig.PWA_URL)
                webContextMessage = "Open the Family screen and wait for linking."
                refreshSetupUi()
            }
            app.preferences.trackingEnabled -> {
                startService(TrackingService.stopIntent(this))
                app.preferences.trackingEnabled = false
                refreshSetupUi()
            }
            else -> {
                ContextCompat.startForegroundService(this, TrackingService.startIntent(this))
                app.preferences.trackingEnabled = true
                refreshSetupUi()
            }
        }
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
                resolveFamilyNatively()
                readFamilyContextFromPwa()
                refreshSetupUi()
            } catch (error: Exception) {
                logger.error("Auth", "Google/Firebase sign-in failed", error.toString())
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
            refreshSetupUi()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow background location")
            .setMessage(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    "Choose Location, then select “Allow all the time”. This is required for family tracking with the screen locked."
                else
                    "Allow location all the time so trips continue with the screen locked."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openAppSettings()
                } else {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            .show()
    }

    private fun resolveFamilyNatively() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            val familyId = FamilyResolver(
                com.google.firebase.firestore.FirebaseFirestore.getInstance(),
                logger
            ).resolveSingleFamily(uid)
            if (familyId != null) {
                app.preferences.familyId = familyId
                refreshSetupUi()
            }
        }
    }

    private fun refreshSetupUi() {
        val signedIn = auth.currentUser != null
        val foreground = hasForegroundLocation()
        val background = hasBackgroundLocation()
        val secondary = missingSecondaryPermissions().isEmpty()
        val family = !app.preferences.familyId.isNullOrBlank()
        val tracking = app.preferences.trackingEnabled
        val batteryExempt = isIgnoringBatteryOptimizations()

        binding.statusText.text = buildString {
            append("Firebase: ${if (signedIn) "signed in" else "not signed in"}")
            append(" · foreground GPS: ${if (foreground) "granted" else "missing"}")
            append(" · background GPS: ${if (background) "granted" else "missing"}")
            append(" · activity/notification: ${if (secondary) "granted" else "missing"}")
            append("\nFamily: ${app.preferences.familyId ?: "not linked"}")
            append(" · tracking: ${if (tracking) "ACTIVE" else "stopped"}")
            append(" · battery unrestricted: ${if (batteryExempt) "yes" else "no"}")
            webContextMessage?.let { append("\n$it") }
        }
        binding.primaryButton.text = when {
            !signedIn -> getString(R.string.sign_in_google)
            !foreground || !secondary || !background -> getString(R.string.grant_permissions)
            !family -> "Link Family from PWA"
            tracking -> getString(R.string.stop_tracking)
            else -> getString(R.string.start_tracking)
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

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
