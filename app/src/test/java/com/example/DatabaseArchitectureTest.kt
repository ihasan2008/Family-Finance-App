package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.TransactionDoc
import com.example.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseArchitectureTest {

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore

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

    @Test
    fun idempotentTransactionWrite_preventsDuplicatesAndEnqueuesAuditAndOutbox() = runTest {
        val famId = "fam_alpha_001"
        val key = DatabaseIdentityHelper.computeIdempotencyKey(
            familyId = famId,
            collectionName = "transactions_EXPENSE",
            discriminator = "Groceries|Weekly market",
            amount = 82.40,
            currency = "USD",
            timeBucketMillis = 29000000L
        )

        val tx1 = LocalTransactionEntity(
            recordId = DatabaseIdentityHelper.generateRecordId("rec"),
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 82.40,
            currency = "USD",
            category = "Groceries",
            description = "Weekly market",
            recordStatus = RecordStatus.ACTIVE.name,
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = key,
            deviceId = "dev_01"
        )
        val audit1 = LocalAuditLogEntity(
            auditId = DatabaseIdentityHelper.generateAuditId(),
            familyId = famId,
            actorUserId = "uid_alice",
            entityCollection = "transactions",
            entityId = tx1.recordId,
            action = AuditAction.CREATE.name,
            summary = "Created Groceries expense",
            deviceId = "dev_01"
        )

        val firstRes = store.saveTransactionIdempotent(tx1, audit1)
        assertFalse(firstRes.deduplicatedExisting)

        // Duplicate retry with a different generated recordId but same familyId + idempotencyKey
        val txDuplicate = tx1.copy(recordId = DatabaseIdentityHelper.generateRecordId("rec"))
        val auditDup = audit1.copy(auditId = DatabaseIdentityHelper.generateAuditId(), entityId = txDuplicate.recordId)
        val secondRes = store.saveTransactionIdempotent(txDuplicate, auditDup)

        assertTrue(secondRes.deduplicatedExisting)
        assertEquals(firstRes.record.recordId, secondRes.record.recordId)

        val stored = store.observeTransactions(famId).first()
        assertEquals(1, stored.size)
        assertEquals(1, store.getPendingOutboxForFamily(famId).size)
        assertEquals(1, store.observeAuditLogs(famId).first().size)

        // Marking outbox synced updates transaction syncStatus and clears pending queue
        store.markOutboxSynced("transactions", firstRes.record.recordId, 1730000000000L)
        assertEquals(0, store.getPendingOutboxForFamily(famId).size)
        assertEquals(SyncState.SYNCED.name, store.getTransactionById(firstRes.record.recordId)?.syncStatus)

        val health = store.observeDatabaseHealth(famId, "uid_alice").first()
        assertEquals(1, health.transactionsCount)
        assertEquals(1, health.auditLogsCount)
        assertEquals(0, health.pendingSyncCount)
    }

    @Test
    fun familyIsolation_scopesLocalQueriesStrictlyByFamilyId() = runTest {
        val famA = "fam_alpha_001"
        val famB = "fam_beta_002"

        val txA = LocalTransactionEntity(
            recordId = "rec_a_1",
            familyId = famA,
            createdByUserId = "uid_alice",
            amount = 100.0,
            currency = "USD",
            category = "Salary",
            description = "Family A income",
            transactionType = TransactionType.INCOME.name,
            idempotencyKey = "idem_a_1"
        )
        val txB = LocalTransactionEntity(
            recordId = "rec_b_1",
            familyId = famB,
            createdByUserId = "uid_bob",
            amount = 250.0,
            currency = "EUR",
            category = "Rent",
            description = "Family B expense",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_b_1"
        )

        store.saveTransactionIdempotent(
            txA,
            LocalAuditLogEntity(
                auditId = "aud_a_1",
                familyId = famA,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = "rec_a_1",
                action = AuditAction.CREATE.name,
                summary = "Alpha income"
            )
        )
        store.saveTransactionIdempotent(
            txB,
            LocalAuditLogEntity(
                auditId = "aud_b_1",
                familyId = famB,
                actorUserId = "uid_bob",
                entityCollection = "transactions",
                entityId = "rec_b_1",
                action = AuditAction.CREATE.name,
                summary = "Beta expense"
            )
        )

        val alphaRows = store.observeTransactions(famA).first()
        val betaRows = store.observeTransactions(famB).first()
        assertEquals(1, alphaRows.size)
        assertEquals("rec_a_1", alphaRows.first().recordId)
        assertEquals(1, betaRows.size)
        assertEquals("rec_b_1", betaRows.first().recordId)
        assertNotEquals(
            DatabaseIdentityHelper.computeIdempotencyKey(famA, "tx", "food", 10.0, "USD", 100L),
            DatabaseIdentityHelper.computeIdempotencyKey(famB, "tx", "food", 10.0, "USD", 100L)
        )
    }

    @Test
    fun firestorePayloadMap_containsAllRequiredFinancialFieldsAndExcludesDocumentId() {
        val doc = TransactionDoc(
            recordId = "rec_test_01",
            familyId = "fam_test_01",
            createdByUserId = "uid_alice",
            amount = 42.50,
            currency = "usd",
            category = "Utilities",
            description = "Electric bill",
            recordStatus = RecordStatus.ACTIVE.name,
            transactionType = TransactionType.EXPENSE.name,
            paymentMethod = null,
            idempotencyKey = "idem_key_0001",
            syncVersion = 1L,
            memberUids = listOf("uid_alice"),
            adminUids = listOf("uid_alice")
        )
        val map = doc.toCreateMap(System.currentTimeMillis() - 1000L)

        assertFalse(map.containsKey("id"))
        assertFalse(map.containsKey("paymentMethod"))
        assertTrue(map.containsKey("recordId"))
        assertTrue(map.containsKey("familyId"))
        assertTrue(map.containsKey("createdByUserId"))
        assertTrue(map.containsKey("amount"))
        assertEquals("USD", map["currency"])
        assertTrue(map.containsKey("category"))
        assertTrue(map.containsKey("description"))
        assertTrue(map.containsKey("createdAt"))
        assertTrue(map.containsKey("updatedAt"))
        assertEquals("ACTIVE", map["recordStatus"])
    }
}
