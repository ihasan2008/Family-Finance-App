package com.example.core.firebase

import android.content.Context
import com.example.BuildConfig
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.firestoreSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppEnvironment(val displayName: String) {
    DEVELOPMENT("Development"),
    PRODUCTION("Production")
}

sealed interface AppCheckStatus {
    data object NotConfigured : AppCheckStatus
    data class DebugProviderActive(val hasDebugTokenExtra: Boolean) : AppCheckStatus
    data object ReleaseRecaptchaActive : AppCheckStatus
    data object ReleaseSiteKeyMissing : AppCheckStatus
    data class Error(val reason: String) : AppCheckStatus

    val label: String
        get() = when (this) {
            NotConfigured -> "Not Configured"
            is DebugProviderActive -> if (hasDebugTokenExtra) "Debug Active (Token Ready)" else "Debug Provider Active"
            ReleaseRecaptchaActive -> "reCAPTCHA Enterprise Active"
            ReleaseSiteKeyMissing -> "Release Site Key Missing"
            is Error -> "App Check Error"
        }
}

data class FirebaseConfigSnapshot(
    val environment: AppEnvironment,
    val isProduction: Boolean,
    val projectId: String,
    val applicationId: String,
    val firestoreDatabaseId: String,
    val hasWebClientId: Boolean,
    val persistentCacheEnabled: Boolean,
    val appCheckStatus: AppCheckStatus
)

/**
 * Centralized Firebase configuration and singleton service registry.
 * Never hardcodes credentials; reads provisioned config from google-services.json
 * and firebase_applet_config.xml plus build-variant environment flags from BuildConfig.
 */
object FirebaseServiceRegistry {

    private val _appCheckStatus = MutableStateFlow<AppCheckStatus>(AppCheckStatus.NotConfigured)
    val appCheckStatus: StateFlow<AppCheckStatus> = _appCheckStatus.asStateFlow()

    @Volatile
    private var firestoreInstance: FirebaseFirestore? = null

    val currentEnvironment: AppEnvironment
        get() = if (BuildConfig.IS_PRODUCTION) AppEnvironment.PRODUCTION else AppEnvironment.DEVELOPMENT

    val auth: FirebaseAuth
        get() = Firebase.auth

    internal fun updateAppCheckStatus(status: AppCheckStatus) {
        _appCheckStatus.value = status
    }

    /**
     * Returns the single named Cloud Firestore instance configured via R.string.firestore_database_id
     * with persistent offline disk caching enabled.
     */
    fun firestore(context: Context): FirebaseFirestore {
        return firestoreInstance ?: synchronized(this) {
            firestoreInstance ?: run {
                val appContext = context.applicationContext
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    FirebaseApp.initializeApp(appContext)
                }
                val databaseId = appContext.getString(R.string.firestore_database_id)
                require(databaseId.isNotBlank()) {
                    "R.string.firestore_database_id must not be blank. Check app/src/main/res/values/firebase_applet_config.xml."
                }
                val instance = FirebaseFirestore.getInstance(databaseId)
                instance.firestoreSettings = firestoreSettings {
                    setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                }
                firestoreInstance = instance
                instance
            }
        }
    }

    fun inspectConfiguration(context: Context): FirebaseConfigSnapshot {
        val appContext = context.applicationContext
        val firebaseApp = FirebaseApp.getApps(appContext).firstOrNull()
        val projectId = firebaseApp?.options?.projectId ?: "Not provisioned"
        val databaseId = runCatching { appContext.getString(R.string.firestore_database_id) }
            .getOrDefault("")
            .ifBlank { "Missing firestore_database_id" }
        val hasWebClient = runCatching {
            appContext.getString(R.string.default_web_client_id).isNotBlank()
        }.getOrDefault(false)

        return FirebaseConfigSnapshot(
            environment = currentEnvironment,
            isProduction = BuildConfig.IS_PRODUCTION,
            projectId = projectId,
            applicationId = BuildConfig.APPLICATION_ID,
            firestoreDatabaseId = databaseId,
            hasWebClientId = hasWebClient,
            persistentCacheEnabled = true,
            appCheckStatus = _appCheckStatus.value
        )
    }
}
