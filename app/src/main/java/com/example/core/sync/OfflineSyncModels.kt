package com.example.core.sync

import kotlinx.serialization.Serializable

/**
 * Resolution strategies when an offline mutation encounters a version mismatch or remote change.
 * [MANUAL_REVIEW] is the safe default so conflicting financial edits are never silently lost.
 */
enum class ConflictResolutionPolicy(
    val label: String,
    val description: String
) {
    MANUAL_REVIEW(
        label = "Manual Review (Recommended)",
        description = "Pause conflicting items for side-by-side human review without silent data loss."
    ),
    SERVER_WINS(
        label = "Cloud Wins",
        description = "Accept the Cloud Firestore snapshot and discard conflicting offline edits."
    ),
    CLIENT_WINS(
        label = "This Device Wins",
        description = "Re-queue this device's edit to overwrite the older cloud version."
    );

    companion object {
        fun fromRaw(raw: String?): ConflictResolutionPolicy =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: MANUAL_REVIEW
    }
}

enum class ConflictType(val label: String) {
    VERSION_MISMATCH("Version Mismatch"),
    REMOTE_MODIFIED("Modified on Another Device"),
    REMOTE_DELETED("Deleted in Cloud"),
    DUPLICATE_KEY("Duplicate Key Collision"),
    CROSS_FAMILY_VIOLATION("Family Isolation Blocked"),
    UNAUTHORIZED_ACTOR("Permission Revoked");

    companion object {
        fun fromRaw(raw: String?): ConflictType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: VERSION_MISMATCH
    }
}

enum class ConflictStatus {
    UNRESOLVED,
    RESOLVED_LOCAL,
    RESOLVED_REMOTE,
    MERGED
}

sealed interface OutboxValidationResult {
    data object Valid : OutboxValidationResult
    data class Rejected(
        val reason: String,
        val conflictType: ConflictType,
        val quarantineAsConflict: Boolean = true
    ) : OutboxValidationResult
}

data class SyncRunSummary(
    val familyId: String,
    val connectivityOnline: Boolean,
    val pendingReadCount: Int = 0,
    val validatedCount: Int = 0,
    val rejectedCount: Int = 0,
    val syncedCount: Int = 0,
    val conflictsDetected: Int = 0,
    val deferredErrorCount: Int = 0,
    val reportRefreshed: Boolean = false,
    val executedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class CachedReportCategoryRow(
    val category: String,
    val amountMinor: Long,
    val sharePercent: Int
)

@Serializable
data class CachedFinancialReportPayload(
    val familyId: String,
    val familyName: String,
    val periodKey: String,
    val currency: String,
    val totalIncomeMinor: Long,
    val totalExpenseMinor: Long,
    val netCashFlowMinor: Long,
    val savingsRatePercent: Int,
    val budgetLimitMinor: Long,
    val budgetSpentMinor: Long,
    val budgetUtilizationPercent: Int,
    val loanBorrowedRemainingMinor: Long,
    val loanLentRemainingMinor: Long,
    val savingsCurrentTotalMinor: Long,
    val savingsTargetTotalMinor: Long,
    val investmentPrincipalMinor: Long,
    val investmentCurrentValueMinor: Long,
    val investmentProfitMinor: Long,
    val investmentLossMinor: Long,
    val topExpenseCategories: List<CachedReportCategoryRow> = emptyList(),
    val topIncomeCategories: List<CachedReportCategoryRow> = emptyList(),
    val recordCount: Int = 0,
    val generatedAtMillis: Long = System.currentTimeMillis()
)
