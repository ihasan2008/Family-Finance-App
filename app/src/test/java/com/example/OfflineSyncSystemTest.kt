package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.LocalEntityCipherMapper
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.BudgetScope
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.FamilyRole
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.core.sync.CachedReportBuilder
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictStatus
import com.example.core.sync.ConflictType
import com.example.core.sync.OfflineSyncEngine
import com.example.core.sync.OutboxValidationResult
import com.example.core.sync.RemoteSyncGateway
import com.example.core.sync.RemoteWriteOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineSyncSystemTest {

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore
    private val mapper = LocalEntityCipherMapper()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FamilyFinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SqliteRoomDatabaseStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFamilyAndMember(
        familyId: String = "fam_01",
        uid: String = "uid_alice",
        role: FamilyRole = FamilyRole.ADMIN,
        canCreate: Boolean = true,
        canChat: Boolean = true,
        chatEnabled: Boolean = true
    ): LocalFamilyEntity {
        val fam = LocalFamilyEntity(
            familyId = familyId,
            name = "Household Alpha",
            ownerId = "uid_alice",
            createdByUserId = "uid_alice",
            currencyCode = "USD",
            memberUidsCsv = "uid_alice,uid_bob",
            adminUidsCsv = "uid_alice",
            memberChatEnabled = chatEnabled,
            membersCanAddExpenses = true
        )
        db.familyDao().upsertFamily(fam)
        db.familyMemberDao().upsertMember(
            LocalFamilyMemberEntity(
                familyId = familyId,
                userId = uid,
                displayName = uid,
                role = role.name,
                canCreateRecords = canCreate,
                canViewSharedFinances = true,
                canParticipateInChat = canChat
            )
        )
        return fam
    }

    @Test
    fun encryptionAtRest_encryptsSensitiveColumnsAndReadsLegacyPlaintext() = runTest {
        val fam = seedFamilyAndMember()
        val tx = LocalTransactionEntity(
            recordId = "rec_enc_1",
            familyId = fam.familyId,
            createdByUserId = "uid_alice",
            amount = 45.25,
            amountMinor = 4525L,
            currency = "USD",
            category = "Medical",
            description = "Prescription refill",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_enc_1"
        )
        val audit = LocalAuditLogEntity(
            auditId = "aud_enc_1",
            familyId = fam.familyId,
            actorUserId = "uid_alice",
            entityCollection = "transactions",
            entityId = tx.recordId,
            action = AuditAction.CREATE.name,
            summary = "Medical expense"
        )
        store.saveTransactionIdempotent(tx, audit)

        val chat = LocalChatMessageEntity(
            messageId = "msg_enc_1",
            familyId = fam.familyId,
            senderUserId = "uid_alice",
            senderDisplayName = "Alice Caretaker",
            messageText = "Secret grocery budget note"
        )
        store.saveChatMessage(chat, audit.copy(auditId = "aud_enc_2", entityCollection = "chats", entityId = chat.messageId))

        // Raw DAO stores enc1: ciphertext
        val rawTx = db.transactionDao().getById("rec_enc_1")!!
        val rawChat = db.chatDao().getById("msg_enc_1")!!
        assertTrue(rawTx.description.startsWith("enc1:"))
        assertFalse(rawTx.description.contains("Prescription"))
        assertTrue(rawChat.messageText.startsWith("enc1:"))
        assertFalse(rawChat.messageText.contains("Secret"))

        // Store decrypts cleanly
        assertEquals("Prescription refill", store.getTransactionById("rec_enc_1")?.description)
        assertEquals("Secret grocery budget note", store.getChatMessageById("msg_enc_1")?.messageText)

        // Legacy plaintext row written prior to v7 reads transparently
        db.transactionDao().upsert(
            tx.copy(recordId = "rec_legacy_1", idempotencyKey = "idem_legacy_1", description = "Plaintext legacy note")
        )
        assertEquals("Plaintext legacy note", store.getTransactionById("rec_legacy_1")?.description)
    }

    @Test
    fun offlineLifecycle_createEditPreservesCreateAndOfflineDeleteCancelsOutbox() = runTest {
        val fam = seedFamilyAndMember()
        val tx = LocalTransactionEntity(
            recordId = "rec_life_1",
            familyId = fam.familyId,
            createdByUserId = "uid_alice",
            amount = 20.0,
            amountMinor = 2000L,
            currency = "USD",
            category = "Food",
            description = "Lunch",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_life_1"
        )
        val audit = LocalAuditLogEntity(
            auditId = "aud_life_1",
            familyId = fam.familyId,
            actorUserId = "uid_alice",
            entityCollection = "transactions",
            entityId = tx.recordId,
            action = AuditAction.CREATE.name,
            summary = "Create Lunch"
        )

        store.saveTransactionIdempotent(tx, audit)
        val outbox1 = store.getOutboxForDocument("transactions", tx.recordId)!!
        assertEquals("CREATE", outbox1.operation)
        assertEquals(0, outbox1.baseSyncVersion)

        // Edit locally while still offline -> must stay CREATE with baseSyncVersion = 0
        val edited = tx.copy(amount = 25.0, amountMinor = 2500L, description = "Team Lunch")
        store.saveTransactionIdempotent(edited, audit.copy(auditId = "aud_life_2", action = AuditAction.UPDATE.name))
        val outbox2 = store.getOutboxForDocument("transactions", tx.recordId)!!
        assertEquals("CREATE", outbox2.operation)
        assertEquals(0, outbox2.baseSyncVersion)

        // Delete locally before ever syncing -> outbox CREATE cancelled cleanly
        val deleted = store.deleteTransactionLocal(tx.recordId, fam.familyId, "uid_alice", audit.copy(auditId = "aud_life_3", action = AuditAction.DELETE.name))
        assertTrue(deleted)
        assertNull(store.getTransactionById(tx.recordId))
        assertNull(store.getOutboxForDocument("transactions", tx.recordId))

        // Edit a SYNCED record -> sets UPDATE and preserves baseSyncVersion = 3
        val syncedTx = tx.copy(
            recordId = "rec_synced_1",
            idempotencyKey = "idem_synced_1",
            syncStatus = SyncState.SYNCED.name,
            syncVersion = 3,
            baseSyncVersion = 3
        )
        db.transactionDao().upsert(mapper.encrypt(syncedTx))
        store.saveTransactionIdempotent(
            syncedTx.copy(description = "Updated after sync", updatedAtMillis = System.currentTimeMillis() + 100),
            audit.copy(auditId = "aud_life_4", entityId = syncedTx.recordId, action = AuditAction.UPDATE.name)
        )
        val outboxSynced = store.getOutboxForDocument("transactions", syncedTx.recordId)!!
        assertEquals("UPDATE", outboxSynced.operation)
        assertEquals(3, outboxSynced.baseSyncVersion)
        assertEquals(4, outboxSynced.syncVersion)
    }

    @Test
    fun syncValidation_rejectsCrossFamilyAndRevokedPermissionsIntoQuarantine() = runTest {
        val fam = seedFamilyAndMember(uid = "uid_bob", role = FamilyRole.MEMBER, canCreate = false, canChat = false, chatEnabled = false)
        val engine = OfflineSyncEngine(store, FakeRemoteSyncGateway())

        // 1. Cross-family violation
        val crossFamilyOp = LocalSyncOutboxEntity(
            outboxId = "out_cross",
            familyId = "fam_OTHER",
            actorUserId = "uid_bob",
            collectionName = "transactions",
            documentId = "rec_x",
            operation = "CREATE"
        )
        val crossRes = engine.validateOutboxOperation(crossFamilyOp, fam.familyId, "uid_bob")
        assertTrue(crossRes is OutboxValidationResult.Rejected)
        assertEquals(ConflictType.CROSS_FAMILY_VIOLATION, (crossRes as OutboxValidationResult.Rejected).conflictType)

        // 2. Chat disabled
        val chatOp = LocalSyncOutboxEntity(
            outboxId = "out_chat_dis",
            familyId = fam.familyId,
            actorUserId = "uid_bob",
            collectionName = "chats",
            documentId = "msg_x",
            operation = "CREATE"
        )
        val chatRes = engine.validateOutboxOperation(chatOp, fam.familyId, "uid_bob")
        assertTrue(chatRes is OutboxValidationResult.Rejected)

        // 3. Synchronize with revoked creation permission -> quarantined to sync_conflicts
        val restrictedTx = LocalTransactionEntity(
            recordId = "rec_bob_1",
            familyId = fam.familyId,
            createdByUserId = "uid_bob",
            amount = 50.0,
            amountMinor = 5000L,
            currency = "USD",
            category = "Food",
            description = "Dinner",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_bob_1"
        )
        store.saveTransactionIdempotent(
            restrictedTx,
            LocalAuditLogEntity("aud_bob_1", fam.familyId, "uid_bob", "transactions", restrictedTx.recordId, "CREATE", "Dinner")
        )
        val summary = engine.synchronize("uid_bob", fam, ConflictResolutionPolicy.MANUAL_REVIEW, isConnectivityOnline = true)
        assertEquals(1, summary.rejectedCount)
        assertEquals(0, store.getPendingOutboxForFamily(fam.familyId).size)
        val conflicts = store.observeConflicts(fam.familyId).first()
        assertEquals(1, conflicts.size)
        assertEquals(ConflictType.UNAUTHORIZED_ACTOR.name, conflicts.first().conflictType)
        assertEquals(SyncState.ERROR.name, store.getTransactionById(restrictedTx.recordId)?.syncStatus)
    }

    @Test
    fun pullConflictDetection_preservesLocalEditAndFlagsConflict() = runTest {
        val fam = seedFamilyAndMember()
        // Seed synced v1 locally
        val localOrig = LocalTransactionEntity(
            recordId = "rec_pull_1",
            familyId = fam.familyId,
            createdByUserId = "uid_alice",
            amount = 100.0,
            amountMinor = 10000L,
            currency = "USD",
            category = "Groceries",
            description = "Local base v1",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_pull_1",
            syncStatus = SyncState.SYNCED.name,
            syncVersion = 1,
            baseSyncVersion = 1
        )
        db.transactionDao().upsert(mapper.encrypt(localOrig))

        // Edit offline -> local v2 pending based on v1
        store.saveTransactionIdempotent(
            localOrig.copy(description = "Local offline edit", amount = 115.0, amountMinor = 11500L),
            LocalAuditLogEntity("aud_p1", fam.familyId, "uid_alice", "transactions", localOrig.recordId, "UPDATE", "Edit offline")
        )

        // Meanwhile cloud delivers remote v3 from another device
        val remoteNewer = localOrig.copy(
            description = "Another device v3",
            amount = 130.0,
            amountMinor = 13000L,
            syncVersion = 3,
            baseSyncVersion = 3,
            syncStatus = SyncState.SYNCED.name
        )
        store.reconcileRemoteTransactions(fam.familyId, listOf(remoteNewer))

        val afterLocal = store.getTransactionById("rec_pull_1")!!
        assertEquals(SyncState.CONFLICT.name, afterLocal.syncStatus)
        assertEquals("Local offline edit", afterLocal.description)
        assertEquals(11500L, afterLocal.effectiveAmountMinor())

        val conflicts = store.observeConflicts(fam.familyId).first()
        assertEquals(1, conflicts.size)
        assertEquals(ConflictType.REMOTE_MODIFIED.name, conflicts.first().conflictType)
        assertEquals(3, conflicts.first().remoteVersion)

        val audits = store.observeAuditLogs(fam.familyId, 20).first()
        assertTrue(audits.any { it.entityId == "rec_pull_1" && it.action == AuditAction.SYNC.name })

        // Verify pull conflict detection & audit log for financial modules (loans) as well
        val loanSynced = LocalLoanEntity(
            recordId = "loan_pull_1",
            familyId = fam.familyId,
            createdByUserId = "uid_alice",
            amount = 500.0,
            amountMinor = 500_00L,
            remainingBalance = 500.0,
            remainingBalanceMinor = 500_00L,
            currency = "USD",
            category = "Personal",
            description = "Loan v1",
            loanDirection = LoanDirection.BORROWED.name,
            counterparty = "Friend",
            idempotencyKey = "idem_loan_pull_1",
            syncStatus = SyncState.SYNCED.name,
            syncVersion = 1,
            baseSyncVersion = 1
        )
        db.loanDao().upsert(mapper.encrypt(loanSynced))
        store.saveLoanIdempotent(
            loanSynced.copy(remainingBalance = 400.0, remainingBalanceMinor = 400_00L),
            LocalAuditLogEntity("aud_lp1", fam.familyId, "uid_alice", "loans", loanSynced.recordId, "UPDATE", "Repaid $100 offline")
        )
        store.reconcileRemoteLoans(
            fam.familyId,
            listOf(loanSynced.copy(remainingBalance = 350.0, remainingBalanceMinor = 350_00L, syncVersion = 2, baseSyncVersion = 2))
        )
        assertEquals(SyncState.CONFLICT.name, store.getLoanById("loan_pull_1")?.syncStatus)
        assertTrue(store.observeAuditLogs(fam.familyId, 20).first().any { it.entityId == "loan_pull_1" && it.action == AuditAction.SYNC.name })
    }

    @Test
    fun pushConflictResolution_manualServerAndClientWinsPolicies() = runTest {
        val fam = seedFamilyAndMember()

        // Helper to seed synced v1 + local edit v2
        suspend fun preparePendingEdit(id: String, desc: String): LocalTransactionEntity {
            val base = LocalTransactionEntity(
                recordId = id,
                familyId = fam.familyId,
                createdByUserId = "uid_alice",
                amount = 60.0,
                amountMinor = 6000L,
                currency = "USD",
                category = "Bills",
                description = "Cloud v1",
                transactionType = TransactionType.EXPENSE.name,
                idempotencyKey = "idem_$id",
                syncStatus = SyncState.SYNCED.name,
                syncVersion = 1,
                baseSyncVersion = 1
            )
            db.transactionDao().upsert(mapper.encrypt(base))
            val edit = base.copy(description = desc, amount = 75.0, amountMinor = 7500L)
            store.saveTransactionIdempotent(
                edit,
                LocalAuditLogEntity("aud_$id", fam.familyId, "uid_alice", "transactions", id, "UPDATE", desc)
            )
            return edit
        }

        // Case A: MANUAL_REVIEW -> creates conflict record & removes outbox until resolved
        preparePendingEdit("rec_man_1", "Manual edit")
        val remoteSnapshotJson = mapper.encodeTransactionSnapshot(
            LocalTransactionEntity(
                recordId = "rec_man_1",
                familyId = fam.familyId,
                createdByUserId = "uid_alice",
                amount = 90.0,
                amountMinor = 9000L,
                currency = "USD",
                category = "Bills",
                description = "Cloud v4 winner",
                transactionType = TransactionType.EXPENSE.name,
                idempotencyKey = "idem_rec_man_1",
                syncStatus = SyncState.SYNCED.name,
                syncVersion = 4,
                baseSyncVersion = 4
            )
        )
        val gatewayManual = FakeRemoteSyncGateway(
            nextUpdateOutcome = { _, expectedBase ->
                if (expectedBase < 4) RemoteWriteOutcome.VersionConflict(4, "Cloud v4", remoteSnapshotJson)
                else RemoteWriteOutcome.Success(5)
            }
        )
        val engineManual = OfflineSyncEngine(store, gatewayManual)
        val sumManual = engineManual.synchronize("uid_alice", fam, ConflictResolutionPolicy.MANUAL_REVIEW, true)
        assertEquals(1, sumManual.conflictsDetected)
        val conflict = store.observeConflicts(fam.familyId).first().first { it.documentId == "rec_man_1" }
        assertEquals(ConflictStatus.UNRESOLVED.name, conflict.status)

        // Resolve MANUAL conflict with CLIENT_WINS -> re-queues with baseSyncVersion = 4 and syncs on next pass
        assertTrue(store.resolveConflict(conflict.conflictId, ConflictResolutionPolicy.CLIENT_WINS, "uid_alice"))
        val retrySum = engineManual.synchronize("uid_alice", fam, ConflictResolutionPolicy.CLIENT_WINS, true)
        assertEquals(1, retrySum.syncedCount)
        assertEquals(5, store.getTransactionById("rec_man_1")?.syncVersion)

        // Case B: SERVER_WINS automatic resolution overwrites local with remote snapshot
        preparePendingEdit("rec_srv_1", "Client edit discarded")
        val srvSnapshotJson = mapper.encodeTransactionSnapshot(
            LocalTransactionEntity(
                recordId = "rec_srv_1",
                familyId = fam.familyId,
                createdByUserId = "uid_alice",
                amount = 120.0,
                amountMinor = 12000L,
                currency = "USD",
                category = "Bills",
                description = "Remote authoritative",
                transactionType = TransactionType.EXPENSE.name,
                idempotencyKey = "idem_rec_srv_1",
                syncStatus = SyncState.SYNCED.name,
                syncVersion = 3,
                baseSyncVersion = 3
            )
        )
        val engineServer = OfflineSyncEngine(
            store,
            FakeRemoteSyncGateway(
                nextUpdateOutcome = { _, _ -> RemoteWriteOutcome.VersionConflict(3, "Remote v3", srvSnapshotJson) }
            )
        )
        engineServer.synchronize("uid_alice", fam, ConflictResolutionPolicy.SERVER_WINS, true)
        val afterSrv = store.getTransactionById("rec_srv_1")!!
        assertEquals("Remote authoritative", afterSrv.description)
        assertEquals(12000L, afterSrv.effectiveAmountMinor())
        assertEquals(SyncState.SYNCED.name, afterSrv.syncStatus)

        // Case C: CLIENT_WINS automatic immediate retry succeeds on version conflict
        preparePendingEdit("rec_cli_1", "Keep device edit")
        var attempts = 0
        val engineClient = OfflineSyncEngine(
            store,
            FakeRemoteSyncGateway(
                nextUpdateOutcome = { _, expectedBase ->
                    attempts++
                    if (expectedBase < 2) RemoteWriteOutcome.VersionConflict(2, "Cloud v2", "")
                    else RemoteWriteOutcome.Success(3)
                }
            )
        )
        val cliSum = engineClient.synchronize("uid_alice", fam, ConflictResolutionPolicy.CLIENT_WINS, true)
        assertEquals(2, attempts)
        assertEquals(1, cliSum.syncedCount)
        assertEquals(3, store.getTransactionById("rec_cli_1")?.syncVersion)
        assertEquals(SyncState.SYNCED.name, store.getTransactionById("rec_cli_1")?.syncStatus)
    }

    @Test
    fun cachedReportsAndSecurityPurge_enforceAccountingRulesAndIsolation() = runTest {
        val famA = seedFamilyAndMember("fam_alpha", "uid_alice")
        val famB = seedFamilyAndMember("fam_beta", "uid_alice")

        val transactions = listOf(
            LocalTransactionEntity(
                recordId = "t1", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 3000.0, amountMinor = 300_000L, currency = "USD",
                category = "Salary", description = "Monthly pay", transactionType = TransactionType.INCOME.name,
                idempotencyKey = "k_t1"
            ),
            LocalTransactionEntity(
                recordId = "t2", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 1200.0, amountMinor = 120_000L, currency = "USD",
                category = "Food", description = "Groceries", transactionType = TransactionType.EXPENSE.name,
                idempotencyKey = "k_t2"
            )
        )
        val loans = listOf(
            LocalLoanEntity(
                recordId = "l1", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 5000.0, amountMinor = 500_000L, remainingBalance = 3500.0, remainingBalanceMinor = 350_000L,
                currency = "USD", category = "Personal", description = "Auto loan",
                loanDirection = LoanDirection.BORROWED.name, counterparty = "Credit Union", idempotencyKey = "k_l1"
            )
        )
        val savings = listOf(
            LocalSavingsEntity(
                recordId = "s1", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 2000.0, amountMinor = 200_000L, targetAmount = 10000.0, targetAmountMinor = 1_000_000L,
                currency = "USD", category = "Emergency", description = "Reserve",
                goalName = "Emergency Fund", idempotencyKey = "k_s1"
            )
        )
        val investments = listOf(
            LocalInvestmentEntity(
                recordId = "i1", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 1000.0, amountMinor = 100_000L, currentValue = 1250.0, currentValueMinor = 125_000L,
                currency = "USD", category = "ETF", description = "Index",
                assetName = "S&P 500", assetType = InvestmentAssetType.STOCK.name, idempotencyKey = "k_i1"
            ),
            LocalInvestmentEntity(
                recordId = "i2", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 500.0, amountMinor = 50_000L, currentValue = 400.0, currentValueMinor = 40_000L,
                currency = "USD", category = "Crypto", description = "Token",
                assetName = "AltCoin", assetType = InvestmentAssetType.CRYPTO.name, idempotencyKey = "k_i2"
            )
        )
        val budgets = listOf(
            LocalBudgetEntity(
                recordId = "b1", familyId = famA.familyId, createdByUserId = "uid_alice",
                amount = 1500.0, amountMinor = 150_000L, currency = "USD", category = "All Expenses",
                description = "Cap", periodKey = CachedReportBuilder.PERIOD_ALL,
                budgetScope = BudgetScope.MONTHLY_OVERALL.name, idempotencyKey = "k_b1"
            )
        )

        val payload = CachedReportBuilder.buildPayload(
            familyId = famA.familyId,
            familyName = famA.name,
            periodKey = CachedReportBuilder.PERIOD_ALL,
            currency = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            budgets = budgets
        )

        // Loans & savings & investment principal must NEVER be mixed into Income/Expense cash flow
        assertEquals(300_000L, payload.totalIncomeMinor)
        assertEquals(120_000L, payload.totalExpenseMinor)
        assertEquals(180_000L, payload.netCashFlowMinor)
        assertEquals(350_000L, payload.loanBorrowedRemainingMinor)
        assertEquals(200_000L, payload.savingsCurrentTotalMinor)
        // Investment profit (+250) and loss (-100) calculated strictly separately from principal (1500)
        assertEquals(150_000L, payload.investmentPrincipalMinor)
        assertEquals(25_000L, payload.investmentProfitMinor)
        assertEquals(10_000L, payload.investmentLossMinor)

        val reportEntity = CachedReportBuilder.toEntity(payload, isOnlineSynced = false)
        store.saveCachedReport(reportEntity)
        val rawReport = db.cachedReportDao().getForPeriod(famA.familyId, CachedReportBuilder.PERIOD_ALL)!!
        assertTrue(rawReport.reportJson.startsWith("enc1:"))
        val decryptedReports = store.observeCachedReports(famA.familyId).first()
        assertEquals(1, decryptedReports.size)
        val parsed = CachedReportBuilder.parsePayload(decryptedReports.first())
        assertNotNull(parsed)
        assertEquals(25_000L, parsed!!.investmentProfitMinor)

        // Also seed a Beta transaction, then purge Beta via purgeUnauthorizedFamilies(setOf(famA.familyId))
        store.saveTransactionIdempotent(
            transactions.first().copy(recordId = "t_beta", familyId = famB.familyId, idempotencyKey = "k_beta"),
            LocalAuditLogEntity("aud_beta", famB.familyId, "uid_alice", "transactions", "t_beta", "CREATE", "Beta tx")
        )
        assertEquals(1, store.observeTransactions(famB.familyId).first().size)

        store.purgeUnauthorizedFamilies(setOf(famA.familyId))
        assertEquals(0, store.observeTransactions(famB.familyId).first().size)
        assertNull(store.getCachedFamily(famB.familyId))
        assertNotNull(store.getCachedFamily(famA.familyId))

        // Full purge on sign-out / revocation clears everything
        store.purgeAllLocalData()
        assertEquals(0, store.observeCachedReports(famA.familyId).first().size)
        assertNull(store.getCachedFamily(famA.familyId))
    }

    private class FakeRemoteSyncGateway(
        private val nextUpdateOutcome: (docId: String, expectedBase: Int) -> RemoteWriteOutcome = { _, expected ->
            RemoteWriteOutcome.Success(expected + 1)
        }
    ) : RemoteSyncGateway {
        override suspend fun executeCreate(
            familyId: String,
            collection: String,
            docId: String,
            idempotencyKey: String,
            payload: Map<String, Any>,
            auditId: String?,
            auditPayload: Map<String, Any>?
        ): RemoteWriteOutcome = RemoteWriteOutcome.Success(1)

        override suspend fun executeUpdateVersionChecked(
            familyId: String,
            collection: String,
            docId: String,
            expectedBaseVersion: Int,
            nextVersion: Int,
            payload: Map<String, Any>,
            auditId: String?,
            auditPayload: Map<String, Any>?
        ): RemoteWriteOutcome = nextUpdateOutcome(docId, expectedBaseVersion)

        override suspend fun executeDelete(
            familyId: String,
            collection: String,
            docId: String,
            expectedBaseVersion: Int,
            auditId: String?,
            auditPayload: Map<String, Any>?
        ): RemoteWriteOutcome = RemoteWriteOutcome.Success(expectedBaseVersion)
    }
}
