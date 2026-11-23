package com.example.data.repository

import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.FirestoreService
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.IdempotentWriteResult
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.AuditLogDoc
import com.example.core.model.BudgetDoc
import com.example.core.model.BudgetScope
import com.example.core.model.ChatMessageDoc
import com.example.core.model.ChatMessageType
import com.example.core.model.DatabaseHealthSnapshot
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
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecurrenceScheduleHelper
import com.example.core.model.SavingsDoc
import com.example.core.model.SyncState
import com.example.core.model.TransactionDoc
import com.example.core.model.TransactionType
import com.example.core.remote.OperationType
import com.example.core.remote.handleFirestoreError
import com.example.core.sync.CachedFinancialReportPayload
import com.example.core.sync.CachedReportBuilder
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.FirestoreSyncGateway
import com.example.core.sync.OfflineSyncEngine
import com.example.core.sync.SyncRunSummary
import com.example.core.sync.toFirestoreDoc
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Coordinates normalized Cloud Firestore collections (`transactions/`, `loans/`, `savings/`,
 * `investments/`, `budgets/`, `financialEvents/`, `chats/`, `notifications/`, `auditLogs/`) with
 * the storage-agnostic [LocalDatabaseStore] (Room SQLite v8 / IndexedDB-parity) and [OfflineSyncEngine].
 */
class FamilyDataArchitectureRepository(
    private val firestoreService: FirestoreService,
    private val localStore: LocalDatabaseStore,
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth
) {
    private val db: FirebaseFirestore get() = firestoreService.db
    private val modulesDelegate by lazy { FinancialModulesDelegate(firestoreService, localStore, auth) }
    private val syncEngine by lazy {
        OfflineSyncEngine(
            localStore = localStore,
            gateway = FirestoreSyncGateway(db = db, firestoreService = firestoreService)
        )
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required before accessing family records.")
    }

    fun observeDatabaseHealth(familyId: String, userId: String): Flow<DatabaseHealthSnapshot> =
        localStore.observeDatabaseHealth(familyId, userId)

    fun observeLocalTransactions(familyId: String): Flow<List<LocalTransactionEntity>> =
        localStore.observeTransactions(familyId)

    fun observeLocalLoans(familyId: String): Flow<List<LocalLoanEntity>> =
        localStore.observeLoans(familyId)

    fun observeLocalSavings(familyId: String): Flow<List<LocalSavingsEntity>> =
        localStore.observeSavings(familyId)

    fun observeLocalInvestments(familyId: String): Flow<List<LocalInvestmentEntity>> =
        localStore.observeInvestments(familyId)

    fun observeLocalBudgets(familyId: String): Flow<List<LocalBudgetEntity>> =
        localStore.observeBudgets(familyId)

    fun observeLocalFinancialEvents(familyId: String): Flow<List<LocalFinancialEventEntity>> =
        localStore.observeFinancialEvents(familyId)

    fun observeLocalChats(familyId: String): Flow<List<LocalChatMessageEntity>> =
        localStore.observeChats(familyId)

    fun observeLocalNotifications(familyId: String, userId: String): Flow<List<LocalNotificationEntity>> =
        localStore.observeNotifications(familyId, userId)

    fun observeLocalAuditLogs(familyId: String, limit: Int = 50): Flow<List<LocalAuditLogEntity>> =
        localStore.observeAuditLogs(familyId, limit)

    fun observeOutbox(familyId: String): Flow<List<LocalSyncOutboxEntity>> =
        localStore.observeOutbox(familyId)

    fun observeConflicts(familyId: String): Flow<List<LocalSyncConflictEntity>> =
        localStore.observeConflicts(familyId)

    fun observeCachedReports(familyId: String): Flow<List<LocalCachedReportEntity>> =
        localStore.observeCachedReports(familyId)

    fun syncRemoteTransactions(userId: String, familyId: String): Flow<List<TransactionDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user transaction query." }
        require(familyId.isNotBlank()) { "Family ID must not be blank" }
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("transactions")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("occurredAt", Query.Direction.DESCENDING),
                clazz = TransactionDoc::class.java,
                pathForLogs = "transactions",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val recId = doc.recordId.ifBlank { doc.id }
                    val resolvedMinor = if (doc.amountMinor > 0L) {
                        doc.amountMinor
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.amount, doc.currency)
                    }
                    LocalTransactionEntity(
                        recordId = recId,
                        familyId = doc.familyId.ifBlank { familyId },
                        createdByUserId = doc.createdByUserId,
                        memberId = doc.memberId.ifBlank { doc.createdByUserId },
                        amount = MoneyCalculator.minorUnitsToDouble(resolvedMinor, doc.currency),
                        amountMinor = resolvedMinor,
                        currency = doc.currency,
                        category = doc.category,
                        description = doc.description,
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        transactionType = TransactionType.fromRaw(doc.transactionType).name,
                        paymentMethod = doc.paymentMethod,
                        occurredAtMillis = doc.occurredAt?.toDate()?.time ?: System.currentTimeMillis(),
                        recurrenceFrequency = RecurrenceFrequency.fromRaw(doc.recurrenceFrequency).name,
                        nextRecurrenceAtMillis = doc.nextRecurrenceAt?.toDate()?.time,
                        recurringParentId = doc.recurringParentId,
                        isRecurringTemplate = doc.isRecurringTemplate,
                        idempotencyKey = doc.idempotencyKey.ifBlank { recId },
                        syncStatus = SyncState.SYNCED.name,
                        syncVersion = doc.syncVersion.toInt().coerceAtLeast(1),
                        deviceId = doc.deviceId ?: "",
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteTransactions(familyId, entities)
            }
        )
    }

    fun syncRemoteLoans(userId: String, familyId: String): Flow<List<LoanDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user loan query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("loans")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("updatedAt", Query.Direction.DESCENDING),
                clazz = LoanDoc::class.java,
                pathForLogs = "loans",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val recId = doc.recordId.ifBlank { doc.id }
                    val principalMinor = if (doc.amountMinor > 0L) {
                        doc.amountMinor
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.amount, doc.currency)
                    }
                    val remainingMinor = (if (doc.remainingBalanceMinor > 0L || doc.remainingBalance == 0.0) {
                        doc.remainingBalanceMinor
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.remainingBalance, doc.currency)
                    }).coerceIn(0L, maxOf(principalMinor, 0L))
                    val totalInst = doc.totalInstallments.toInt().coerceIn(1, 600)
                    val paidInst = doc.paidInstallments.toInt().coerceIn(0, totalInst)
                    val instMinor = if (doc.installmentAmountMinor > 0L) {
                        doc.installmentAmountMinor
                    } else if (principalMinor > 0L) {
                        principalMinor / totalInst
                    } else {
                        0L
                    }
                    LocalLoanEntity(
                        recordId = recId,
                        familyId = doc.familyId.ifBlank { familyId },
                        createdByUserId = doc.createdByUserId,
                        amount = MoneyCalculator.minorUnitsToDouble(principalMinor, doc.currency),
                        amountMinor = principalMinor,
                        remainingBalance = MoneyCalculator.minorUnitsToDouble(remainingMinor, doc.currency),
                        remainingBalanceMinor = remainingMinor,
                        interestRateApr = doc.interestRateApr,
                        currency = doc.currency,
                        category = doc.category,
                        description = doc.description,
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        loanDirection = LoanDirection.fromRaw(doc.loanDirection).name,
                        counterparty = doc.counterparty,
                        borrowerName = doc.borrowerName,
                        borrowerContact = doc.borrowerContact,
                        lenderName = doc.lenderName,
                        lenderContact = doc.lenderContact,
                        totalInstallments = totalInst,
                        paidInstallments = paidInst,
                        installmentAmountMinor = instMinor,
                        startDateMillis = doc.startDate?.toDate()?.time ?: doc.createdAt?.toDate()?.time,
                        dueDateMillis = doc.dueDate?.toDate()?.time,
                        idempotencyKey = doc.idempotencyKey.ifBlank { recId },
                        syncStatus = SyncState.SYNCED.name,
                        syncVersion = doc.syncVersion.toInt().coerceAtLeast(1),
                        deviceId = doc.deviceId ?: "",
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteLoans(familyId, entities)
            }
        )
    }

    fun syncRemoteSavings(userId: String, familyId: String): Flow<List<SavingsDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user savings query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("savings")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("updatedAt", Query.Direction.DESCENDING),
                clazz = SavingsDoc::class.java,
                pathForLogs = "savings",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val recId = doc.recordId.ifBlank { doc.id }
                    val currentMinor = if (doc.amountMinor > 0L || doc.amount == 0.0) {
                        doc.amountMinor.coerceAtLeast(0L)
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.amount, doc.currency)
                    }
                    val targetMinor = if (doc.targetAmountMinor > 0L) {
                        doc.targetAmountMinor
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.targetAmount, doc.currency).coerceAtLeast(1L)
                    }
                    LocalSavingsEntity(
                        recordId = recId,
                        familyId = doc.familyId.ifBlank { familyId },
                        createdByUserId = doc.createdByUserId,
                        amount = MoneyCalculator.minorUnitsToDouble(currentMinor, doc.currency),
                        amountMinor = currentMinor,
                        targetAmount = MoneyCalculator.minorUnitsToDouble(targetMinor, doc.currency),
                        targetAmountMinor = targetMinor,
                        currency = doc.currency,
                        category = doc.category,
                        description = doc.description,
                        goalName = doc.goalName,
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        targetDateMillis = doc.targetDate?.toDate()?.time,
                        idempotencyKey = doc.idempotencyKey.ifBlank { recId },
                        syncStatus = SyncState.SYNCED.name,
                        syncVersion = doc.syncVersion.toInt().coerceAtLeast(1),
                        deviceId = doc.deviceId ?: "",
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteSavings(familyId, entities)
            }
        )
    }

    fun syncRemoteInvestments(userId: String, familyId: String): Flow<List<InvestmentDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user investment query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("investments")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("updatedAt", Query.Direction.DESCENDING),
                clazz = InvestmentDoc::class.java,
                pathForLogs = "investments",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val recId = doc.recordId.ifBlank { doc.id }
                    val principalMinor = if (doc.amountMinor > 0L || doc.amount == 0.0) {
                        doc.amountMinor.coerceAtLeast(0L)
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.amount, doc.currency)
                    }
                    val currentMinor = if (doc.currentValueMinor > 0L || doc.currentValue == 0.0) {
                        doc.currentValueMinor.coerceAtLeast(0L)
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.currentValue, doc.currency)
                    }
                    val investedMillis = doc.investedAt?.toDate()?.time
                        ?: doc.createdAt?.toDate()?.time
                        ?: System.currentTimeMillis()
                    LocalInvestmentEntity(
                        recordId = recId,
                        familyId = doc.familyId.ifBlank { familyId },
                        createdByUserId = doc.createdByUserId,
                        amount = MoneyCalculator.minorUnitsToDouble(principalMinor, doc.currency),
                        amountMinor = principalMinor,
                        currentValue = MoneyCalculator.minorUnitsToDouble(currentMinor, doc.currency),
                        currentValueMinor = currentMinor,
                        units = doc.units,
                        currency = doc.currency,
                        category = doc.category,
                        description = doc.description,
                        assetName = doc.assetName,
                        assetType = InvestmentAssetType.fromRaw(doc.assetType).name,
                        symbol = doc.symbol,
                        investedAtMillis = investedMillis,
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        idempotencyKey = doc.idempotencyKey.ifBlank { recId },
                        syncStatus = SyncState.SYNCED.name,
                        syncVersion = doc.syncVersion.toInt().coerceAtLeast(1),
                        deviceId = doc.deviceId ?: "",
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteInvestments(familyId, entities)
            }
        )
    }

    fun syncRemoteBudgets(userId: String, familyId: String): Flow<List<BudgetDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user budget query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("budgets")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid),
                clazz = BudgetDoc::class.java,
                pathForLogs = "budgets",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val recId = doc.recordId.ifBlank { doc.id }
                    val limitMinor = if (doc.amountMinor > 0L) {
                        doc.amountMinor
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.amount, doc.currency).coerceAtLeast(1L)
                    }
                    val spentMinor = if (doc.spentAmountMinor > 0L || doc.spentAmount == 0.0) {
                        doc.spentAmountMinor.coerceAtLeast(0L)
                    } else {
                        MoneyCalculator.doubleToMinorUnits(doc.spentAmount, doc.currency)
                    }
                    LocalBudgetEntity(
                        recordId = recId,
                        familyId = doc.familyId.ifBlank { familyId },
                        createdByUserId = doc.createdByUserId,
                        amount = MoneyCalculator.minorUnitsToDouble(limitMinor, doc.currency),
                        amountMinor = limitMinor,
                        spentAmount = MoneyCalculator.minorUnitsToDouble(spentMinor, doc.currency),
                        spentAmountMinor = spentMinor,
                        alertThresholdPercent = doc.alertThresholdPercent.toInt().coerceIn(10, 100),
                        currency = doc.currency,
                        category = doc.category,
                        description = doc.description,
                        periodKey = doc.periodKey,
                        budgetScope = BudgetScope.fromRaw(doc.budgetScope).name,
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        idempotencyKey = doc.idempotencyKey.ifBlank { recId },
                        syncStatus = SyncState.SYNCED.name,
                        syncVersion = doc.syncVersion.toInt().coerceAtLeast(1),
                        deviceId = doc.deviceId ?: "",
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteBudgets(familyId, entities)
            }
        )
    }

    fun syncRemoteFinancialEvents(userId: String, familyId: String): Flow<List<FinancialEventDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user financial events query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("financialEvents")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("eventDate", Query.Direction.DESCENDING)
                    .limit(200),
                clazz = FinancialEventDoc::class.java,
                pathForLogs = "financialEvents",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val evtId = doc.eventId.ifBlank { doc.id }
                    LocalFinancialEventEntity(
                        eventId = evtId,
                        familyId = doc.familyId.ifBlank { familyId },
                        parentRecordId = doc.parentRecordId,
                        moduleType = FinancialModuleType.fromRaw(doc.moduleType).name,
                        eventType = doc.eventType.ifBlank { "EVENT" },
                        actorUserId = doc.actorUserId,
                        amountMinor = doc.amountMinor.coerceAtLeast(0L),
                        secondaryAmountMinor = doc.secondaryAmountMinor,
                        balanceAfterMinor = doc.balanceAfterMinor,
                        currency = doc.currency,
                        note = doc.note,
                        eventDateMillis = doc.eventDate?.toDate()?.time
                            ?: doc.createdAt?.toDate()?.time
                            ?: System.currentTimeMillis(),
                        syncStatus = SyncState.SYNCED.name,
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteFinancialEvents(entities)
            }
        )
    }

    fun syncRemoteNotifications(userId: String, familyId: String): Flow<List<FamilyNotificationDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user notifications query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("notifications")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(100),
                clazz = FamilyNotificationDoc::class.java,
                pathForLogs = "notifications",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val notifId = doc.notificationId.ifBlank { doc.id }
                    LocalNotificationEntity(
                        notificationId = notifId,
                        familyId = doc.familyId.ifBlank { familyId },
                        recipientUserId = doc.recipientUserId,
                        createdByUserId = doc.createdByUserId,
                        title = doc.title,
                        body = doc.body,
                        notificationType = doc.notificationType,
                        relatedRecordId = doc.relatedRecordId,
                        deepLinkModule = doc.deepLinkModule,
                        acknowledged = doc.acknowledged,
                        readByUidsCsv = doc.readByUids.distinct().joinToString(","),
                        recordStatus = RecordStatus.fromRaw(doc.recordStatus).name,
                        syncStatus = SyncState.SYNCED.name,
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteNotifications(entities)
            }
        )
    }

    fun syncRemoteAuditLogs(
        userId: String,
        familyId: String,
        isAdmin: Boolean = false
    ): Flow<List<AuditLogDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        require(familyId.isNotBlank())
        val scopedQuery = if (isAdmin) {
            db.collection("auditLogs")
                .whereEqualTo("familyId", familyId)
                .whereArrayContains("adminUids", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
        } else {
            db.collection("auditLogs")
                .whereEqualTo("familyId", familyId)
                .whereEqualTo("actorUserId", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
        }
        emitAll(
            firestoreService.observeQuery(
                query = scopedQuery,
                clazz = AuditLogDoc::class.java,
                pathForLogs = "auditLogs",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    LocalAuditLogEntity(
                        auditId = doc.auditId.ifBlank { doc.id },
                        familyId = doc.familyId.ifBlank { familyId },
                        actorUserId = doc.actorUserId,
                        entityCollection = doc.entityCollection,
                        entityId = doc.entityId,
                        action = AuditAction.fromRaw(doc.action).name,
                        summary = doc.summary,
                        afterHash = doc.afterHash,
                        deviceId = doc.deviceId ?: "",
                        syncStatus = SyncState.SYNCED.name,
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteAuditLogs(entities)
            }
        )
    }

    fun syncRemoteChats(
        userId: String,
        familyId: String,
        pageLimit: Int = 30
    ): Flow<List<ChatMessageDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user chat query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("chats")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(pageLimit.coerceIn(10, 200).toLong()),
                clazz = ChatMessageDoc::class.java,
                pathForLogs = "chats",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc -> doc.toLocalEntity(familyId) }
                localStore.reconcileRemoteChats(entities)
            }
        )
    }

    suspend fun loadOlderChatMessages(
        userId: String,
        familyId: String,
        beforeMillis: Long?,
        pageSize: Int = 30
    ): Result<Int> {
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user chat query." }
            require(familyId.isNotBlank())
            val safeLimit = pageSize.coerceIn(5, 100)
            var query = db.collection("chats")
                .whereEqualTo("familyId", familyId)
                .whereArrayContains("memberUids", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
            if (beforeMillis != null) {
                query = query.startAfter(Timestamp(Date(beforeMillis)))
            }
            val snap = query.limit(safeLimit.toLong()).get().await()
            val docs = snap.documents.mapNotNull { it.toObject(ChatMessageDoc::class.java) }
            val entities = docs.map { it.toLocalEntity(familyId) }
            if (entities.isNotEmpty()) {
                localStore.reconcileRemoteChats(entities)
            }
            Result.success(entities.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cachedOlder = localStore.getCachedChatsPage(familyId, beforeMillis, pageSize.coerceIn(5, 100))
            Result.success(cachedOlder.size)
        }
    }

    private fun ChatMessageDoc.toLocalEntity(fallbackFamilyId: String): LocalChatMessageEntity {
        val msgId = messageId.ifBlank { id }
        val resolvedCreatedAt = createdAt?.toDate()?.time ?: System.currentTimeMillis()
        return LocalChatMessageEntity(
            messageId = msgId,
            familyId = familyId.ifBlank { fallbackFamilyId },
            senderUserId = senderUserId,
            senderDisplayName = senderDisplayName,
            senderPhotoUrl = senderPhotoUrl,
            senderRoleLabel = senderRoleLabel ?: "",
            messageText = messageText,
            messageType = ChatMessageType.fromRaw(messageType).name,
            relatedRecordId = relatedRecordId,
            recordStatus = RecordStatus.fromRaw(recordStatus).name,
            readByUidsCsv = readByUids.ifEmpty { listOf(senderUserId) }.distinct().joinToString(","),
            editedAtMillis = editedAt?.toDate()?.time,
            deletedAtMillis = deletedAt?.toDate()?.time,
            deletedByUserId = deletedByUserId,
            expiresAtMillis = expiresAt?.toDate()?.time,
            syncVersion = syncVersion.toInt().coerceAtLeast(1),
            syncStatus = SyncState.SYNCED.name,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = resolvedCreatedAt,
            updatedAtMillis = updatedAt?.toDate()?.time ?: resolvedCreatedAt
        )
    }

    /**
     * Backward-compatible helper delegating to [createTransactionMinor] using decimal-safe minor units.
     */
    suspend fun recordTransactionIdempotent(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        amount: Double,
        currency: String,
        category: String,
        description: String,
        transactionType: TransactionType,
        paymentMethod: String? = null,
        occurredAtMillis: Long = System.currentTimeMillis(),
        deviceId: String = "android_device",
        explicitIdempotencyKey: String? = null
    ): Result<IdempotentWriteResult<LocalTransactionEntity>> = createTransactionMinor(
        familyId = familyId,
        memberUids = memberUids,
        adminUids = adminUids,
        amountMinor = MoneyCalculator.doubleToMinorUnits(amount, currency),
        currency = currency,
        category = category,
        description = description,
        transactionType = transactionType,
        memberId = auth.currentUser?.uid ?: "",
        paymentMethod = paymentMethod,
        occurredAtMillis = occurredAtMillis,
        recurrenceFrequency = RecurrenceFrequency.NONE,
        deviceId = deviceId,
        explicitIdempotencyKey = explicitIdempotencyKey
    )

    /**
     * Decimal-safe, duplicate-preventing transaction creation using integer minor currency units.
     * Saves atomically to Room + AuditLog + SyncOutbox, then commits immediately to Firestore when online.
     */
    suspend fun createTransactionMinor(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        amountMinor: Long,
        currency: String,
        category: String,
        description: String,
        transactionType: TransactionType,
        memberId: String = "",
        paymentMethod: String? = null,
        occurredAtMillis: Long = System.currentTimeMillis(),
        recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        recurringParentId: String? = null,
        explicitRecordId: String? = null,
        deviceId: String = "android_device",
        explicitIdempotencyKey: String? = null
    ): Result<IdempotentWriteResult<LocalTransactionEntity>> {
        return try {
            val uid = requireUserId()
            require(familyId.isNotBlank()) { "Active family workspace required." }
            require(amountMinor in 1L..100_000_000_000L) { "Amount must be greater than zero." }

            val now = System.currentTimeMillis()
            val safeOccurredAt = minOf(occurredAtMillis, now)
            val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
            val cleanCategory = category.trim().take(60).ifBlank { "General" }
            val cleanDesc = description.trim().take(300)
            val resolvedMemberId = memberId.trim().ifBlank { uid }
            val majorAmount = MoneyCalculator.minorUnitsToDouble(amountMinor, cleanCurrency)

            val idempotencyKey = explicitIdempotencyKey?.takeIf { it.length >= 8 }
                ?: DatabaseIdentityHelper.computeIdempotencyKeyMinor(
                    familyId = familyId,
                    collectionName = "transactions_${transactionType.name}",
                    discriminator = "$cleanCategory|$cleanDesc|$resolvedMemberId",
                    amountMinor = amountMinor,
                    currency = cleanCurrency,
                    timeBucketMillis = safeOccurredAt / 60_000L
                )

            val recordId = explicitRecordId?.takeIf { it.isNotBlank() }
                ?: DatabaseIdentityHelper.generateRecordId("rec")
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val formattedAmount = MoneyCalculator.formatMinorUnits(amountMinor, cleanCurrency)
            val isTemplate = recurrenceFrequency != RecurrenceFrequency.NONE && recurringParentId.isNullOrBlank()
            val nextDueMillis = if (isTemplate) {
                RecurrenceScheduleHelper.computeNextOccurrenceMillis(safeOccurredAt, recurrenceFrequency)
            } else {
                null
            }
            val summary = "Recorded ${transactionType.name} ($cleanCategory) $formattedAmount"

            val localEntity = LocalTransactionEntity(
                recordId = recordId,
                familyId = familyId,
                createdByUserId = uid,
                memberId = resolvedMemberId,
                amount = majorAmount,
                amountMinor = amountMinor,
                currency = cleanCurrency,
                category = cleanCategory,
                description = cleanDesc,
                recordStatus = RecordStatus.ACTIVE.name,
                transactionType = transactionType.name,
                paymentMethod = paymentMethod?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
                occurredAtMillis = safeOccurredAt,
                recurrenceFrequency = recurrenceFrequency.name,
                nextRecurrenceAtMillis = nextDueMillis,
                recurringParentId = recurringParentId,
                isRecurringTemplate = isTemplate,
                idempotencyKey = idempotencyKey,
                syncStatus = SyncState.PENDING_CREATE.name,
                syncVersion = 1,
                deviceId = deviceId,
                createdAtMillis = now,
                updatedAtMillis = now
            )

            val auditEntity = LocalAuditLogEntity(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = recordId,
                action = AuditAction.CREATE.name,
                summary = summary.take(240),
                afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$idempotencyKey|$amountMinor"),
                deviceId = deviceId,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )

            val localWrite = localStore.saveTransactionIdempotent(localEntity, auditEntity)
            if (localWrite.deduplicatedExisting) {
                return Result.success(localWrite)
            }

            val safeMembers = (memberUids + uid).distinct().take(20)
            val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)

            val txPayload = localWrite.record.toFirestoreDoc(safeMembers, safeAdmins)
                .toCreateMap(localWrite.record.occurredAtMillis, localWrite.record.nextRecurrenceAtMillis)

            val auditPayload = AuditLogDoc(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = localWrite.record.recordId,
                action = AuditAction.CREATE.name,
                summary = auditEntity.summary,
                afterHash = auditEntity.afterHash,
                deviceId = deviceId,
                memberUids = safeMembers,
                adminUids = safeAdmins
            ).toCreateMap()

            val notifId = "ntf_tx_${localWrite.record.recordId.take(36)}"
            val txNotifEntity = LocalNotificationEntity(
                notificationId = notifId,
                familyId = familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = "New ${transactionType.label}: $cleanCategory".take(100),
                body = "${formattedAmount}${if (cleanDesc.isNotBlank()) " • $cleanDesc" else ""}".take(400),
                notificationType = NotificationType.NEW_TRANSACTION.name,
                relatedRecordId = localWrite.record.recordId,
                deepLinkModule = transactionType.name,
                acknowledged = false,
                readByUidsCsv = uid,
                recordStatus = RecordStatus.ACTIVE.name,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            localStore.saveNotificationWithOutbox(txNotifEntity)
            val txNotifPayload = FamilyNotificationDoc(
                notificationId = notifId,
                familyId = familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = txNotifEntity.title,
                body = txNotifEntity.body,
                notificationType = NotificationType.NEW_TRANSACTION.name,
                relatedRecordId = localWrite.record.recordId,
                deepLinkModule = transactionType.name,
                acknowledged = false,
                readByUids = listOf(uid),
                recordStatus = RecordStatus.ACTIVE.name,
                memberUids = safeMembers
            ).toCreateMap()

            val batchResult = firestoreService.commitBatch("transactions/${localWrite.record.recordId}") { batch ->
                batch.set(db.collection("transactions").document(localWrite.record.recordId), txPayload)
                batch.set(db.collection("auditLogs").document(auditId), auditPayload)
                batch.set(db.collection("notifications").document(notifId), txNotifPayload)
            }

            if (batchResult.isSuccess) {
                val syncedAt = System.currentTimeMillis()
                localStore.markOutboxSynced("transactions", localWrite.record.recordId, syncedAt)
                localStore.markOutboxSynced("notifications", notifId, syncedAt)
                Result.success(
                    localWrite.copy(record = localWrite.record.copy(syncStatus = SyncState.SYNCED.name))
                )
            } else {
                Result.success(localWrite)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "transactions")
            Result.failure(e)
        }
    }

    suspend fun updateTransactionMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        amountMinor: Long,
        currency: String,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency,
        keepRecurringActive: Boolean,
        deviceId: String = "android_device"
    ): Result<LocalTransactionEntity> {
        return try {
            val uid = requireUserId()
            require(amountMinor in 1L..100_000_000_000L) { "Amount must be greater than zero." }
            val existing = localStore.getTransactionById(recordId)
                ?: return Result.failure(IllegalArgumentException("Transaction not found locally."))
            check(existing.familyId == familyId) { "Cross-family transaction update denied." }
            val isAdmin = uid in adminUids
            check(existing.createdByUserId == uid || isAdmin) {
                "Only the creator or a Family Admin can edit this transaction."
            }

            val now = System.currentTimeMillis()
            val safeOccurred = minOf(occurredAtMillis, now)
            val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { existing.currency }
            val cleanCategory = category.trim().take(60).ifBlank { existing.category }
            val cleanDesc = description.trim().take(300)
            val resolvedMemberId = memberId.trim().ifBlank { existing.effectiveMemberId() }
            val majorAmount = MoneyCalculator.minorUnitsToDouble(amountMinor, cleanCurrency)
            val isTemplate = recurrenceFrequency != RecurrenceFrequency.NONE && keepRecurringActive && existing.recurringParentId.isNullOrBlank()
            val nextDueMillis = if (isTemplate) {
                if (existing.recurrenceFrequency == recurrenceFrequency.name &&
                    existing.nextRecurrenceAtMillis != null &&
                    existing.occurredAtMillis == safeOccurred
                ) {
                    existing.nextRecurrenceAtMillis
                } else {
                    RecurrenceScheduleHelper.computeNextOccurrenceMillis(safeOccurred, recurrenceFrequency)
                }
            } else {
                null
            }

            val updatedLocal = existing.copy(
                memberId = resolvedMemberId,
                amount = majorAmount,
                amountMinor = amountMinor,
                currency = cleanCurrency,
                category = cleanCategory,
                description = cleanDesc,
                paymentMethod = paymentMethod?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
                occurredAtMillis = safeOccurred,
                recurrenceFrequency = recurrenceFrequency.name,
                nextRecurrenceAtMillis = nextDueMillis,
                isRecurringTemplate = isTemplate,
                updatedAtMillis = now
            )

            val formattedAmount = MoneyCalculator.formatMinorUnits(amountMinor, cleanCurrency)
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val summary = "Updated ${existing.transactionType} ($cleanCategory) to $formattedAmount"
            val auditEntity = LocalAuditLogEntity(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = recordId,
                action = AuditAction.UPDATE.name,
                summary = summary.take(240),
                afterHash = DatabaseIdentityHelper.computePayloadHash("$recordId|$amountMinor|$cleanCategory|${updatedLocal.syncVersion + 1}"),
                deviceId = deviceId,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )

            val writeResult = localStore.saveTransactionIdempotent(updatedLocal, auditEntity)
            val safeMembers = (memberUids + uid).distinct().take(20)
            val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)

            val updateMap = writeResult.record.toFirestoreDoc(safeMembers, safeAdmins)
                .toUpdateMap(writeResult.record.occurredAtMillis, writeResult.record.nextRecurrenceAtMillis)

            val auditPayload = AuditLogDoc(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = recordId,
                action = AuditAction.UPDATE.name,
                summary = auditEntity.summary,
                afterHash = auditEntity.afterHash,
                deviceId = deviceId,
                memberUids = safeMembers,
                adminUids = safeAdmins
            ).toCreateMap()

            val batchResult = firestoreService.commitBatch("transactions/$recordId") { batch ->
                batch.update(db.collection("transactions").document(recordId), updateMap)
                batch.set(db.collection("auditLogs").document(auditId), auditPayload)
            }

            if (batchResult.isSuccess) {
                localStore.markOutboxSynced("transactions", recordId, System.currentTimeMillis())
                Result.success(writeResult.record.copy(syncStatus = SyncState.SYNCED.name))
            } else {
                Result.success(writeResult.record)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "transactions/$recordId")
            Result.failure(e)
        }
    }

    suspend fun deleteTransaction(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        deviceId: String = "android_device"
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val existing = localStore.getTransactionById(recordId)
                ?: return Result.failure(IllegalArgumentException("Transaction not found."))
            check(existing.familyId == familyId) { "Cross-family transaction delete denied." }
            val isAdmin = uid in adminUids
            check(existing.createdByUserId == uid || isAdmin) {
                "Only the creator or a Family Admin can delete this transaction."
            }

            val formattedAmount = MoneyCalculator.formatMinorUnits(existing.effectiveAmountMinor(), existing.currency)
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val summary = "Deleted ${existing.transactionType} (${existing.category}) $formattedAmount"
            val auditEntity = LocalAuditLogEntity(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = recordId,
                action = AuditAction.DELETE.name,
                summary = summary.take(240),
                afterHash = DatabaseIdentityHelper.computePayloadHash("deleted|$recordId"),
                deviceId = deviceId,
                syncStatus = SyncState.PENDING_CREATE.name
            )

            val deleted = localStore.deleteTransactionLocal(recordId, familyId, uid, auditEntity)
            if (!deleted) {
                return Result.failure(IllegalStateException("Unable to delete transaction locally."))
            }
            if (localStore.getOutboxForDocument("transactions", recordId) == null) {
                return Result.success(Unit)
            }

            val safeMembers = (memberUids + uid).distinct().take(20)
            val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
            val auditPayload = AuditLogDoc(
                auditId = auditId,
                familyId = familyId,
                actorUserId = uid,
                entityCollection = "transactions",
                entityId = recordId,
                action = AuditAction.DELETE.name,
                summary = auditEntity.summary,
                afterHash = auditEntity.afterHash,
                deviceId = deviceId,
                memberUids = safeMembers,
                adminUids = safeAdmins
            ).toCreateMap()

            val batchResult = firestoreService.commitBatch("transactions/$recordId") { batch ->
                batch.delete(db.collection("transactions").document(recordId))
                batch.set(db.collection("auditLogs").document(auditId), auditPayload)
            }
            if (batchResult.isSuccess) {
                localStore.markOutboxSynced("transactions", recordId, System.currentTimeMillis())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "transactions/$recordId")
            Result.failure(e)
        }
    }

    suspend fun materializeDueRecurringTransactions(
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        forceTemplateId: String? = null
    ): Result<Int> {
        return try {
            val uid = requireUserId()
            val templates = localStore.getActiveRecurringTemplates(familyId)
                .filter { forceTemplateId == null || it.recordId == forceTemplateId }
            val now = System.currentTimeMillis()
            var createdCount = 0

            for (template in templates) {
                val freq = RecurrenceFrequency.fromRaw(template.recurrenceFrequency)
                if (freq == RecurrenceFrequency.NONE) continue
                var dueMillis = template.nextRecurrenceAtMillis
                    ?: RecurrenceScheduleHelper.computeNextOccurrenceMillis(template.occurredAtMillis, freq)
                    ?: continue

                val shouldRunNow = forceTemplateId != null || dueMillis <= now
                if (!shouldRunNow) continue

                var safetyCycles = 0
                val maxCycles = if (forceTemplateId != null) 1 else 12
                while ((dueMillis <= now || (forceTemplateId != null && safetyCycles == 0)) && safetyCycles < maxCycles) {
                    val effectiveOccurredMillis = minOf(dueMillis, now)
                    val occKey = DatabaseIdentityHelper.computeRecurringOccurrenceKey(
                        familyId = familyId,
                        parentRecordId = template.recordId,
                        occurrenceDateMillis = dueMillis
                    )
                    val occRecordId = DatabaseIdentityHelper.generateDeterministicRecurringRecordId(occKey)
                    val txType = TransactionType.fromRaw(template.transactionType)
                    val writeRes = createTransactionMinor(
                        familyId = familyId,
                        memberUids = memberUids,
                        adminUids = adminUids,
                        amountMinor = template.effectiveAmountMinor(),
                        currency = template.currency,
                        category = template.category,
                        description = if (template.description.isBlank()) {
                            "${template.category} (${freq.label})"
                        } else {
                            template.description
                        },
                        transactionType = txType,
                        memberId = template.effectiveMemberId(),
                        paymentMethod = template.paymentMethod,
                        occurredAtMillis = effectiveOccurredMillis,
                        recurrenceFrequency = RecurrenceFrequency.NONE,
                        recurringParentId = template.recordId,
                        explicitRecordId = occRecordId,
                        deviceId = template.deviceId.ifBlank { "android_device" },
                        explicitIdempotencyKey = occKey
                    )
                    if (writeRes.isSuccess && writeRes.getOrNull()?.deduplicatedExisting == false) {
                        createdCount++
                    }
                    dueMillis = RecurrenceScheduleHelper.computeNextOccurrenceMillis(dueMillis, freq) ?: break
                    safetyCycles++
                }

                val isCreatorOrAdmin = template.createdByUserId == uid || uid in adminUids
                if (isCreatorOrAdmin && dueMillis != template.nextRecurrenceAtMillis) {
                    val advancedTemplate = template.copy(
                        nextRecurrenceAtMillis = dueMillis,
                        updatedAtMillis = now
                    )
                    val auditEntry = LocalAuditLogEntity(
                        auditId = DatabaseIdentityHelper.generateAuditId(),
                        familyId = familyId,
                        actorUserId = uid,
                        entityCollection = "transactions",
                        entityId = template.recordId,
                        action = AuditAction.UPDATE.name,
                        summary = "Advanced recurring schedule (${template.category})",
                        deviceId = template.deviceId,
                        syncStatus = SyncState.PENDING_CREATE.name,
                        createdAtMillis = now
                    )
                    val saved = localStore.saveTransactionIdempotent(advancedTemplate, auditEntry)
                    val safeMembers = (memberUids + uid).distinct().take(20)
                    val safeAdmins = adminUids.ifEmpty { listOf(uid) }.distinct().take(20)
                    val updateMap = saved.record.toFirestoreDoc(safeMembers, safeAdmins)
                        .toUpdateMap(saved.record.occurredAtMillis, saved.record.nextRecurrenceAtMillis)
                    val res = firestoreService.updateDocument(
                        db.collection("transactions").document(template.recordId),
                        updateMap,
                        "transactions/${template.recordId}"
                    )
                    if (res.isSuccess) {
                        localStore.markOutboxSynced("transactions", template.recordId, System.currentTimeMillis())
                    }
                }
            }
            Result.success(createdCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "transactions")
            Result.failure(e)
        }
    }

    suspend fun toggleRecurringTemplate(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        active: Boolean
    ): Result<Unit> {
        val existing = localStore.getTransactionById(recordId)
            ?: return Result.failure(IllegalArgumentException("Recurring transaction not found."))
        val freq = RecurrenceFrequency.fromRaw(existing.recurrenceFrequency)
        if (freq == RecurrenceFrequency.NONE) {
            return Result.failure(IllegalArgumentException("Transaction has no recurring schedule."))
        }
        return updateTransactionMinor(
            recordId = recordId,
            familyId = familyId,
            memberUids = memberUids,
            adminUids = adminUids,
            amountMinor = existing.effectiveAmountMinor(),
            currency = existing.currency,
            category = existing.category,
            description = existing.description,
            memberId = existing.effectiveMemberId(),
            paymentMethod = existing.paymentMethod,
            occurredAtMillis = existing.occurredAtMillis,
            recurrenceFrequency = freq,
            keepRecurringActive = active,
            deviceId = existing.deviceId
        ).map { Unit }
    }

    // --- Prompt 08: Financial Management Modules (Delegated) ---

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
        description: String
    ) = modulesDelegate.createLoanMinor(
        familyId, memberUids, adminUids, loanDirection, principalMinor, currency, category,
        borrowerName, borrowerContact, lenderName, lenderContact, interestRateApr,
        totalInstallments, installmentAmountMinor, startDateMillis, dueDateMillis, description
    )

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
        description: String
    ) = modulesDelegate.updateLoanMinor(
        recordId, familyId, memberUids, adminUids, loanDirection, principalMinor,
        remainingBalanceMinor, currency, category, borrowerName, borrowerContact,
        lenderName, lenderContact, interestRateApr, totalInstallments, paidInstallments,
        installmentAmountMinor, startDateMillis, dueDateMillis, description
    )

    suspend fun recordLoanRepaymentMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        repaymentMinor: Long,
        paymentDateMillis: Long,
        note: String
    ) = modulesDelegate.recordLoanRepaymentMinor(
        recordId, familyId, memberUids, adminUids, repaymentMinor, paymentDateMillis, note
    )

    suspend fun deleteLoan(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>
    ) = modulesDelegate.deleteLoan(recordId, familyId, memberUids, adminUids)

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
        description: String
    ) = modulesDelegate.createSavingsGoalMinor(
        familyId, memberUids, adminUids, goalName, category, initialAmountMinor,
        targetAmountMinor, currency, targetDateMillis, description
    )

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
        description: String
    ) = modulesDelegate.updateSavingsGoalMinor(
        recordId, familyId, memberUids, adminUids, goalName, category,
        targetAmountMinor, currency, targetDateMillis, description
    )

    suspend fun recordSavingsMoveMinor(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        moveAmountMinor: Long,
        isDeposit: Boolean,
        moveDateMillis: Long,
        note: String
    ) = modulesDelegate.recordSavingsMoveMinor(
        recordId, familyId, memberUids, adminUids, moveAmountMinor, isDeposit, moveDateMillis, note
    )

    suspend fun deleteSavingsGoal(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>
    ) = modulesDelegate.deleteSavingsGoal(recordId, familyId, memberUids, adminUids)

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
        description: String
    ) = modulesDelegate.createInvestmentMinor(
        familyId, memberUids, adminUids, assetName, assetType, symbol,
        principalMinor, currentValueMinor, units, currency, investedAtMillis, description
    )

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
        note: String = ""
    ) = modulesDelegate.updateInvestmentMinor(
        recordId, familyId, memberUids, adminUids, assetName, assetType, symbol,
        principalMinor, currentValueMinor, units, currency, investedAtMillis, description, note
    )

    suspend fun deleteInvestment(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>
    ) = modulesDelegate.deleteInvestment(recordId, familyId, memberUids, adminUids)

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
        description: String
    ) = modulesDelegate.createBudgetMinor(
        familyId, memberUids, adminUids, budgetScope, category, periodKey,
        limitMinor, alertThresholdPercent, currency, description
    )

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
        description: String
    ) = modulesDelegate.updateBudgetMinor(
        recordId, familyId, memberUids, adminUids, budgetScope, category, periodKey,
        limitMinor, alertThresholdPercent, currency, description
    )

    suspend fun recordBudgetAlertIfNeeded(
        budget: LocalBudgetEntity,
        spentMinor: Long,
        alertLevel: String,
        memberUids: List<String>
    ) = modulesDelegate.recordBudgetAlertIfNeeded(budget, spentMinor, alertLevel, memberUids)

    suspend fun deleteBudget(
        recordId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>
    ) = modulesDelegate.deleteBudget(recordId, familyId, memberUids, adminUids)

    suspend fun evaluateLoanDueDateNotifications(
        familyId: String,
        loans: List<LocalLoanEntity>,
        memberUids: List<String>
    ) = modulesDelegate.evaluateLoanDueDateNotifications(familyId, loans, memberUids)

    suspend fun sendChatMessageOfflineFirst(
        family: LocalFamilyEntity,
        senderDisplayName: String,
        messageText: String,
        canParticipateInChat: Boolean,
        relatedRecordId: String? = null,
        messageType: ChatMessageType = ChatMessageType.TEXT,
        senderPhotoUrl: String? = null,
        senderRoleLabel: String = ""
    ): Result<LocalChatMessageEntity> {
        return try {
            val uid = requireUserId()
            check(uid in family.memberUids) { "Only active family members can post in chat." }
            check(family.memberChatEnabled) { "Family Admin has disabled family chat for this workspace." }
            check(canParticipateInChat || family.isAdmin(uid)) { "Chat permission was restricted for your account." }
            val cleanText = messageText.trim().take(1000)
            require(cleanText.isNotEmpty()) { "Message cannot be empty." }

            val now = System.currentTimeMillis()
            val msgId = DatabaseIdentityHelper.generateRecordId("msg")
            val safeMembers = (family.memberUids + uid).distinct().take(20)
            val expiresAtMillis = if (family.chatRetentionDays > 0) {
                now + family.chatRetentionDays.toLong() * 86_400_000L
            } else {
                null
            }
            val resolvedRole = senderRoleLabel.trim().take(40).ifBlank {
                if (family.isAdmin(uid)) "Admin" else "Member"
            }
            val cleanDisplay = senderDisplayName.trim().take(80).ifBlank { "Family Member" }
            val entity = LocalChatMessageEntity(
                messageId = msgId,
                familyId = family.familyId,
                senderUserId = uid,
                senderDisplayName = cleanDisplay,
                senderPhotoUrl = senderPhotoUrl?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
                senderRoleLabel = resolvedRole,
                messageText = cleanText,
                messageType = messageType.name,
                relatedRecordId = relatedRecordId?.takeIf { it.isNotBlank() },
                recordStatus = RecordStatus.ACTIVE.name,
                readByUidsCsv = uid,
                expiresAtMillis = expiresAtMillis,
                syncVersion = 1,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            val audit = LocalAuditLogEntity(
                auditId = DatabaseIdentityHelper.generateAuditId(),
                familyId = family.familyId,
                actorUserId = uid,
                entityCollection = "chats",
                entityId = msgId,
                action = AuditAction.CREATE.name,
                summary = "Sent family message (${cleanText.take(40)})".take(240),
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )
            localStore.saveChatMessage(entity, audit)

            val notifId = "ntf_msg_${msgId.take(36)}"
            val chatNotif = LocalNotificationEntity(
                notificationId = notifId,
                familyId = family.familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = "${family.name}: $cleanDisplay".take(100),
                body = cleanText.take(400),
                notificationType = NotificationType.NEW_MESSAGE.name,
                relatedRecordId = msgId,
                deepLinkModule = "FAMILY_CHAT",
                acknowledged = false,
                readByUidsCsv = uid,
                recordStatus = RecordStatus.ACTIVE.name,
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            localStore.saveNotificationWithOutbox(chatNotif)

            val payload = ChatMessageDoc(
                messageId = msgId,
                familyId = family.familyId,
                senderUserId = uid,
                senderDisplayName = entity.senderDisplayName,
                senderPhotoUrl = entity.senderPhotoUrl,
                senderRoleLabel = entity.senderRoleLabel.takeIf { it.isNotBlank() },
                messageText = cleanText,
                messageType = messageType.name,
                relatedRecordId = entity.relatedRecordId,
                recordStatus = RecordStatus.ACTIVE.name,
                readByUids = listOf(uid),
                syncVersion = 1L,
                memberUids = safeMembers
            ).toCreateMap(expiresAtMillis = expiresAtMillis)

            val notifPayload = FamilyNotificationDoc(
                notificationId = notifId,
                familyId = family.familyId,
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = uid,
                title = chatNotif.title,
                body = chatNotif.body,
                notificationType = NotificationType.NEW_MESSAGE.name,
                relatedRecordId = msgId,
                deepLinkModule = "FAMILY_CHAT",
                acknowledged = false,
                readByUids = listOf(uid),
                recordStatus = RecordStatus.ACTIVE.name,
                memberUids = safeMembers
            ).toCreateMap()

            val batchResult = firestoreService.commitBatch("chats/$msgId") { batch ->
                batch.set(db.collection("chats").document(msgId), payload)
                batch.set(db.collection("notifications").document(notifId), notifPayload)
            }
            if (batchResult.isSuccess) {
                val syncedAt = System.currentTimeMillis()
                localStore.markOutboxSynced("chats", msgId, syncedAt)
                localStore.markOutboxSynced("notifications", notifId, syncedAt)
                Result.success(entity.copy(syncStatus = SyncState.SYNCED.name))
            } else {
                Result.success(entity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats")
            Result.failure(e)
        }
    }

    suspend fun editChatMessage(
        family: LocalFamilyEntity,
        messageId: String,
        newText: String,
        canParticipateInChat: Boolean
    ): Result<LocalChatMessageEntity> {
        return try {
            val uid = requireUserId()
            check(uid in family.memberUids) { "Only active family members can edit messages." }
            check(family.memberChatEnabled) { "Family Admin has disabled family chat." }
            check(canParticipateInChat || family.isAdmin(uid)) { "Chat permission was restricted for your account." }
            val cleanText = newText.trim().take(1000)
            require(cleanText.isNotEmpty()) { "Edited message cannot be empty." }
            val existing = localStore.getChatMessageById(messageId)
                ?: return Result.failure(IllegalArgumentException("Message not found locally."))
            check(existing.senderUserId == uid) { "Only the message sender can edit this message." }
            check(existing.recordStatus == RecordStatus.ACTIVE.name) { "Deleted messages cannot be edited." }

            val now = System.currentTimeMillis()
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val audit = LocalAuditLogEntity(
                auditId = auditId,
                familyId = family.familyId,
                actorUserId = uid,
                entityCollection = "chats",
                entityId = messageId,
                action = AuditAction.UPDATE.name,
                summary = "Edited chat message (${cleanText.take(40)})".take(240),
                afterHash = DatabaseIdentityHelper.computePayloadHash("$messageId|$cleanText|$now"),
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )
            val updated = localStore.editChatMessageLocal(
                messageId = messageId,
                familyId = family.familyId,
                actorUserId = uid,
                newText = cleanText,
                auditEntry = audit
            ) ?: return Result.failure(IllegalStateException("Unable to edit message."))

            val safeMembers = (family.memberUids + uid).distinct().take(20)
            val safeAdmins = family.adminUids.ifEmpty { listOf(family.ownerId) }.distinct().take(20)
            val batchRes = firestoreService.commitBatch("chats/$messageId") { batch ->
                batch.update(
                    db.collection("chats").document(messageId),
                    mapOf(
                        "messageText" to cleanText,
                        "editedAt" to Timestamp(Date(updated.editedAtMillis ?: now)),
                        "syncVersion" to updated.syncVersion.toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    db.collection("auditLogs").document(auditId),
                    AuditLogDoc(
                        auditId = auditId,
                        familyId = family.familyId,
                        actorUserId = uid,
                        entityCollection = "chats",
                        entityId = messageId,
                        action = AuditAction.UPDATE.name,
                        summary = audit.summary,
                        afterHash = audit.afterHash,
                        memberUids = safeMembers,
                        adminUids = safeAdmins
                    ).toCreateMap()
                )
            }
            if (batchRes.isSuccess) {
                localStore.markOutboxSynced("chats", messageId, System.currentTimeMillis(), updated.syncVersion)
                Result.success(updated.copy(syncStatus = SyncState.SYNCED.name))
            } else {
                Result.success(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "chats/$messageId")
            Result.failure(e)
        }
    }

    suspend fun deleteChatMessage(
        family: LocalFamilyEntity,
        messageId: String
    ): Result<LocalChatMessageEntity> {
        return try {
            val uid = requireUserId()
            check(uid in family.memberUids) { "Only active family members can access chat." }
            val existing = localStore.getChatMessageById(messageId)
                ?: return Result.failure(IllegalArgumentException("Message not found locally."))
            check(existing.familyId == family.familyId) { "Cross-family chat delete denied." }
            check(existing.senderUserId == uid || family.isAdmin(uid)) {
                "Only the sender or Family Admin can delete this message."
            }

            val now = System.currentTimeMillis()
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val audit = LocalAuditLogEntity(
                auditId = auditId,
                familyId = family.familyId,
                actorUserId = uid,
                entityCollection = "chats",
                entityId = messageId,
                action = AuditAction.DELETE.name,
                summary = "Deleted family message by ${existing.senderDisplayName}".take(240),
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = now
            )
            val deleted = localStore.softDeleteChatMessageLocal(
                messageId = messageId,
                familyId = family.familyId,
                actorUserId = uid,
                auditEntry = audit
            ) ?: return Result.failure(IllegalStateException("Unable to delete message."))

            if (deleted.syncStatus == SyncState.SYNCED.name && localStore.getOutboxForDocument("chats", messageId) == null) {
                // Local-only draft was cancelled cleanly; remove any local notification if generated
                return Result.success(deleted)
            }

            val safeMembers = (family.memberUids + uid).distinct().take(20)
            val safeAdmins = family.adminUids.ifEmpty { listOf(family.ownerId) }.distinct().take(20)
            val batchRes = firestoreService.commitBatch("chats/$messageId") { batch ->
                batch.update(
                    db.collection("chats").document(messageId),
                    mapOf(
                        "messageText" to "[Message deleted]",
                        "recordStatus" to RecordStatus.VOIDED.name,
                        "deletedAt" to Timestamp(Date(deleted.deletedAtMillis ?: now)),
                        "deletedByUserId" to uid,
                        "syncVersion" to deleted.syncVersion.toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    db.collection("auditLogs").document(auditId),
                    AuditLogDoc(
                        auditId = auditId,
                        familyId = family.familyId,
                        actorUserId = uid,
                        entityCollection = "chats",
                        entityId = messageId,
                        action = AuditAction.DELETE.name,
                        summary = audit.summary,
                        memberUids = safeMembers,
                        adminUids = safeAdmins
                    ).toCreateMap()
                )
            }
            if (batchRes.isSuccess) {
                localStore.markOutboxSynced("chats", messageId, System.currentTimeMillis(), deleted.syncVersion)
                Result.success(deleted.copy(syncStatus = SyncState.SYNCED.name))
            } else {
                Result.success(deleted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "chats/$messageId")
            Result.failure(e)
        }
    }

    suspend fun markChatMessagesRead(
        familyId: String,
        messageIds: List<String>
    ): Int {
        val uid = auth.currentUser?.uid ?: return 0
        if (familyId.isBlank() || messageIds.isEmpty()) return 0
        val changedIds = mutableListOf<String>()
        for (id in messageIds.distinct()) {
            val msg = localStore.getChatMessageById(id) ?: continue
            if (msg.familyId == familyId && msg.senderUserId != uid && uid !in msg.readByUids) {
                changedIds.add(id)
            }
        }
        if (changedIds.isEmpty()) return 0
        val count = localStore.markChatMessagesReadLocal(familyId, uid, changedIds)
        if (count > 0) {
            val syncedNow = System.currentTimeMillis()
            changedIds.chunked(20).forEach { chunk ->
                val batchRes = firestoreService.commitBatch("chats/read_receipts") { batch ->
                    chunk.forEach { msgId ->
                        batch.update(
                            db.collection("chats").document(msgId),
                            mapOf(
                                "readByUids" to FieldValue.arrayUnion(uid),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
                if (batchRes.isSuccess) {
                    chunk.forEach { msgId ->
                        localStore.markOutboxSynced("chats", msgId, syncedNow)
                    }
                }
            }
        }
        return count
    }

    suspend fun purgeExpiredChatMessages(
        family: LocalFamilyEntity,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<Int> {
        return try {
            val uid = requireUserId()
            val auditId = DatabaseIdentityHelper.generateAuditId()
            val audit = LocalAuditLogEntity(
                auditId = auditId,
                familyId = family.familyId,
                actorUserId = uid,
                entityCollection = "chats",
                entityId = family.familyId,
                action = AuditAction.DELETE.name,
                summary = "Applied ${family.chatRetentionDays}-day chat retention purge",
                syncStatus = SyncState.PENDING_CREATE.name,
                createdAtMillis = nowMillis
            )
            val purgedIds = localStore.purgeExpiredChatsLocal(family.familyId, nowMillis, audit)
            if (purgedIds.isNotEmpty()) {
                val safeMembers = (family.memberUids + uid).distinct().take(20)
                val safeAdmins = family.adminUids.ifEmpty { listOf(family.ownerId) }.distinct().take(20)
                val canDeleteRemotely = family.isAdmin(uid)
                val batchRes = firestoreService.commitBatch("chats/purge_${family.familyId}") { batch ->
                    purgedIds.take(40).forEach { msgId ->
                        if (canDeleteRemotely) {
                            batch.delete(db.collection("chats").document(msgId))
                        }
                    }
                    batch.set(
                        db.collection("auditLogs").document(auditId),
                        AuditLogDoc(
                            auditId = auditId,
                            familyId = family.familyId,
                            actorUserId = uid,
                            entityCollection = "chats",
                            entityId = family.familyId,
                            action = AuditAction.DELETE.name,
                            summary = "Purged ${purgedIds.size} expired chat messages",
                            memberUids = safeMembers,
                            adminUids = safeAdmins
                        ).toCreateMap()
                    )
                }
                if (batchRes.isSuccess) {
                    val syncedAt = System.currentTimeMillis()
                    purgedIds.forEach { msgId -> localStore.markOutboxSynced("chats", msgId, syncedAt) }
                }
            }
            Result.success(purgedIds.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(familyId: String, notificationId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val updated = localStore.markNotificationReadLocal(notificationId, familyId, uid)
                ?: return Result.success(Unit)
            val res = firestoreService.updateDocument(
                docRef = db.collection("notifications").document(notificationId),
                updates = mapOf(
                    "acknowledged" to updated.acknowledged,
                    "readByUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                pathForLogs = "notifications/$notificationId"
            )
            if (res.isSuccess) {
                localStore.markOutboxSynced("notifications", notificationId, System.currentTimeMillis())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markAllNotificationsRead(familyId: String, unreadIds: List<String>): Result<Int> {
        return try {
            val uid = requireUserId()
            val count = localStore.markAllNotificationsReadLocal(familyId, uid)
            if (unreadIds.isNotEmpty()) {
                val now = System.currentTimeMillis()
                unreadIds.distinct().chunked(40).forEach { chunk ->
                    val batchRes = firestoreService.commitBatch("notifications/mark_all") { batch ->
                        chunk.forEach { id ->
                            batch.update(
                                db.collection("notifications").document(id),
                                mapOf(
                                    "readByUids" to FieldValue.arrayUnion(uid),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                    }
                    if (batchRes.isSuccess) {
                        chunk.forEach { id -> localStore.markOutboxSynced("notifications", id, now) }
                    }
                }
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun archiveNotification(familyId: String, notificationId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            localStore.archiveNotificationLocal(notificationId, familyId, uid)
            val res = firestoreService.updateDocument(
                docRef = db.collection("notifications").document(notificationId),
                updates = mapOf(
                    "recordStatus" to RecordStatus.ARCHIVED.name,
                    "readByUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                pathForLogs = "notifications/$notificationId"
            )
            if (res.isSuccess) {
                localStore.markOutboxSynced("notifications", notificationId, System.currentTimeMillis())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recordPendingJoinRequestAlert(
        familyId: String,
        requesterUid: String,
        requesterName: String,
        relationLabel: String,
        memberUids: List<String>
    ) {
        val uid = auth.currentUser?.uid ?: return
        val notifId = "ntf_req_${familyId.take(12)}_${requesterUid.take(16)}"
        if (localStore.getNotificationById(notifId) != null) return
        val now = System.currentTimeMillis()
        val safeMembers = (memberUids + uid).distinct().take(20)
        val entity = LocalNotificationEntity(
            notificationId = notifId,
            familyId = familyId,
            recipientUserId = uid,
            createdByUserId = uid,
            title = "Join Request Pending Approval",
            body = "$requesterName ($relationLabel) requested to join your family workspace.",
            notificationType = NotificationType.FAMILY_INVITATION.name,
            relatedRecordId = requesterUid,
            deepLinkModule = "FAMILY_MANAGEMENT",
            acknowledged = false,
            readByUidsCsv = "",
            recordStatus = RecordStatus.ACTIVE.name,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        localStore.saveNotificationWithOutbox(entity)
        val res = firestoreService.setDocument(
            db.collection("notifications").document(notifId),
            FamilyNotificationDoc(
                notificationId = notifId,
                familyId = familyId,
                recipientUserId = uid,
                createdByUserId = uid,
                title = entity.title,
                body = entity.body,
                notificationType = NotificationType.FAMILY_INVITATION.name,
                relatedRecordId = requesterUid,
                deepLinkModule = "FAMILY_MANAGEMENT",
                acknowledged = false,
                readByUids = emptyList(),
                recordStatus = RecordStatus.ACTIVE.name,
                memberUids = safeMembers
            ).toCreateMap(),
            "notifications/$notifId"
        )
        if (res.isSuccess) {
            localStore.markOutboxSynced("notifications", notifId, System.currentTimeMillis())
        }
    }

    suspend fun refreshCachedReport(
        family: LocalFamilyEntity,
        periodKey: String,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        budgets: List<LocalBudgetEntity>,
        isOnlineSynced: Boolean
    ): CachedFinancialReportPayload {
        val payload = CachedReportBuilder.buildPayload(
            familyId = family.familyId,
            familyName = family.name,
            periodKey = periodKey,
            currency = family.currencyCode,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            budgets = budgets
        )
        localStore.saveCachedReport(CachedReportBuilder.toEntity(payload, isOnlineSynced))
        return payload
    }

    suspend fun resolveConflict(
        conflictId: String,
        chosenPolicy: ConflictResolutionPolicy,
        family: LocalFamilyEntity?,
        isOnline: Boolean
    ): Result<Boolean> {
        return try {
            val uid = requireUserId()
            val resolved = localStore.resolveConflict(conflictId, chosenPolicy, uid)
            if (resolved && chosenPolicy == ConflictResolutionPolicy.CLIENT_WINS && family != null && isOnline) {
                syncEngine.synchronize(uid, family, ConflictResolutionPolicy.CLIENT_WINS, isConnectivityOnline = true)
            }
            Result.success(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun synchronizeWithEngine(
        userId: String,
        family: LocalFamilyEntity,
        policy: ConflictResolutionPolicy,
        isConnectivityOnline: Boolean
    ): Result<SyncRunSummary> {
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user outbox sync." }
            val summary = syncEngine.synchronize(
                authenticatedUid = uid,
                family = family,
                policy = policy,
                isConnectivityOnline = isConnectivityOnline
            )
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "sync_outbox")
            Result.failure(e)
        }
    }

    /**
     * Flushes any offline-created financial records queued in `sync_outbox` for [familyId]
     * using [OfflineSyncEngine] for 7-step validation, idempotency, and conflict detection.
     */
    suspend fun flushPendingOutbox(
        userId: String,
        familyId: String,
        memberUids: List<String>,
        adminUids: List<String>,
        policy: ConflictResolutionPolicy = ConflictResolutionPolicy.MANUAL_REVIEW,
        isOnline: Boolean = true
    ): Result<Int> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Signed-in session required."))
        val cachedFam = localStore.getCachedFamily(familyId) ?: LocalFamilyEntity(
            familyId = familyId,
            name = "Family Workspace",
            ownerId = adminUids.firstOrNull() ?: uid,
            createdByUserId = adminUids.firstOrNull() ?: uid,
            currencyCode = "USD",
            memberUidsCsv = (memberUids + uid).distinct().joinToString(","),
            adminUidsCsv = adminUids.ifEmpty { listOf(uid) }.distinct().joinToString(",")
        )
        return synchronizeWithEngine(
            userId = userId,
            family = cachedFam,
            policy = policy,
            isConnectivityOnline = isOnline
        ).map { it.syncedCount }
    }

    /**
     * Explicit one-way contribution from the user's isolated Personal workspace (`personal_<uid>`)
     * into an active shared family workspace. Private personal history is never auto-merged.
     */
    suspend fun contributeFromPersonalToFamily(
        personalFamily: LocalFamilyEntity,
        targetFamily: LocalFamilyEntity,
        amountMinor: Long,
        category: String,
        description: String
    ): Result<Unit> {
        val uid = requireUserId()
        if (!personalFamily.isPersonal) {
            return Result.failure(IllegalArgumentException("Source workspace must be your Personal Finance workspace."))
        }
        if (targetFamily.isPersonal || targetFamily.isSuspended(uid) || uid !in targetFamily.memberUids) {
            return Result.failure(IllegalAccessException("Target must be an active shared family workspace where you hold membership."))
        }
        if (amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("Contribution amount must be greater than zero."))
        }
        val cleanCategory = category.trim().take(60).ifBlank { "Family Contribution" }
        val cleanDesc = description.trim().take(240).ifBlank {
            "Explicit personal contribution to ${targetFamily.name}"
        }
        val now = System.currentTimeMillis()
        val expenseRes = createTransactionMinor(
            familyId = personalFamily.familyId,
            memberUids = personalFamily.memberUids,
            adminUids = personalFamily.adminUids,
            amountMinor = amountMinor,
            currency = personalFamily.currencyCode,
            category = cleanCategory,
            description = "$cleanDesc (Sent to ${targetFamily.name})",
            transactionType = TransactionType.EXPENSE,
            memberId = uid,
            paymentMethod = "Workspace Transfer",
            occurredAtMillis = now
        )
        if (expenseRes.isFailure) {
            return Result.failure(expenseRes.exceptionOrNull() ?: IllegalStateException("Failed recording personal contribution expense."))
        }
        val incomeRes = createTransactionMinor(
            familyId = targetFamily.familyId,
            memberUids = targetFamily.memberUids,
            adminUids = targetFamily.adminUids,
            amountMinor = amountMinor,
            currency = targetFamily.currencyCode,
            category = "Personal Contribution",
            description = cleanDesc,
            transactionType = TransactionType.INCOME,
            memberId = uid,
            paymentMethod = "Personal Contribution",
            occurredAtMillis = now
        )
        return incomeRes.map { Unit }
    }
}
