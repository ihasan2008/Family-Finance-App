package com.example.core.local.abstraction

import androidx.room.withTransaction
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.AuditAction
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.SyncState
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictStatus
import com.example.core.sync.ConflictType

/**
 * Conflict-aware pull reconciliation and conflict resolution helper for [SqliteRoomDatabaseStore].
 * Prevents silent data loss when offline edits collide with newer remote cloud revisions.
 */
internal class RemoteReconciliationHelper(
    private val db: FamilyFinanceDatabase,
    private val mapper: LocalEntityCipherMapper
) {
    private val transactionDao = db.transactionDao()
    private val loanDao = db.loanDao()
    private val savingsDao = db.savingsDao()
    private val investmentDao = db.investmentDao()
    private val budgetDao = db.budgetDao()
    private val auditLogDao = db.auditLogDao()
    private val syncOutboxDao = db.syncOutboxDao()
    private val syncConflictDao = db.syncConflictDao()

    suspend fun reconcileTransactions(familyId: String, remote: List<LocalTransactionEntity>) {
        db.withTransaction {
            val pendingDeleteIds = syncOutboxDao.getPendingDeleteIds(familyId, "transactions").toSet()
            val validRemote = remote.filter { it.familyId == familyId && it.recordId !in pendingDeleteIds }
            validRemote.forEach { r ->
                val localRaw = transactionDao.getById(r.recordId)
                if (localRaw == null || localRaw.syncStatus == SyncState.SYNCED.name) {
                    transactionDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name, baseSyncVersion = r.syncVersion)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    if (r.syncVersion > localDec.baseSyncVersion) {
                        transactionDao.upsert(localRaw.copy(syncStatus = SyncState.CONFLICT.name))
                        val conflict = LocalSyncConflictEntity(
                            conflictId = DatabaseIdentityHelper.generateConflictId(),
                            familyId = familyId,
                            collectionName = "transactions",
                            documentId = r.recordId,
                            operationId = syncOutboxDao.getForDocument("transactions", r.recordId)?.operationId
                                ?: DatabaseIdentityHelper.generateOperationId(),
                            conflictType = ConflictType.REMOTE_MODIFIED.name,
                            localVersion = localDec.syncVersion,
                            remoteVersion = r.syncVersion,
                            localSummary = mapper.summarizeTransaction(localDec),
                            remoteSummary = mapper.summarizeTransaction(r),
                            localPayloadJson = mapper.encodeTransactionSnapshot(localDec),
                            remotePayloadJson = mapper.encodeTransactionSnapshot(r),
                            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
                            status = ConflictStatus.UNRESOLVED.name
                        )
                        syncConflictDao.upsert(mapper.encrypt(conflict))
                        syncOutboxDao.deleteForDocument("transactions", r.recordId)
                        auditLogDao.upsert(
                            LocalAuditLogEntity(
                                auditId = DatabaseIdentityHelper.generateAuditId(),
                                familyId = familyId,
                                actorUserId = localDec.createdByUserId,
                                entityCollection = "transactions",
                                entityId = r.recordId,
                                action = AuditAction.SYNC.name,
                                summary = "Sync conflict detected on ${localDec.category} (local v${localDec.syncVersion} vs cloud v${r.syncVersion})".take(240),
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    }
                }
            }
            if (validRemote.isNotEmpty()) {
                transactionDao.pruneSyncedNotIn(familyId, validRemote.map { it.recordId })
            } else if (remote.isEmpty() && pendingDeleteIds.isEmpty()) {
                transactionDao.pruneSyncedNotIn(familyId, listOf("__none__"))
            }
        }
    }

    suspend fun reconcileLoans(familyId: String, remote: List<LocalLoanEntity>) {
        db.withTransaction {
            val pendingDeleteIds = syncOutboxDao.getPendingDeleteIds(familyId, "loans").toSet()
            val validRemote = remote.filter { it.familyId == familyId && it.recordId !in pendingDeleteIds }
            validRemote.forEach { r ->
                val localRaw = loanDao.getById(r.recordId)
                if (localRaw == null || localRaw.syncStatus == SyncState.SYNCED.name) {
                    loanDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name, baseSyncVersion = r.syncVersion)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    if (r.syncVersion > localDec.baseSyncVersion) {
                        loanDao.upsert(localRaw.copy(syncStatus = SyncState.CONFLICT.name))
                        val conflict = LocalSyncConflictEntity(
                            conflictId = DatabaseIdentityHelper.generateConflictId(),
                            familyId = familyId,
                            collectionName = "loans",
                            documentId = r.recordId,
                            operationId = syncOutboxDao.getForDocument("loans", r.recordId)?.operationId
                                ?: DatabaseIdentityHelper.generateOperationId(),
                            conflictType = ConflictType.REMOTE_MODIFIED.name,
                            localVersion = localDec.syncVersion,
                            remoteVersion = r.syncVersion,
                            localSummary = mapper.summarizeLoan(localDec),
                            remoteSummary = mapper.summarizeLoan(r),
                            localPayloadJson = mapper.encodeLoanSnapshot(localDec),
                            remotePayloadJson = mapper.encodeLoanSnapshot(r),
                            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
                            status = ConflictStatus.UNRESOLVED.name
                        )
                        syncConflictDao.upsert(mapper.encrypt(conflict))
                        syncOutboxDao.deleteForDocument("loans", r.recordId)
                        auditLogDao.upsert(
                            LocalAuditLogEntity(
                                auditId = DatabaseIdentityHelper.generateAuditId(),
                                familyId = familyId,
                                actorUserId = localDec.createdByUserId,
                                entityCollection = "loans",
                                entityId = r.recordId,
                                action = AuditAction.SYNC.name,
                                summary = "Sync conflict detected on loan ${localDec.counterparty} (local v${localDec.syncVersion} vs cloud v${r.syncVersion})".take(240),
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    }
                }
            }
            if (validRemote.isNotEmpty()) {
                loanDao.pruneSyncedNotIn(familyId, validRemote.map { it.recordId })
            } else if (remote.isEmpty() && pendingDeleteIds.isEmpty()) {
                loanDao.pruneSyncedNotIn(familyId, listOf("__none__"))
            }
        }
    }

    suspend fun reconcileSavings(familyId: String, remote: List<LocalSavingsEntity>) {
        db.withTransaction {
            val pendingDeleteIds = syncOutboxDao.getPendingDeleteIds(familyId, "savings").toSet()
            val validRemote = remote.filter { it.familyId == familyId && it.recordId !in pendingDeleteIds }
            validRemote.forEach { r ->
                val localRaw = savingsDao.getById(r.recordId)
                if (localRaw == null || localRaw.syncStatus == SyncState.SYNCED.name) {
                    savingsDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name, baseSyncVersion = r.syncVersion)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    if (r.syncVersion > localDec.baseSyncVersion) {
                        savingsDao.upsert(localRaw.copy(syncStatus = SyncState.CONFLICT.name))
                        val conflict = LocalSyncConflictEntity(
                            conflictId = DatabaseIdentityHelper.generateConflictId(),
                            familyId = familyId,
                            collectionName = "savings",
                            documentId = r.recordId,
                            operationId = syncOutboxDao.getForDocument("savings", r.recordId)?.operationId
                                ?: DatabaseIdentityHelper.generateOperationId(),
                            conflictType = ConflictType.REMOTE_MODIFIED.name,
                            localVersion = localDec.syncVersion,
                            remoteVersion = r.syncVersion,
                            localSummary = mapper.summarizeSavings(localDec),
                            remoteSummary = mapper.summarizeSavings(r),
                            localPayloadJson = mapper.encodeSavingsSnapshot(localDec),
                            remotePayloadJson = mapper.encodeSavingsSnapshot(r),
                            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
                            status = ConflictStatus.UNRESOLVED.name
                        )
                        syncConflictDao.upsert(mapper.encrypt(conflict))
                        syncOutboxDao.deleteForDocument("savings", r.recordId)
                        auditLogDao.upsert(
                            LocalAuditLogEntity(
                                auditId = DatabaseIdentityHelper.generateAuditId(),
                                familyId = familyId,
                                actorUserId = localDec.createdByUserId,
                                entityCollection = "savings",
                                entityId = r.recordId,
                                action = AuditAction.SYNC.name,
                                summary = "Sync conflict detected on savings ${localDec.goalName} (local v${localDec.syncVersion} vs cloud v${r.syncVersion})".take(240),
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    }
                }
            }
            if (validRemote.isNotEmpty()) {
                savingsDao.pruneSyncedNotIn(familyId, validRemote.map { it.recordId })
            } else if (remote.isEmpty() && pendingDeleteIds.isEmpty()) {
                savingsDao.pruneSyncedNotIn(familyId, listOf("__none__"))
            }
        }
    }

    suspend fun reconcileInvestments(familyId: String, remote: List<LocalInvestmentEntity>) {
        db.withTransaction {
            val pendingDeleteIds = syncOutboxDao.getPendingDeleteIds(familyId, "investments").toSet()
            val validRemote = remote.filter { it.familyId == familyId && it.recordId !in pendingDeleteIds }
            validRemote.forEach { r ->
                val localRaw = investmentDao.getById(r.recordId)
                if (localRaw == null || localRaw.syncStatus == SyncState.SYNCED.name) {
                    investmentDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name, baseSyncVersion = r.syncVersion)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    if (r.syncVersion > localDec.baseSyncVersion) {
                        investmentDao.upsert(localRaw.copy(syncStatus = SyncState.CONFLICT.name))
                        val conflict = LocalSyncConflictEntity(
                            conflictId = DatabaseIdentityHelper.generateConflictId(),
                            familyId = familyId,
                            collectionName = "investments",
                            documentId = r.recordId,
                            operationId = syncOutboxDao.getForDocument("investments", r.recordId)?.operationId
                                ?: DatabaseIdentityHelper.generateOperationId(),
                            conflictType = ConflictType.REMOTE_MODIFIED.name,
                            localVersion = localDec.syncVersion,
                            remoteVersion = r.syncVersion,
                            localSummary = mapper.summarizeInvestment(localDec),
                            remoteSummary = mapper.summarizeInvestment(r),
                            localPayloadJson = mapper.encodeInvestmentSnapshot(localDec),
                            remotePayloadJson = mapper.encodeInvestmentSnapshot(r),
                            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
                            status = ConflictStatus.UNRESOLVED.name
                        )
                        syncConflictDao.upsert(mapper.encrypt(conflict))
                        syncOutboxDao.deleteForDocument("investments", r.recordId)
                        auditLogDao.upsert(
                            LocalAuditLogEntity(
                                auditId = DatabaseIdentityHelper.generateAuditId(),
                                familyId = familyId,
                                actorUserId = localDec.createdByUserId,
                                entityCollection = "investments",
                                entityId = r.recordId,
                                action = AuditAction.SYNC.name,
                                summary = "Sync conflict detected on investment ${localDec.assetName} (local v${localDec.syncVersion} vs cloud v${r.syncVersion})".take(240),
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    }
                }
            }
            if (validRemote.isNotEmpty()) {
                investmentDao.pruneSyncedNotIn(familyId, validRemote.map { it.recordId })
            } else if (remote.isEmpty() && pendingDeleteIds.isEmpty()) {
                investmentDao.pruneSyncedNotIn(familyId, listOf("__none__"))
            }
        }
    }

    suspend fun reconcileBudgets(familyId: String, remote: List<LocalBudgetEntity>) {
        db.withTransaction {
            val pendingDeleteIds = syncOutboxDao.getPendingDeleteIds(familyId, "budgets").toSet()
            val validRemote = remote.filter { it.familyId == familyId && it.recordId !in pendingDeleteIds }
            validRemote.forEach { r ->
                val localRaw = budgetDao.getById(r.recordId)
                if (localRaw == null || localRaw.syncStatus == SyncState.SYNCED.name) {
                    budgetDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name, baseSyncVersion = r.syncVersion)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    if (r.syncVersion > localDec.baseSyncVersion) {
                        budgetDao.upsert(localRaw.copy(syncStatus = SyncState.CONFLICT.name))
                        val conflict = LocalSyncConflictEntity(
                            conflictId = DatabaseIdentityHelper.generateConflictId(),
                            familyId = familyId,
                            collectionName = "budgets",
                            documentId = r.recordId,
                            operationId = syncOutboxDao.getForDocument("budgets", r.recordId)?.operationId
                                ?: DatabaseIdentityHelper.generateOperationId(),
                            conflictType = ConflictType.REMOTE_MODIFIED.name,
                            localVersion = localDec.syncVersion,
                            remoteVersion = r.syncVersion,
                            localSummary = mapper.summarizeBudget(localDec),
                            remoteSummary = mapper.summarizeBudget(r),
                            localPayloadJson = mapper.encodeBudgetSnapshot(localDec),
                            remotePayloadJson = mapper.encodeBudgetSnapshot(r),
                            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
                            status = ConflictStatus.UNRESOLVED.name
                        )
                        syncConflictDao.upsert(mapper.encrypt(conflict))
                        syncOutboxDao.deleteForDocument("budgets", r.recordId)
                        auditLogDao.upsert(
                            LocalAuditLogEntity(
                                auditId = DatabaseIdentityHelper.generateAuditId(),
                                familyId = familyId,
                                actorUserId = localDec.createdByUserId,
                                entityCollection = "budgets",
                                entityId = r.recordId,
                                action = AuditAction.SYNC.name,
                                summary = "Sync conflict detected on budget ${localDec.category} (local v${localDec.syncVersion} vs cloud v${r.syncVersion})".take(240),
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    }
                }
            }
            if (validRemote.isNotEmpty()) {
                budgetDao.pruneSyncedNotIn(familyId, validRemote.map { it.recordId })
            } else if (remote.isEmpty() && pendingDeleteIds.isEmpty()) {
                budgetDao.pruneSyncedNotIn(familyId, listOf("__none__"))
            }
        }
    }

    suspend fun resolveConflict(
        conflictId: String,
        chosenPolicy: ConflictResolutionPolicy,
        actorUserId: String,
        remoteVersionOverride: Int?
    ): Boolean = db.withTransaction {
        val raw = syncConflictDao.getById(conflictId) ?: return@withTransaction false
        val conflict = mapper.decrypt(raw)
        if (chosenPolicy == ConflictResolutionPolicy.MANUAL_REVIEW) return@withTransaction false

        val now = System.currentTimeMillis()
        when (chosenPolicy) {
            ConflictResolutionPolicy.CLIENT_WINS -> {
                val targetRemoteVer = remoteVersionOverride ?: conflict.remoteVersion
                val bumpedVer = maxOf(conflict.localVersion + 1, targetRemoteVer + 1)
                val outboxOp = if (conflict.conflictType == ConflictType.REMOTE_DELETED.name) "CREATE" else "UPDATE"
                val baseVer = if (outboxOp == "CREATE") 0 else targetRemoteVer
                var idempotencyKey = ""

                when (conflict.collectionName) {
                    "transactions" -> transactionDao.getById(conflict.documentId)?.let { rec ->
                        idempotencyKey = rec.idempotencyKey
                        transactionDao.upsert(
                            rec.copy(
                                syncStatus = if (outboxOp == "CREATE") SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
                                syncVersion = bumpedVer,
                                baseSyncVersion = baseVer,
                                updatedAtMillis = now
                            )
                        )
                    }
                    "loans" -> loanDao.getById(conflict.documentId)?.let { rec ->
                        idempotencyKey = rec.idempotencyKey
                        loanDao.upsert(
                            rec.copy(
                                syncStatus = if (outboxOp == "CREATE") SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
                                syncVersion = bumpedVer,
                                baseSyncVersion = baseVer,
                                updatedAtMillis = now
                            )
                        )
                    }
                    "savings" -> savingsDao.getById(conflict.documentId)?.let { rec ->
                        idempotencyKey = rec.idempotencyKey
                        savingsDao.upsert(
                            rec.copy(
                                syncStatus = if (outboxOp == "CREATE") SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
                                syncVersion = bumpedVer,
                                baseSyncVersion = baseVer,
                                updatedAtMillis = now
                            )
                        )
                    }
                    "investments" -> investmentDao.getById(conflict.documentId)?.let { rec ->
                        idempotencyKey = rec.idempotencyKey
                        investmentDao.upsert(
                            rec.copy(
                                syncStatus = if (outboxOp == "CREATE") SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
                                syncVersion = bumpedVer,
                                baseSyncVersion = baseVer,
                                updatedAtMillis = now
                            )
                        )
                    }
                    "budgets" -> budgetDao.getById(conflict.documentId)?.let { rec ->
                        idempotencyKey = rec.idempotencyKey
                        budgetDao.upsert(
                            rec.copy(
                                syncStatus = if (outboxOp == "CREATE") SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
                                syncVersion = bumpedVer,
                                baseSyncVersion = baseVer,
                                updatedAtMillis = now
                            )
                        )
                    }
                }

                syncOutboxDao.upsert(
                    LocalSyncOutboxEntity(
                        outboxId = DatabaseIdentityHelper.generateOutboxId(),
                        familyId = conflict.familyId,
                        actorUserId = actorUserId,
                        collectionName = conflict.collectionName,
                        documentId = conflict.documentId,
                        operation = outboxOp,
                        status = "PENDING",
                        syncVersion = bumpedVer,
                        baseSyncVersion = baseVer,
                        operationId = DatabaseIdentityHelper.generateOperationId(),
                        idempotencyKey = idempotencyKey,
                        resolutionPolicy = ConflictResolutionPolicy.CLIENT_WINS.name,
                        updatedAtMillis = now
                    )
                )
                syncConflictDao.upsert(
                    raw.copy(
                        policy = ConflictResolutionPolicy.CLIENT_WINS.name,
                        status = ConflictStatus.RESOLVED_LOCAL.name,
                        resolvedAtMillis = now,
                        resolvedByUserId = actorUserId
                    )
                )
                auditLogDao.upsert(
                    LocalAuditLogEntity(
                        auditId = DatabaseIdentityHelper.generateAuditId(),
                        familyId = conflict.familyId,
                        actorUserId = actorUserId,
                        entityCollection = conflict.collectionName,
                        entityId = conflict.documentId,
                        action = AuditAction.SYNC.name,
                        summary = "Resolved conflict (This Device Wins -> v$bumpedVer)".take(240),
                        syncStatus = SyncState.PENDING_CREATE.name,
                        createdAtMillis = now
                    )
                )
            }
            ConflictResolutionPolicy.SERVER_WINS -> {
                syncOutboxDao.deleteForDocument(conflict.collectionName, conflict.documentId)
                if (conflict.conflictType == ConflictType.REMOTE_DELETED.name) {
                    when (conflict.collectionName) {
                        "transactions" -> transactionDao.deleteById(conflict.documentId)
                        "loans" -> loanDao.deleteById(conflict.documentId)
                        "savings" -> savingsDao.deleteById(conflict.documentId)
                        "investments" -> investmentDao.deleteById(conflict.documentId)
                        "budgets" -> budgetDao.deleteById(conflict.documentId)
                    }
                } else if (conflict.remotePayloadJson.isNotBlank()) {
                    when (conflict.collectionName) {
                        "transactions" -> mapper.decodeTransactionSnapshot(conflict.remotePayloadJson)?.let {
                            transactionDao.upsert(mapper.encrypt(it))
                        }
                        "loans" -> mapper.decodeLoanSnapshot(conflict.remotePayloadJson)?.let {
                            loanDao.upsert(mapper.encrypt(it))
                        }
                        "savings" -> mapper.decodeSavingsSnapshot(conflict.remotePayloadJson)?.let {
                            savingsDao.upsert(mapper.encrypt(it))
                        }
                        "investments" -> mapper.decodeInvestmentSnapshot(conflict.remotePayloadJson)?.let {
                            investmentDao.upsert(mapper.encrypt(it))
                        }
                        "budgets" -> mapper.decodeBudgetSnapshot(conflict.remotePayloadJson)?.let {
                            budgetDao.upsert(mapper.encrypt(it))
                        }
                    }
                }
                syncConflictDao.upsert(
                    raw.copy(
                        policy = ConflictResolutionPolicy.SERVER_WINS.name,
                        status = ConflictStatus.RESOLVED_REMOTE.name,
                        resolvedAtMillis = now,
                        resolvedByUserId = actorUserId
                    )
                )
                auditLogDao.upsert(
                    LocalAuditLogEntity(
                        auditId = DatabaseIdentityHelper.generateAuditId(),
                        familyId = conflict.familyId,
                        actorUserId = actorUserId,
                        entityCollection = conflict.collectionName,
                        entityId = conflict.documentId,
                        action = AuditAction.SYNC.name,
                        summary = "Resolved conflict (Cloud Wins v${conflict.remoteVersion})".take(240),
                        syncStatus = SyncState.SYNCED.name,
                        createdAtMillis = now
                    )
                )
            }
            ConflictResolutionPolicy.MANUAL_REVIEW -> return@withTransaction false
        }
        true
    }
}
