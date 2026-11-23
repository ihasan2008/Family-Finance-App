package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.BudgetScope
import com.example.core.model.FinancialModuleType
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.BudgetAlertLevel
import com.example.ui.screens.modules.BudgetSummaryCalculator
import com.example.ui.screens.modules.InvestmentsSummaryCalculator
import com.example.ui.screens.modules.LoansSummaryCalculator
import com.example.ui.screens.modules.SavingsSummaryCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class FinancialModulesTest {

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
    fun loansSummary_andPendingDeleteGuard_preserveAccountingSeparationAndRepaymentHistory() = runTest {
        val famId = "fam_loans_01"
        val now = LocalDate.of(2026, 11, 18).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = LocalDate.of(2026, 11, 17).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val borrowed = LocalLoanEntity(
            recordId = "loan_1",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 1000.0,
            amountMinor = 1000_00L,
            remainingBalance = 600.0,
            remainingBalanceMinor = 600_00L,
            currency = "USD",
            category = "Personal Loan",
            description = "Emergency car repair",
            recordStatus = RecordStatus.ACTIVE.name,
            loanDirection = LoanDirection.BORROWED.name,
            counterparty = "Credit Union",
            borrowerName = "Alice",
            lenderName = "Credit Union",
            totalInstallments = 5,
            paidInstallments = 2,
            installmentAmountMinor = 200_00L,
            dueDateMillis = yesterday,
            idempotencyKey = "idem_loan_1"
        )
        val lentSettled = LocalLoanEntity(
            recordId = "loan_2",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 500.0,
            amountMinor = 500_00L,
            remainingBalance = 0.0,
            remainingBalanceMinor = 0L,
            currency = "USD",
            category = "Family Support",
            description = "Sister tuition advance",
            recordStatus = RecordStatus.SETTLED.name,
            loanDirection = LoanDirection.LENT.name,
            counterparty = "Maya",
            borrowerName = "Maya",
            lenderName = "Family Reserve",
            totalInstallments = 2,
            paidInstallments = 2,
            installmentAmountMinor = 250_00L,
            dueDateMillis = yesterday,
            idempotencyKey = "idem_loan_2"
        )

        store.saveLoanIdempotent(borrowed, audit(famId, "loans", "loan_1", AuditAction.CREATE))
        store.saveLoanIdempotent(lentSettled, audit(famId, "loans", "loan_2", AuditAction.CREATE))
        store.saveFinancialEventWithOutbox(
            LocalFinancialEventEntity(
                eventId = "evt_repay_1",
                familyId = famId,
                parentRecordId = "loan_1",
                moduleType = FinancialModuleType.LOANS.name,
                eventType = "REPAYMENT",
                actorUserId = "uid_alice",
                amountMinor = 200_00L,
                balanceAfterMinor = 600_00L,
                currency = "USD",
                note = "Installment #2",
                eventDateMillis = now
            )
        )

        val summary = LoansSummaryCalculator.summarize(store.observeLoans(famId).first(), now)
        assertEquals(1000_00L, summary.totalBorrowedMinor)
        assertEquals(600_00L, summary.remainingBorrowedMinor)
        assertEquals(500_00L, summary.totalLentMinor)
        assertEquals(0L, summary.remainingLentMinor)
        assertEquals(900_00L, summary.totalRepaidMinor)
        assertEquals(1, summary.activeCount)
        assertEquals(1, summary.settledCount)
        assertEquals(1, summary.overdueCount)

        // Loan principal and repayment NEVER pollute Income/Expense transactions
        assertTrue(store.observeTransactions(famId).first().isEmpty())
        assertEquals(1, store.observeFinancialEvents(famId).first().size)

        // Mark loan_1 synced first, then delete locally -> pending delete guard blocks stale remote snapshot resurrection
        store.markOutboxSynced("loans", "loan_1", now)
        assertTrue(store.deleteLoanLocal("loan_1", famId, "uid_alice", audit(famId, "loans", "loan_1", AuditAction.DELETE)))
        store.reconcileRemoteLoans(famId, listOf(borrowed.copy(syncStatus = SyncState.SYNCED.name)))
        val afterReconcile = store.observeLoans(famId).first()
        assertEquals(1, afterReconcile.size)
        assertEquals("loan_2", afterReconcile.first().recordId)
    }

    @Test
    fun savingsSummary_tracksGoalMilestonesAndZeroIncomeInflation() = runTest {
        val famId = "fam_sav_01"
        val goal1 = LocalSavingsEntity(
            recordId = "sav_1",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 750.0,
            amountMinor = 750_00L,
            targetAmount = 1000.0,
            targetAmountMinor = 1000_00L,
            currency = "USD",
            category = "Emergency Fund",
            description = "6 month reserve",
            goalName = "Emergency Buffer",
            idempotencyKey = "idem_sav_1"
        )
        val goal2 = LocalSavingsEntity(
            recordId = "sav_2",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 500.0,
            amountMinor = 500_00L,
            targetAmount = 500.0,
            targetAmountMinor = 500_00L,
            currency = "USD",
            category = "Education",
            description = "Books",
            goalName = "College Books",
            recordStatus = RecordStatus.SETTLED.name,
            idempotencyKey = "idem_sav_2"
        )

        store.saveSavingsIdempotent(goal1, audit(famId, "savings", "sav_1", AuditAction.CREATE))
        store.saveSavingsIdempotent(goal2, audit(famId, "savings", "sav_2", AuditAction.CREATE))

        val summary = SavingsSummaryCalculator.summarize(store.observeSavings(famId).first())
        assertEquals(1250_00L, summary.totalSavedMinor)
        assertEquals(1500_00L, summary.totalTargetMinor)
        assertEquals(250_00L, summary.remainingToTargetMinor)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.activeCount)
        assertTrue(store.observeTransactions(famId).first().isEmpty())

        store.markOutboxSynced("savings", "sav_1", System.currentTimeMillis())
        assertTrue(store.deleteSavingsLocal("sav_1", famId, "uid_alice", audit(famId, "savings", "sav_1", AuditAction.DELETE)))
        store.reconcileRemoteSavings(famId, listOf(goal1.copy(syncStatus = SyncState.SYNCED.name)))
        assertEquals(1, store.observeSavings(famId).first().size)
    }

    @Test
    fun investmentsSummary_computesProfitAndLossSeparatelyFromPrincipal() = runTest {
        val famId = "fam_inv_01"
        val winner = LocalInvestmentEntity(
            recordId = "inv_win",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 1000.0,
            amountMinor = 1000_00L,
            currentValue = 1500.0,
            currentValueMinor = 1500_00L,
            currency = "USD",
            category = "Investment",
            description = "Index ETF",
            assetName = "VTI Index ETF",
            assetType = InvestmentAssetType.ETF.name,
            symbol = "VTI",
            idempotencyKey = "idem_inv_win"
        )
        val loser = LocalInvestmentEntity(
            recordId = "inv_lose",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 800.0,
            amountMinor = 800_00L,
            currentValue = 600.0,
            currentValueMinor = 600_00L,
            currency = "USD",
            category = "Investment",
            description = "Bond fund",
            assetName = "Treasury Bond Fund",
            assetType = InvestmentAssetType.BOND.name,
            symbol = "TLT",
            idempotencyKey = "idem_inv_lose"
        )

        store.saveInvestmentIdempotent(winner, audit(famId, "investments", "inv_win", AuditAction.CREATE))
        store.saveInvestmentIdempotent(loser, audit(famId, "investments", "inv_lose", AuditAction.CREATE))

        val summary = InvestmentsSummaryCalculator.summarize(store.observeInvestments(famId).first())
        assertEquals(1800_00L, summary.totalPrincipalMinor)
        assertEquals(2100_00L, summary.totalCurrentValueMinor)
        assertEquals(500_00L, summary.totalProfitMinor)
        assertEquals(200_00L, summary.totalLossMinor)
        assertEquals(300_00L, summary.netGainOrLossMinor)

        store.markOutboxSynced("investments", "inv_lose", System.currentTimeMillis())
        assertTrue(store.deleteInvestmentLocal("inv_lose", famId, "uid_alice", audit(famId, "investments", "inv_lose", AuditAction.DELETE)))
        store.reconcileRemoteInvestments(famId, listOf(loser.copy(syncStatus = SyncState.SYNCED.name)))
        assertEquals(1, store.observeInvestments(famId).first().size)
    }

    @Test
    fun budgetsSummary_computesLiveExpenseSpendTriggersAlertsAndAvoidsDoubleCounting() = runTest {
        val famId = "fam_bud_01"
        val zone = ZoneId.systemDefault()
        val novMillis = LocalDate.of(2026, 11, 10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val overallBudget = LocalBudgetEntity(
            recordId = "bud_overall",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 2000.0,
            amountMinor = 2000_00L,
            alertThresholdPercent = 80,
            currency = "USD",
            category = "All Categories",
            description = "Family monthly cap",
            periodKey = "2026-11",
            budgetScope = BudgetScope.MONTHLY_OVERALL.name,
            idempotencyKey = "idem_bud_overall"
        )
        val foodBudget = LocalBudgetEntity(
            recordId = "bud_food",
            familyId = famId,
            createdByUserId = "uid_alice",
            amount = 500.0,
            amountMinor = 500_00L,
            alertThresholdPercent = 80,
            currency = "USD",
            category = "Food",
            description = "Groceries limit",
            periodKey = "2026-11",
            budgetScope = BudgetScope.CATEGORY.name,
            idempotencyKey = "idem_bud_food"
        )
        store.saveBudgetIdempotent(overallBudget, audit(famId, "budgets", "bud_overall", AuditAction.CREATE))
        store.saveBudgetIdempotent(foodBudget, audit(famId, "budgets", "bud_food", AuditAction.CREATE))

        val transactions = listOf(
            LocalTransactionEntity(
                recordId = "tx_income_1",
                familyId = famId,
                createdByUserId = "uid_alice",
                amount = 5000.0,
                amountMinor = 5000_00L,
                currency = "USD",
                category = "Salary",
                description = "Payroll",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = novMillis,
                idempotencyKey = "k_inc"
            ),
            LocalTransactionEntity(
                recordId = "tx_exp_food",
                familyId = famId,
                createdByUserId = "uid_alice",
                amount = 420.0,
                amountMinor = 420_00L,
                currency = "USD",
                category = "Food",
                description = "Grocery shopping",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = novMillis,
                idempotencyKey = "k_food"
            ),
            LocalTransactionEntity(
                recordId = "tx_exp_bills",
                familyId = famId,
                createdByUserId = "uid_alice",
                amount = 300.0,
                amountMinor = 300_00L,
                currency = "USD",
                category = "Bills",
                description = "Electric + Internet",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = novMillis,
                idempotencyKey = "k_bills"
            )
        )

        val summary = BudgetSummaryCalculator.summarizeForPeriod(
            budgets = store.observeBudgets(famId).first(),
            expenseTransactions = transactions,
            periodKey = "2026-11"
        )

        // Total month expense counts ONLY EXPENSE rows (420 + 300 = 720), ignoring Salary
        assertEquals(720_00L, summary.totalMonthExpenseMinor)
        assertNotNull(summary.overallBudget)
        assertEquals(720_00L, summary.overallBudget!!.spentMinor)
        assertEquals(1280_00L, summary.overallBudget!!.remainingMinor)
        assertEquals(BudgetAlertLevel.OK, summary.overallBudget!!.alertLevel)

        // Category allocations kept separate from Monthly Overall limit (500, NOT 2500)
        assertEquals(500_00L, summary.totalCategoryAllocatedMinor)
        assertEquals(1, summary.categoryBudgets.size)
        val foodEval = summary.categoryBudgets.first()
        assertEquals(420_00L, foodEval.spentMinor)
        assertEquals(80_00L, foodEval.remainingMinor)
        assertEquals(84, foodEval.usagePercent)
        assertEquals(BudgetAlertLevel.WARNING, foodEval.alertLevel)
        assertEquals(1, summary.warningCount)
        assertEquals(0, summary.exceededCount)

        val history = BudgetSummaryCalculator.buildHistory(store.observeBudgets(famId).first(), transactions)
        assertEquals(1, history.size)
        assertEquals(2000_00L, history.first().overallLimitMinor)

        val health = store.observeDatabaseHealth(famId, "uid_alice").first()
        assertEquals(2, health.budgetsCount)
    }

    private fun audit(
        familyId: String,
        collection: String,
        entityId: String,
        action: AuditAction
    ): LocalAuditLogEntity = LocalAuditLogEntity(
        auditId = "aud_${collection}_${entityId}_${action.name}",
        familyId = familyId,
        actorUserId = "uid_alice",
        entityCollection = collection,
        entityId = entityId,
        action = action.name,
        summary = "${action.name} $entityId"
    )
}
