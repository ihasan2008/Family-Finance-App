package com.example.core.firebase

import android.content.Context
import com.example.data.repository.FamilyFinanceRepository
import com.google.firebase.auth.FirebaseUser

data class DatabaseInitReport(
    val configSnapshot: FirebaseConfigSnapshot,
    val profileInitialized: Boolean,
    val pendingFamiliesSynced: Int,
    val warningMessage: String? = null
)

/**
 * Database initialization utility that verifies provisioned Firebase configuration,
 * initializes the authenticated user's profile document, and flushes pending Room writes.
 */
class DatabaseInitializer(
    private val context: Context,
    private val repository: FamilyFinanceRepository
) {
    suspend fun initializeSession(firebaseUser: FirebaseUser): DatabaseInitReport {
        val configSnapshot = FirebaseServiceRegistry.inspectConfiguration(context)
        var warning: String? = null

        val profileResult = repository.ensureUserProfileInitialized(firebaseUser)
        if (profileResult.isFailure) {
            warning = profileResult.exceptionOrNull()?.message
                ?: "Profile synced locally; cloud write deferred."
        }

        val syncResult = repository.syncPendingFamilies(firebaseUser.uid)
        val syncedCount = syncResult.getOrDefault(0)
        if (syncResult.isFailure && warning == null) {
            warning = "Pending offline families will sync when connectivity resumes."
        }

        return DatabaseInitReport(
            configSnapshot = configSnapshot,
            profileInitialized = profileResult.isSuccess,
            pendingFamiliesSynced = syncedCount,
            warningMessage = warning
        )
    }
}
