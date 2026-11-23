package com.example.core.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.core.model.MoneyCalculator

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["familyId", "idempotencyKey"], unique = true),
        Index(value = ["familyId", "recordStatus", "occurredAtMillis"]),
        Index(value = ["familyId", "transactionType", "occurredAtMillis"])
    ]
)
data class LocalTransactionEntity(
    @PrimaryKey val recordId: String,
    val familyId: String,
    val createdByUserId: String,
    val memberId: String = createdByUserId,
    val amount: Double,
    val amountMinor: Long = MoneyCalculator.doubleToMinorUnits(amount, "USD"),
    val currency: String,
    val category: String,
    val description: String,
    val recordStatus: String = "ACTIVE",
    val transactionType: String = "EXPENSE",
    val paymentMethod: String? = null,
    val occurredAtMillis: Long = System.currentTimeMillis(),
    val recurrenceFrequency: String = "NONE",
    val nextRecurrenceAtMillis: Long? = null,
    val recurringParentId: String? = null,
    val isRecurringTemplate: Boolean = false,
    val idempotencyKey: String,
    val syncStatus: String = "PENDING_CREATE",
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val deviceId: String = "",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun effectiveAmountMinor(): Long =
        if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)

    fun effectiveMemberId(): String =
        memberId.ifBlank { createdByUserId }
}

@Entity(
    tableName = "loans",
    indices = [
        Index(value = ["familyId", "idempotencyKey"], unique = true),
        Index(value = ["familyId", "recordStatus", "updatedAtMillis"])
    ]
)
data class LocalLoanEntity(
    @PrimaryKey val recordId: String,
    val familyId: String,
    val createdByUserId: String,
    val amount: Double,
    val amountMinor: Long = MoneyCalculator.doubleToMinorUnits(amount, "USD"),
    val remainingBalance: Double,
    val remainingBalanceMinor: Long = MoneyCalculator.doubleToMinorUnits(remainingBalance, "USD"),
    val interestRateApr: Double? = null,
    val currency: String,
    val category: String,
    val description: String,
    val recordStatus: String = "ACTIVE",
    val loanDirection: String = "BORROWED",
    val counterparty: String,
    val borrowerName: String = "",
    val borrowerContact: String = "",
    val lenderName: String = "",
    val lenderContact: String = "",
    val totalInstallments: Int = 1,
    val paidInstallments: Int = 0,
    val installmentAmountMinor: Long = 0L,
    val startDateMillis: Long? = null,
    val dueDateMillis: Long? = null,
    val idempotencyKey: String,
    val syncStatus: String = "PENDING_CREATE",
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val deviceId: String = "",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun effectiveAmountMinor(): Long =
        if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)

    fun effectiveRemainingMinor(): Long =
        if (remainingBalanceMinor > 0L || remainingBalance == 0.0) {
            remainingBalanceMinor.coerceIn(0L, effectiveAmountMinor())
        } else {
            MoneyCalculator.doubleToMinorUnits(remainingBalance, currency).coerceIn(0L, effectiveAmountMinor())
        }

    fun effectiveBorrowerName(): String =
        borrowerName.ifBlank {
            if (loanDirection.equals("BORROWED", ignoreCase = true)) "Family Borrower" else counterparty
        }

    fun effectiveLenderName(): String =
        lenderName.ifBlank {
            if (loanDirection.equals("LENT", ignoreCase = true)) "Family Lender" else counterparty
        }
}

@Entity(
    tableName = "savings",
    indices = [
        Index(value = ["familyId", "idempotencyKey"], unique = true),
        Index(value = ["familyId", "recordStatus", "updatedAtMillis"])
    ]
)
data class LocalSavingsEntity(
    @PrimaryKey val recordId: String,
    val familyId: String,
    val createdByUserId: String,
    val amount: Double,
    val amountMinor: Long = MoneyCalculator.doubleToMinorUnits(amount, "USD"),
    val targetAmount: Double,
    val targetAmountMinor: Long = MoneyCalculator.doubleToMinorUnits(targetAmount, "USD"),
    val currency: String,
    val category: String,
    val description: String,
    val goalName: String,
    val recordStatus: String = "ACTIVE",
    val targetDateMillis: Long? = null,
    val idempotencyKey: String,
    val syncStatus: String = "PENDING_CREATE",
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val deviceId: String = "",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun effectiveCurrentMinor(): Long =
        if (amountMinor > 0L || amount == 0.0) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)

    fun effectiveTargetMinor(): Long =
        if (targetAmountMinor > 0L) targetAmountMinor else MoneyCalculator.doubleToMinorUnits(targetAmount, currency).coerceAtLeast(1L)
}

@Entity(
    tableName = "investments",
    indices = [
        Index(value = ["familyId", "idempotencyKey"], unique = true),
        Index(value = ["familyId", "recordStatus", "updatedAtMillis"])
    ]
)
data class LocalInvestmentEntity(
    @PrimaryKey val recordId: String,
    val familyId: String,
    val createdByUserId: String,
    val amount: Double,
    val amountMinor: Long = MoneyCalculator.doubleToMinorUnits(amount, "USD"),
    val currentValue: Double,
    val currentValueMinor: Long = MoneyCalculator.doubleToMinorUnits(currentValue, "USD"),
    val units: Double? = null,
    val currency: String,
    val category: String,
    val description: String,
    val assetName: String,
    val assetType: String = "ETF",
    val symbol: String? = null,
    val investedAtMillis: Long = System.currentTimeMillis(),
    val recordStatus: String = "ACTIVE",
    val idempotencyKey: String,
    val syncStatus: String = "PENDING_CREATE",
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val deviceId: String = "",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun effectivePrincipalMinor(): Long =
        if (amountMinor > 0L || amount == 0.0) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)

    fun effectiveCurrentValueMinor(): Long =
        if (currentValueMinor > 0L || currentValue == 0.0) currentValueMinor else MoneyCalculator.doubleToMinorUnits(currentValue, currency)

    /** Investment profit calculated strictly separately from principal and loss. */
    fun profitMinor(): Long = (effectiveCurrentValueMinor() - effectivePrincipalMinor()).coerceAtLeast(0L)

    /** Investment loss calculated strictly separately from principal and profit. */
    fun lossMinor(): Long = (effectivePrincipalMinor() - effectiveCurrentValueMinor()).coerceAtLeast(0L)
}

@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["familyId", "idempotencyKey"], unique = true),
        Index(value = ["familyId", "periodKey", "recordStatus"])
    ]
)
data class LocalBudgetEntity(
    @PrimaryKey val recordId: String,
    val familyId: String,
    val createdByUserId: String,
    val amount: Double,
    val amountMinor: Long = MoneyCalculator.doubleToMinorUnits(amount, "USD"),
    val spentAmount: Double = 0.0,
    val spentAmountMinor: Long = MoneyCalculator.doubleToMinorUnits(spentAmount, "USD"),
    val alertThresholdPercent: Int = 80,
    val currency: String,
    val category: String,
    val description: String,
    val periodKey: String,
    val budgetScope: String = "CATEGORY",
    val recordStatus: String = "ACTIVE",
    val idempotencyKey: String,
    val syncStatus: String = "PENDING_CREATE",
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val deviceId: String = "",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun effectiveLimitMinor(): Long =
        if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency).coerceAtLeast(1L)

    fun effectiveSpentMinor(): Long =
        if (spentAmountMinor > 0L || spentAmount == 0.0) spentAmountMinor else MoneyCalculator.doubleToMinorUnits(spentAmount, currency)
}

@Entity(
    tableName = "financial_events",
    indices = [
        Index(value = ["familyId", "moduleType", "eventDateMillis"]),
        Index(value = ["familyId", "parentRecordId", "eventDateMillis"])
    ]
)
data class LocalFinancialEventEntity(
    @PrimaryKey val eventId: String,
    val familyId: String,
    val parentRecordId: String,
    val moduleType: String,
    val eventType: String,
    val actorUserId: String,
    val amountMinor: Long,
    val secondaryAmountMinor: Long? = null,
    val balanceAfterMinor: Long? = null,
    val currency: String,
    val note: String = "",
    val eventDateMillis: Long,
    val syncStatus: String = "PENDING_CREATE",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["familyId", "createdAtMillis"]),
        Index(value = ["familyId", "expiresAtMillis"])
    ]
)
data class LocalChatMessageEntity(
    @PrimaryKey val messageId: String,
    val familyId: String,
    val senderUserId: String,
    val senderDisplayName: String,
    val senderPhotoUrl: String? = null,
    val senderRoleLabel: String = "",
    val messageText: String,
    val messageType: String = "TEXT",
    val relatedRecordId: String? = null,
    val recordStatus: String = "ACTIVE",
    val readByUidsCsv: String = "",
    val editedAtMillis: Long? = null,
    val deletedAtMillis: Long? = null,
    val deletedByUserId: String? = null,
    val expiresAtMillis: Long? = null,
    val syncVersion: Int = 1,
    val syncStatus: String = "PENDING_CREATE",
    val lastSyncedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val readByUids: List<String>
        get() = readByUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun isReadBy(uid: String): Boolean = uid == senderUserId || uid in readByUids

    val isEdited: Boolean
        get() = editedAtMillis != null && recordStatus == "ACTIVE"

    val isVoided: Boolean
        get() = recordStatus == "VOIDED"
}

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["familyId", "recipientUserId", "createdAtMillis"])
    ]
)
data class LocalNotificationEntity(
    @PrimaryKey val notificationId: String,
    val familyId: String,
    val recipientUserId: String,
    val createdByUserId: String,
    val title: String,
    val body: String,
    val notificationType: String = "GENERAL",
    val relatedRecordId: String? = null,
    val deepLinkModule: String? = null,
    val acknowledged: Boolean = false,
    val readByUidsCsv: String = "",
    val recordStatus: String = "ACTIVE",
    val syncStatus: String = "SYNCED",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val readByUids: List<String>
        get() = readByUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun isUnreadFor(uid: String): Boolean =
        recordStatus == "ACTIVE" && !acknowledged && uid !in readByUids
}

@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["familyId", "createdAtMillis"]),
        Index(value = ["familyId", "entityCollection", "createdAtMillis"])
    ]
)
data class LocalAuditLogEntity(
    @PrimaryKey val auditId: String,
    val familyId: String,
    val actorUserId: String,
    val entityCollection: String,
    val entityId: String,
    val action: String,
    val summary: String,
    val afterHash: String? = null,
    val deviceId: String = "",
    val syncStatus: String = "PENDING_CREATE",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["familyId", "status", "createdAtMillis"]),
        Index(value = ["collectionName", "documentId"], unique = true)
    ]
)
data class LocalSyncOutboxEntity(
    @PrimaryKey val outboxId: String,
    val familyId: String,
    val actorUserId: String,
    val collectionName: String,
    val documentId: String,
    val operation: String,
    val status: String = "PENDING",
    val retryCount: Int = 0,
    val lastError: String? = null,
    val syncVersion: Int = 1,
    val baseSyncVersion: Int = 0,
    val operationId: String = outboxId,
    val idempotencyKey: String = "",
    val payloadHash: String = "",
    val resolutionPolicy: String = "MANUAL_REVIEW",
    val deviceId: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["familyId", "status", "createdAtMillis"]),
        Index(value = ["collectionName", "documentId"])
    ]
)
data class LocalSyncConflictEntity(
    @PrimaryKey val conflictId: String,
    val familyId: String,
    val collectionName: String,
    val documentId: String,
    val operationId: String,
    val conflictType: String,
    val localVersion: Int,
    val remoteVersion: Int,
    val localSummary: String,
    val remoteSummary: String,
    val localPayloadJson: String = "",
    val remotePayloadJson: String = "",
    val policy: String = "MANUAL_REVIEW",
    val status: String = "UNRESOLVED",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val resolvedAtMillis: Long? = null,
    val resolvedByUserId: String? = null
)

@Entity(
    tableName = "cached_reports",
    indices = [
        Index(value = ["familyId", "periodKey"], unique = true)
    ]
)
data class LocalCachedReportEntity(
    @PrimaryKey val reportId: String,
    val familyId: String,
    val periodKey: String,
    val currency: String,
    val reportJson: String,
    val recordCount: Int = 0,
    val sourceHash: String = "",
    val generatedAtMillis: Long = System.currentTimeMillis(),
    val syncStatus: String = "CACHED_LOCAL"
)

@Entity(
    tableName = "call_history",
    indices = [
        Index(value = ["familyId", "createdAtMillis"]),
        Index(value = ["familyId", "callerUid"]),
        Index(value = ["familyId", "calleeUid"])
    ]
)
data class LocalCallRecordEntity(
    @PrimaryKey val callId: String,
    val familyId: String,
    val callerUid: String,
    val calleeUid: String,
    val callerName: String,
    val calleeName: String,
    val mediaType: String = "AUDIO",
    val status: String = "ENDED",
    val networkQuality: String = "UNKNOWN",
    val durationSeconds: Int = 0,
    val endedByUid: String? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "meetings",
    indices = [
        Index(value = ["familyId", "createdAtMillis"]),
        Index(value = ["familyId", "inviteCode"])
    ]
)
data class LocalMeetingEntity(
    @PrimaryKey val meetingId: String,
    val familyId: String,
    val roomName: String,
    val title: String,
    val hostUid: String,
    val hostName: String,
    val inviteCode: String,
    val inviteLink: String,
    val status: String = "LIVE",
    val isScheduled: Boolean = false,
    val scheduledForMillis: Long? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val endedByUid: String? = null,
    val audioEnabledDefault: Boolean = true,
    val videoEnabledDefault: Boolean = true,
    val invitedUidsCsv: String = "",
    val activeParticipantUidsCsv: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val invitedUids: List<String>
        get() = invitedUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val activeParticipantUids: List<String>
        get() = activeParticipantUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

