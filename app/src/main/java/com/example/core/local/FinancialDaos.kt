package com.example.core.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE familyId = :familyId ORDER BY occurredAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalTransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM transactions WHERE familyId = :familyId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(familyId: String, idempotencyKey: String): LocalTransactionEntity?

    @Query("SELECT * FROM transactions WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: String): LocalTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalTransactionEntity>)

    @Query("SELECT * FROM transactions WHERE familyId = :familyId AND isRecurringTemplate = 1 AND recordStatus = 'ACTIVE'")
    suspend fun getActiveRecurringTemplates(familyId: String): List<LocalTransactionEntity>

    @Query("DELETE FROM transactions WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM transactions WHERE familyId = :familyId AND syncStatus = 'SYNCED' AND recordId NOT IN (:activeIds)")
    suspend fun pruneSyncedNotIn(familyId: String, activeIds: List<String>)

    @Query("DELETE FROM transactions WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans WHERE familyId = :familyId ORDER BY updatedAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalLoanEntity>>

    @Query("SELECT COUNT(*) FROM loans WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM loans WHERE familyId = :familyId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(familyId: String, idempotencyKey: String): LocalLoanEntity?

    @Query("SELECT * FROM loans WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: String): LocalLoanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalLoanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalLoanEntity>)

    @Query("DELETE FROM loans WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM loans WHERE familyId = :familyId AND syncStatus = 'SYNCED' AND recordId NOT IN (:activeIds)")
    suspend fun pruneSyncedNotIn(familyId: String, activeIds: List<String>)

    @Query("DELETE FROM loans WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM loans")
    suspend fun deleteAll()
}

@Dao
interface SavingsDao {
    @Query("SELECT * FROM savings WHERE familyId = :familyId ORDER BY updatedAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalSavingsEntity>>

    @Query("SELECT COUNT(*) FROM savings WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM savings WHERE familyId = :familyId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(familyId: String, idempotencyKey: String): LocalSavingsEntity?

    @Query("SELECT * FROM savings WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: String): LocalSavingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalSavingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalSavingsEntity>)

    @Query("DELETE FROM savings WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM savings WHERE familyId = :familyId AND syncStatus = 'SYNCED' AND recordId NOT IN (:activeIds)")
    suspend fun pruneSyncedNotIn(familyId: String, activeIds: List<String>)

    @Query("DELETE FROM savings WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM savings")
    suspend fun deleteAll()
}

@Dao
interface InvestmentDao {
    @Query("SELECT * FROM investments WHERE familyId = :familyId ORDER BY updatedAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalInvestmentEntity>>

    @Query("SELECT COUNT(*) FROM investments WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM investments WHERE familyId = :familyId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(familyId: String, idempotencyKey: String): LocalInvestmentEntity?

    @Query("SELECT * FROM investments WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: String): LocalInvestmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalInvestmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalInvestmentEntity>)

    @Query("DELETE FROM investments WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM investments WHERE familyId = :familyId AND syncStatus = 'SYNCED' AND recordId NOT IN (:activeIds)")
    suspend fun pruneSyncedNotIn(familyId: String, activeIds: List<String>)

    @Query("DELETE FROM investments WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM investments")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE familyId = :familyId ORDER BY periodKey DESC, category ASC")
    fun observeByFamily(familyId: String): Flow<List<LocalBudgetEntity>>

    @Query("SELECT COUNT(*) FROM budgets WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM budgets WHERE familyId = :familyId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(familyId: String, idempotencyKey: String): LocalBudgetEntity?

    @Query("SELECT * FROM budgets WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: String): LocalBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalBudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalBudgetEntity>)

    @Query("DELETE FROM budgets WHERE recordId = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM budgets WHERE familyId = :familyId AND syncStatus = 'SYNCED' AND recordId NOT IN (:activeIds)")
    suspend fun pruneSyncedNotIn(familyId: String, activeIds: List<String>)

    @Query("DELETE FROM budgets WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

@Dao
interface FinancialEventDao {
    @Query("SELECT * FROM financial_events WHERE familyId = :familyId ORDER BY eventDateMillis DESC, createdAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalFinancialEventEntity>>

    @Query("SELECT * FROM financial_events WHERE familyId = :familyId AND parentRecordId = :parentRecordId ORDER BY eventDateMillis DESC, createdAtMillis DESC")
    fun observeForRecord(familyId: String, parentRecordId: String): Flow<List<LocalFinancialEventEntity>>

    @Query("SELECT COUNT(*) FROM financial_events WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM financial_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getById(eventId: String): LocalFinancialEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFinancialEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalFinancialEventEntity>)

    @Query("DELETE FROM financial_events WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM financial_events")
    suspend fun deleteAll()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE familyId = :familyId AND recordStatus != 'ARCHIVED' ORDER BY createdAtMillis ASC")
    fun observeByFamily(familyId: String): Flow<List<LocalChatMessageEntity>>

    @Query("SELECT * FROM chats WHERE familyId = :familyId AND recordStatus != 'ARCHIVED' ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getLatestPaged(familyId: String, limit: Int): List<LocalChatMessageEntity>

    @Query("SELECT * FROM chats WHERE familyId = :familyId AND recordStatus != 'ARCHIVED' AND createdAtMillis < :beforeMillis ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getOlderThan(familyId: String, beforeMillis: Long, limit: Int): List<LocalChatMessageEntity>

    @Query("SELECT * FROM chats WHERE familyId = :familyId AND recordStatus = 'ACTIVE'")
    suspend fun getActiveMessages(familyId: String): List<LocalChatMessageEntity>

    @Query("SELECT * FROM chats WHERE familyId = :familyId AND expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun getExpiredChats(familyId: String, nowMillis: Long): List<LocalChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chats WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM chats WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): LocalChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalChatMessageEntity>)

    @Query("DELETE FROM chats WHERE familyId = :familyId AND expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun deleteExpiredChats(familyId: String, nowMillis: Long): Int

    @Query("DELETE FROM chats WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM chats WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE familyId = :familyId AND recordStatus = 'ACTIVE' AND (recipientUserId = :userId OR recipientUserId = 'ALL_MEMBERS') ORDER BY createdAtMillis DESC")
    fun observeForMember(familyId: String, userId: String): Flow<List<LocalNotificationEntity>>

    @Query("SELECT * FROM notifications WHERE familyId = :familyId AND recordStatus = 'ACTIVE' AND (recipientUserId = :userId OR recipientUserId = 'ALL_MEMBERS') ORDER BY createdAtMillis DESC")
    suspend fun getActiveForMember(familyId: String, userId: String): List<LocalNotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE familyId = :familyId AND recordStatus = 'ACTIVE' AND (recipientUserId = :userId OR recipientUserId = 'ALL_MEMBERS')")
    fun observeCountForMember(familyId: String, userId: String): Flow<Int>

    @Query("SELECT * FROM notifications WHERE notificationId = :notificationId LIMIT 1")
    suspend fun getById(notificationId: String): LocalNotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalNotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalNotificationEntity>)

    @Query("DELETE FROM notifications WHERE notificationId = :notificationId")
    suspend fun deleteById(notificationId: String)

    @Query("DELETE FROM notifications WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs WHERE familyId = :familyId ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecentByFamily(familyId: String, limit: Int = 50): Flow<List<LocalAuditLogEntity>>

    @Query("SELECT COUNT(*) FROM audit_logs WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM audit_logs WHERE auditId = :auditId LIMIT 1")
    suspend fun getById(auditId: String): LocalAuditLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalAuditLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalAuditLogEntity>)

    @Query("DELETE FROM audit_logs WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM audit_logs")
    suspend fun deleteAll()
}

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox WHERE familyId = :familyId ORDER BY createdAtMillis ASC")
    fun observeForFamily(familyId: String): Flow<List<LocalSyncOutboxEntity>>

    @Query("SELECT * FROM sync_outbox WHERE familyId = :familyId AND status IN ('PENDING', 'ERROR') ORDER BY createdAtMillis ASC")
    suspend fun getPendingForFamily(familyId: String): List<LocalSyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE actorUserId = :userId AND status IN ('PENDING', 'ERROR') ORDER BY createdAtMillis ASC")
    suspend fun getPendingForUser(userId: String): List<LocalSyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE collectionName = :collectionName AND documentId = :documentId LIMIT 1")
    suspend fun getForDocument(collectionName: String, documentId: String): LocalSyncOutboxEntity?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE familyId = :familyId AND status = 'PENDING'")
    fun observePendingCount(familyId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE familyId = :familyId AND status = 'ERROR'")
    fun observeErrorCount(familyId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalSyncOutboxEntity)

    @Query("SELECT documentId FROM sync_outbox WHERE familyId = :familyId AND collectionName = :collectionName AND operation = 'DELETE'")
    suspend fun getPendingDeleteIds(familyId: String, collectionName: String): List<String>

    @Query("DELETE FROM sync_outbox WHERE collectionName = :collectionName AND documentId = :documentId")
    suspend fun deleteForDocument(collectionName: String, documentId: String)

    @Query("DELETE FROM sync_outbox WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun deleteAll()
}

@Dao
interface SyncConflictDao {
    @Query("SELECT * FROM sync_conflicts WHERE familyId = :familyId ORDER BY createdAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalSyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE familyId = :familyId AND status = 'UNRESOLVED'")
    fun observeUnresolvedCount(familyId: String): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE conflictId = :conflictId LIMIT 1")
    suspend fun getById(conflictId: String): LocalSyncConflictEntity?

    @Query("SELECT * FROM sync_conflicts WHERE familyId = :familyId AND collectionName = :collectionName AND documentId = :documentId AND status = 'UNRESOLVED' LIMIT 1")
    suspend fun findUnresolvedForDocument(
        familyId: String,
        collectionName: String,
        documentId: String
    ): LocalSyncConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conflict: LocalSyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM sync_conflicts")
    suspend fun deleteAll()
}

@Dao
interface CachedReportDao {
    @Query("SELECT * FROM cached_reports WHERE familyId = :familyId ORDER BY periodKey DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalCachedReportEntity>>

    @Query("SELECT COUNT(*) FROM cached_reports WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM cached_reports WHERE familyId = :familyId AND periodKey = :periodKey LIMIT 1")
    suspend fun getForPeriod(familyId: String, periodKey: String): LocalCachedReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: LocalCachedReportEntity)

    @Query("DELETE FROM cached_reports WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM cached_reports")
    suspend fun deleteAll()
}

@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM call_history WHERE familyId = :familyId AND (callerUid = :userId OR calleeUid = :userId) ORDER BY createdAtMillis DESC")
    fun observeForUserInFamily(familyId: String, userId: String): Flow<List<LocalCallRecordEntity>>

    @Query("SELECT COUNT(*) FROM call_history WHERE familyId = :familyId AND (callerUid = :userId OR calleeUid = :userId)")
    fun observeCountForUser(familyId: String, userId: String): Flow<Int>

    @Query("SELECT * FROM call_history WHERE callId = :callId LIMIT 1")
    suspend fun getById(callId: String): LocalCallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: LocalCallRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<LocalCallRecordEntity>)

    @Query("DELETE FROM call_history WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM call_history")
    suspend fun deleteAll()
}

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings WHERE familyId = :familyId ORDER BY createdAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalMeetingEntity>>

    @Query("SELECT COUNT(*) FROM meetings WHERE familyId = :familyId")
    fun observeCountByFamily(familyId: String): Flow<Int>

    @Query("SELECT * FROM meetings WHERE meetingId = :meetingId LIMIT 1")
    suspend fun getById(meetingId: String): LocalMeetingEntity?

    @Query("SELECT * FROM meetings WHERE familyId = :familyId AND UPPER(inviteCode) = UPPER(:inviteCode) LIMIT 1")
    suspend fun findByInviteCode(familyId: String, inviteCode: String): LocalMeetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meeting: LocalMeetingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(meetings: List<LocalMeetingEntity>)

    @Query("DELETE FROM meetings WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM meetings")
    suspend fun deleteAll()
}

