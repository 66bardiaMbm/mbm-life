package com.mbmlife.companion

import android.app.Application
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.mbmlife.companion.data.AppDatabase
import com.mbmlife.companion.data.TrackingPreferences

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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
