package com.mbmlife.companion

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.mbmlife.companion.data.AppDatabase
import com.mbmlife.companion.data.TrackingPreferences
import com.mbmlife.companion.data.TrackingRepository
import com.mbmlife.companion.sync.SyncWorker

class MbmApplication : Application(), Configuration.Provider {
    val database by lazy { AppDatabase.create(this) }
    val preferences by lazy { TrackingPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        FirebaseFirestore.getInstance().firestoreSettings =
            FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()

        // v2 uses one coalesced drain worker. Remove the legacy appended chain
        // so previously blocked work cannot keep the live-location outbox stuck.
        WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.LEGACY_UNIQUE_WORK)
        TrackingRepository(this).scheduleSync()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
