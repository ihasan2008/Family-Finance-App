package com.example.core.local.abstraction

import androidx.room.withTransaction
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalProposalVoteHistoryEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.security.AndroidKeyStoreCipher
import com.example.core.local.security.LocalDataCipher
import com.example.core.model.ApprovalDecisionEngine
import com.example.core.model.AuditAction
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.DecisionCategory
import com.example.core.model.FinancialModuleType
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.ProposalStatus
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictStatus
import com.example.core.sync.ConflictType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Room/SQLite v12 implementation of [LocalDatabaseStore].
 * - Encrypts sensitive local fields via [LocalDataCipher] (AES-256-GCM).
 * - Preserves `CREATE` / `baseSyncVersion = 0` for offline edit-after-create and cancels un-synced
 *   pending creates cleanly when deleted offline.
 * - Delegates pull conflict detection & resolution to [RemoteReconciliationHelper].
 */
class SqliteRoomDatabaseStore(
    private val db: FamilyFinanceDatabase,
    private val cipher: LocalDataCipher = AndroidKeyStoreCipher()
) : LocalDatabaseStore {

    override val engineLabel: String = "Room SQLite v12 (IndexedDB-Parity Store)"
    override val encryptionEngineLabel: String get() = cipher.engineName

    private val mapper = LocalEntityCipherMapper(cipher)
    private val reconciler = RemoteReconciliationHelper(db, mapper)

    private val familyDao = db.familyDao()
    private val memberDao = db.familyMemberDao()
    private val memberCardDao = db.memberCardDao()
    private val transactionDao = db.transactionDao()
    private val loanDao = db.loanDao()
    private val savingsDao = db.savingsDao()
    private val investmentDao = db.investmentDao()
    private val budgetDao = db.budgetDao()
    private val financialEventDao = db.financialEventDao()
    private val chatDao = db.chatDao()
    private val notificationDao = db.notificationDao()
    private val auditLogDao = db.auditLogDao()
    private val syncOutboxDao = db.syncOutboxDao()
    private val syncConflictDao = db.syncConflictDao()
    private val cachedReportDao = db.cachedReportDao()
    private val callHistoryDao = db.callHistoryDao()
    private val meetingDao = db.meetingDao()
    private val approvalSettingsDao = db.approvalSettingsDao()
    private val proposalDao = db.proposalDao()
    private val proposalVoteDao = db.proposalVoteDao()
    private val proposalVoteHistoryDao = db.proposalVoteHistoryDao()

    override fun observeTransactions(familyId: String): Flow<List<LocalTransactionEntity>> =
        transactionDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeLoans(familyId: String): Flow<List<LocalLoanEntity>> =
        loanDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeSavings(familyId: String): Flow<List<LocalSavingsEntity>> =
        savingsDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeInvestments(familyId: String): Flow<List<LocalInvestmentEntity>> =
        investmentDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeBudgets(familyId: String): Flow<List<LocalBudgetEntity>> =
        budgetDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeFinancialEvents(familyId: String): Flow<List<LocalFinancialEventEntity>> =
        financialEventDao.observeByFamily(familyId)

    override fun observeChats(familyId: String): Flow<List<LocalChatMessageEntity>> =
        chatDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeNotifications(familyId: String, userId: String): Flow<List<LocalNotificationEntity>> =
        notificationDao.observeForMember(familyId, userId).map { list -> list.map(mapper::decrypt) }

    override fun observeAuditLogs(familyId: String, limit: Int): Flow<List<LocalAuditLogEntity>> =
        auditLogDao.observeRecentByFamily(familyId, limit)

    override fun observeOutbox(familyId: String): Flow<List<LocalSyncOutboxEntity>> =
        syncOutboxDao.observeForFamily(familyId)

    override fun observeConflicts(familyId: String): Flow<List<LocalSyncConflictEntity>> =
        syncConflictDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeCachedReports(familyId: String): Flow<List<LocalCachedReportEntity>> =
        cachedReportDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeCallHistory(familyId: String, userId: String): Flow<List<LocalCallRecordEntity>> =
        callHistoryDao.observeForUserInFamily(familyId, userId).map { list -> list.map(mapper::decrypt) }

    override fun observeMeetings(familyId: String): Flow<List<LocalMeetingEntity>> =
        meetingDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeMemberCards(familyId: String): Flow<List<LocalMemberCardEntity>> =
        memberCardDao.observeByFamily(familyId)

    override fun observeApprovalSettings(familyId: String): Flow<LocalApprovalSettingsEntity?> =
        approvalSettingsDao.observeByFamily(familyId)

    override fun observeProposals(familyId: String): Flow<List<LocalProposalEntity>> =
        proposalDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeProposalVotes(proposalId: String): Flow<List<LocalProposalVoteEntity>> =
        proposalVoteDao.observeByProposal(proposalId).map { list -> list.map(mapper::decrypt) }

    override fun observeVotesForFamily(familyId: String): Flow<List<LocalProposalVoteEntity>> =
        proposalVoteDao.observeByFamily(familyId).map { list -> list.map(mapper::decrypt) }

    override fun observeProposalHistory(proposalId: String): Flow<List<LocalProposalVoteHistoryEntity>> =
        proposalVoteHistoryDao.observeByProposal(proposalId)

    override fun observeAllDecisionHistoryForFamily(familyId: String): Flow<List<LocalProposalVoteHistoryEntity>> =
        proposalVoteHistoryDao.observeByFamily(familyId)

    override fun observeDatabaseHealth(familyId: String, userId: String): Flow<DatabaseHealthSnapshot> {
        val coreCounts = combine(
            transactionDao.observeCountByFamily(familyId),
            loanDao.observeCountByFamily(familyId),
            savingsDao.observeCountByFamily(familyId),
            investmentDao.observeCountByFamily(familyId),
            budgetDao.observeCountByFamily(familyId)
        ) { tx, loans, savings, investments, budgets ->
            intArrayOf(tx, loans, savings, investments, budgets)
        }
        val metaCounts = combine(
            chatDao.observeCountByFamily(familyId),
            notificationDao.observeCountForMember(familyId, userId),
            auditLogDao.observeCountByFamily(familyId),
            syncOutboxDao.observePendingCount(familyId),
            syncOutboxDao.observeErrorCount(familyId)
        ) { chats, notifications, audits, pending, failed ->
            intArrayOf(chats, notifications, audits, pending, failed)
        }
        val cacheAndConflict = combine(
            financialEventDao.observeCountByFamily(familyId),
            cachedReportDao.observeCountByFamily(familyId),
            syncConflictDao.observeUnresolvedCount(familyId),
            callHistoryDao.observeCountForUser(familyId, userId),
            meetingDao.observeCountByFamily(familyId)
        ) { eventsCount, reportsCount, conflictsCount, callsCount, meetingsCount ->
            intArrayOf(eventsCount, reportsCount, conflictsCount, callsCount, meetingsCount)
        }
        return combine(coreCounts, metaCounts, cacheAndConflict) { core, meta, cc ->
            DatabaseHealthSnapshot(
                familyId = familyId,
                localEngineName = engineLabel,
                encryptionEngineName = encryptionEngineLabel,
                transactionsCount = core[0],
                loansCount = core[1],
                savingsCount = core[2],
                investmentsCount = core[3],
                budgetsCount = core[4],
                financialEventsCount = cc[0],
                chatsCount = meta[0],
                callsCount = cc[3],
                meetingsCount = cc[4],
                notificationsCount = meta[1],
                auditLogsCount = meta[2],
                cachedReportsCount = cc[1],
                pendingSyncCount = meta[3],
                failedSyncCount = meta[4],
                unresolvedConflictsCount = cc[2]
            )
        }
    }

    override suspend fun getCachedFamily(familyId: String): LocalFamilyEntity? =
        familyDao.getFamilyById(familyId)

    override suspend fun getCachedMember(familyId: String, userId: String): LocalFamilyMemberEntity? =
        memberDao.getMember(familyId, userId)

    override suspend fun saveTransactionIdempotent(
        entity: LocalTransactionEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalTransactionEntity> = db.withTransaction {
        val byKey = transactionDao.findByIdempotencyKey(entity.familyId, entity.idempotencyKey)
        if (byKey != null && byKey.recordId != entity.recordId) {
            return@withTransaction IdempotentWriteResult(record = mapper.decrypt(byKey), deduplicatedExisting = true)
        }
        val existingById = transactionDao.getById(entity.recordId)
        val existingOutbox = syncOutboxDao.getForDocument("transactions", entity.recordId)
        val isStillCreate = existingById == null ||
            existingById.syncStatus == SyncState.PENDING_CREATE.name ||
            existingOutbox?.operation == "CREATE"
        val operation = if (isStillCreate) "CREATE" else "UPDATE"
        val baseVer = if (isStillCreate) 0 else resolveBaseVersion(existingById.baseSyncVersion, existingById.syncVersion)
        val nextVer = if (existingById == null) entity.syncVersion else maxOf(entity.syncVersion, existingById.syncVersion + 1)
        val staged = entity.copy(
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
            syncVersion = nextVer,
            baseSyncVersion = baseVer
        )
        transactionDao.upsert(mapper.encrypt(staged))
        auditLogDao.upsert(auditEntry.copy(entityId = staged.recordId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = staged.familyId,
                actorUserId = auditEntry.actorUserId.ifBlank { staged.createdByUserId },
                collectionName = "transactions",
                documentId = staged.recordId,
                operation = operation,
                syncVersion = staged.syncVersion,
                baseSyncVersion = baseVer,
                idempotencyKey = staged.idempotencyKey,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("${staged.recordId}|${staged.syncVersion}"),
                deviceId = staged.deviceId,
                existingOutbox = existingOutbox
            )
        )
        IdempotentWriteResult(record = staged, deduplicatedExisting = false)
    }

    override suspend fun saveLoanIdempotent(
        entity: LocalLoanEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalLoanEntity> = db.withTransaction {
        val byKey = loanDao.findByIdempotencyKey(entity.familyId, entity.idempotencyKey)
        if (byKey != null && byKey.recordId != entity.recordId) {
            return@withTransaction IdempotentWriteResult(record = mapper.decrypt(byKey), deduplicatedExisting = true)
        }
        val existingById = loanDao.getById(entity.recordId)
        val existingOutbox = syncOutboxDao.getForDocument("loans", entity.recordId)
        val isStillCreate = existingById == null ||
            existingById.syncStatus == SyncState.PENDING_CREATE.name ||
            existingOutbox?.operation == "CREATE"
        val operation = if (isStillCreate) "CREATE" else "UPDATE"
        val baseVer = if (isStillCreate) 0 else resolveBaseVersion(existingById.baseSyncVersion, existingById.syncVersion)
        val nextVer = if (existingById == null) entity.syncVersion else maxOf(entity.syncVersion, existingById.syncVersion + 1)
        val staged = entity.copy(
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
            syncVersion = nextVer,
            baseSyncVersion = baseVer
        )
        loanDao.upsert(mapper.encrypt(staged))
        auditLogDao.upsert(auditEntry.copy(entityId = staged.recordId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = staged.familyId,
                actorUserId = auditEntry.actorUserId.ifBlank { staged.createdByUserId },
                collectionName = "loans",
                documentId = staged.recordId,
                operation = operation,
                syncVersion = staged.syncVersion,
                baseSyncVersion = baseVer,
                idempotencyKey = staged.idempotencyKey,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("${staged.recordId}|${staged.syncVersion}"),
                deviceId = staged.deviceId,
                existingOutbox = existingOutbox
            )
        )
        IdempotentWriteResult(record = staged, deduplicatedExisting = false)
    }

    override suspend fun saveSavingsIdempotent(
        entity: LocalSavingsEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalSavingsEntity> = db.withTransaction {
        val byKey = savingsDao.findByIdempotencyKey(entity.familyId, entity.idempotencyKey)
        if (byKey != null && byKey.recordId != entity.recordId) {
            return@withTransaction IdempotentWriteResult(record = mapper.decrypt(byKey), deduplicatedExisting = true)
        }
        val existingById = savingsDao.getById(entity.recordId)
        val existingOutbox = syncOutboxDao.getForDocument("savings", entity.recordId)
        val isStillCreate = existingById == null ||
            existingById.syncStatus == SyncState.PENDING_CREATE.name ||
            existingOutbox?.operation == "CREATE"
        val operation = if (isStillCreate) "CREATE" else "UPDATE"
        val baseVer = if (isStillCreate) 0 else resolveBaseVersion(existingById.baseSyncVersion, existingById.syncVersion)
        val nextVer = if (existingById == null) entity.syncVersion else maxOf(entity.syncVersion, existingById.syncVersion + 1)
        val staged = entity.copy(
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
            syncVersion = nextVer,
            baseSyncVersion = baseVer
        )
        savingsDao.upsert(mapper.encrypt(staged))
        auditLogDao.upsert(auditEntry.copy(entityId = staged.recordId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = staged.familyId,
                actorUserId = auditEntry.actorUserId.ifBlank { staged.createdByUserId },
                collectionName = "savings",
                documentId = staged.recordId,
                operation = operation,
                syncVersion = staged.syncVersion,
                baseSyncVersion = baseVer,
                idempotencyKey = staged.idempotencyKey,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("${staged.recordId}|${staged.syncVersion}"),
                deviceId = staged.deviceId,
                existingOutbox = existingOutbox
            )
        )
        IdempotentWriteResult(record = staged, deduplicatedExisting = false)
    }

    override suspend fun saveInvestmentIdempotent(
        entity: LocalInvestmentEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalInvestmentEntity> = db.withTransaction {
        val byKey = investmentDao.findByIdempotencyKey(entity.familyId, entity.idempotencyKey)
        if (byKey != null && byKey.recordId != entity.recordId) {
            return@withTransaction IdempotentWriteResult(record = mapper.decrypt(byKey), deduplicatedExisting = true)
        }
        val existingById = investmentDao.getById(entity.recordId)
        val existingOutbox = syncOutboxDao.getForDocument("investments", entity.recordId)
        val isStillCreate = existingById == null ||
            existingById.syncStatus == SyncState.PENDING_CREATE.name ||
            existingOutbox?.operation == "CREATE"
        val operation = if (isStillCreate) "CREATE" else "UPDATE"
        val baseVer = if (isStillCreate) 0 else resolveBaseVersion(existingById.baseSyncVersion, existingById.syncVersion)
        val nextVer = if (existingById == null) entity.syncVersion else maxOf(entity.syncVersion, existingById.syncVersion + 1)
        val staged = entity.copy(
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
            syncVersion = nextVer,
            baseSyncVersion = baseVer
        )
        investmentDao.upsert(mapper.encrypt(staged))
        auditLogDao.upsert(auditEntry.copy(entityId = staged.recordId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = staged.familyId,
                actorUserId = auditEntry.actorUserId.ifBlank { staged.createdByUserId },
                collectionName = "investments",
                documentId = staged.recordId,
                operation = operation,
                syncVersion = staged.syncVersion,
                baseSyncVersion = baseVer,
                idempotencyKey = staged.idempotencyKey,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("${staged.recordId}|${staged.syncVersion}"),
                deviceId = staged.deviceId,
                existingOutbox = existingOutbox
            )
        )
        IdempotentWriteResult(record = staged, deduplicatedExisting = false)
    }

    override suspend fun saveBudgetIdempotent(
        entity: LocalBudgetEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalBudgetEntity> = db.withTransaction {
        val byKey = budgetDao.findByIdempotencyKey(entity.familyId, entity.idempotencyKey)
        if (byKey != null && byKey.recordId != entity.recordId) {
            return@withTransaction IdempotentWriteResult(record = mapper.decrypt(byKey), deduplicatedExisting = true)
        }
        val existingById = budgetDao.getById(entity.recordId)
        val existingOutbox = syncOutboxDao.getForDocument("budgets", entity.recordId)
        val isStillCreate = existingById == null ||
            existingById.syncStatus == SyncState.PENDING_CREATE.name ||
            existingOutbox?.operation == "CREATE"
        val operation = if (isStillCreate) "CREATE" else "UPDATE"
        val baseVer = if (isStillCreate) 0 else resolveBaseVersion(existingById.baseSyncVersion, existingById.syncVersion)
        val nextVer = if (existingById == null) entity.syncVersion else maxOf(entity.syncVersion, existingById.syncVersion + 1)
        val staged = entity.copy(
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name,
            syncVersion = nextVer,
            baseSyncVersion = baseVer
        )
        budgetDao.upsert(mapper.encrypt(staged))
        auditLogDao.upsert(auditEntry.copy(entityId = staged.recordId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = staged.familyId,
                actorUserId = auditEntry.actorUserId.ifBlank { staged.createdByUserId },
                collectionName = "budgets",
                documentId = staged.recordId,
                operation = operation,
                syncVersion = staged.syncVersion,
                baseSyncVersion = baseVer,
                idempotencyKey = staged.idempotencyKey,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("${staged.recordId}|${staged.syncVersion}"),
                deviceId = staged.deviceId,
                existingOutbox = existingOutbox
            )
        )
        IdempotentWriteResult(record = staged, deduplicatedExisting = false)
    }

    override suspend fun saveFinancialEventWithOutbox(entity: LocalFinancialEventEntity) = db.withTransaction {
        financialEventDao.upsert(entity.copy(syncStatus = SyncState.PENDING_CREATE.name))
        val opId = DatabaseIdentityHelper.generateOperationId()
        syncOutboxDao.upsert(
            LocalSyncOutboxEntity(
                outboxId = DatabaseIdentityHelper.generateOutboxId(),
                familyId = entity.familyId,
                actorUserId = entity.actorUserId,
                collectionName = "financialEvents",
                documentId = entity.eventId,
                operation = "CREATE",
                status = "PENDING",
                operationId = opId
            )
        )
    }

    override suspend fun saveChatMessage(
        entity: LocalChatMessageEntity,
        auditEntry: LocalAuditLogEntity
    ) = db.withTransaction {
        chatDao.upsert(mapper.encrypt(entity.copy(syncStatus = SyncState.PENDING_CREATE.name)))
        auditLogDao.upsert(auditEntry.copy(entityId = entity.messageId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            LocalSyncOutboxEntity(
                outboxId = DatabaseIdentityHelper.generateOutboxId(),
                familyId = entity.familyId,
                actorUserId = entity.senderUserId,
                collectionName = "chats",
                documentId = entity.messageId,
                operation = "CREATE",
                status = "PENDING",
                operationId = DatabaseIdentityHelper.generateOperationId(),
                idempotencyKey = entity.messageId
            )
        )
    }

    override suspend fun getCachedChatsPage(
        familyId: String,
        beforeMillis: Long?,
        limit: Int
    ): List<LocalChatMessageEntity> {
        val raw = if (beforeMillis == null) {
            chatDao.getLatestPaged(familyId, limit)
        } else {
            chatDao.getOlderThan(familyId, beforeMillis, limit)
        }
        return raw.map(mapper::decrypt).sortedBy { it.createdAtMillis }
    }

    override suspend fun editChatMessageLocal(
        messageId: String,
        familyId: String,
        actorUserId: String,
        newText: String,
        auditEntry: LocalAuditLogEntity
    ): LocalChatMessageEntity? = db.withTransaction {
        val raw = chatDao.getById(messageId) ?: return@withTransaction null
        if (raw.familyId != familyId || raw.senderUserId != actorUserId || raw.recordStatus != "ACTIVE") {
            return@withTransaction null
        }
        val dec = mapper.decrypt(raw)
        val now = System.currentTimeMillis()
        val existingOutbox = syncOutboxDao.getForDocument("chats", messageId)
        val isStillCreate = raw.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
        val nextVer = dec.syncVersion + 1
        val updated = dec.copy(
            messageText = newText.trim().take(1000),
            editedAtMillis = now,
            updatedAtMillis = now,
            syncVersion = nextVer,
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name
        )
        chatDao.upsert(mapper.encrypt(updated))
        auditLogDao.upsert(auditEntry.copy(entityId = messageId, syncStatus = SyncState.PENDING_CREATE.name))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = familyId,
                actorUserId = actorUserId,
                collectionName = "chats",
                documentId = messageId,
                operation = if (isStillCreate) "CREATE" else "UPDATE",
                syncVersion = nextVer,
                baseSyncVersion = if (isStillCreate) 0 else dec.syncVersion,
                idempotencyKey = messageId,
                payloadHash = auditEntry.afterHash ?: DatabaseIdentityHelper.computePayloadHash("$messageId|$nextVer"),
                deviceId = auditEntry.deviceId,
                existingOutbox = existingOutbox
            )
        )
        updated
    }

    override suspend fun softDeleteChatMessageLocal(
        messageId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): LocalChatMessageEntity? = db.withTransaction {
        val raw = chatDao.getById(messageId) ?: return@withTransaction null
        if (raw.familyId != familyId) return@withTransaction null
        val dec = mapper.decrypt(raw)
        val now = System.currentTimeMillis()
        val existingOutbox = syncOutboxDao.getForDocument("chats", messageId)
        val neverSyncedToCloud = raw.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
        val nextVer = dec.syncVersion + 1
        val updated = dec.copy(
            messageText = "[Message deleted]",
            recordStatus = "VOIDED",
            deletedAtMillis = now,
            deletedByUserId = actorUserId,
            updatedAtMillis = now,
            syncVersion = nextVer,
            syncStatus = if (neverSyncedToCloud) SyncState.SYNCED.name else SyncState.PENDING_UPDATE.name
        )
        chatDao.upsert(mapper.encrypt(updated))
        auditLogDao.upsert(auditEntry.copy(entityId = messageId, syncStatus = SyncState.PENDING_CREATE.name))
        if (neverSyncedToCloud) {
            syncOutboxDao.deleteForDocument("chats", messageId)
        } else {
            syncOutboxDao.upsert(
                newOutboxEntry(
                    familyId = familyId,
                    actorUserId = actorUserId,
                    collectionName = "chats",
                    documentId = messageId,
                    operation = "UPDATE",
                    syncVersion = nextVer,
                    baseSyncVersion = dec.syncVersion,
                    idempotencyKey = messageId,
                    payloadHash = DatabaseIdentityHelper.computePayloadHash("$messageId|VOIDED|$nextVer"),
                    deviceId = auditEntry.deviceId,
                    existingOutbox = existingOutbox
                )
            )
        }
        updated
    }

    override suspend fun markChatMessagesReadLocal(
        familyId: String,
        readerUserId: String,
        messageIds: List<String>
    ): Int = db.withTransaction {
        if (readerUserId.isBlank() || messageIds.isEmpty()) return@withTransaction 0
        var marked = 0
        val now = System.currentTimeMillis()
        for (msgId in messageIds.distinct()) {
            val raw = chatDao.getById(msgId) ?: continue
            if (raw.familyId != familyId || raw.recordStatus == "ARCHIVED") continue
            if (raw.senderUserId == readerUserId || readerUserId in raw.readByUids) continue
            val mergedCsv = (raw.readByUids + readerUserId).distinct().take(20).joinToString(",")
            chatDao.upsert(raw.copy(readByUidsCsv = mergedCsv, updatedAtMillis = now))
            val existingOutbox = syncOutboxDao.getForDocument("chats", msgId)
            if (existingOutbox == null && raw.syncStatus == SyncState.SYNCED.name) {
                syncOutboxDao.upsert(
                    LocalSyncOutboxEntity(
                        outboxId = DatabaseIdentityHelper.generateOutboxId(),
                        familyId = familyId,
                        actorUserId = readerUserId,
                        collectionName = "chats",
                        documentId = msgId,
                        operation = "READ_RECEIPT",
                        status = "PENDING",
                        operationId = DatabaseIdentityHelper.generateOperationId(),
                        updatedAtMillis = now
                    )
                )
            }
            marked++
        }
        marked
    }

    override suspend fun purgeExpiredChatsLocal(
        familyId: String,
        nowMillis: Long,
        auditEntry: LocalAuditLogEntity?
    ): List<String> = db.withTransaction {
        val expired = chatDao.getExpiredChats(familyId, nowMillis)
        if (expired.isEmpty()) return@withTransaction emptyList()
        val deletedIds = mutableListOf<String>()
        for (msg in expired) {
            val existingOutbox = syncOutboxDao.getForDocument("chats", msg.messageId)
            val neverSynced = msg.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
            chatDao.deleteById(msg.messageId)
            deletedIds.add(msg.messageId)
            if (neverSynced) {
                syncOutboxDao.deleteForDocument("chats", msg.messageId)
            } else {
                syncOutboxDao.upsert(
                    LocalSyncOutboxEntity(
                        outboxId = existingOutbox?.outboxId ?: DatabaseIdentityHelper.generateOutboxId(),
                        familyId = familyId,
                        actorUserId = auditEntry?.actorUserId ?: msg.senderUserId,
                        collectionName = "chats",
                        documentId = msg.messageId,
                        operation = "DELETE",
                        status = "PENDING",
                        syncVersion = msg.syncVersion + 1,
                        baseSyncVersion = msg.syncVersion,
                        operationId = DatabaseIdentityHelper.generateOperationId(),
                        updatedAtMillis = nowMillis
                    )
                )
            }
        }
        if (auditEntry != null) {
            auditLogDao.upsert(auditEntry.copy(syncStatus = SyncState.PENDING_CREATE.name))
        }
        deletedIds
    }

    override suspend fun saveNotification(entity: LocalNotificationEntity) {
        notificationDao.upsert(mapper.encrypt(entity))
    }

    override suspend fun saveNotificationWithOutbox(entity: LocalNotificationEntity) = db.withTransaction {
        notificationDao.upsert(mapper.encrypt(entity.copy(syncStatus = SyncState.PENDING_CREATE.name)))
        syncOutboxDao.upsert(
            LocalSyncOutboxEntity(
                outboxId = DatabaseIdentityHelper.generateOutboxId(),
                familyId = entity.familyId,
                actorUserId = entity.createdByUserId,
                collectionName = "notifications",
                documentId = entity.notificationId,
                operation = "CREATE",
                status = "PENDING",
                operationId = DatabaseIdentityHelper.generateOperationId()
            )
        )
    }

    override suspend fun markNotificationReadLocal(
        notificationId: String,
        familyId: String,
        readerUserId: String
    ): LocalNotificationEntity? = db.withTransaction {
        val raw = notificationDao.getById(notificationId) ?: return@withTransaction null
        if (raw.familyId != familyId) return@withTransaction null
        val dec = mapper.decrypt(raw)
        if (!dec.isUnreadFor(readerUserId)) return@withTransaction dec
        val now = System.currentTimeMillis()
        val mergedReadCsv = (dec.readByUids + readerUserId).distinct().take(20).joinToString(",")
        val ack = if (dec.recipientUserId == readerUserId) true else dec.acknowledged
        val existingOutbox = syncOutboxDao.getForDocument("notifications", notificationId)
        val isStillCreate = raw.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
        val updated = dec.copy(
            acknowledged = ack,
            readByUidsCsv = mergedReadCsv,
            updatedAtMillis = now,
            syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name
        )
        notificationDao.upsert(mapper.encrypt(updated))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = familyId,
                actorUserId = readerUserId,
                collectionName = "notifications",
                documentId = notificationId,
                operation = if (isStillCreate) "CREATE" else "UPDATE",
                syncVersion = 1,
                baseSyncVersion = 0,
                idempotencyKey = notificationId,
                payloadHash = "",
                deviceId = "",
                existingOutbox = existingOutbox
            )
        )
        updated
    }

    override suspend fun markAllNotificationsReadLocal(
        familyId: String,
        readerUserId: String
    ): Int = db.withTransaction {
        val active = notificationDao.getActiveForMember(familyId, readerUserId)
        var updatedCount = 0
        val now = System.currentTimeMillis()
        for (raw in active) {
            val dec = mapper.decrypt(raw)
            if (!dec.isUnreadFor(readerUserId)) continue
            val mergedReadCsv = (dec.readByUids + readerUserId).distinct().take(20).joinToString(",")
            val ack = if (dec.recipientUserId == readerUserId) true else dec.acknowledged
            val existingOutbox = syncOutboxDao.getForDocument("notifications", dec.notificationId)
            val isStillCreate = raw.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
            val updated = dec.copy(
                acknowledged = ack,
                readByUidsCsv = mergedReadCsv,
                updatedAtMillis = now,
                syncStatus = if (isStillCreate) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name
            )
            notificationDao.upsert(mapper.encrypt(updated))
            syncOutboxDao.upsert(
                newOutboxEntry(
                    familyId = familyId,
                    actorUserId = readerUserId,
                    collectionName = "notifications",
                    documentId = dec.notificationId,
                    operation = if (isStillCreate) "CREATE" else "UPDATE",
                    syncVersion = 1,
                    baseSyncVersion = 0,
                    idempotencyKey = dec.notificationId,
                    payloadHash = "",
                    deviceId = "",
                    existingOutbox = existingOutbox
                )
            )
            updatedCount++
        }
        updatedCount
    }

    override suspend fun archiveNotificationLocal(
        notificationId: String,
        familyId: String,
        actorUserId: String
    ): Boolean = db.withTransaction {
        val raw = notificationDao.getById(notificationId) ?: return@withTransaction false
        if (raw.familyId != familyId) return@withTransaction false
        val dec = mapper.decrypt(raw)
        val now = System.currentTimeMillis()
        val existingOutbox = syncOutboxDao.getForDocument("notifications", notificationId)
        val neverSynced = raw.syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
        val mergedReadCsv = (dec.readByUids + actorUserId).distinct().take(20).joinToString(",")
        val archived = dec.copy(
            recordStatus = "ARCHIVED",
            acknowledged = if (dec.recipientUserId == actorUserId) true else dec.acknowledged,
            readByUidsCsv = mergedReadCsv,
            updatedAtMillis = now,
            syncStatus = if (neverSynced) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name
        )
        notificationDao.upsert(mapper.encrypt(archived))
        syncOutboxDao.upsert(
            newOutboxEntry(
                familyId = familyId,
                actorUserId = actorUserId,
                collectionName = "notifications",
                documentId = notificationId,
                operation = if (neverSynced) "CREATE" else "UPDATE",
                syncVersion = 1,
                baseSyncVersion = 0,
                idempotencyKey = notificationId,
                payloadHash = "",
                deviceId = "",
                existingOutbox = existingOutbox
            )
        )
        true
    }

    override suspend fun saveCachedReport(report: LocalCachedReportEntity) {
        cachedReportDao.upsert(mapper.encrypt(report))
    }

    override suspend fun getCachedReportForPeriod(familyId: String, periodKey: String): LocalCachedReportEntity? =
        cachedReportDao.getForPeriod(familyId, periodKey)?.let(mapper::decrypt)

    override suspend fun appendAuditLog(entry: LocalAuditLogEntity) {
        auditLogDao.upsert(entry)
    }

    override suspend fun deleteTransactionLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean = deleteRecordSafely(
        collection = "transactions",
        recordId = recordId,
        familyId = familyId,
        actorUserId = actorUserId,
        auditEntry = auditEntry,
        findRecord = { transactionDao.getById(recordId)?.let { Triple(it.familyId, it.syncStatus, it.syncVersion to it.deviceId) } },
        deleteLocal = { transactionDao.deleteById(recordId) }
    )

    override suspend fun deleteLoanLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean = deleteRecordSafely(
        collection = "loans",
        recordId = recordId,
        familyId = familyId,
        actorUserId = actorUserId,
        auditEntry = auditEntry,
        findRecord = { loanDao.getById(recordId)?.let { Triple(it.familyId, it.syncStatus, it.syncVersion to it.deviceId) } },
        deleteLocal = { loanDao.deleteById(recordId) }
    )

    override suspend fun deleteSavingsLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean = deleteRecordSafely(
        collection = "savings",
        recordId = recordId,
        familyId = familyId,
        actorUserId = actorUserId,
        auditEntry = auditEntry,
        findRecord = { savingsDao.getById(recordId)?.let { Triple(it.familyId, it.syncStatus, it.syncVersion to it.deviceId) } },
        deleteLocal = { savingsDao.deleteById(recordId) }
    )

    override suspend fun deleteInvestmentLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean = deleteRecordSafely(
        collection = "investments",
        recordId = recordId,
        familyId = familyId,
        actorUserId = actorUserId,
        auditEntry = auditEntry,
        findRecord = { investmentDao.getById(recordId)?.let { Triple(it.familyId, it.syncStatus, it.syncVersion to it.deviceId) } },
        deleteLocal = { investmentDao.deleteById(recordId) }
    )

    override suspend fun deleteBudgetLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean = deleteRecordSafely(
        collection = "budgets",
        recordId = recordId,
        familyId = familyId,
        actorUserId = actorUserId,
        auditEntry = auditEntry,
        findRecord = { budgetDao.getById(recordId)?.let { Triple(it.familyId, it.syncStatus, it.syncVersion to it.deviceId) } },
        deleteLocal = { budgetDao.deleteById(recordId) }
    )

    private suspend fun deleteRecordSafely(
        collection: String,
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity,
        findRecord: suspend () -> Triple<String, String, Pair<Int, String>>?,
        deleteLocal: suspend () -> Unit
    ): Boolean = db.withTransaction {
        val found = findRecord() ?: return@withTransaction false
        val (recFamilyId, syncStatus, verAndDevice) = found
        if (recFamilyId != familyId) return@withTransaction false

        val existingOutbox = syncOutboxDao.getForDocument(collection, recordId)
        val neverSyncedToCloud = syncStatus == SyncState.PENDING_CREATE.name || existingOutbox?.operation == "CREATE"
        deleteLocal()
        auditLogDao.upsert(auditEntry.copy(entityId = recordId, syncStatus = SyncState.PENDING_CREATE.name))

        if (neverSyncedToCloud) {
            // Unsynced offline draft deleted before ever reaching Firestore: remove its CREATE outbox op cleanly.
            syncOutboxDao.deleteForDocument(collection, recordId)
        } else {
            syncOutboxDao.upsert(
                LocalSyncOutboxEntity(
                    outboxId = DatabaseIdentityHelper.generateOutboxId(),
                    familyId = familyId,
                    actorUserId = actorUserId,
                    collectionName = collection,
                    documentId = recordId,
                    operation = "DELETE",
                    status = "PENDING",
                    syncVersion = verAndDevice.first + 1,
                    baseSyncVersion = verAndDevice.first,
                    operationId = DatabaseIdentityHelper.generateOperationId(),
                    deviceId = verAndDevice.second
                )
            )
        }
        true
    }

    override suspend fun getActiveRecurringTemplates(familyId: String): List<LocalTransactionEntity> =
        transactionDao.getActiveRecurringTemplates(familyId).map(mapper::decrypt)

    override suspend fun getPendingOutboxForFamily(familyId: String): List<LocalSyncOutboxEntity> =
        syncOutboxDao.getPendingForFamily(familyId)

    override suspend fun getPendingOutboxForUser(userId: String): List<LocalSyncOutboxEntity> =
        syncOutboxDao.getPendingForUser(userId)

    override suspend fun getOutboxForDocument(collectionName: String, documentId: String): LocalSyncOutboxEntity? =
        syncOutboxDao.getForDocument(collectionName, documentId)

    override suspend fun getTransactionById(recordId: String): LocalTransactionEntity? =
        transactionDao.getById(recordId)?.let(mapper::decrypt)

    override suspend fun getLoanById(recordId: String): LocalLoanEntity? =
        loanDao.getById(recordId)?.let(mapper::decrypt)

    override suspend fun getSavingsById(recordId: String): LocalSavingsEntity? =
        savingsDao.getById(recordId)?.let(mapper::decrypt)

    override suspend fun getInvestmentById(recordId: String): LocalInvestmentEntity? =
        investmentDao.getById(recordId)?.let(mapper::decrypt)

    override suspend fun getBudgetById(recordId: String): LocalBudgetEntity? =
        budgetDao.getById(recordId)?.let(mapper::decrypt)

    override suspend fun getFinancialEventById(eventId: String): LocalFinancialEventEntity? =
        financialEventDao.getById(eventId)

    override suspend fun getNotificationById(notificationId: String): LocalNotificationEntity? =
        notificationDao.getById(notificationId)?.let(mapper::decrypt)

    override suspend fun getChatMessageById(messageId: String): LocalChatMessageEntity? =
        chatDao.getById(messageId)?.let(mapper::decrypt)

    override suspend fun markOutboxSynced(
        collectionName: String,
        documentId: String,
        syncedAtMillis: Long,
        confirmedServerVersion: Int?
    ) = db.withTransaction {
        syncOutboxDao.deleteForDocument(collectionName, documentId)
        when (collectionName) {
            "transactions" -> transactionDao.getById(documentId)?.let {
                val ver = confirmedServerVersion ?: it.syncVersion
                transactionDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, syncVersion = ver, baseSyncVersion = ver, lastSyncedAtMillis = syncedAtMillis))
            }
            "loans" -> loanDao.getById(documentId)?.let {
                val ver = confirmedServerVersion ?: it.syncVersion
                loanDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, syncVersion = ver, baseSyncVersion = ver, lastSyncedAtMillis = syncedAtMillis))
            }
            "savings" -> savingsDao.getById(documentId)?.let {
                val ver = confirmedServerVersion ?: it.syncVersion
                savingsDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, syncVersion = ver, baseSyncVersion = ver, lastSyncedAtMillis = syncedAtMillis))
            }
            "investments" -> investmentDao.getById(documentId)?.let {
                val ver = confirmedServerVersion ?: it.syncVersion
                investmentDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, syncVersion = ver, baseSyncVersion = ver, lastSyncedAtMillis = syncedAtMillis))
            }
            "budgets" -> budgetDao.getById(documentId)?.let {
                val ver = confirmedServerVersion ?: it.syncVersion
                budgetDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, syncVersion = ver, baseSyncVersion = ver, lastSyncedAtMillis = syncedAtMillis))
            }
            "financialEvents" -> financialEventDao.getById(documentId)?.let {
                financialEventDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name))
            }
            "notifications" -> notificationDao.getById(documentId)?.let {
                notificationDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name))
            }
            "chats" -> chatDao.getById(documentId)?.let {
                chatDao.upsert(it.copy(syncStatus = SyncState.SYNCED.name, lastSyncedAtMillis = syncedAtMillis))
            }
        }
    }

    override suspend fun markOutboxFailed(outboxItem: LocalSyncOutboxEntity, errorMessage: String) {
        syncOutboxDao.upsert(
            outboxItem.copy(
                status = "ERROR",
                retryCount = outboxItem.retryCount + 1,
                lastError = errorMessage.take(300),
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun quarantineInvalidOutbox(
        outboxItem: LocalSyncOutboxEntity,
        conflictType: ConflictType,
        reason: String,
        auditEntry: LocalAuditLogEntity
    ) = db.withTransaction {
        syncOutboxDao.deleteForDocument(outboxItem.collectionName, outboxItem.documentId)
        val conflict = LocalSyncConflictEntity(
            conflictId = DatabaseIdentityHelper.generateConflictId(),
            familyId = outboxItem.familyId,
            collectionName = outboxItem.collectionName,
            documentId = outboxItem.documentId,
            operationId = outboxItem.operationId,
            conflictType = conflictType.name,
            localVersion = outboxItem.syncVersion,
            remoteVersion = outboxItem.baseSyncVersion,
            localSummary = "Rejected ${outboxItem.operation} on ${outboxItem.collectionName}/${outboxItem.documentId}",
            remoteSummary = reason.take(240),
            policy = ConflictResolutionPolicy.MANUAL_REVIEW.name,
            status = ConflictStatus.UNRESOLVED.name
        )
        syncConflictDao.upsert(mapper.encrypt(conflict))
        when (outboxItem.collectionName) {
            "transactions" -> transactionDao.getById(outboxItem.documentId)?.let {
                transactionDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
            "loans" -> loanDao.getById(outboxItem.documentId)?.let {
                loanDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
            "savings" -> savingsDao.getById(outboxItem.documentId)?.let {
                savingsDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
            "investments" -> investmentDao.getById(outboxItem.documentId)?.let {
                investmentDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
            "budgets" -> budgetDao.getById(outboxItem.documentId)?.let {
                budgetDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
            "chats" -> chatDao.getById(outboxItem.documentId)?.let {
                chatDao.upsert(it.copy(syncStatus = SyncState.ERROR.name))
            }
        }
        auditLogDao.upsert(auditEntry)
    }

    override suspend fun recordSyncConflict(
        conflict: LocalSyncConflictEntity,
        auditEntry: LocalAuditLogEntity,
        removeFromOutbox: Boolean
    ) = db.withTransaction {
        syncConflictDao.upsert(mapper.encrypt(conflict))
        if (removeFromOutbox) {
            syncOutboxDao.deleteForDocument(conflict.collectionName, conflict.documentId)
        }
        when (conflict.collectionName) {
            "transactions" -> transactionDao.getById(conflict.documentId)?.let {
                transactionDao.upsert(it.copy(syncStatus = SyncState.CONFLICT.name))
            }
            "loans" -> loanDao.getById(conflict.documentId)?.let {
                loanDao.upsert(it.copy(syncStatus = SyncState.CONFLICT.name))
            }
            "savings" -> savingsDao.getById(conflict.documentId)?.let {
                savingsDao.upsert(it.copy(syncStatus = SyncState.CONFLICT.name))
            }
            "investments" -> investmentDao.getById(conflict.documentId)?.let {
                investmentDao.upsert(it.copy(syncStatus = SyncState.CONFLICT.name))
            }
            "budgets" -> budgetDao.getById(conflict.documentId)?.let {
                budgetDao.upsert(it.copy(syncStatus = SyncState.CONFLICT.name))
            }
        }
        auditLogDao.upsert(auditEntry)
    }

    override suspend fun resolveConflict(
        conflictId: String,
        chosenPolicy: ConflictResolutionPolicy,
        actorUserId: String,
        remoteVersionOverride: Int?
    ): Boolean = reconciler.resolveConflict(conflictId, chosenPolicy, actorUserId, remoteVersionOverride)

    override suspend fun reconcileRemoteTransactions(familyId: String, remote: List<LocalTransactionEntity>) =
        reconciler.reconcileTransactions(familyId, remote)

    override suspend fun reconcileRemoteLoans(familyId: String, remote: List<LocalLoanEntity>) =
        reconciler.reconcileLoans(familyId, remote)

    override suspend fun reconcileRemoteSavings(familyId: String, remote: List<LocalSavingsEntity>) =
        reconciler.reconcileSavings(familyId, remote)

    override suspend fun reconcileRemoteInvestments(familyId: String, remote: List<LocalInvestmentEntity>) =
        reconciler.reconcileInvestments(familyId, remote)

    override suspend fun reconcileRemoteBudgets(familyId: String, remote: List<LocalBudgetEntity>) =
        reconciler.reconcileBudgets(familyId, remote)

    override suspend fun reconcileRemoteFinancialEvents(remote: List<LocalFinancialEventEntity>) {
        if (remote.isNotEmpty()) {
            financialEventDao.upsertAll(remote.map { it.copy(syncStatus = SyncState.SYNCED.name) })
        }
    }

    override suspend fun reconcileRemoteChats(remote: List<LocalChatMessageEntity>) {
        if (remote.isEmpty()) return
        db.withTransaction {
            remote.forEach { r ->
                val localRaw = chatDao.getById(r.messageId)
                val outbox = syncOutboxDao.getForDocument("chats", r.messageId)
                if (localRaw == null || (localRaw.syncStatus == SyncState.SYNCED.name && outbox == null)) {
                    val mergedRead = ((localRaw?.readByUids ?: emptyList()) + r.readByUids).distinct().take(20).joinToString(",")
                    chatDao.upsert(
                        mapper.encrypt(
                            r.copy(
                                readByUidsCsv = mergedRead,
                                syncStatus = SyncState.SYNCED.name
                            )
                        )
                    )
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    val mergedRead = (localDec.readByUids + r.readByUids).distinct().take(20).joinToString(",")
                    if (outbox?.operation == "READ_RECEIPT" && r.syncVersion >= localDec.syncVersion) {
                        chatDao.upsert(
                            mapper.encrypt(
                                r.copy(
                                    readByUidsCsv = mergedRead,
                                    syncStatus = SyncState.PENDING_UPDATE.name
                                )
                            )
                        )
                    } else {
                        chatDao.upsert(mapper.encrypt(localDec.copy(readByUidsCsv = mergedRead)))
                    }
                }
            }
        }
    }

    override suspend fun reconcileRemoteNotifications(remote: List<LocalNotificationEntity>) {
        if (remote.isEmpty()) return
        db.withTransaction {
            remote.forEach { r ->
                val localRaw = notificationDao.getById(r.notificationId)
                val outbox = syncOutboxDao.getForDocument("notifications", r.notificationId)
                if (localRaw == null || (localRaw.syncStatus == SyncState.SYNCED.name && outbox == null)) {
                    notificationDao.upsert(mapper.encrypt(r.copy(syncStatus = SyncState.SYNCED.name)))
                } else {
                    val localDec = mapper.decrypt(localRaw)
                    val mergedRead = (localDec.readByUids + r.readByUids).distinct().take(20).joinToString(",")
                    val mergedAck = localDec.acknowledged || r.acknowledged
                    val mergedStatus = if (localDec.recordStatus == "ARCHIVED" || r.recordStatus == "ARCHIVED") {
                        "ARCHIVED"
                    } else {
                        localDec.recordStatus
                    }
                    notificationDao.upsert(
                        mapper.encrypt(
                            localDec.copy(
                                readByUidsCsv = mergedRead,
                                acknowledged = mergedAck,
                                recordStatus = mergedStatus
                            )
                        )
                    )
                }
            }
        }
    }

    override suspend fun reconcileRemoteAuditLogs(remote: List<LocalAuditLogEntity>) {
        if (remote.isNotEmpty()) auditLogDao.upsertAll(remote)
    }

    override suspend fun saveCallRecord(entity: LocalCallRecordEntity) {
        callHistoryDao.upsert(mapper.encrypt(entity))
    }

    override suspend fun reconcileRemoteCallHistory(familyId: String, remote: List<LocalCallRecordEntity>) {
        if (remote.isNotEmpty()) {
            callHistoryDao.upsertAll(remote.filter { it.familyId == familyId }.map(mapper::encrypt))
        }
    }

    override suspend fun saveMeeting(entity: LocalMeetingEntity) {
        meetingDao.upsert(mapper.encrypt(entity))
    }

    override suspend fun getMeetingById(meetingId: String): LocalMeetingEntity? =
        meetingDao.getById(meetingId)?.let(mapper::decrypt)

    override suspend fun findMeetingByInviteCode(familyId: String, inviteCode: String): LocalMeetingEntity? =
        meetingDao.findByInviteCode(familyId, inviteCode)?.let(mapper::decrypt)

    override suspend fun reconcileRemoteMeetings(familyId: String, remote: List<LocalMeetingEntity>) {
        if (remote.isNotEmpty()) {
            meetingDao.upsertAll(remote.filter { it.familyId == familyId }.map(mapper::encrypt))
        }
    }

    override suspend fun getMemberCard(familyId: String, userId: String): LocalMemberCardEntity? =
        memberCardDao.getCard(familyId, userId)

    override suspend fun saveMemberCard(entity: LocalMemberCardEntity) {
        memberCardDao.upsert(entity)
    }

    override suspend fun reconcileRemoteMemberCards(familyId: String, remote: List<LocalMemberCardEntity>) {
        if (remote.isNotEmpty()) {
            db.withTransaction {
                remote.filter { it.familyId == familyId }.forEach { remoteCard ->
                    val existing = memberCardDao.getCard(familyId, remoteCard.userId)
                    if (existing == null || remoteCard.updatedAtMillis >= existing.updatedAtMillis || existing.syncStatus == SyncState.SYNCED.name) {
                        memberCardDao.upsert(remoteCard.copy(syncStatus = SyncState.SYNCED.name))
                    }
                }
            }
        }
    }

    override suspend fun getApprovalSettings(familyId: String): LocalApprovalSettingsEntity? =
        approvalSettingsDao.getByFamily(familyId)

    override suspend fun saveApprovalSettings(entity: LocalApprovalSettingsEntity) {
        approvalSettingsDao.upsert(entity)
    }

    override suspend fun getProposalById(proposalId: String): LocalProposalEntity? =
        proposalDao.getById(proposalId)?.let(mapper::decrypt)

    override suspend fun saveProposal(
        entity: LocalProposalEntity,
        historyEntry: LocalProposalVoteHistoryEntity?
    ) {
        db.withTransaction {
            proposalDao.upsert(mapper.encrypt(entity))
            if (historyEntry != null) {
                proposalVoteHistoryDao.upsert(historyEntry)
            }
        }
    }

    override suspend fun deleteDraftProposalLocal(proposalId: String, familyId: String) {
        db.withTransaction {
            val existing = proposalDao.getById(proposalId)
            if (existing != null && existing.familyId == familyId && existing.status == ProposalStatus.DRAFT.name) {
                proposalDao.deleteById(proposalId, familyId)
            }
        }
    }

    override suspend fun getVotesForProposal(proposalId: String): List<LocalProposalVoteEntity> =
        proposalVoteDao.getVotesForProposal(proposalId).map(mapper::decrypt)

    override suspend fun saveVoteAndUpdateProposalAtomic(
        vote: LocalProposalVoteEntity,
        historyEntry: LocalProposalVoteHistoryEntity,
        updatedProposal: LocalProposalEntity
    ) {
        db.withTransaction {
            proposalVoteDao.upsert(mapper.encrypt(vote))
            proposalVoteHistoryDao.upsert(historyEntry)
            proposalDao.upsert(mapper.encrypt(updatedProposal))
        }
    }

    override suspend fun cancelProposalAtomicLocal(
        updatedProposal: LocalProposalEntity,
        historyEntry: LocalProposalVoteHistoryEntity,
        auditEntry: LocalAuditLogEntity
    ) {
        db.withTransaction {
            proposalDao.upsert(mapper.encrypt(updatedProposal))
            proposalVoteHistoryDao.upsert(historyEntry)
            auditLogDao.upsert(auditEntry)
        }
    }

    override suspend fun executeApprovedProposalAtomicLocal(
        proposalId: String,
        actorUid: String,
        actorName: String,
        deviceId: String,
        nowMillis: Long,
        enqueueOutbox: Boolean
    ): ProposalExecutionOutcome = db.withTransaction {
        val proposal = proposalDao.getById(proposalId)?.let(mapper::decrypt)
            ?: return@withTransaction ProposalExecutionOutcome.Failure("Proposal not found.")
        if (!proposal.executedRecordId.isNullOrBlank() || proposal.status == ProposalStatus.EXECUTED.name) {
            return@withTransaction ProposalExecutionOutcome.Failure("This proposal has already been executed.")
        }
        if (proposal.status != ProposalStatus.APPROVED.name) {
            return@withTransaction ProposalExecutionOutcome.Failure(
                "Only APPROVED proposals can execute (current status: ${proposal.status})."
            )
        }

        val category = DecisionCategory.fromRaw(proposal.category)
        val desc = ApprovalDecisionEngine.buildExecutionDescriptor(proposal.proposalId, category)
        val recordId = "rec_${desc.idempotencyKey}"
        val amountMajor = MoneyCalculator.minorUnitsToDouble(proposal.amountMinor, proposal.currency)
        val syncStatus = if (enqueueOutbox) SyncState.PENDING_CREATE.name else SyncState.SYNCED.name

        val executedTargetId: String = when (category) {
            DecisionCategory.TAKE_LOAN, DecisionCategory.GIVE_LOAN -> {
                val direction = desc.loanDirection ?: LoanDirection.BORROWED
                val existingLoan = loanDao.getById(recordId)
                if (existingLoan != null) {
                    return@withTransaction ProposalExecutionOutcome.Failure("Execution record already exists.")
                }
                val loan = LocalLoanEntity(
                    recordId = recordId,
                    familyId = proposal.familyId,
                    createdByUserId = actorUid,
                    amount = amountMajor,
                    amountMinor = proposal.amountMinor,
                    remainingBalance = amountMajor,
                    remainingBalanceMinor = proposal.amountMinor,
                    interestRateApr = null,
                    currency = proposal.currency,
                    category = if (direction == LoanDirection.BORROWED) "Approved Loan In" else "Approved Loan Out",
                    description = "${proposal.title}: ${proposal.reason}".take(300),
                    recordStatus = RecordStatus.ACTIVE.name,
                    loanDirection = direction.name,
                    counterparty = proposal.relatedParty.ifBlank { "Family Counterparty" }.take(80),
                    borrowerName = if (direction == LoanDirection.LENT) proposal.relatedParty else actorName,
                    lenderName = if (direction == LoanDirection.BORROWED) proposal.relatedParty else actorName,
                    startDateMillis = nowMillis,
                    dueDateMillis = null,
                    idempotencyKey = desc.idempotencyKey,
                    syncStatus = syncStatus,
                    syncVersion = 1,
                    baseSyncVersion = if (enqueueOutbox) 0 else 1,
                    deviceId = deviceId,
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
                loanDao.upsert(mapper.encrypt(loan))
                if (enqueueOutbox) {
                    syncOutboxDao.upsert(
                        newOutboxEntry(
                            familyId = proposal.familyId,
                            actorUserId = actorUid,
                            collectionName = "loans",
                            documentId = recordId,
                            operation = "CREATE",
                            syncVersion = 1,
                            baseSyncVersion = 0,
                            idempotencyKey = desc.idempotencyKey,
                            payloadHash = DatabaseIdentityHelper.computePayloadHash(mapper.encodeLoanSnapshot(loan)),
                            deviceId = deviceId,
                            existingOutbox = syncOutboxDao.getForDocument("loans", recordId)
                        )
                    )
                }
                financialEventDao.upsert(
                    LocalFinancialEventEntity(
                        eventId = "evt_${desc.idempotencyKey}",
                        familyId = proposal.familyId,
                        parentRecordId = recordId,
                        moduleType = FinancialModuleType.LOANS.name,
                        eventType = "DISBURSEMENT",
                        actorUserId = actorUid,
                        amountMinor = proposal.amountMinor,
                        balanceAfterMinor = proposal.amountMinor,
                        currency = proposal.currency,
                        note = "Approved proposal ${proposal.title}".take(200),
                        eventDateMillis = nowMillis,
                        syncStatus = syncStatus,
                        createdAtMillis = nowMillis
                    )
                )
                recordId
            }

            DecisionCategory.MAKE_INVESTMENT -> {
                val existingInv = investmentDao.getById(recordId)
                if (existingInv != null) {
                    return@withTransaction ProposalExecutionOutcome.Failure("Execution record already exists.")
                }
                val inv = LocalInvestmentEntity(
                    recordId = recordId,
                    familyId = proposal.familyId,
                    createdByUserId = actorUid,
                    amount = amountMajor,
                    amountMinor = proposal.amountMinor,
                    currentValue = amountMajor,
                    currentValueMinor = proposal.amountMinor,
                    units = null,
                    currency = proposal.currency,
                    category = "Approved Investment",
                    description = "${proposal.title}: ${proposal.reason}".take(300),
                    assetName = proposal.relatedParty.ifBlank { proposal.title }.take(80),
                    assetType = InvestmentAssetType.OTHER.name,
                    symbol = null,
                    investedAtMillis = nowMillis,
                    recordStatus = RecordStatus.ACTIVE.name,
                    idempotencyKey = desc.idempotencyKey,
                    syncStatus = syncStatus,
                    syncVersion = 1,
                    baseSyncVersion = if (enqueueOutbox) 0 else 1,
                    deviceId = deviceId,
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
                investmentDao.upsert(mapper.encrypt(inv))
                if (enqueueOutbox) {
                    syncOutboxDao.upsert(
                        newOutboxEntry(
                            familyId = proposal.familyId,
                            actorUserId = actorUid,
                            collectionName = "investments",
                            documentId = recordId,
                            operation = "CREATE",
                            syncVersion = 1,
                            baseSyncVersion = 0,
                            idempotencyKey = desc.idempotencyKey,
                            payloadHash = DatabaseIdentityHelper.computePayloadHash(mapper.encodeInvestmentSnapshot(inv)),
                            deviceId = deviceId,
                            existingOutbox = syncOutboxDao.getForDocument("investments", recordId)
                        )
                    )
                }
                financialEventDao.upsert(
                    LocalFinancialEventEntity(
                        eventId = "evt_${desc.idempotencyKey}",
                        familyId = proposal.familyId,
                        parentRecordId = recordId,
                        moduleType = FinancialModuleType.INVESTMENTS.name,
                        eventType = "INITIAL_INVESTMENT",
                        actorUserId = actorUid,
                        amountMinor = proposal.amountMinor,
                        secondaryAmountMinor = proposal.amountMinor,
                        balanceAfterMinor = proposal.amountMinor,
                        currency = proposal.currency,
                        note = "Approved proposal ${proposal.title}".take(200),
                        eventDateMillis = nowMillis,
                        syncStatus = syncStatus,
                        createdAtMillis = nowMillis
                    )
                )
                recordId
            }

            DecisionCategory.WITHDRAW_SAVINGS -> {
                val savingsId = proposal.targetSavingsId?.takeIf { it.isNotBlank() }
                    ?: return@withTransaction ProposalExecutionOutcome.Failure("Target savings goal ID is missing.")
                val existingSav = savingsDao.getById(savingsId)?.let(mapper::decrypt)
                    ?: return@withTransaction ProposalExecutionOutcome.Failure("Target savings goal not found.")
                if (existingSav.familyId != proposal.familyId) {
                    return@withTransaction ProposalExecutionOutcome.Failure("Cross-family savings withdrawal blocked.")
                }
                val currentMinor = existingSav.effectiveCurrentMinor()
                if (currentMinor < proposal.amountMinor) {
                    return@withTransaction ProposalExecutionOutcome.Failure(
                        "Insufficient savings balance (${MoneyCalculator.formatMinorUnits(currentMinor, existingSav.currency)}) for withdrawal."
                    )
                }
                val nextMinor = currentMinor - proposal.amountMinor
                val nextVersion = existingSav.syncVersion + 1
                val updatedSav = existingSav.copy(
                    amount = MoneyCalculator.minorUnitsToDouble(nextMinor, existingSav.currency),
                    amountMinor = nextMinor,
                    syncStatus = if (enqueueOutbox) {
                        if (existingSav.baseSyncVersion == 0) SyncState.PENDING_CREATE.name else SyncState.PENDING_UPDATE.name
                    } else {
                        SyncState.SYNCED.name
                    },
                    syncVersion = nextVersion,
                    baseSyncVersion = resolveBaseVersion(existingSav.baseSyncVersion, existingSav.syncVersion),
                    updatedAtMillis = nowMillis
                )
                savingsDao.upsert(mapper.encrypt(updatedSav))
                if (enqueueOutbox) {
                    syncOutboxDao.upsert(
                        newOutboxEntry(
                            familyId = proposal.familyId,
                            actorUserId = actorUid,
                            collectionName = "savings",
                            documentId = savingsId,
                            operation = if (existingSav.baseSyncVersion == 0) "CREATE" else "UPDATE",
                            syncVersion = nextVersion,
                            baseSyncVersion = updatedSav.baseSyncVersion,
                            idempotencyKey = updatedSav.idempotencyKey,
                            payloadHash = DatabaseIdentityHelper.computePayloadHash(mapper.encodeSavingsSnapshot(updatedSav)),
                            deviceId = deviceId,
                            existingOutbox = syncOutboxDao.getForDocument("savings", savingsId)
                        )
                    )
                }
                financialEventDao.upsert(
                    LocalFinancialEventEntity(
                        eventId = "evt_${desc.idempotencyKey}",
                        familyId = proposal.familyId,
                        parentRecordId = savingsId,
                        moduleType = FinancialModuleType.SAVINGS.name,
                        eventType = "WITHDRAWAL",
                        actorUserId = actorUid,
                        amountMinor = proposal.amountMinor,
                        balanceAfterMinor = nextMinor,
                        currency = proposal.currency,
                        note = "Approved withdrawal: ${proposal.title}".take(200),
                        eventDateMillis = nowMillis,
                        syncStatus = syncStatus,
                        createdAtMillis = nowMillis
                    )
                )
                savingsId
            }

            DecisionCategory.LARGE_EXPENSE,
            DecisionCategory.LARGE_PURCHASE,
            DecisionCategory.OTHER_CONFIGURABLE -> {
                val existingTx = transactionDao.getById(recordId)
                if (existingTx != null) {
                    return@withTransaction ProposalExecutionOutcome.Failure("Execution record already exists.")
                }
                val tx = LocalTransactionEntity(
                    recordId = recordId,
                    familyId = proposal.familyId,
                    createdByUserId = actorUid,
                    memberId = proposal.proposerUid,
                    amount = amountMajor,
                    amountMinor = proposal.amountMinor,
                    currency = proposal.currency,
                    category = when (category) {
                        DecisionCategory.LARGE_PURCHASE -> "Major Purchase"
                        DecisionCategory.LARGE_EXPENSE -> "Approved Large Expense"
                        else -> "Approved Decision"
                    },
                    description = "${proposal.title}: ${proposal.reason}".take(300),
                    recordStatus = RecordStatus.ACTIVE.name,
                    transactionType = TransactionType.EXPENSE.name,
                    paymentMethod = null,
                    occurredAtMillis = nowMillis,
                    idempotencyKey = desc.idempotencyKey,
                    syncStatus = syncStatus,
                    syncVersion = 1,
                    baseSyncVersion = if (enqueueOutbox) 0 else 1,
                    deviceId = deviceId,
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
                transactionDao.upsert(mapper.encrypt(tx))
                if (enqueueOutbox) {
                    syncOutboxDao.upsert(
                        newOutboxEntry(
                            familyId = proposal.familyId,
                            actorUserId = actorUid,
                            collectionName = "transactions",
                            documentId = recordId,
                            operation = "CREATE",
                            syncVersion = 1,
                            baseSyncVersion = 0,
                            idempotencyKey = desc.idempotencyKey,
                            payloadHash = DatabaseIdentityHelper.computePayloadHash(mapper.encodeTransactionSnapshot(tx)),
                            deviceId = deviceId,
                            existingOutbox = syncOutboxDao.getForDocument("transactions", recordId)
                        )
                    )
                }
                recordId
            }
        }

        val updatedProposal = proposal.copy(
            status = ProposalStatus.EXECUTED.name,
            finalDecision = ProposalStatus.EXECUTED.name,
            executedRecordId = executedTargetId,
            executedCollection = desc.targetCollection,
            executedByUid = actorUid,
            executedAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        proposalDao.upsert(mapper.encrypt(updatedProposal))

        proposalVoteHistoryDao.upsert(
            LocalProposalVoteHistoryEntity(
                historyId = "vh_exec_${proposal.proposalId}",
                proposalId = proposal.proposalId,
                familyId = proposal.familyId,
                actorUid = actorUid,
                actorName = actorName,
                actionType = com.example.core.model.VoteAuditActionType.PROPOSAL_EXECUTED.name,
                note = "Executed ${desc.targetCollection}/$executedTargetId (${MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency)})",
                createdAtMillis = nowMillis
            )
        )
        auditLogDao.upsert(
            LocalAuditLogEntity(
                auditId = DatabaseIdentityHelper.generateAuditId(),
                familyId = proposal.familyId,
                actorUserId = actorUid,
                entityCollection = desc.targetCollection,
                entityId = executedTargetId,
                action = AuditAction.CREATE.name,
                summary = "Executed approved proposal ${proposal.title} (${MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency)})",
                deviceId = deviceId,
                syncStatus = SyncState.SYNCED.name,
                createdAtMillis = nowMillis
            )
        )

        ProposalExecutionOutcome.Executed(
            updatedProposal = updatedProposal,
            executedRecordId = executedTargetId,
            executedCollection = desc.targetCollection
        )
    }

    override suspend fun reconcileRemoteApprovalSettings(familyId: String, remote: LocalApprovalSettingsEntity?) {
        if (remote != null && remote.familyId == familyId) {
            approvalSettingsDao.upsert(remote.copy(syncStatus = SyncState.SYNCED.name))
        }
    }

    override suspend fun reconcileRemoteProposals(familyId: String, remote: List<LocalProposalEntity>) {
        if (remote.isNotEmpty()) {
            db.withTransaction {
                remote.filter { it.familyId == familyId }.forEach { item ->
                    val existing = proposalDao.getById(item.proposalId)
                    if (existing == null || item.updatedAtMillis >= existing.updatedAtMillis) {
                        proposalDao.upsert(mapper.encrypt(item.copy(syncStatus = SyncState.SYNCED.name)))
                    }
                }
            }
        }
    }

    override suspend fun reconcileRemoteProposalVotes(proposalId: String, remote: List<LocalProposalVoteEntity>) {
        if (remote.isNotEmpty()) {
            proposalVoteDao.upsertAll(remote.filter { it.proposalId == proposalId }.map(mapper::encrypt))
        }
    }

    override suspend fun reconcileRemoteProposalHistory(
        proposalId: String,
        remote: List<LocalProposalVoteHistoryEntity>
    ) {
        if (remote.isNotEmpty()) {
            proposalVoteHistoryDao.upsertAll(remote.filter { it.proposalId == proposalId })
        }
    }

    override suspend fun purgeFamilyCache(familyId: String) = db.withTransaction {
        transactionDao.deleteByFamilyId(familyId)
        loanDao.deleteByFamilyId(familyId)
        savingsDao.deleteByFamilyId(familyId)
        investmentDao.deleteByFamilyId(familyId)
        budgetDao.deleteByFamilyId(familyId)
        financialEventDao.deleteByFamilyId(familyId)
        chatDao.deleteByFamilyId(familyId)
        callHistoryDao.deleteByFamilyId(familyId)
        meetingDao.deleteByFamilyId(familyId)
        notificationDao.deleteByFamilyId(familyId)
        auditLogDao.deleteByFamilyId(familyId)
        syncOutboxDao.deleteByFamilyId(familyId)
        syncConflictDao.deleteByFamilyId(familyId)
        cachedReportDao.deleteByFamilyId(familyId)
        memberCardDao.deleteByFamilyId(familyId)
        approvalSettingsDao.deleteByFamilyId(familyId)
        proposalVoteHistoryDao.deleteByFamilyId(familyId)
        proposalVoteDao.deleteByFamilyId(familyId)
        proposalDao.deleteByFamilyId(familyId)
        memberDao.clearMembersForFamily(familyId)
        familyDao.deleteFamilyById(familyId)
    }

    override suspend fun purgeUnauthorizedFamilies(allowedFamilyIds: Set<String>) {
        val allCached = familyDao.getAllFamilies().map { it.familyId }
        for (fid in allCached) {
            if (fid !in allowedFamilyIds) {
                purgeFamilyCache(fid)
            }
        }
    }

    override suspend fun purgeAllLocalData() = db.withTransaction {
        transactionDao.deleteAll()
        loanDao.deleteAll()
        savingsDao.deleteAll()
        investmentDao.deleteAll()
        budgetDao.deleteAll()
        financialEventDao.deleteAll()
        chatDao.deleteAll()
        callHistoryDao.deleteAll()
        meetingDao.deleteAll()
        notificationDao.deleteAll()
        auditLogDao.deleteAll()
        syncOutboxDao.deleteAll()
        syncConflictDao.deleteAll()
        cachedReportDao.deleteAll()
        memberCardDao.deleteAll()
        approvalSettingsDao.deleteAll()
        proposalVoteHistoryDao.deleteAll()
        proposalVoteDao.deleteAll()
        proposalDao.deleteAll()
        memberDao.deleteAll()
        familyDao.deleteAll()
        db.myJoinRequestDao().deleteAll()
        db.userProfileDao().deleteAll()
    }

    private fun resolveBaseVersion(baseSyncVersion: Int, syncVersion: Int): Int =
        if (baseSyncVersion > 0) baseSyncVersion else syncVersion

    private fun newOutboxEntry(
        familyId: String,
        actorUserId: String,
        collectionName: String,
        documentId: String,
        operation: String,
        syncVersion: Int,
        baseSyncVersion: Int,
        idempotencyKey: String,
        payloadHash: String,
        deviceId: String,
        existingOutbox: LocalSyncOutboxEntity?
    ): LocalSyncOutboxEntity {
        val now = System.currentTimeMillis()
        return LocalSyncOutboxEntity(
            outboxId = existingOutbox?.outboxId ?: DatabaseIdentityHelper.generateOutboxId(),
            familyId = familyId,
            actorUserId = actorUserId,
            collectionName = collectionName,
            documentId = documentId,
            operation = operation,
            status = "PENDING",
            retryCount = 0,
            lastError = null,
            syncVersion = syncVersion,
            baseSyncVersion = baseSyncVersion,
            operationId = DatabaseIdentityHelper.generateOperationId(),
            idempotencyKey = idempotencyKey,
            payloadHash = payloadHash,
            deviceId = deviceId,
            createdAtMillis = existingOutbox?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
    }
}
