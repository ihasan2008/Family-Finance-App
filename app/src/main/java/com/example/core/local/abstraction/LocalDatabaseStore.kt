package com.example.core.local.abstraction

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
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictType
import kotlinx.coroutines.flow.Flow

/**
 * Storage-agnostic local database abstraction layer.
 *
 * Parity design:
 * - On Android native, implemented by [SqliteRoomDatabaseStore] (Room / SQLite v8 + AES-256-GCM).
 * - On Web PWA, the exact same store contract & compound index keys map 1:1 to IndexedDB
 *   object stores (e.g., `transactions`, `sync_outbox`, `sync_conflicts`, `cached_reports`).
 *
 * All queries are strictly scoped by `familyId` so families never mix local data.
 */
interface LocalDatabaseStore {
    val engineLabel: String
    val encryptionEngineLabel: String

    fun observeTransactions(familyId: String): Flow<List<LocalTransactionEntity>>
    fun observeLoans(familyId: String): Flow<List<LocalLoanEntity>>
    fun observeSavings(familyId: String): Flow<List<LocalSavingsEntity>>
    fun observeInvestments(familyId: String): Flow<List<LocalInvestmentEntity>>
    fun observeBudgets(familyId: String): Flow<List<LocalBudgetEntity>>
    fun observeFinancialEvents(familyId: String): Flow<List<LocalFinancialEventEntity>>
    fun observeChats(familyId: String): Flow<List<LocalChatMessageEntity>>
    fun observeNotifications(familyId: String, userId: String): Flow<List<LocalNotificationEntity>>
    fun observeAuditLogs(familyId: String, limit: Int = 50): Flow<List<LocalAuditLogEntity>>
    fun observeOutbox(familyId: String): Flow<List<LocalSyncOutboxEntity>>
    fun observeConflicts(familyId: String): Flow<List<LocalSyncConflictEntity>>
    fun observeCachedReports(familyId: String): Flow<List<LocalCachedReportEntity>>
    fun observeCallHistory(familyId: String, userId: String): Flow<List<LocalCallRecordEntity>>
    fun observeMeetings(familyId: String): Flow<List<LocalMeetingEntity>>
    fun observeMemberCards(familyId: String): Flow<List<LocalMemberCardEntity>>
    fun observeApprovalSettings(familyId: String): Flow<LocalApprovalSettingsEntity?>
    fun observeProposals(familyId: String): Flow<List<LocalProposalEntity>>
    fun observeProposalVotes(proposalId: String): Flow<List<LocalProposalVoteEntity>>
    fun observeVotesForFamily(familyId: String): Flow<List<LocalProposalVoteEntity>>
    fun observeProposalHistory(proposalId: String): Flow<List<LocalProposalVoteHistoryEntity>>
    fun observeAllDecisionHistoryForFamily(familyId: String): Flow<List<LocalProposalVoteHistoryEntity>>
    fun observeDatabaseHealth(familyId: String, userId: String): Flow<DatabaseHealthSnapshot>

    suspend fun getCachedFamily(familyId: String): LocalFamilyEntity?
    suspend fun getCachedMember(familyId: String, userId: String): LocalFamilyMemberEntity?

    suspend fun saveTransactionIdempotent(
        entity: LocalTransactionEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalTransactionEntity>

    suspend fun saveLoanIdempotent(
        entity: LocalLoanEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalLoanEntity>

    suspend fun saveSavingsIdempotent(
        entity: LocalSavingsEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalSavingsEntity>

    suspend fun saveInvestmentIdempotent(
        entity: LocalInvestmentEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalInvestmentEntity>

    suspend fun saveBudgetIdempotent(
        entity: LocalBudgetEntity,
        auditEntry: LocalAuditLogEntity
    ): IdempotentWriteResult<LocalBudgetEntity>

    suspend fun saveFinancialEventWithOutbox(entity: LocalFinancialEventEntity)

    suspend fun saveChatMessage(
        entity: LocalChatMessageEntity,
        auditEntry: LocalAuditLogEntity
    )

    suspend fun getCachedChatsPage(
        familyId: String,
        beforeMillis: Long?,
        limit: Int
    ): List<LocalChatMessageEntity>

    suspend fun editChatMessageLocal(
        messageId: String,
        familyId: String,
        actorUserId: String,
        newText: String,
        auditEntry: LocalAuditLogEntity
    ): LocalChatMessageEntity?

    suspend fun softDeleteChatMessageLocal(
        messageId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): LocalChatMessageEntity?

    suspend fun markChatMessagesReadLocal(
        familyId: String,
        readerUserId: String,
        messageIds: List<String>
    ): Int

    suspend fun purgeExpiredChatsLocal(
        familyId: String,
        nowMillis: Long,
        auditEntry: LocalAuditLogEntity? = null
    ): List<String>

    suspend fun saveNotification(entity: LocalNotificationEntity)

    suspend fun saveNotificationWithOutbox(entity: LocalNotificationEntity)

    suspend fun markNotificationReadLocal(
        notificationId: String,
        familyId: String,
        readerUserId: String
    ): LocalNotificationEntity?

    suspend fun markAllNotificationsReadLocal(
        familyId: String,
        readerUserId: String
    ): Int

    suspend fun archiveNotificationLocal(
        notificationId: String,
        familyId: String,
        actorUserId: String
    ): Boolean

    suspend fun saveCachedReport(report: LocalCachedReportEntity)

    suspend fun getCachedReportForPeriod(familyId: String, periodKey: String): LocalCachedReportEntity?

    suspend fun appendAuditLog(entry: LocalAuditLogEntity)

    suspend fun deleteTransactionLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean

    suspend fun deleteLoanLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean

    suspend fun deleteSavingsLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean

    suspend fun deleteInvestmentLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean

    suspend fun deleteBudgetLocal(
        recordId: String,
        familyId: String,
        actorUserId: String,
        auditEntry: LocalAuditLogEntity
    ): Boolean

    suspend fun getActiveRecurringTemplates(familyId: String): List<LocalTransactionEntity>

    suspend fun getPendingOutboxForFamily(familyId: String): List<LocalSyncOutboxEntity>
    suspend fun getPendingOutboxForUser(userId: String): List<LocalSyncOutboxEntity>
    suspend fun getOutboxForDocument(collectionName: String, documentId: String): LocalSyncOutboxEntity?
    suspend fun getTransactionById(recordId: String): LocalTransactionEntity?
    suspend fun getLoanById(recordId: String): LocalLoanEntity?
    suspend fun getSavingsById(recordId: String): LocalSavingsEntity?
    suspend fun getInvestmentById(recordId: String): LocalInvestmentEntity?
    suspend fun getBudgetById(recordId: String): LocalBudgetEntity?
    suspend fun getFinancialEventById(eventId: String): LocalFinancialEventEntity?
    suspend fun getNotificationById(notificationId: String): LocalNotificationEntity?
    suspend fun getChatMessageById(messageId: String): LocalChatMessageEntity?

    suspend fun markOutboxSynced(
        collectionName: String,
        documentId: String,
        syncedAtMillis: Long,
        confirmedServerVersion: Int? = null
    )

    suspend fun markOutboxFailed(outboxItem: LocalSyncOutboxEntity, errorMessage: String)

    suspend fun quarantineInvalidOutbox(
        outboxItem: LocalSyncOutboxEntity,
        conflictType: ConflictType,
        reason: String,
        auditEntry: LocalAuditLogEntity
    )

    suspend fun recordSyncConflict(
        conflict: LocalSyncConflictEntity,
        auditEntry: LocalAuditLogEntity,
        removeFromOutbox: Boolean = true
    )

    suspend fun resolveConflict(
        conflictId: String,
        chosenPolicy: ConflictResolutionPolicy,
        actorUserId: String,
        remoteVersionOverride: Int? = null
    ): Boolean

    suspend fun reconcileRemoteTransactions(familyId: String, remote: List<LocalTransactionEntity>)
    suspend fun reconcileRemoteLoans(familyId: String, remote: List<LocalLoanEntity>)
    suspend fun reconcileRemoteSavings(familyId: String, remote: List<LocalSavingsEntity>)
    suspend fun reconcileRemoteInvestments(familyId: String, remote: List<LocalInvestmentEntity>)
    suspend fun reconcileRemoteBudgets(familyId: String, remote: List<LocalBudgetEntity>)
    suspend fun reconcileRemoteFinancialEvents(remote: List<LocalFinancialEventEntity>)
    suspend fun reconcileRemoteChats(remote: List<LocalChatMessageEntity>)
    suspend fun reconcileRemoteNotifications(remote: List<LocalNotificationEntity>)
    suspend fun reconcileRemoteAuditLogs(remote: List<LocalAuditLogEntity>)

    suspend fun saveCallRecord(entity: LocalCallRecordEntity)
    suspend fun reconcileRemoteCallHistory(familyId: String, remote: List<LocalCallRecordEntity>)

    suspend fun saveMeeting(entity: LocalMeetingEntity)
    suspend fun getMeetingById(meetingId: String): LocalMeetingEntity?
    suspend fun findMeetingByInviteCode(familyId: String, inviteCode: String): LocalMeetingEntity?
    suspend fun reconcileRemoteMeetings(familyId: String, remote: List<LocalMeetingEntity>)

    suspend fun getMemberCard(familyId: String, userId: String): LocalMemberCardEntity?
    suspend fun saveMemberCard(entity: LocalMemberCardEntity)
    suspend fun reconcileRemoteMemberCards(familyId: String, remote: List<LocalMemberCardEntity>)

    suspend fun getApprovalSettings(familyId: String): LocalApprovalSettingsEntity?
    suspend fun saveApprovalSettings(entity: LocalApprovalSettingsEntity)
    suspend fun getProposalById(proposalId: String): LocalProposalEntity?
    suspend fun saveProposal(entity: LocalProposalEntity, historyEntry: LocalProposalVoteHistoryEntity? = null)
    suspend fun deleteDraftProposalLocal(proposalId: String, familyId: String)
    suspend fun getVotesForProposal(proposalId: String): List<LocalProposalVoteEntity>
    suspend fun saveVoteAndUpdateProposalAtomic(
        vote: LocalProposalVoteEntity,
        historyEntry: LocalProposalVoteHistoryEntity,
        updatedProposal: LocalProposalEntity
    )
    suspend fun cancelProposalAtomicLocal(
        updatedProposal: LocalProposalEntity,
        historyEntry: LocalProposalVoteHistoryEntity,
        auditEntry: LocalAuditLogEntity
    )
    suspend fun executeApprovedProposalAtomicLocal(
        proposalId: String,
        actorUid: String,
        actorName: String,
        deviceId: String,
        nowMillis: Long,
        enqueueOutbox: Boolean = true
    ): ProposalExecutionOutcome

    suspend fun reconcileRemoteApprovalSettings(familyId: String, remote: LocalApprovalSettingsEntity?)
    suspend fun reconcileRemoteProposals(familyId: String, remote: List<LocalProposalEntity>)
    suspend fun reconcileRemoteProposalVotes(proposalId: String, remote: List<LocalProposalVoteEntity>)
    suspend fun reconcileRemoteProposalHistory(proposalId: String, remote: List<LocalProposalVoteHistoryEntity>)

    /** Removes all locally cached records for a specific family (e.g., on member removal). */
    suspend fun purgeFamilyCache(familyId: String)

    /** Purges all cached family workspaces other than [allowedFamilyIds] and deletes unlinked records. */
    suspend fun purgeUnauthorizedFamilies(allowedFamilyIds: Set<String>)

    /** Full wipe on sign-out or session revocation to protect financial privacy on shared/lost devices. */
    suspend fun purgeAllLocalData()
}

sealed interface ProposalExecutionOutcome {
    data class Executed(
        val updatedProposal: LocalProposalEntity,
        val executedRecordId: String,
        val executedCollection: String
    ) : ProposalExecutionOutcome

    data class Failure(val reason: String) : ProposalExecutionOutcome
}

data class IdempotentWriteResult<T>(
    val record: T,
    val deduplicatedExisting: Boolean
)
