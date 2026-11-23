package com.example.data.repository

import com.example.core.firebase.FirestoreService
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.abstraction.IdempotentWriteResult
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.AuditLogDoc
import com.example.core.model.BudgetDoc
import com.example.core.model.BudgetScope
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.FamilyNotificationDoc
import com.example.core.model.FinancialEventDoc
import com.example.core.model.FinancialModuleType
import com.example.core.model.InvestmentAssetType
import com.example.core.model.InvestmentDoc
import com.example.core.model.LoanDirection
import com.example.core.model.LoanDoc
import com.example.core.model.MoneyCalculator
import com.example.core.model.NotificationType
import com.example.core.model.RecordStatus
import com.example.core.model.SavingsDoc
import com.example.core.model.SyncState
import com.example.core.remote.OperationType
import com.example.core.remote.handleFirestoreError
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException

/**
 * Handles CRUD, history events, and financial integrity rules for Prompt 08 modules:
 * Loans, Savings, Investments, and Budgets.
 *
 * Financial accounting invariants enforced here:
 * - Loan principal and loan repayments NEVER write to `transactions` (not income / not ordinary expense).
 * - Savings deposits and withdrawals NEVER write to `transactions` (internal transfer, not new income).
 * - Investment principal is tracked separately from unrealized profit and loss.
 */
internal class FinancialModulesDelegate(
    private val firestoreService: FirestoreService,
    private val localStore: LocalDatabaseStore,
    private val auth: FirebaseAuth
) {
    private val db: FirebaseFirestore get() = firestoreService.db

    private fun requireUid(): String =
        auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required.")

    // --- 1. LOANS ---

    suspend fun createLoanMinor(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        loanDirection: LoanDirection,
        principalMinor: Long,
        currency: String,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long,
        dueDateMillis: Long?,
        description: String,
        deviceId: String = "android_device"
    ): Result<IdempotentWriteResult<LocalLoanEntity>> = runCatchingSuspended("loans", OperationType.CREATE) {
        val uid = requireUid()
        require(familyId.isNotBlank()) { "Active family workspace required." }
        require(principalMinor in 1L..100_000_000_000L) { "Loan amount must be greater than zero." }
        val safeTotalInst = totalInstallments.coerceIn(1, 600)
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val cleanCategory = category.trim().take(60).ifBlank { "Personal Loan" }
        val cleanBorrower = borrowerName.trim().take(80)
        val cleanLender = lenderName.trim().take(80)
        val counterparty = if (loanDirection == LoanDirection.BORROWED) {
            cleanLender.ifBlank { "Lender" }
        } else {
            cleanBorrower.ifBlank { "Borrower" }
        }
        val cleanDesc = description.trim().take(300)
        val now = System.currentTimeMillis()
        val safeStart = minOf(startDateMillis, now)
        val resolvedInstallment = if (installmentAmountMinor > 0L) {
            installmentAmountMinor
        } else {
            (principalMinor / safeTotalInst).coerceAtLeast(0L)
        }
        val recordId = DatabaseIdentityHelper.generateRecordId("loan")
        val idempotencyKey = DatabaseIdentityHelper.computeIdempotencyKeyMinor(
            familyId = familyId,
            collectionName = "loans_${loanDirection.name}",
            discriminator = "$counterparty|$cleanCategory|$safeTotalInst",
            amountMinor = principalMinor,
            currency = cleanCurrency,
            timeBucketMillis = now / 60_000L
        )

        val entity = LocalLoanEntity(
            recordId = recordId,
            familyId = familyId,
            createdByUserId = uid,
            amount = MoneyCalculator.minorUnitsToDouble(principalMinor, cleanCurrency),
            amountMinor = principalMinor,
            remainingBalance = MoneyCalculator.minorUnitsToDouble(principalMinor, cleanCurrency),
            remainingBalanceMinor = principalMinor,
            interestRateApr = interestRateApr?.coerceIn(0.0, 200.0),
            currency = cleanCurrency,
            category = cleanCategory,
            description = cleanDesc,
            recordStatus = RecordStatus.ACTIVE.name,
            loanDirection = loanDirection.name,
            counterparty = counterparty,
            borrowerName = cleanBorrower,
            borrowerContact = borrowerContact.trim().take(120),
            lenderName = cleanLender,
            lenderContact = lenderContact.trim().take(120),
            totalInstallments = safeTotalInst,
            paidInstallments = 0,
            installmentAmountMinor = resolvedInstallment,
            startDateMillis = safeStart,
            dueDateMillis = dueDateMillis,
            idempotencyKey = idempotencyKey,
            syncStatus = SyncState.PENDING_CREATE.name,
            syncVersion = 1,
            deviceId = deviceId,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        val formatted = MoneyCalculator.formatMinorUnits(principalMinor, cleanCurrency)
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "loans",
            entityId = recordId,
            action = AuditAction.CREATE.name,
            summary = "Created ${loanDirection.label} loan ($counterparty) $formatted".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$principalMinor|$counterparty"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )

        val localWrite = localStore.saveLoanIdempotent(entity, auditEntity)
        if (localWrite.deduplicatedExisting) return@runCatchingSuspended localWrite

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = localWrite.record.recordId,
            moduleType = FinancialModuleType.LOANS.name,
            eventType = "DISBURSEMENT",
            actorUserId = uid,
            amountMinor = principalMinor,
            balanceAfterMinor = principalMinor,
            currency = cleanCurrency,
            note = "${loanDirection.label} principal recorded ($counterparty)",
            eventDateMillis = safeStart,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val loanPayload = localWrite.record.toFirestoreDoc(safeMembers, safeAdmins)
            .toCreateMap(localWrite.record.dueDateMillis, localWrite.record.startDateMillis)
        val eventPayload = eventEntity.toFirestoreDoc(safeMembers).toCreateMap(safeStart)
        val auditPayload = auditEntity.toCreatePayload(safeMembers, safeAdmins)

        val batchResult = firestoreService.commitBatch("loans/${localWrite.record.recordId}") { batch ->
            batch.set(db.collection("loans").document(localWrite.record.recordId), loanPayload)
            batch.set(db.collection("financialEvents").document(eventId), eventPayload)
            batch.set(db.collection("auditLogs").document(auditId), auditPayload)
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("loans", localWrite.record.recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            localWrite.copy(record = localWrite.record.copy(syncStatus = SyncState.SYNCED.name))
        } else {
            localWrite
        }
    }

    suspend fun updateLoanMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        loanDirection: LoanDirection,
        principalMinor: Long,
        remainingBalanceMinor: Long,
        currency: String,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        paidInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long?,
        dueDateMillis: Long?,
        description: String,
        deviceId: String = "android_device"
    ): Result<LocalLoanEntity> = runCatchingSuspended("loans/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getLoanById(recordId)
            ?: throw IllegalArgumentException("Loan record not found locally.")
        check(existing.familyId == familyId) { "Cross-family loan update denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can edit this loan."
        }
        require(principalMinor in 1L..100_000_000_000L) { "Loan amount must be greater than zero." }
        require(remainingBalanceMinor in 0L..principalMinor) { "Remaining balance cannot exceed principal." }

        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { existing.currency }
        val safeTotalInst = totalInstallments.coerceIn(1, 600)
        val safePaidInst = paidInstallments.coerceIn(0, safeTotalInst)
        val counterparty = if (loanDirection == LoanDirection.BORROWED) {
            lenderName.trim().take(80).ifBlank { existing.counterparty }
        } else {
            borrowerName.trim().take(80).ifBlank { existing.counterparty }
        }
        val nextStatus = if (remainingBalanceMinor == 0L) RecordStatus.SETTLED.name else RecordStatus.ACTIVE.name
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            amount = MoneyCalculator.minorUnitsToDouble(principalMinor, cleanCurrency),
            amountMinor = principalMinor,
            remainingBalance = MoneyCalculator.minorUnitsToDouble(remainingBalanceMinor, cleanCurrency),
            remainingBalanceMinor = remainingBalanceMinor,
            interestRateApr = interestRateApr?.coerceIn(0.0, 200.0),
            currency = cleanCurrency,
            category = category.trim().take(60).ifBlank { existing.category },
            description = description.trim().take(300),
            recordStatus = nextStatus,
            loanDirection = loanDirection.name,
            counterparty = counterparty,
            borrowerName = borrowerName.trim().take(80),
            borrowerContact = borrowerContact.trim().take(120),
            lenderName = lenderName.trim().take(80),
            lenderContact = lenderContact.trim().take(120),
            totalInstallments = safeTotalInst,
            paidInstallments = safePaidInst,
            installmentAmountMinor = installmentAmountMinor.coerceIn(0L, 100_000_000_000L),
            startDateMillis = startDateMillis,
            dueDateMillis = dueDateMillis,
            updatedAtMillis = now
        )

        val auditId = DatabaseIdentityHelper.generateAuditId()
        val formattedBal = MoneyCalculator.formatMinorUnits(remainingBalanceMinor, cleanCurrency)
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "loans",
            entityId = recordId,
            action = AuditAction.UPDATE.name,
            summary = "Updated loan ($counterparty), remaining balance $formattedBal".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$principalMinor|$remainingBalanceMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )

        val saved = localStore.saveLoanIdempotent(updated, auditEntity).record
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val updateMap = saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap(saved.dueDateMillis, saved.startDateMillis)
        val batchResult = firestoreService.commitBatch("loans/$recordId") { batch ->
            batch.update(db.collection("loans").document(recordId), updateMap)
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("loans", recordId, System.currentTimeMillis())
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
    }

    suspend fun recordLoanRepaymentMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        repaymentMinor: Long,
        paymentDateMillis: Long,
        note: String,
        deviceId: String = "android_device"
    ): Result<LocalLoanEntity> = runCatchingSuspended("loans/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getLoanById(recordId)
            ?: throw IllegalArgumentException("Loan not found.")
        check(existing.familyId == familyId) { "Cross-family loan repayment denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the loan creator or a Family Admin can record repayments."
        }
        val currentRemaining = existing.effectiveRemainingMinor()
        require(currentRemaining > 0L) { "This loan is already fully settled." }
        require(repaymentMinor in 1L..currentRemaining) {
            "Repayment must be between 0.01 and remaining balance (${MoneyCalculator.formatMinorUnits(currentRemaining, existing.currency)})."
        }

        val newRemainingMinor = currentRemaining - repaymentMinor
        val nextPaidInst = (existing.paidInstallments + 1).coerceAtMost(existing.totalInstallments.coerceAtLeast(1))
        val newStatus = if (newRemainingMinor == 0L) RecordStatus.SETTLED.name else RecordStatus.ACTIVE.name
        val now = System.currentTimeMillis()
        val safePaymentDate = minOf(paymentDateMillis, now)

        val updated = existing.copy(
            remainingBalance = MoneyCalculator.minorUnitsToDouble(newRemainingMinor, existing.currency),
            remainingBalanceMinor = newRemainingMinor,
            paidInstallments = nextPaidInst,
            recordStatus = newStatus,
            updatedAtMillis = now
        )

        val formattedRepay = MoneyCalculator.formatMinorUnits(repaymentMinor, existing.currency)
        val formattedAfter = MoneyCalculator.formatMinorUnits(newRemainingMinor, existing.currency)
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "loans",
            entityId = recordId,
            action = if (newRemainingMinor == 0L) AuditAction.STATUS_CHANGE.name else AuditAction.UPDATE.name,
            summary = "Loan repayment $formattedRepay for ${existing.counterparty} (balance $formattedAfter)".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|repay|$repaymentMinor|$newRemainingMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val saved = localStore.saveLoanIdempotent(updated, auditEntity).record

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = recordId,
            moduleType = FinancialModuleType.LOANS.name,
            eventType = "REPAYMENT",
            actorUserId = uid,
            amountMinor = repaymentMinor,
            balanceAfterMinor = newRemainingMinor,
            currency = existing.currency,
            note = note.trim().take(300).ifBlank { "Installment #$nextPaidInst" },
            eventDateMillis = safePaymentDate,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val updateMap = saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap(saved.dueDateMillis, saved.startDateMillis)
        val batchResult = firestoreService.commitBatch("loans/$recordId") { batch ->
            batch.update(db.collection("loans").document(recordId), updateMap)
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(safePaymentDate))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("loans", recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
    }

    suspend fun deleteLoan(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        deviceId: String = "android_device"
    ): Result<Unit> = runCatchingSuspended("loans/$recordId", OperationType.DELETE) {
        val uid = requireUid()
        val existing = localStore.getLoanById(recordId) ?: throw IllegalArgumentException("Loan not found.")
        check(existing.familyId == familyId) { "Cross-family delete denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can delete this loan."
        }
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "loans",
            entityId = recordId,
            action = AuditAction.DELETE.name,
            summary = "Deleted loan (${existing.counterparty}) ${MoneyCalculator.formatMinorUnits(existing.effectiveAmountMinor(), existing.currency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("deleted|$recordId"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name
        )
        check(localStore.deleteLoanLocal(recordId, familyId, uid, auditEntity)) { "Failed to delete loan locally." }
        if (localStore.getOutboxForDocument("loans", recordId) == null) return@runCatchingSuspended
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("loans/$recordId") { batch ->
            batch.delete(db.collection("loans").document(recordId))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("loans", recordId, System.currentTimeMillis())
        }
    }

    // --- 2. SAVINGS ---

    suspend fun createSavingsGoalMinor(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        goalName: String,
        category: String,
        initialAmountMinor: Long,
        targetAmountMinor: Long,
        currency: String,
        targetDateMillis: Long?,
        description: String,
        deviceId: String = "android_device"
    ): Result<IdempotentWriteResult<LocalSavingsEntity>> = runCatchingSuspended("savings", OperationType.CREATE) {
        val uid = requireUid()
        require(familyId.isNotBlank()) { "Active family workspace required." }
        val cleanGoal = goalName.trim().take(80)
        require(cleanGoal.isNotEmpty()) { "Savings goal name is required." }
        require(targetAmountMinor in 1L..100_000_000_000L) { "Target amount must be greater than zero." }
        require(initialAmountMinor in 0L..100_000_000_000L) { "Initial amount cannot be negative." }

        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val cleanCategory = category.trim().take(60).ifBlank { "Emergency Fund" }
        val now = System.currentTimeMillis()
        val recordId = DatabaseIdentityHelper.generateRecordId("sav")
        val status = if (initialAmountMinor >= targetAmountMinor) RecordStatus.SETTLED.name else RecordStatus.ACTIVE.name
        val idempotencyKey = DatabaseIdentityHelper.computeIdempotencyKeyMinor(
            familyId = familyId,
            collectionName = "savings",
            discriminator = "$cleanGoal|$cleanCategory",
            amountMinor = targetAmountMinor,
            currency = cleanCurrency,
            timeBucketMillis = now / 60_000L
        )
        val entity = LocalSavingsEntity(
            recordId = recordId,
            familyId = familyId,
            createdByUserId = uid,
            amount = MoneyCalculator.minorUnitsToDouble(initialAmountMinor, cleanCurrency),
            amountMinor = initialAmountMinor,
            targetAmount = MoneyCalculator.minorUnitsToDouble(targetAmountMinor, cleanCurrency),
            targetAmountMinor = targetAmountMinor,
            currency = cleanCurrency,
            category = cleanCategory,
            description = description.trim().take(300),
            goalName = cleanGoal,
            recordStatus = status,
            targetDateMillis = targetDateMillis,
            idempotencyKey = idempotencyKey,
            syncStatus = SyncState.PENDING_CREATE.name,
            syncVersion = 1,
            deviceId = deviceId,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "savings",
            entityId = recordId,
            action = AuditAction.CREATE.name,
            summary = "Created savings goal \"$cleanGoal\" target ${MoneyCalculator.formatMinorUnits(targetAmountMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$targetAmountMinor|$initialAmountMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val localWrite = localStore.saveSavingsIdempotent(entity, auditEntity)
        if (localWrite.deduplicatedExisting) return@runCatchingSuspended localWrite

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = localWrite.record.recordId,
            moduleType = FinancialModuleType.SAVINGS.name,
            eventType = if (initialAmountMinor > 0L) "DEPOSIT" else "GOAL_CREATED",
            actorUserId = uid,
            amountMinor = initialAmountMinor,
            balanceAfterMinor = initialAmountMinor,
            currency = cleanCurrency,
            note = if (initialAmountMinor > 0L) "Opening savings deposit" else "Goal initialized",
            eventDateMillis = now,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("savings/${localWrite.record.recordId}") { batch ->
            batch.set(
                db.collection("savings").document(localWrite.record.recordId),
                localWrite.record.toFirestoreDoc(safeMembers, safeAdmins).toCreateMap(targetDateMillis)
            )
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(now))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        val savedRecord = if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("savings", localWrite.record.recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            localWrite.copy(record = localWrite.record.copy(syncStatus = SyncState.SYNCED.name))
        } else localWrite
        if (initialAmountMinor > 0L) {
            recordSavingsMilestoneIfNeeded(savedRecord.record, safeMembers)
        }
        savedRecord
    }

    suspend fun updateSavingsGoalMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        goalName: String,
        category: String,
        targetAmountMinor: Long,
        currency: String,
        targetDateMillis: Long?,
        description: String,
        deviceId: String = "android_device"
    ): Result<LocalSavingsEntity> = runCatchingSuspended("savings/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getSavingsById(recordId) ?: throw IllegalArgumentException("Savings goal not found.")
        check(existing.familyId == familyId) { "Cross-family savings update denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can edit this savings goal."
        }
        val cleanGoal = goalName.trim().take(80)
        require(cleanGoal.isNotEmpty()) { "Savings goal name is required." }
        require(targetAmountMinor in 1L..100_000_000_000L) { "Target amount must be greater than zero." }

        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { existing.currency }
        val currentMinor = existing.effectiveCurrentMinor()
        val newStatus = if (currentMinor >= targetAmountMinor) RecordStatus.SETTLED.name else RecordStatus.ACTIVE.name
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            goalName = cleanGoal,
            category = category.trim().take(60).ifBlank { existing.category },
            targetAmount = MoneyCalculator.minorUnitsToDouble(targetAmountMinor, cleanCurrency),
            targetAmountMinor = targetAmountMinor,
            currency = cleanCurrency,
            targetDateMillis = targetDateMillis,
            description = description.trim().take(300),
            recordStatus = newStatus,
            updatedAtMillis = now
        )
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "savings",
            entityId = recordId,
            action = AuditAction.UPDATE.name,
            summary = "Updated savings goal \"$cleanGoal\" target ${MoneyCalculator.formatMinorUnits(targetAmountMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$targetAmountMinor|$currentMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val saved = localStore.saveSavingsIdempotent(updated, auditEntity).record
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("savings/$recordId") { batch ->
            batch.update(db.collection("savings").document(recordId), saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap(targetDateMillis))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("savings", recordId, System.currentTimeMillis())
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
    }

    suspend fun recordSavingsMoveMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        moveAmountMinor: Long,
        isDeposit: Boolean,
        moveDateMillis: Long,
        note: String,
        deviceId: String = "android_device"
    ): Result<LocalSavingsEntity> = runCatchingSuspended("savings/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getSavingsById(recordId) ?: throw IllegalArgumentException("Savings goal not found.")
        check(existing.familyId == familyId) { "Cross-family savings access denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the goal creator or a Family Admin can record deposits or withdrawals."
        }
        val currentMinor = existing.effectiveCurrentMinor()
        require(moveAmountMinor > 0L) { "Amount must be greater than zero." }
        val newBalanceMinor = if (isDeposit) {
            (currentMinor + moveAmountMinor).coerceAtMost(100_000_000_000L)
        } else {
            require(moveAmountMinor <= currentMinor) {
                "Withdrawal cannot exceed current saved balance (${MoneyCalculator.formatMinorUnits(currentMinor, existing.currency)})."
            }
            currentMinor - moveAmountMinor
        }
        val targetMinor = existing.effectiveTargetMinor()
        val newStatus = if (newBalanceMinor >= targetMinor) RecordStatus.SETTLED.name else RecordStatus.ACTIVE.name
        val now = System.currentTimeMillis()
        val safeDate = minOf(moveDateMillis, now)

        val updated = existing.copy(
            amount = MoneyCalculator.minorUnitsToDouble(newBalanceMinor, existing.currency),
            amountMinor = newBalanceMinor,
            recordStatus = newStatus,
            updatedAtMillis = now
        )
        val formattedMove = MoneyCalculator.formatMinorUnits(moveAmountMinor, existing.currency)
        val formattedBal = MoneyCalculator.formatMinorUnits(newBalanceMinor, existing.currency)
        val verb = if (isDeposit) "Deposit" else "Withdrawal"
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "savings",
            entityId = recordId,
            action = AuditAction.UPDATE.name,
            summary = "Savings $verb $formattedMove for \"${existing.goalName}\" (balance $formattedBal)".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$verb|$moveAmountMinor|$newBalanceMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val saved = localStore.saveSavingsIdempotent(updated, auditEntity).record

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = recordId,
            moduleType = FinancialModuleType.SAVINGS.name,
            eventType = if (isDeposit) "DEPOSIT" else "WITHDRAWAL",
            actorUserId = uid,
            amountMinor = moveAmountMinor,
            balanceAfterMinor = newBalanceMinor,
            currency = existing.currency,
            note = note.trim().take(300).ifBlank { if (isDeposit) "Savings deposit" else "Savings withdrawal" },
            eventDateMillis = safeDate,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("savings/$recordId") { batch ->
            batch.update(db.collection("savings").document(recordId), saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap(saved.targetDateMillis))
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(safeDate))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        val savedRecord = if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("savings", recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
        if (isDeposit) {
            recordSavingsMilestoneIfNeeded(savedRecord, safeMembers)
        }
        savedRecord
    }

    suspend fun deleteSavingsGoal(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        deviceId: String = "android_device"
    ): Result<Unit> = runCatchingSuspended("savings/$recordId", OperationType.DELETE) {
        val uid = requireUid()
        val existing = localStore.getSavingsById(recordId) ?: throw IllegalArgumentException("Savings goal not found.")
        check(existing.familyId == familyId) { "Cross-family delete denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can delete this savings goal."
        }
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "savings",
            entityId = recordId,
            action = AuditAction.DELETE.name,
            summary = "Deleted savings goal \"${existing.goalName}\"".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("deleted|$recordId"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name
        )
        check(localStore.deleteSavingsLocal(recordId, familyId, uid, auditEntity)) { "Unable to delete savings goal locally." }
        if (localStore.getOutboxForDocument("savings", recordId) == null) return@runCatchingSuspended
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("savings/$recordId") { batch ->
            batch.delete(db.collection("savings").document(recordId))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("savings", recordId, System.currentTimeMillis())
        }
    }

    // --- 3. INVESTMENTS ---

    suspend fun createInvestmentMinor(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        currency: String,
        investedAtMillis: Long,
        description: String,
        deviceId: String = "android_device"
    ): Result<IdempotentWriteResult<LocalInvestmentEntity>> = runCatchingSuspended("investments", OperationType.CREATE) {
        val uid = requireUid()
        require(familyId.isNotBlank()) { "Active family workspace required." }
        val cleanName = assetName.trim().take(80)
        require(cleanName.isNotEmpty()) { "Investment name is required." }
        require(principalMinor in 1L..100_000_000_000L) { "Principal amount must be greater than zero." }
        require(currentValueMinor in 0L..100_000_000_000L) { "Current value cannot be negative." }

        val now = System.currentTimeMillis()
        val safeInvestedAt = minOf(investedAtMillis, now)
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val cleanSymbol = symbol?.trim()?.uppercase()?.take(20)?.takeIf { it.isNotEmpty() }
        val recordId = DatabaseIdentityHelper.generateRecordId("inv")
        val idempotencyKey = DatabaseIdentityHelper.computeIdempotencyKeyMinor(
            familyId = familyId,
            collectionName = "investments_${assetType.name}",
            discriminator = "$cleanName|${cleanSymbol ?: ""}",
            amountMinor = principalMinor,
            currency = cleanCurrency,
            timeBucketMillis = now / 60_000L
        )
        val entity = LocalInvestmentEntity(
            recordId = recordId,
            familyId = familyId,
            createdByUserId = uid,
            amount = MoneyCalculator.minorUnitsToDouble(principalMinor, cleanCurrency),
            amountMinor = principalMinor,
            currentValue = MoneyCalculator.minorUnitsToDouble(currentValueMinor, cleanCurrency),
            currentValueMinor = currentValueMinor,
            units = units?.coerceAtLeast(0.0),
            currency = cleanCurrency,
            category = assetType.label,
            description = description.trim().take(300),
            assetName = cleanName,
            assetType = assetType.name,
            symbol = cleanSymbol,
            investedAtMillis = safeInvestedAt,
            recordStatus = RecordStatus.ACTIVE.name,
            idempotencyKey = idempotencyKey,
            syncStatus = SyncState.PENDING_CREATE.name,
            syncVersion = 1,
            deviceId = deviceId,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "investments",
            entityId = recordId,
            action = AuditAction.CREATE.name,
            summary = "Added investment \"$cleanName\" principal ${MoneyCalculator.formatMinorUnits(principalMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$principalMinor|$currentValueMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val localWrite = localStore.saveInvestmentIdempotent(entity, auditEntity)
        if (localWrite.deduplicatedExisting) return@runCatchingSuspended localWrite

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = localWrite.record.recordId,
            moduleType = FinancialModuleType.INVESTMENTS.name,
            eventType = "INITIAL_INVESTMENT",
            actorUserId = uid,
            amountMinor = currentValueMinor,
            secondaryAmountMinor = principalMinor,
            balanceAfterMinor = currentValueMinor,
            currency = cleanCurrency,
            note = "Initial principal recorded (${assetType.label})",
            eventDateMillis = safeInvestedAt,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("investments/${localWrite.record.recordId}") { batch ->
            batch.set(
                db.collection("investments").document(localWrite.record.recordId),
                localWrite.record.toFirestoreDoc(safeMembers, safeAdmins).toCreateMap(safeInvestedAt)
            )
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(safeInvestedAt))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("investments", localWrite.record.recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            localWrite.copy(record = localWrite.record.copy(syncStatus = SyncState.SYNCED.name))
        } else localWrite
    }

    suspend fun updateInvestmentMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        currency: String,
        investedAtMillis: Long,
        description: String,
        note: String = "",
        deviceId: String = "android_device"
    ): Result<LocalInvestmentEntity> = runCatchingSuspended("investments/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getInvestmentById(recordId) ?: throw IllegalArgumentException("Investment not found.")
        check(existing.familyId == familyId) { "Cross-family investment update denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can update this investment."
        }
        val cleanName = assetName.trim().take(80)
        require(cleanName.isNotEmpty()) { "Investment name is required." }
        require(principalMinor in 1L..100_000_000_000L) { "Principal amount must be greater than zero." }
        require(currentValueMinor in 0L..100_000_000_000L) { "Current value cannot be negative." }

        val now = System.currentTimeMillis()
        val safeInvestedAt = minOf(investedAtMillis, now)
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { existing.currency }
        val updated = existing.copy(
            amount = MoneyCalculator.minorUnitsToDouble(principalMinor, cleanCurrency),
            amountMinor = principalMinor,
            currentValue = MoneyCalculator.minorUnitsToDouble(currentValueMinor, cleanCurrency),
            currentValueMinor = currentValueMinor,
            units = units?.coerceAtLeast(0.0),
            currency = cleanCurrency,
            category = assetType.label,
            description = description.trim().take(300),
            assetName = cleanName,
            assetType = assetType.name,
            symbol = symbol?.trim()?.uppercase()?.take(20)?.takeIf { it.isNotEmpty() },
            investedAtMillis = safeInvestedAt,
            updatedAtMillis = now
        )
        val valChanged = currentValueMinor != existing.effectiveCurrentValueMinor() || principalMinor != existing.effectivePrincipalMinor()
        val eventType = when {
            principalMinor > existing.effectivePrincipalMinor() -> "CAPITAL_ADDED"
            valChanged -> "VALUATION_UPDATE"
            else -> "DETAILS_UPDATED"
        }
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "investments",
            entityId = recordId,
            action = AuditAction.UPDATE.name,
            summary = "Updated \"$cleanName\" value ${MoneyCalculator.formatMinorUnits(currentValueMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$principalMinor|$currentValueMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val saved = localStore.saveInvestmentIdempotent(updated, auditEntity).record

        var createdEventId: String? = null
        var createdEventEntity: LocalFinancialEventEntity? = null
        if (valChanged) {
            val eventId = DatabaseIdentityHelper.generateEventId()
            val eventEntity = LocalFinancialEventEntity(
                eventId = eventId,
                familyId = familyId,
                parentRecordId = recordId,
                moduleType = FinancialModuleType.INVESTMENTS.name,
                eventType = eventType,
                actorUserId = uid,
                amountMinor = currentValueMinor,
                secondaryAmountMinor = principalMinor,
                balanceAfterMinor = currentValueMinor,
                currency = cleanCurrency,
                note = note.trim().take(300).ifBlank {
                    val gain = (currentValueMinor - principalMinor)
                    when {
                        gain > 0L -> "Unrealized profit ${MoneyCalculator.formatMinorUnits(gain, cleanCurrency)}"
                        gain < 0L -> "Unrealized loss ${MoneyCalculator.formatMinorUnits(-gain, cleanCurrency)}"
                        else -> "At break-even principal"
                    }
                },
                eventDateMillis = now,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )
            localStore.saveFinancialEventWithOutbox(eventEntity)
            createdEventId = eventId
            createdEventEntity = eventEntity
        }

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("investments/$recordId") { batch ->
            batch.update(db.collection("investments").document(recordId), saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap(safeInvestedAt))
            if (createdEventEntity != null && createdEventId != null) {
                batch.set(db.collection("financialEvents").document(createdEventId), createdEventEntity.toFirestoreDoc(safeMembers).toCreateMap(now))
            }
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("investments", recordId, syncedAt)
            createdEventId?.let { localStore.markOutboxSynced("financialEvents", it, syncedAt) }
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
    }

    suspend fun deleteInvestment(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        deviceId: String = "android_device"
    ): Result<Unit> = runCatchingSuspended("investments/$recordId", OperationType.DELETE) {
        val uid = requireUid()
        val existing = localStore.getInvestmentById(recordId) ?: throw IllegalArgumentException("Investment not found.")
        check(existing.familyId == familyId) { "Cross-family delete denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can delete this investment."
        }
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "investments",
            entityId = recordId,
            action = AuditAction.DELETE.name,
            summary = "Deleted investment \"${existing.assetName}\"".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("deleted|$recordId"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name
        )
        check(localStore.deleteInvestmentLocal(recordId, familyId, uid, auditEntity)) { "Unable to delete investment locally." }
        if (localStore.getOutboxForDocument("investments", recordId) == null) return@runCatchingSuspended
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("investments/$recordId") { batch ->
            batch.delete(db.collection("investments").document(recordId))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("investments", recordId, System.currentTimeMillis())
        }
    }

    // --- 4. BUDGETS ---

    suspend fun createBudgetMinor(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        currency: String,
        description: String,
        deviceId: String = "android_device"
    ): Result<IdempotentWriteResult<LocalBudgetEntity>> = runCatchingSuspended("budgets", OperationType.CREATE) {
        val uid = requireUid()
        require(familyId.isNotBlank()) { "Active family workspace required." }
        require(limitMinor in 1L..100_000_000_000L) { "Spending limit must be greater than zero." }
        val cleanPeriod = periodKey.trim()
        require(cleanPeriod.matches(Regex("^20\\d{2}-(0[1-9]|1[0-2])$"))) { "Select a valid month (YYYY-MM)." }
        val cleanCategory = if (budgetScope == BudgetScope.MONTHLY_OVERALL) "All Categories" else category.trim().take(60).ifBlank { "Food" }
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val safeThreshold = alertThresholdPercent.coerceIn(10, 100)
        val now = System.currentTimeMillis()
        val recordId = DatabaseIdentityHelper.generateRecordId("bud")

        // Deterministic key per (family, periodKey, budgetScope, category) prevents duplicate active budgets
        val idempotencyKey = DatabaseIdentityHelper.computeIdempotencyKeyMinor(
            familyId = familyId,
            collectionName = "budgets_${budgetScope.name}",
            discriminator = "$cleanPeriod|$cleanCategory",
            amountMinor = 0L,
            currency = cleanCurrency,
            timeBucketMillis = 0L
        )
        val entity = LocalBudgetEntity(
            recordId = recordId,
            familyId = familyId,
            createdByUserId = uid,
            amount = MoneyCalculator.minorUnitsToDouble(limitMinor, cleanCurrency),
            amountMinor = limitMinor,
            spentAmount = 0.0,
            spentAmountMinor = 0L,
            alertThresholdPercent = safeThreshold,
            currency = cleanCurrency,
            category = cleanCategory,
            description = description.trim().take(300),
            periodKey = cleanPeriod,
            budgetScope = budgetScope.name,
            recordStatus = RecordStatus.ACTIVE.name,
            idempotencyKey = idempotencyKey,
            syncStatus = SyncState.PENDING_CREATE.name,
            syncVersion = 1,
            deviceId = deviceId,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "budgets",
            entityId = recordId,
            action = AuditAction.CREATE.name,
            summary = "Set $cleanPeriod ${budgetScope.label} budget ($cleanCategory) ${MoneyCalculator.formatMinorUnits(limitMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$cleanPeriod|$cleanCategory|$limitMinor"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val localWrite = localStore.saveBudgetIdempotent(entity, auditEntity)
        if (localWrite.deduplicatedExisting) return@runCatchingSuspended localWrite

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = localWrite.record.recordId,
            moduleType = FinancialModuleType.BUDGETS.name,
            eventType = "BUDGET_CREATED",
            actorUserId = uid,
            amountMinor = limitMinor,
            balanceAfterMinor = limitMinor,
            currency = cleanCurrency,
            note = "$cleanPeriod • $cleanCategory (${safeThreshold}% alert threshold)",
            eventDateMillis = now,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("budgets/${localWrite.record.recordId}") { batch ->
            batch.set(db.collection("budgets").document(localWrite.record.recordId), localWrite.record.toFirestoreDoc(safeMembers, safeAdmins).toCreateMap())
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(now))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("budgets", localWrite.record.recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            localWrite.copy(record = localWrite.record.copy(syncStatus = SyncState.SYNCED.name))
        } else localWrite
    }

    suspend fun updateBudgetMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        currency: String,
        description: String,
        deviceId: String = "android_device"
    ): Result<LocalBudgetEntity> = runCatchingSuspended("budgets/$recordId", OperationType.UPDATE) {
        val uid = requireUid()
        val existing = localStore.getBudgetById(recordId) ?: throw IllegalArgumentException("Budget not found.")
        check(existing.familyId == familyId) { "Cross-family budget update denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can edit this budget."
        }
        require(limitMinor in 1L..100_000_000_000L) { "Spending limit must be greater than zero." }
        val cleanPeriod = periodKey.trim()
        require(cleanPeriod.matches(Regex("^20\\d{2}-(0[1-9]|1[0-2])$"))) { "Select a valid month (YYYY-MM)." }
        val cleanCategory = if (budgetScope == BudgetScope.MONTHLY_OVERALL) "All Categories" else category.trim().take(60).ifBlank { existing.category }
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { existing.currency }
        val safeThreshold = alertThresholdPercent.coerceIn(10, 100)
        val now = System.currentTimeMillis()

        val updated = existing.copy(
            amount = MoneyCalculator.minorUnitsToDouble(limitMinor, cleanCurrency),
            amountMinor = limitMinor,
            alertThresholdPercent = safeThreshold,
            currency = cleanCurrency,
            category = cleanCategory,
            description = description.trim().take(300),
            periodKey = cleanPeriod,
            budgetScope = budgetScope.name,
            updatedAtMillis = now
        )
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "budgets",
            entityId = recordId,
            action = AuditAction.UPDATE.name,
            summary = "Updated $cleanPeriod budget ($cleanCategory) limit to ${MoneyCalculator.formatMinorUnits(limitMinor, cleanCurrency)}".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$limitMinor|$safeThreshold"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        val saved = localStore.saveBudgetIdempotent(updated, auditEntity).record

        val eventId = DatabaseIdentityHelper.generateEventId()
        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = familyId,
            parentRecordId = recordId,
            moduleType = FinancialModuleType.BUDGETS.name,
            eventType = "LIMIT_UPDATED",
            actorUserId = uid,
            amountMinor = limitMinor,
            balanceAfterMinor = limitMinor,
            currency = cleanCurrency,
            note = "$cleanPeriod • $cleanCategory (${safeThreshold}% threshold)",
            eventDateMillis = now,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.saveFinancialEventWithOutbox(eventEntity)

        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("budgets/$recordId") { batch ->
            batch.update(db.collection("budgets").document(recordId), saved.toFirestoreDoc(safeMembers, safeAdmins).toUpdateMap())
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(now))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("budgets", recordId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
            saved.copy(syncStatus = SyncState.SYNCED.name)
        } else saved
    }

    suspend fun recordBudgetAlertIfNeeded(
        budget: LocalBudgetEntity,
        spentMinor: Long,
        alertLevel: String,
        memberUids: List<String>
    ) {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        val cleanLevel = alertLevel.trim().uppercase()
        if (cleanLevel != "WARNING" && cleanLevel != "EXCEEDED") return
        val notifId = "ntf_${budget.recordId.take(40)}_${cleanLevel.lowercase()}"
        if (localStore.getNotificationById(notifId) != null) return

        val limitMinor = budget.effectiveLimitMinor()
        val eventId = "evt_${budget.recordId.take(40)}_${cleanLevel.lowercase()}"
        val now = System.currentTimeMillis()
        val formattedSpent = MoneyCalculator.formatMinorUnits(spentMinor, budget.currency)
        val formattedLimit = MoneyCalculator.formatMinorUnits(limitMinor, budget.currency)
        val title = if (cleanLevel == "EXCEEDED") {
            "Budget Exceeded: ${budget.category}"
        } else {
            "Budget Alert (${budget.alertThresholdPercent}%): ${budget.category}"
        }
        val body = "${budget.periodKey} spending reached $formattedSpent of $formattedLimit limit."

        val notifEntity = LocalNotificationEntity(
            notificationId = notifId,
            familyId = budget.familyId,
            recipientUserId = "ALL_MEMBERS",
            createdByUserId = uid,
            title = title.take(100),
            body = body.take(400),
            notificationType = NotificationType.BUDGET_ALERT.name,
            relatedRecordId = budget.recordId,
            deepLinkModule = "BUDGETS",
            acknowledged = false,
            recordStatus = RecordStatus.ACTIVE.name,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        localStore.saveNotificationWithOutbox(notifEntity)

        val eventEntity = LocalFinancialEventEntity(
            eventId = eventId,
            familyId = budget.familyId,
            parentRecordId = budget.recordId,
            moduleType = FinancialModuleType.BUDGETS.name,
            eventType = "ALERT_$cleanLevel",
            actorUserId = uid,
            amountMinor = spentMinor,
            secondaryAmountMinor = limitMinor,
            balanceAfterMinor = (limitMinor - spentMinor).coerceAtLeast(0L),
            currency = budget.currency,
            note = body.take(300),
            eventDateMillis = now,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        if (localStore.getFinancialEventById(eventId) == null) {
            localStore.saveFinancialEventWithOutbox(eventEntity)
        }

        val safeMembers = (memberUids + uid).distinct().take(20)
        val res = firestoreService.commitBatch("notifications/$notifId") { batch ->
            batch.set(
                db.collection("notifications").document(notifId),
                FamilyNotificationDoc(
                    notificationId = notifId,
                    familyId = budget.familyId,
                    recipientUserId = "ALL_MEMBERS",
                    createdByUserId = uid,
                    title = notifEntity.title,
                    body = notifEntity.body,
                    notificationType = NotificationType.BUDGET_ALERT.name,
                    relatedRecordId = budget.recordId,
                    deepLinkModule = "BUDGETS",
                    acknowledged = false,
                    recordStatus = RecordStatus.ACTIVE.name,
                    memberUids = safeMembers
                ).toCreateMap()
            )
            batch.set(db.collection("financialEvents").document(eventId), eventEntity.toFirestoreDoc(safeMembers).toCreateMap(now))
        }
        if (res.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            localStore.markOutboxSynced("notifications", notifId, syncedAt)
            localStore.markOutboxSynced("financialEvents", eventId, syncedAt)
        }
    }

    suspend fun recordSavingsMilestoneIfNeeded(
        goal: LocalSavingsEntity,
        memberUids: List<String>
    ) {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        val targetMinor = goal.effectiveTargetMinor()
        if (targetMinor <= 0L) return
        val currentMinor = goal.effectiveCurrentMinor()
        val progressPct = ((currentMinor * 100L) / targetMinor).toInt().coerceAtLeast(0)
        val highestMilestone = listOf(100, 75, 50, 25).firstOrNull { progressPct >= it } ?: return
        val notifId = "ntf_sav_${goal.recordId.take(26)}_ms_$highestMilestone"
        if (localStore.getNotificationById(notifId) != null) return

        val now = System.currentTimeMillis()
        val formattedSaved = MoneyCalculator.formatMinorUnits(currentMinor, goal.currency)
        val formattedTarget = MoneyCalculator.formatMinorUnits(targetMinor, goal.currency)
        val title = if (highestMilestone >= 100) {
            "Savings Goal Reached: ${goal.goalName}"
        } else {
            "Savings Milestone ($highestMilestone%): ${goal.goalName}"
        }
        val body = "Saved $formattedSaved of $formattedTarget target ($progressPct% complete)."
        val safeMembers = (memberUids + uid).distinct().take(20)
        val notifEntity = LocalNotificationEntity(
            notificationId = notifId,
            familyId = goal.familyId,
            recipientUserId = "ALL_MEMBERS",
            createdByUserId = uid,
            title = title.take(100),
            body = body.take(400),
            notificationType = NotificationType.SAVINGS_MILESTONE.name,
            relatedRecordId = goal.recordId,
            deepLinkModule = "SAVINGS",
            acknowledged = false,
            recordStatus = RecordStatus.ACTIVE.name,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        localStore.saveNotificationWithOutbox(notifEntity)
        val res = firestoreService.setDocument(
            db.collection("notifications").document(notifId),
            FamilyNotificationDoc(
                notificationId = notifId,
                familyId = goal.familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = notifEntity.title,
                body = notifEntity.body,
                notificationType = NotificationType.SAVINGS_MILESTONE.name,
                relatedRecordId = goal.recordId,
                deepLinkModule = "SAVINGS",
                acknowledged = false,
                recordStatus = RecordStatus.ACTIVE.name,
                memberUids = safeMembers
            ).toCreateMap(),
            "notifications/$notifId"
        )
        if (res.isSuccess) {
            localStore.markOutboxSynced("notifications", notifId, System.currentTimeMillis())
        }
    }

    suspend fun evaluateLoanDueDateNotifications(
        familyId: String,
        loans: List<LocalLoanEntity>,
        memberUids: List<String>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        val safeMembers = (memberUids + uid).distinct().take(20)
        val threeDaysMillis = 3L * 86_400_000L

        for (loan in loans) {
            if (loan.familyId != familyId) continue
            if (loan.recordStatus != RecordStatus.ACTIVE.name) continue
            val dueMillis = loan.dueDateMillis ?: continue
            val remainingMinor = loan.effectiveRemainingMinor()
            if (remainingMinor <= 0L) continue

            val diffMillis = dueMillis - nowMillis
            val bucket = when {
                diffMillis < 0L -> "overdue"
                diffMillis <= threeDaysMillis -> "due3d"
                else -> continue
            }
            val notifId = "ntf_loan_${loan.recordId.take(28)}_$bucket"
            if (localStore.getNotificationById(notifId) != null) continue

            val formattedRem = MoneyCalculator.formatMinorUnits(remainingMinor, loan.currency)
            val title = if (bucket == "overdue") {
                "Loan Overdue: ${loan.counterparty}"
            } else {
                "Loan Due Soon: ${loan.counterparty}"
            }
            val daysText = if (bucket == "overdue") {
                val overdueDays = ((-diffMillis) / 86_400_000L).coerceAtLeast(1L)
                "${overdueDays}d overdue"
            } else {
                val leftDays = (diffMillis / 86_400_000L).coerceAtLeast(0L)
                if (leftDays == 0L) "due today" else "due in ${leftDays}d"
            }
            val body = "${loan.loanDirection.lowercase().replaceFirstChar { it.uppercase() }} balance $formattedRem is $daysText."
            val entity = LocalNotificationEntity(
                notificationId = notifId,
                familyId = familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = title.take(100),
                body = body.take(400),
                notificationType = NotificationType.LOAN_DUE_DATE.name,
                relatedRecordId = loan.recordId,
                deepLinkModule = "LOANS",
                acknowledged = false,
                recordStatus = RecordStatus.ACTIVE.name,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
            localStore.saveNotificationWithOutbox(entity)
            val res = firestoreService.setDocument(
                db.collection("notifications").document(notifId),
                FamilyNotificationDoc(
                    notificationId = notifId,
                    familyId = familyId,
                    recipientUserId = "ALL_MEMBERS",
                    createdByUserId = uid,
                    title = entity.title,
                    body = entity.body,
                    notificationType = NotificationType.LOAN_DUE_DATE.name,
                    relatedRecordId = loan.recordId,
                    deepLinkModule = "LOANS",
                    acknowledged = false,
                    recordStatus = RecordStatus.ACTIVE.name,
                    memberUids = safeMembers
                ).toCreateMap(),
                "notifications/$notifId"
            )
            if (res.isSuccess) {
                localStore.markOutboxSynced("notifications", notifId, System.currentTimeMillis())
            }
        }
    }

    suspend fun deleteBudget(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        deviceId: String = "android_device"
    ): Result<Unit> = runCatchingSuspended("budgets/$recordId", OperationType.DELETE) {
        val uid = requireUid()
        val existing = localStore.getBudgetById(recordId) ?: throw IllegalArgumentException("Budget not found.")
        check(existing.familyId == familyId) { "Cross-family delete denied." }
        check(existing.createdByUserId == uid || uid in adminUids) {
            "Only the creator or a Family Admin can delete this budget."
        }
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val auditEntity = LocalAuditLogEntity(
            auditId = auditId,
            familyId = familyId,
            actorUserId = uid,
            entityCollection = "budgets",
            entityId = recordId,
            action = AuditAction.DELETE.name,
            summary = "Deleted ${existing.periodKey} budget (${existing.category})".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("deleted|$recordId"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name
        )
        check(localStore.deleteBudgetLocal(recordId, familyId, uid, auditEntity)) { "Unable to delete budget locally." }
        if (localStore.getOutboxForDocument("budgets", recordId) == null) return@runCatchingSuspended
        val safeMembers = (memberUids + uid).distinct().take(20)
        val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
        val batchResult = firestoreService.commitBatch("budgets/$recordId") { batch ->
            batch.delete(db.collection("budgets").document(recordId))
            batch.set(db.collection("auditLogs").document(auditId), auditEntity.toCreatePayload(safeMembers, safeAdmins))
        }
        if (batchResult.isSuccess) {
            localStore.markOutboxSynced("budgets", recordId, System.currentTimeMillis())
        }
    }

    private suspend inline fun <T> runCatchingSuspended(
        path: String,
        operation: OperationType,
        crossinline block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, operation, path)
        Result.failure(e)
    }
}

internal fun LocalLoanEntity.toFirestoreDoc(memberUids: List<String>, adminUids: List<String>): LoanDoc =
    LoanDoc(
        recordId = recordId,
        familyId = familyId,
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(effectiveAmountMinor(), currency),
        amountMinor = effectiveAmountMinor(),
        remainingBalance = MoneyCalculator.minorUnitsToDouble(effectiveRemainingMinor(), currency),
        remainingBalanceMinor = effectiveRemainingMinor(),
        interestRateApr = interestRateApr,
        currency = currency,
        category = category,
        description = description,
        recordStatus = recordStatus,
        loanDirection = loanDirection,
        counterparty = counterparty,
        borrowerName = borrowerName,
        borrowerContact = borrowerContact,
        lenderName = lenderName,
        lenderContact = lenderContact,
        totalInstallments = totalInstallments.toLong(),
        paidInstallments = paidInstallments.toLong(),
        installmentAmountMinor = installmentAmountMinor,
        idempotencyKey = idempotencyKey,
        syncVersion = syncVersion.toLong(),
        deviceId = deviceId,
        memberUids = memberUids,
        adminUids = adminUids
    )

internal fun LocalSavingsEntity.toFirestoreDoc(memberUids: List<String>, adminUids: List<String>): SavingsDoc =
    SavingsDoc(
        recordId = recordId,
        familyId = familyId,
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(effectiveCurrentMinor(), currency),
        amountMinor = effectiveCurrentMinor(),
        targetAmount = MoneyCalculator.minorUnitsToDouble(effectiveTargetMinor(), currency),
        targetAmountMinor = effectiveTargetMinor(),
        currency = currency,
        category = category,
        description = description,
        goalName = goalName,
        recordStatus = recordStatus,
        idempotencyKey = idempotencyKey,
        syncVersion = syncVersion.toLong(),
        deviceId = deviceId,
        memberUids = memberUids,
        adminUids = adminUids
    )

internal fun LocalInvestmentEntity.toFirestoreDoc(memberUids: List<String>, adminUids: List<String>): InvestmentDoc =
    InvestmentDoc(
        recordId = recordId,
        familyId = familyId,
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(effectivePrincipalMinor(), currency),
        amountMinor = effectivePrincipalMinor(),
        currentValue = MoneyCalculator.minorUnitsToDouble(effectiveCurrentValueMinor(), currency),
        currentValueMinor = effectiveCurrentValueMinor(),
        units = units,
        currency = currency,
        category = category,
        description = description,
        assetName = assetName,
        assetType = assetType,
        symbol = symbol,
        recordStatus = recordStatus,
        idempotencyKey = idempotencyKey,
        syncVersion = syncVersion.toLong(),
        deviceId = deviceId,
        memberUids = memberUids,
        adminUids = adminUids
    )

internal fun LocalBudgetEntity.toFirestoreDoc(memberUids: List<String>, adminUids: List<String>): BudgetDoc =
    BudgetDoc(
        recordId = recordId,
        familyId = familyId,
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(effectiveLimitMinor(), currency),
        amountMinor = effectiveLimitMinor(),
        spentAmount = MoneyCalculator.minorUnitsToDouble(effectiveSpentMinor(), currency),
        spentAmountMinor = effectiveSpentMinor(),
        alertThresholdPercent = alertThresholdPercent.toLong(),
        currency = currency,
        category = category,
        description = description,
        periodKey = periodKey,
        budgetScope = BudgetScope.fromRaw(budgetScope).name,
        recordStatus = recordStatus,
        idempotencyKey = idempotencyKey,
        syncVersion = syncVersion.toLong(),
        deviceId = deviceId,
        memberUids = memberUids,
        adminUids = adminUids
    )

internal fun LocalFinancialEventEntity.toFirestoreDoc(memberUids: List<String>): FinancialEventDoc =
    FinancialEventDoc(
        eventId = eventId,
        familyId = familyId,
        parentRecordId = parentRecordId,
        moduleType = moduleType,
        eventType = eventType,
        actorUserId = actorUserId,
        amountMinor = amountMinor,
        secondaryAmountMinor = secondaryAmountMinor,
        balanceAfterMinor = balanceAfterMinor,
        currency = currency,
        note = note,
        memberUids = memberUids
    )

internal fun LocalAuditLogEntity.toCreatePayload(
    memberUids: List<String>,
    adminUids: List<String> = emptyList()
): Map<String, Any> =
    AuditLogDoc(
        auditId = auditId,
        familyId = familyId,
        actorUserId = actorUserId,
        entityCollection = entityCollection,
        entityId = entityId,
        action = action,
        summary = summary,
        afterHash = afterHash,
        deviceId = deviceId,
        memberUids = memberUids,
        adminUids = adminUids
    ).toCreateMap()
