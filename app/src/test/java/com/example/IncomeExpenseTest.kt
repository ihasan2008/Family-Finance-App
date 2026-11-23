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
import com.example.core.model.DateFilterPreset
import com.example.core.model.ExpenseCategory
import com.example.core.model.IncomeCategory
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecurrenceScheduleHelper
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.ui.screens.transactions.TransactionFilterEngine
import com.example.ui.screens.transactions.TransactionFilterState
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
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomeExpenseTest {

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
    fun moneyCalculator_avoidsFloatingPointDriftAndValidatesPrecision() {
        val a = MoneyCalculator.parseDecimalToMinorUnits("0.10", "USD").getOrThrow()
        val b = MoneyCalculator.parseDecimalToMinorUnits("0.20", "USD").getOrThrow()
        val sum = MoneyCalculator.sumMinorUnits(listOf(a, b))

        assertEquals(10L, a)
        assertEquals(20L, b)
        assertEquals(30L, sum)
        assertEquals("$0.30", MoneyCalculator.formatMinorUnits(sum, "USD"))

        // Reject zero, negative, excessive decimals, and JPY decimals
        assertTrue(MoneyCalculator.parseDecimalToMinorUnits("0", "USD").isFailure)
        assertTrue(MoneyCalculator.parseDecimalToMinorUnits("-12.50", "USD").isFailure)
        assertTrue(MoneyCalculator.parseDecimalToMinorUnits("12.345", "USD").isFailure)
        assertTrue(MoneyCalculator.parseDecimalToMinorUnits("100.5", "JPY").isFailure)
        assertEquals(1500L, MoneyCalculator.parseDecimalToMinorUnits("1500", "JPY").getOrThrow())
    }

    @Test
    fun categoryLists_matchPrompt07Specifications() {
        assertEquals(
            listOf("Salary", "Business", "Freelancing", "Gifts", "Rental Income", "Other Income"),
            IncomeCategory.allNames
        )
        assertEquals(
            listOf(
                "Food",
                "Shopping",
                "Education",
                "Medical",
                "Transportation",
                "Housing",
                "Bills",
                "Entertainment",
                "Other Expenses"
            ),
            ExpenseCategory.allNames
        )
    }

    @Test
    fun recurrenceScheduleHelper_handlesCalendarBoundariesCorrectly() {
        val zone = ZoneId.of("UTC")
        val jan31 = LocalDate.of(2025, 1, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthly = RecurrenceScheduleHelper.computeNextOccurrenceMillis(
            baseMillis = jan31,
            frequency = RecurrenceFrequency.MONTHLY,
            zoneId = zone
        )
        assertNotNull(nextMonthly)
        val febDate = java.time.Instant.ofEpochMilli(nextMonthly!!).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2025, 2, 28), febDate)

        assertNull(RecurrenceScheduleHelper.computeNextOccurrenceMillis(jan31, RecurrenceFrequency.NONE, zone))
    }

    @Test
    fun transactionFilterEngine_filtersSearchCategoryDateMemberAndSumsExactMinorUnits() {
        val zone = ZoneId.of("UTC")
        val now = LocalDate.of(2026, 11, 18).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lastMonth = LocalDate.of(2026, 10, 15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val tx1 = LocalTransactionEntity(
            recordId = "rec_inc_1",
            familyId = "fam_1",
            createdByUserId = "uid_alice",
            memberId = "uid_alice",
            amount = 5200.00,
            amountMinor = 520000L,
            currency = "USD",
            category = "Salary",
            description = "November payroll deposit",
            transactionType = TransactionType.INCOME.name,
            occurredAtMillis = now,
            idempotencyKey = "k1"
        )
        val tx2 = LocalTransactionEntity(
            recordId = "rec_inc_2",
            familyId = "fam_1",
            createdByUserId = "uid_bob",
            memberId = "uid_bob",
            amount = 750.25,
            amountMinor = 75025L,
            currency = "USD",
            category = "Freelancing",
            description = "UI design sprint",
            transactionType = TransactionType.INCOME.name,
            occurredAtMillis = lastMonth,
            idempotencyKey = "k2"
        )

        val result = TransactionFilterEngine.filterAndSummarize(
            transactions = listOf(tx1, tx2),
            filterState = TransactionFilterState(
                searchQuery = "alice",
                selectedCategory = "Salary",
                datePreset = DateFilterPreset.THIS_MONTH,
                selectedMemberId = "uid_alice"
            ),
            memberNameLookup = mapOf("uid_alice" to "Alice", "uid_bob" to "Bob"),
            currencyCode = "USD",
            nowMillis = now,
            zoneId = zone
        )

        assertEquals(1, result.filteredTransactions.size)
        assertEquals("rec_inc_1", result.filteredTransactions.first().recordId)
        assertEquals(520000L, result.filteredTotalMinor)
        assertEquals(520000L, result.thisMonthMinor)
        assertEquals(1, result.categoryBreakdown.size)
        assertEquals(1f, result.categoryBreakdown.first().share, 0.001f)
    }

    @Test
    fun updateDeleteAndPendingDeleteGuard_preventResurrectionAndTrackAuditLogs() = runTest {
        val famId = "fam_tx_crud"
        val tx = LocalTransactionEntity(
            recordId = "rec_crud_1",
            familyId = famId,
            createdByUserId = "uid_alice",
            memberId = "uid_alice",
            amount = 45.99,
            amountMinor = 4599L,
            currency = "USD",
            category = "Food",
            description = "Organic market",
            transactionType = TransactionType.EXPENSE.name,
            recurrenceFrequency = RecurrenceFrequency.WEEKLY.name,
            nextRecurrenceAtMillis = 1763500000000L,
            isRecurringTemplate = true,
            idempotencyKey = "idem_crud_1"
        )

        val createRes = store.saveTransactionIdempotent(
            tx,
            LocalAuditLogEntity(
                auditId = "aud_create_1",
                familyId = famId,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = tx.recordId,
                action = AuditAction.CREATE.name,
                summary = "Created Food expense"
            )
        )
        assertFalse(createRes.deduplicatedExisting)
        assertEquals(1, store.getActiveRecurringTemplates(famId).size)

        // Simulate initial create sync before updating & deleting synced record
        store.markOutboxSynced("transactions", tx.recordId, System.currentTimeMillis())
        val syncedTx = store.getTransactionById(tx.recordId)!!

        // Update existing synced record -> queues UPDATE operation
        val updatedRes = store.saveTransactionIdempotent(
            syncedTx.copy(
                amount = 49.50,
                amountMinor = 4950L,
                description = "Organic market + bakery",
                updatedAtMillis = syncedTx.updatedAtMillis + 5000L
            ),
            LocalAuditLogEntity(
                auditId = "aud_update_1",
                familyId = famId,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = tx.recordId,
                action = AuditAction.UPDATE.name,
                summary = "Updated Food expense"
            )
        )
        assertFalse(updatedRes.deduplicatedExisting)
        assertEquals(2, updatedRes.record.syncVersion)
        assertEquals(4950L, store.getTransactionById(tx.recordId)?.effectiveAmountMinor())
        val outboxAfterUpdate = store.getPendingOutboxForFamily(famId)
        assertEquals(1, outboxAfterUpdate.size)
        assertEquals("UPDATE", outboxAfterUpdate.first().operation)

        // Delete locally -> queued as DELETE in outbox + audit entry recorded
        val deleted = store.deleteTransactionLocal(
            recordId = tx.recordId,
            familyId = famId,
            actorUserId = "uid_alice",
            auditEntry = LocalAuditLogEntity(
                auditId = "aud_delete_1",
                familyId = famId,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = tx.recordId,
                action = AuditAction.DELETE.name,
                summary = "Deleted Food expense"
            )
        )
        assertTrue(deleted)
        assertTrue(store.observeTransactions(famId).first().isEmpty())

        // Stale remote snapshot emission MUST NOT resurrect the deleted transaction while DELETE outbox is pending
        store.reconcileRemoteTransactions(famId, listOf(updatedRes.record.copy(syncStatus = SyncState.SYNCED.name)))
        assertTrue(store.observeTransactions(famId).first().isEmpty())

        // Once DELETE outbox is marked synced, pending outbox is clean
        store.markOutboxSynced("transactions", tx.recordId, System.currentTimeMillis())
        assertEquals(0, store.getPendingOutboxForFamily(famId).size)
        val audits = store.observeAuditLogs(famId).first()
        assertTrue(audits.any { it.action == AuditAction.DELETE.name })
    }

    @Test
    fun recurringOccurrenceKeys_areDeterministicAcrossDevices() {
        val keyA = DatabaseIdentityHelper.computeRecurringOccurrenceKey(
            familyId = "fam_01",
            parentRecordId = "rec_parent_01",
            occurrenceDateMillis = 1763510400000L
        )
        val keyB = DatabaseIdentityHelper.computeRecurringOccurrenceKey(
            familyId = "fam_01",
            parentRecordId = "rec_parent_01",
            occurrenceDateMillis = 1763510400000L
        )
        assertEquals(keyA, keyB)
        assertEquals(
            DatabaseIdentityHelper.generateDeterministicRecurringRecordId(keyA),
            DatabaseIdentityHelper.generateDeterministicRecurringRecordId(keyB)
        )
    }
}
