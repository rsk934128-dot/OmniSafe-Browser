package com.example

import android.app.Application
import com.example.data.repository.FirestoreClientProvider
import com.example.security.UserGeminiKeyStore

class OnionShieldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firestore named instance with explicit PersistentCacheSettings at app startup
        // so synced bookmarks, history, and monitor logs remain cached for offline access.
        runCatching { FirestoreClientProvider.getInstance(this) }
        runCatching { UserGeminiKeyStore.syncFromPrefs(this) }
    }
}
