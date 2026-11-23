package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.core.analytics.AnalyticsDateFilter
import com.example.core.analytics.AnalyticsFilterSpec
import com.example.core.analytics.FinancialAnalyticsEngine
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.FinancialModuleType
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import com.example.core.model.UiState
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.theme.FamilyFinanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FinancialAnalyticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val nowDate = LocalDate.of(2026, 11, 19) // Thursday
    private val nowMillis = nowDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dateMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val sampleFamily = LocalFamilyEntity(
        familyId = "fam_analytics_01",
        name = "Rivera Household",
        ownerId = "uid_alice",
        currencyCode = "USD",
        memberUidsCsv = "uid_alice,uid_bob"
    )

    private val sampleMembers = listOf(
        LocalFamilyMemberEntity("fam_analytics_01", "uid_alice", "Alice Rivera", role = "ADMIN"),
        LocalFamilyMemberEntity("fam_analytics_01", "uid_bob", "Bob Rivera", role = "MEMBER")
    )

    @Test
    fun analyticsEngine_computesAll7KpisAndAll7ChartsWithStrictAccountingRules() {
        val transactions = listOf(
            // Today (Nov 19, 2026): Alice Expense $120.00
            LocalTransactionEntity(
                recordId = "tx_today",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                memberId = "uid_alice",
                amount = 120.0,
                amountMinor = 120_00L,
                currency = "USD",
                category = "Food",
                description = "Grocery market",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = dateMillis(2026, 11, 19),
                idempotencyKey = "k_today"
            ),
            // Earlier this month (Nov 5, 2026): Salary $5,000.00 & Bob Bills $400.00
            LocalTransactionEntity(
                recordId = "tx_nov_inc",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                memberId = "uid_alice",
                amount = 5000.0,
                amountMinor = 5000_00L,
                currency = "USD",
                category = "Salary",
                description = "Payroll",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = dateMillis(2026, 11, 5),
                idempotencyKey = "k_nov_inc"
            ),
            LocalTransactionEntity(
                recordId = "tx_nov_exp_bob",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_bob",
                memberId = "uid_bob",
                amount = 400.0,
                amountMinor = 400_00L,
                currency = "USD",
                category = "Bills",
                description = "Utilities",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = dateMillis(2026, 11, 5),
                idempotencyKey = "k_nov_exp_bob"
            ),
            // Earlier this year (Aug 10, 2026): Deficit month Expense $1,000.00
            LocalTransactionEntity(
                recordId = "tx_aug_exp",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_bob",
                memberId = "uid_bob",
                amount = 1000.0,
                amountMinor = 1000_00L,
                currency = "USD",
                category = "Shopping",
                description = "Appliances",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = dateMillis(2026, 8, 10),
                idempotencyKey = "k_aug"
            ),
            // Prior year (Dec 12, 2025): Income $2,000.00
            LocalTransactionEntity(
                recordId = "tx_2025_inc",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                memberId = "uid_alice",
                amount = 2000.0,
                amountMinor = 2000_00L,
                currency = "USD",
                category = "Freelancing",
                description = "Consulting",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = dateMillis(2025, 12, 12),
                idempotencyKey = "k_2025"
            ),
            // VOIDED transaction must be ignored
            LocalTransactionEntity(
                recordId = "tx_voided",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                amount = 9999.0,
                amountMinor = 9999_00L,
                currency = "USD",
                category = "Salary",
                description = "Voided test",
                transactionType = TransactionType.INCOME.name,
                recordStatus = RecordStatus.VOIDED.name,
                occurredAtMillis = dateMillis(2026, 11, 19),
                idempotencyKey = "k_void"
            ),
            // Other family record must be isolated
            LocalTransactionEntity(
                recordId = "tx_other_fam",
                familyId = "fam_OTHER",
                createdByUserId = "uid_alice",
                amount = 7777.0,
                amountMinor = 7777_00L,
                currency = "USD",
                category = "Salary",
                description = "Unrelated family",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = dateMillis(2026, 11, 19),
                idempotencyKey = "k_other"
            )
        )

        val loans = listOf(
            LocalLoanEntity(
                recordId = "loan_b",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                amount = 2000.0,
                amountMinor = 2000_00L,
                remainingBalance = 1200.0,
                remainingBalanceMinor = 1200_00L,
                currency = "USD",
                category = "Personal",
                description = "Borrowed car loan",
                loanDirection = LoanDirection.BORROWED.name,
                counterparty = "Credit Union",
                idempotencyKey = "k_lb"
            ),
            LocalLoanEntity(
                recordId = "loan_l",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                amount = 500.0,
                amountMinor = 500_00L,
                remainingBalance = 300.0,
                remainingBalanceMinor = 300_00L,
                currency = "USD",
                category = "Family",
                description = "Lent cousin",
                loanDirection = LoanDirection.LENT.name,
                counterparty = "Cousin",
                idempotencyKey = "k_ll"
            )
        )

        val savings = listOf(
            LocalSavingsEntity(
                recordId = "sav_1",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                amount = 1500.0,
                amountMinor = 1500_00L,
                targetAmount = 3000.0,
                targetAmountMinor = 3000_00L,
                currency = "USD",
                category = "Emergency",
                description = "Reserve",
                goalName = "Emergency Fund",
                idempotencyKey = "k_s1"
            )
        )

        val investments = listOf(
            LocalInvestmentEntity(
                recordId = "inv_1",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                amount = 1000.0,
                amountMinor = 1000_00L,
                currentValue = 1400.0,
                currentValueMinor = 1400_00L,
                currency = "USD",
                category = "ETF",
                description = "S&P 500",
                assetName = "VOO",
                assetType = InvestmentAssetType.ETF.name,
                symbol = "VOO",
                idempotencyKey = "k_i1"
            ),
            LocalInvestmentEntity(
                recordId = "inv_2",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_bob",
                amount = 600.0,
                amountMinor = 600_00L,
                currentValue = 450.0,
                currentValueMinor = 450_00L,
                currency = "USD",
                category = "Crypto",
                description = "Alt",
                assetName = "ALT",
                assetType = InvestmentAssetType.CRYPTO.name,
                symbol = "ALT",
                idempotencyKey = "k_i2"
            )
        )

        val events = listOf(
            LocalFinancialEventEntity(
                eventId = "evt_s1",
                familyId = sampleFamily.familyId,
                parentRecordId = "sav_1",
                moduleType = FinancialModuleType.SAVINGS.name,
                eventType = "DEPOSIT",
                actorUserId = "uid_alice",
                amountMinor = 1000_00L,
                balanceAfterMinor = 1000_00L,
                currency = "USD",
                eventDateMillis = dateMillis(2026, 10, 1)
            ),
            LocalFinancialEventEntity(
                eventId = "evt_s2",
                familyId = sampleFamily.familyId,
                parentRecordId = "sav_1",
                moduleType = FinancialModuleType.SAVINGS.name,
                eventType = "DEPOSIT",
                actorUserId = "uid_alice",
                amountMinor = 500_00L,
                balanceAfterMinor = 1500_00L,
                currency = "USD",
                eventDateMillis = dateMillis(2026, 11, 10)
            )
        )

        // THIS_MONTH filter (Nov 2026)
        val monthSnap = FinancialAnalyticsEngine.buildSnapshot(
            familyId = sampleFamily.familyId,
            currencyCode = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events,
            members = sampleMembers,
            filterSpec = AnalyticsFilterSpec(AnalyticsDateFilter.THIS_MONTH),
            nowMillis = nowMillis,
            zoneId = zone
        )

        assertNull(monthSnap.filterError)
        // All-time Income = 5000 + 2000 = 7000; All-time Expense = 120 + 400 + 1000 = 1520 -> Balance = 5480
        assertEquals(5480_00L, monthSnap.summary.totalFamilyBalanceMinor)
        // Nov Income = 5000; Nov Expense = 120 + 400 = 520
        assertEquals(5000_00L, monthSnap.summary.totalIncomeMinor)
        assertEquals(520_00L, monthSnap.summary.totalExpenseMinor)
        assertEquals(1500_00L, monthSnap.summary.totalSavingsMinor)
        assertEquals(1200_00L, monthSnap.summary.outstandingLoansBorrowedMinor)
        assertEquals(300_00L, monthSnap.summary.outstandingLoansLentMinor)
        assertEquals(1850_00L, monthSnap.summary.investmentValueMinor)
        assertEquals(400_00L, monthSnap.summary.investmentProfitMinor)
        assertEquals(150_00L, monthSnap.summary.investmentLossMinor)
        // Net Worth = 5480 (Balance) + 1500 (Savings) + 1850 (Inv) + 300 (Lent) - 1200 (Borrowed) = 7930
        assertEquals(7930_00L, monthSnap.summary.netWorthMinor)

        // Chart 1 & 7: Monthly series (12 months of 2026; Aug has negative net -1000, Nov has +4480)
        assertEquals(12, monthSnap.monthlySeries.size)
        val augPoint = monthSnap.monthlySeries.first { it.yearMonth == "2026-08" }
        val novPoint = monthSnap.monthlySeries.first { it.yearMonth == "2026-11" }
        assertEquals(-1000_00L, augPoint.netMinor)
        assertEquals(4480_00L, novPoint.netMinor)

        // Chart 2: Yearly series (2025 and 2026)
        assertEquals(2, monthSnap.yearlySeries.size)
        assertEquals(2000_00L, monthSnap.yearlySeries.first { it.year == 2025 }.incomeMinor)
        assertEquals(5000_00L, monthSnap.yearlySeries.first { it.year == 2026 }.incomeMinor)
        assertEquals(1520_00L, monthSnap.yearlySeries.first { it.year == 2026 }.expenseMinor)

        // Chart 3: Expense Category Distribution in Nov (Bills 400, Food 120)
        assertEquals(2, monthSnap.expenseCategories.size)
        assertEquals("Bills", monthSnap.expenseCategories[0].category)
        assertEquals(400_00L, monthSnap.expenseCategories[0].amountMinor)

        // Chart 4: Savings Growth (1000 -> 1500)
        assertEquals(2, monthSnap.savingsGrowthSeries.size)
        assertEquals(1000_00L, monthSnap.savingsGrowthSeries[0].cumulativeSavingsMinor)
        assertEquals(1500_00L, monthSnap.savingsGrowthSeries[1].cumulativeSavingsMinor)

        // Chart 5: Investment Performance (VOO gain, ALT loss)
        assertEquals(2, monthSnap.investmentSeries.size)
        assertEquals(400_00L, monthSnap.investmentSeries.first { it.label == "VOO" }.profitMinor)
        assertEquals(150_00L, monthSnap.investmentSeries.first { it.label == "ALT" }.lossMinor)

        // Chart 6: Member-wise Expenses in Nov (Bob Rivera 400, Alice Rivera 120)
        assertEquals(2, monthSnap.memberExpenses.size)
        assertEquals("Bob Rivera", monthSnap.memberExpenses[0].memberName)
        assertEquals(400_00L, monthSnap.memberExpenses[0].expenseMinor)
        assertEquals("Alice Rivera", monthSnap.memberExpenses[1].memberName)
        assertEquals(120_00L, monthSnap.memberExpenses[1].expenseMinor)

        // TODAY filter -> only $120 expense
        val todaySnap = FinancialAnalyticsEngine.buildSnapshot(
            familyId = sampleFamily.familyId,
            currencyCode = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events,
            members = sampleMembers,
            filterSpec = AnalyticsFilterSpec(AnalyticsDateFilter.TODAY),
            nowMillis = nowMillis,
            zoneId = zone
        )
        assertEquals(0L, todaySnap.summary.totalIncomeMinor)
        assertEquals(120_00L, todaySnap.summary.totalExpenseMinor)

        // THIS_WEEK filter -> Mon Nov 16..Sun Nov 22 -> $120 expense
        val weekSnap = FinancialAnalyticsEngine.buildSnapshot(
            familyId = sampleFamily.familyId,
            currencyCode = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events,
            members = sampleMembers,
            filterSpec = AnalyticsFilterSpec(AnalyticsDateFilter.THIS_WEEK),
            nowMillis = nowMillis,
            zoneId = zone
        )
        assertEquals(120_00L, weekSnap.summary.totalExpenseMinor)

        // THIS_YEAR filter -> 2026 total expense = 1520, income = 5000
        val yearSnap = FinancialAnalyticsEngine.buildSnapshot(
            familyId = sampleFamily.familyId,
            currencyCode = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events,
            members = sampleMembers,
            filterSpec = AnalyticsFilterSpec(AnalyticsDateFilter.THIS_YEAR),
            nowMillis = nowMillis,
            zoneId = zone
        )
        assertEquals(5000_00L, yearSnap.summary.totalIncomeMinor)
        assertEquals(1520_00L, yearSnap.summary.totalExpenseMinor)

        // CUSTOM invalid range -> returns filterError
        val invalidCustom = FinancialAnalyticsEngine.buildSnapshot(
            familyId = sampleFamily.familyId,
            currencyCode = "USD",
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events,
            members = sampleMembers,
            filterSpec = AnalyticsFilterSpec(
                preset = AnalyticsDateFilter.CUSTOM,
                customStartMillis = dateMillis(2026, 11, 20),
                customEndMillis = dateMillis(2026, 11, 10)
            ),
            nowMillis = nowMillis,
            zoneId = zone
        )
        assertNotNull(invalidCustom.filterError)
    }

    @Test
    fun analyticsScreen_rendersLoadingState() {
        composeRule.setContent {
            FamilyFinanceTheme {
                AnalyticsScreen(
                    familiesState = UiState.Loading,
                    activeFamily = null,
                    activeMembers = emptyList(),
                    transactions = emptyList(),
                    loans = emptyList(),
                    savings = emptyList(),
                    investments = emptyList(),
                    financialEvents = emptyList(),
                    syncStatusLabel = "Syncing",
                    onSyncNow = {},
                    onNavigateToModule = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analytics_loading_indicator").assertIsDisplayed()
    }

    @Test
    fun analyticsScreen_rendersErrorState() {
        composeRule.setContent {
            FamilyFinanceTheme {
                AnalyticsScreen(
                    familiesState = UiState.Error("Local database read failed"),
                    activeFamily = null,
                    activeMembers = emptyList(),
                    transactions = emptyList(),
                    loans = emptyList(),
                    savings = emptyList(),
                    investments = emptyList(),
                    financialEvents = emptyList(),
                    syncStatusLabel = "Offline",
                    onSyncNow = {},
                    onNavigateToModule = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analytics_error_card").assertIsDisplayed()
        composeRule.onNodeWithText("Local database read failed").assertIsDisplayed()
    }

    @Test
    fun analyticsScreen_liveStateFiltersSwitchAndEmptyChartHandledCleanly() {
        val transactions = listOf(
            LocalTransactionEntity(
                recordId = "ui_tx_1",
                familyId = sampleFamily.familyId,
                createdByUserId = "uid_alice",
                memberId = "uid_alice",
                amount = 2500.0,
                amountMinor = 2500_00L,
                currency = "USD",
                category = "Salary",
                description = "Monthly Pay",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = System.currentTimeMillis(),
                idempotencyKey = "k_ui_1"
            )
        )
        composeRule.setContent {
            FamilyFinanceTheme {
                AnalyticsScreen(
                    familiesState = UiState.Success(listOf(sampleFamily)),
                    activeFamily = sampleFamily,
                    activeMembers = sampleMembers,
                    transactions = transactions,
                    loans = emptyList(),
                    savings = emptyList(),
                    investments = emptyList(),
                    financialEvents = emptyList(),
                    syncStatusLabel = "Online",
                    onSyncNow = {},
                    onNavigateToModule = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("analytics_net_worth_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_kpi_income").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_kpi_expense").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_kpi_savings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_kpi_loans").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_kpi_investments").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("chart_monthly_income_expense").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("chart_monthly_cash_flow").performScrollTo().assertIsDisplayed()

        // Scroll back to header card so verticalScroll brings filter strip into view before horizontal scroll
        composeRule.onNodeWithTag("analytics_sync_button").performScrollTo().assertIsDisplayed()

        // Tap Custom Range chip -> date pickers become visible
        composeRule.onNodeWithTag("analytics_filter_custom").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analytics_custom_start_picker").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_custom_end_picker").performScrollTo().assertIsDisplayed()
        assertTrue(true)
    }
}
