package com.example.core.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalUserProfileEntity::class,
        LocalFamilyEntity::class,
        LocalFamilyMemberEntity::class,
        LocalMyJoinRequestEntity::class,
        LocalTransactionEntity::class,
        LocalLoanEntity::class,
        LocalSavingsEntity::class,
        LocalInvestmentEntity::class,
        LocalBudgetEntity::class,
        LocalFinancialEventEntity::class,
        LocalChatMessageEntity::class,
        LocalNotificationEntity::class,
        LocalAuditLogEntity::class,
        LocalSyncOutboxEntity::class,
        LocalSyncConflictEntity::class,
        LocalCachedReportEntity::class,
        LocalCallRecordEntity::class,
        LocalMeetingEntity::class,
        LocalMemberCardEntity::class,
        LocalApprovalSettingsEntity::class,
        LocalProposalEntity::class,
        LocalProposalVoteEntity::class,
        LocalProposalVoteHistoryEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class FamilyFinanceDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun familyDao(): FamilyDao
    abstract fun familyMemberDao(): FamilyMemberDao
    abstract fun memberCardDao(): MemberCardDao
    abstract fun myJoinRequestDao(): MyJoinRequestDao
    abstract fun transactionDao(): TransactionDao
    abstract fun loanDao(): LoanDao
    abstract fun savingsDao(): SavingsDao
    abstract fun investmentDao(): InvestmentDao
    abstract fun budgetDao(): BudgetDao
    abstract fun financialEventDao(): FinancialEventDao
    abstract fun chatDao(): ChatDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun cachedReportDao(): CachedReportDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun meetingDao(): MeetingDao
    abstract fun approvalSettingsDao(): ApprovalSettingsDao
    abstract fun proposalDao(): ProposalDao
    abstract fun proposalVoteDao(): ProposalVoteDao
    abstract fun proposalVoteHistoryDao(): ProposalVoteHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: FamilyFinanceDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN phoneNumber TEXT")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN accountStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN passwordConfigured INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN passkeyCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN newDeviceCheckEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN registeredAtMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS families_new (
                        familyId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        ownerId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        currencyCode TEXT NOT NULL,
                        memberUidsCsv TEXT NOT NULL,
                        adminUidsCsv TEXT NOT NULL,
                        description TEXT,
                        requireAdminApproval INTEGER NOT NULL DEFAULT 1,
                        memberChatEnabled INTEGER NOT NULL DEFAULT 1,
                        membersCanAddExpenses INTEGER NOT NULL DEFAULT 1,
                        budgetStartDay INTEGER NOT NULL DEFAULT 1,
                        createdAtMillis INTEGER NOT NULL DEFAULT 0,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO families_new (
                        familyId, name, ownerId, createdByUserId, currencyCode,
                        memberUidsCsv, adminUidsCsv, description, syncStatus, updatedAtMillis
                    )
                    SELECT familyId, name, ownerId, ownerId, currencyCode,
                           memberUidsCsv, ownerId, description, syncStatus, updatedAtMillis
                    FROM families
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE families")
                db.execSQL("ALTER TABLE families_new RENAME TO families")

                db.execSQL("ALTER TABLE family_members ADD COLUMN canCreateRecords INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE family_members ADD COLUMN canViewSharedFinances INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE family_members ADD COLUMN canParticipateInChat INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE family_members ADD COLUMN joinedAtMillis INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS my_join_requests (
                        familyId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        familyName TEXT NOT NULL,
                        tokenPreview TEXT NOT NULL,
                        relationLabel TEXT NOT NULL,
                        status TEXT NOT NULL,
                        requestedAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(familyId, userId)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transactions (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        transactionType TEXT NOT NULL DEFAULT 'EXPENSE',
                        paymentMethod TEXT,
                        occurredAtMillis INTEGER NOT NULL,
                        idempotencyKey TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_familyId_idempotencyKey ON transactions(familyId, idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_familyId_recordStatus_occurredAtMillis ON transactions(familyId, recordStatus, occurredAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_familyId_transactionType_occurredAtMillis ON transactions(familyId, transactionType, occurredAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS loans (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        remainingBalance REAL NOT NULL,
                        interestRateApr REAL,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        loanDirection TEXT NOT NULL DEFAULT 'BORROWED',
                        counterparty TEXT NOT NULL,
                        dueDateMillis INTEGER,
                        idempotencyKey TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_loans_familyId_idempotencyKey ON loans(familyId, idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_loans_familyId_recordStatus_updatedAtMillis ON loans(familyId, recordStatus, updatedAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS savings (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        targetAmount REAL NOT NULL,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        goalName TEXT NOT NULL,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        targetDateMillis INTEGER,
                        idempotencyKey TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_savings_familyId_idempotencyKey ON savings(familyId, idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_savings_familyId_recordStatus_updatedAtMillis ON savings(familyId, recordStatus, updatedAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS investments (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currentValue REAL NOT NULL,
                        units REAL,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        assetName TEXT NOT NULL,
                        assetType TEXT NOT NULL DEFAULT 'ETF',
                        symbol TEXT,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        idempotencyKey TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_investments_familyId_idempotencyKey ON investments(familyId, idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_investments_familyId_recordStatus_updatedAtMillis ON investments(familyId, recordStatus, updatedAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budgets (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        spentAmount REAL NOT NULL DEFAULT 0.0,
                        alertThresholdPercent INTEGER NOT NULL DEFAULT 80,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        periodKey TEXT NOT NULL,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        idempotencyKey TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_familyId_idempotencyKey ON budgets(familyId, idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_familyId_periodKey_recordStatus ON budgets(familyId, periodKey, recordStatus)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chats (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        senderUserId TEXT NOT NULL,
                        senderDisplayName TEXT NOT NULL,
                        messageText TEXT NOT NULL,
                        messageType TEXT NOT NULL DEFAULT 'TEXT',
                        relatedRecordId TEXT,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        lastSyncedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_familyId_createdAtMillis ON chats(familyId, createdAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notifications (
                        notificationId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        recipientUserId TEXT NOT NULL,
                        createdByUserId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        notificationType TEXT NOT NULL DEFAULT 'GENERAL',
                        relatedRecordId TEXT,
                        acknowledged INTEGER NOT NULL DEFAULT 0,
                        recordStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_familyId_recipientUserId_createdAtMillis ON notifications(familyId, recipientUserId, createdAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_logs (
                        auditId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        actorUserId TEXT NOT NULL,
                        entityCollection TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        afterHash TEXT,
                        deviceId TEXT NOT NULL DEFAULT '',
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_familyId_createdAtMillis ON audit_logs(familyId, createdAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_familyId_entityCollection_createdAtMillis ON audit_logs(familyId, entityCollection, createdAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        outboxId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        actorUserId TEXT NOT NULL,
                        collectionName TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT,
                        syncVersion INTEGER NOT NULL DEFAULT 1,
                        deviceId TEXT NOT NULL DEFAULT '',
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_familyId_status_createdAtMillis ON sync_outbox(familyId, status, createdAtMillis)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_collectionName_documentId ON sync_outbox(collectionName, documentId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN memberId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE transactions SET memberId = createdByUserId WHERE memberId = ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN amountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET amountMinor = CAST(ROUND(amount * 100.0) AS INTEGER) WHERE amountMinor = 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceFrequency TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN nextRecurrenceAtMillis INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringParentId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN isRecurringTemplate INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE loans ADD COLUMN amountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loans ADD COLUMN remainingBalanceMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loans ADD COLUMN borrowerName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE loans ADD COLUMN borrowerContact TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE loans ADD COLUMN lenderName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE loans ADD COLUMN lenderContact TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE loans ADD COLUMN totalInstallments INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE loans ADD COLUMN paidInstallments INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loans ADD COLUMN installmentAmountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loans ADD COLUMN startDateMillis INTEGER")
                db.execSQL("UPDATE loans SET amountMinor = CAST(ROUND(amount * 100.0) AS INTEGER) WHERE amountMinor = 0")
                db.execSQL("UPDATE loans SET remainingBalanceMinor = CAST(ROUND(remainingBalance * 100.0) AS INTEGER) WHERE remainingBalanceMinor = 0 AND remainingBalance > 0")

                db.execSQL("ALTER TABLE savings ADD COLUMN amountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE savings ADD COLUMN targetAmountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE savings SET amountMinor = CAST(ROUND(amount * 100.0) AS INTEGER) WHERE amountMinor = 0 AND amount > 0")
                db.execSQL("UPDATE savings SET targetAmountMinor = CAST(ROUND(targetAmount * 100.0) AS INTEGER) WHERE targetAmountMinor = 0")

                db.execSQL("ALTER TABLE investments ADD COLUMN amountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE investments ADD COLUMN currentValueMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE investments ADD COLUMN investedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE investments SET amountMinor = CAST(ROUND(amount * 100.0) AS INTEGER) WHERE amountMinor = 0 AND amount > 0")
                db.execSQL("UPDATE investments SET currentValueMinor = CAST(ROUND(currentValue * 100.0) AS INTEGER) WHERE currentValueMinor = 0 AND currentValue > 0")
                db.execSQL("UPDATE investments SET investedAtMillis = createdAtMillis WHERE investedAtMillis = 0")

                db.execSQL("ALTER TABLE budgets ADD COLUMN amountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE budgets ADD COLUMN spentAmountMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE budgets ADD COLUMN budgetScope TEXT NOT NULL DEFAULT 'CATEGORY'")
                db.execSQL("UPDATE budgets SET amountMinor = CAST(ROUND(amount * 100.0) AS INTEGER) WHERE amountMinor = 0")
                db.execSQL("UPDATE budgets SET spentAmountMinor = CAST(ROUND(spentAmount * 100.0) AS INTEGER) WHERE spentAmountMinor = 0 AND spentAmount > 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS financial_events (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        parentRecordId TEXT NOT NULL,
                        moduleType TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        actorUserId TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        secondaryAmountMinor INTEGER,
                        balanceAfterMinor INTEGER,
                        currency TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        eventDateMillis INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'PENDING_CREATE',
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_events_familyId_moduleType_eventDateMillis ON financial_events(familyId, moduleType, eventDateMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_events_familyId_parentRecordId_eventDateMillis ON financial_events(familyId, parentRecordId, eventDateMillis)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET baseSyncVersion = syncVersion WHERE syncStatus = 'SYNCED'")

                db.execSQL("ALTER TABLE loans ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE loans SET baseSyncVersion = syncVersion WHERE syncStatus = 'SYNCED'")

                db.execSQL("ALTER TABLE savings ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE savings SET baseSyncVersion = syncVersion WHERE syncStatus = 'SYNCED'")

                db.execSQL("ALTER TABLE investments ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE investments SET baseSyncVersion = syncVersion WHERE syncStatus = 'SYNCED'")

                db.execSQL("ALTER TABLE budgets ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE budgets SET baseSyncVersion = syncVersion WHERE syncStatus = 'SYNCED'")

                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN baseSyncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN operationId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE sync_outbox SET operationId = outboxId WHERE operationId = ''")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN payloadHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN resolutionPolicy TEXT NOT NULL DEFAULT 'MANUAL_REVIEW'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_conflicts (
                        conflictId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        collectionName TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        conflictType TEXT NOT NULL,
                        localVersion INTEGER NOT NULL,
                        remoteVersion INTEGER NOT NULL,
                        localSummary TEXT NOT NULL,
                        remoteSummary TEXT NOT NULL,
                        localPayloadJson TEXT NOT NULL DEFAULT '',
                        remotePayloadJson TEXT NOT NULL DEFAULT '',
                        policy TEXT NOT NULL DEFAULT 'MANUAL_REVIEW',
                        status TEXT NOT NULL DEFAULT 'UNRESOLVED',
                        createdAtMillis INTEGER NOT NULL,
                        resolvedAtMillis INTEGER,
                        resolvedByUserId TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_conflicts_familyId_status_createdAtMillis ON sync_conflicts(familyId, status, createdAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_conflicts_collectionName_documentId ON sync_conflicts(collectionName, documentId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_reports (
                        reportId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        periodKey TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        reportJson TEXT NOT NULL,
                        recordCount INTEGER NOT NULL DEFAULT 0,
                        sourceHash TEXT NOT NULL DEFAULT '',
                        generatedAtMillis INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'CACHED_LOCAL'
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cached_reports_familyId_periodKey ON cached_reports(familyId, periodKey)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE families ADD COLUMN chatRetentionDays INTEGER NOT NULL DEFAULT 90")

                db.execSQL("ALTER TABLE chats ADD COLUMN senderPhotoUrl TEXT")
                db.execSQL("ALTER TABLE chats ADD COLUMN senderRoleLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chats ADD COLUMN readByUidsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chats ADD COLUMN editedAtMillis INTEGER")
                db.execSQL("ALTER TABLE chats ADD COLUMN deletedAtMillis INTEGER")
                db.execSQL("ALTER TABLE chats ADD COLUMN deletedByUserId TEXT")
                db.execSQL("ALTER TABLE chats ADD COLUMN expiresAtMillis INTEGER")
                db.execSQL("ALTER TABLE chats ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_familyId_expiresAtMillis ON chats(familyId, expiresAtMillis)")

                db.execSQL("ALTER TABLE notifications ADD COLUMN deepLinkModule TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN readByUidsCsv TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS call_history (
                        callId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        callerUid TEXT NOT NULL,
                        calleeUid TEXT NOT NULL,
                        callerName TEXT NOT NULL,
                        calleeName TEXT NOT NULL,
                        mediaType TEXT NOT NULL DEFAULT 'AUDIO',
                        status TEXT NOT NULL DEFAULT 'ENDED',
                        networkQuality TEXT NOT NULL DEFAULT 'UNKNOWN',
                        durationSeconds INTEGER NOT NULL DEFAULT 0,
                        endedByUid TEXT,
                        startedAtMillis INTEGER,
                        endedAtMillis INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_history_familyId_createdAtMillis ON call_history(familyId, createdAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_history_familyId_callerUid ON call_history(familyId, callerUid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_history_familyId_calleeUid ON call_history(familyId, calleeUid)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meetings (
                        meetingId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        roomName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        hostUid TEXT NOT NULL,
                        hostName TEXT NOT NULL,
                        inviteCode TEXT NOT NULL,
                        inviteLink TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'LIVE',
                        isScheduled INTEGER NOT NULL DEFAULT 0,
                        scheduledForMillis INTEGER,
                        startedAtMillis INTEGER,
                        endedAtMillis INTEGER,
                        endedByUid TEXT,
                        audioEnabledDefault INTEGER NOT NULL DEFAULT 1,
                        videoEnabledDefault INTEGER NOT NULL DEFAULT 1,
                        invitedUidsCsv TEXT NOT NULL DEFAULT '',
                        activeParticipantUidsCsv TEXT NOT NULL DEFAULT '',
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meetings_familyId_createdAtMillis ON meetings(familyId, createdAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meetings_familyId_inviteCode ON meetings(familyId, inviteCode)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN memberId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN maskedPhone TEXT")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN dateOfBirth TEXT")

                db.execSQL("ALTER TABLE families ADD COLUMN suspendedUidsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE families ADD COLUMN isPersonal INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE family_members ADD COLUMN memberId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE family_members ADD COLUMN maskedPhone TEXT")
                db.execSQL("ALTER TABLE family_members ADD COLUMN memberStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE family_members ADD COLUMN cardMemberId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS member_cards (
                        familyId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        cardMemberId TEXT NOT NULL,
                        familyName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        photoUrl TEXT,
                        role TEXT NOT NULL DEFAULT 'MEMBER',
                        memberStatus TEXT NOT NULL DEFAULT 'ACTIVE',
                        joinedAtMillis INTEGER NOT NULL,
                        showAvailableBalance INTEGER NOT NULL DEFAULT 1,
                        showIncomeAndExpense INTEGER NOT NULL DEFAULT 1,
                        showFamilyContributions INTEGER NOT NULL DEFAULT 1,
                        showLoans INTEGER NOT NULL DEFAULT 1,
                        showSavings INTEGER NOT NULL DEFAULT 1,
                        showInvestments INTEGER NOT NULL DEFAULT 1,
                        showTransactionHistory INTEGER NOT NULL DEFAULT 1,
                        allowAdminDetailedAudit INTEGER NOT NULL DEFAULT 1,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(familyId, userId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_member_cards_familyId_cardMemberId ON member_cards(familyId, cardMemberId)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS approval_settings (
                        familyId TEXT NOT NULL PRIMARY KEY,
                        ruleMode TEXT NOT NULL DEFAULT 'MAJORITY',
                        minApprovalPercent INTEGER NOT NULL DEFAULT 60,
                        minQuorumCount INTEGER NOT NULL DEFAULT 1,
                        defaultDeadlineHours INTEGER NOT NULL DEFAULT 72,
                        allowCreatorVote INTEGER NOT NULL DEFAULT 1,
                        allowVoteChangeBeforeDeadline INTEGER NOT NULL DEFAULT 1,
                        eligibleVoterRule TEXT NOT NULL DEFAULT 'ALL_ACTIVE_MEMBERS',
                        specificEligibleUidsCsv TEXT NOT NULL DEFAULT '',
                        requiredCategoriesCsv TEXT NOT NULL DEFAULT '',
                        largeExpenseThresholdMinor INTEGER NOT NULL DEFAULT 500000,
                        updatedByUid TEXT NOT NULL DEFAULT '',
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proposals (
                        proposalId TEXT NOT NULL PRIMARY KEY,
                        familyId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        category TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        reason TEXT NOT NULL DEFAULT '',
                        amountMinor INTEGER NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'BDT',
                        relatedParty TEXT NOT NULL DEFAULT '',
                        supportingDocName TEXT,
                        supportingDocUri TEXT,
                        targetSavingsId TEXT,
                        proposedDateMillis INTEGER NOT NULL,
                        votingDeadlineMillis INTEGER NOT NULL,
                        proposerUid TEXT NOT NULL,
                        proposerName TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        ruleMode TEXT NOT NULL DEFAULT 'MAJORITY',
                        minApprovalPercent INTEGER NOT NULL DEFAULT 60,
                        minQuorumRequired INTEGER NOT NULL DEFAULT 1,
                        allowCreatorVote INTEGER NOT NULL DEFAULT 1,
                        allowVoteChangeBeforeDeadline INTEGER NOT NULL DEFAULT 1,
                        eligibleVoterRule TEXT NOT NULL DEFAULT 'ALL_ACTIVE_MEMBERS',
                        eligibleVoterUidsCsv TEXT NOT NULL DEFAULT '',
                        approvedCount INTEGER NOT NULL DEFAULT 0,
                        rejectedCount INTEGER NOT NULL DEFAULT 0,
                        finalDecision TEXT,
                        cancelReason TEXT,
                        cancelledByUid TEXT,
                        executedRecordId TEXT,
                        executedCollection TEXT,
                        executedByUid TEXT,
                        executedAtMillis INTEGER,
                        decidedAtMillis INTEGER,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_proposals_familyId_status_votingDeadlineMillis ON proposals(familyId, status, votingDeadlineMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_proposals_familyId_updatedAtMillis ON proposals(familyId, updatedAtMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proposal_votes (
                        proposalId TEXT NOT NULL,
                        voterUid TEXT NOT NULL,
                        familyId TEXT NOT NULL,
                        voterName TEXT NOT NULL,
                        choice TEXT NOT NULL,
                        comment TEXT,
                        votedAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(proposalId, voterUid)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_proposal_votes_familyId_proposalId ON proposal_votes(familyId, proposalId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proposal_vote_history (
                        historyId TEXT NOT NULL PRIMARY KEY,
                        proposalId TEXT NOT NULL,
                        familyId TEXT NOT NULL,
                        actorUid TEXT NOT NULL,
                        actorName TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        previousChoice TEXT,
                        newChoice TEXT,
                        note TEXT,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_proposal_vote_history_proposalId_createdAtMillis ON proposal_vote_history(proposalId, createdAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_proposal_vote_history_familyId_createdAtMillis ON proposal_vote_history(familyId, createdAtMillis)")
            }
        }

        fun getInstance(context: Context): FamilyFinanceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FamilyFinanceDatabase::class.java,
                    "family_finance_local.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
