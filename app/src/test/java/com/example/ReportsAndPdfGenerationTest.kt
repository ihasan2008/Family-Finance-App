package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.core.analytics.AnalyticsDateFilter
import com.example.core.analytics.AnalyticsFilterSpec
import com.example.core.analytics.FinancialAnalyticsEngine
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.TransactionType
import com.example.core.reports.FamilyReportPdfDocumentBuilder
import com.example.core.reports.FinancialReportEngine
import com.example.core.reports.PdfBlock
import com.example.core.reports.ReportFilterSpec
import com.example.core.reports.ReportPeriodScope
import com.example.core.reports.ReportType
import com.example.ui.screens.modules.ReportsScreen
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
class ReportsAndPdfGenerationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()

    private fun dateMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val anchorMillis = dateMillis(2026, 11, 19)

    private val bdtFamily = LocalFamilyEntity(
        familyId = "fam_dhaka_01",
        name = "Rahman Family",
        ownerId = "uid_tariq",
        currencyCode = "BDT",
        memberUidsCsv = "uid_tariq,uid_nadia"
    )

    private val members = listOf(
        LocalFamilyMemberEntity("fam_dhaka_01", "uid_tariq", "Tariq Rahman", role = "ADMIN"),
        LocalFamilyMemberEntity("fam_dhaka_01", "uid_nadia", "Nadia Rahman", role = "MEMBER")
    )

    private val sampleTransactions = listOf(
        LocalTransactionEntity(
            recordId = "tx_sal",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            memberId = "uid_tariq",
            amount = 85000.0,
            amountMinor = 85000_00L,
            currency = "BDT",
            category = "Salary",
            description = "Monthly Payroll",
            transactionType = TransactionType.INCOME.name,
            occurredAtMillis = dateMillis(2026, 11, 2),
            idempotencyKey = "k_sal"
        ),
        LocalTransactionEntity(
            recordId = "tx_food",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_nadia",
            memberId = "uid_nadia",
            amount = 12450.50,
            amountMinor = 12450_50L,
            currency = "BDT",
            category = "Food",
            description = "Fresh Groceries",
            transactionType = TransactionType.EXPENSE.name,
            occurredAtMillis = dateMillis(2026, 11, 19),
            idempotencyKey = "k_food"
        ),
        LocalTransactionEntity(
            recordId = "tx_bills",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            memberId = "uid_tariq",
            amount = 4500.0,
            amountMinor = 4500_00L,
            currency = "BDT",
            category = "Bills",
            description = "DESCO Electricity",
            transactionType = TransactionType.EXPENSE.name,
            occurredAtMillis = dateMillis(2026, 11, 10),
            idempotencyKey = "k_bills"
        )
    )

    private val sampleLoans = listOf(
        LocalLoanEntity(
            recordId = "loan_1",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            amount = 50000.0,
            amountMinor = 50000_00L,
            remainingBalance = 30000.0,
            remainingBalanceMinor = 30000_00L,
            currency = "BDT",
            category = "Personal",
            description = "Bank Loan",
            loanDirection = LoanDirection.BORROWED.name,
            counterparty = "City Bank",
            totalInstallments = 10,
            paidInstallments = 4,
            idempotencyKey = "k_loan_1"
        )
    )

    private val sampleSavings = listOf(
        LocalSavingsEntity(
            recordId = "sav_1",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_nadia",
            amount = 40000.0,
            amountMinor = 40000_00L,
            targetAmount = 100000.0,
            targetAmountMinor = 100000_00L,
            currency = "BDT",
            category = "Emergency Fund",
            description = "Household emergency reserve",
            goalName = "Emergency Cushion",
            idempotencyKey = "k_sav_1"
        )
    )

    private val sampleInvestments = listOf(
        LocalInvestmentEntity(
            recordId = "inv_win",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            amount = 20000.0,
            amountMinor = 20000_00L,
            currentValue = 24500.0,
            currentValueMinor = 24500_00L,
            currency = "BDT",
            category = "Mutual Fund",
            description = "Equity index fund",
            assetName = "Shariah Index Fund",
            assetType = InvestmentAssetType.MUTUAL_FUND.name,
            idempotencyKey = "k_inv_win"
        ),
        LocalInvestmentEntity(
            recordId = "inv_loss",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            amount = 10000.0,
            amountMinor = 10000_00L,
            currentValue = 8500.0,
            currentValueMinor = 8500_00L,
            currency = "BDT",
            category = "Stock",
            description = "Tech note",
            assetName = "Tech Growth Note",
            assetType = InvestmentAssetType.STOCK.name,
            idempotencyKey = "k_inv_loss"
        )
    )

    private val sampleBudgets = listOf(
        LocalBudgetEntity(
            recordId = "bud_food",
            familyId = "fam_dhaka_01",
            createdByUserId = "uid_tariq",
            amount = 15000.0,
            amountMinor = 15000_00L,
            currency = "BDT",
            category = "Food",
            description = "Monthly grocery budget",
            periodKey = "2026-11",
            idempotencyKey = "k_bud_food"
        )
    )

    private val sampleEvents = listOf(
        LocalFinancialEventEntity(
            eventId = "evt_loan_repay_1",
            familyId = "fam_dhaka_01",
            parentRecordId = "loan_1",
            moduleType = "LOAN",
            eventType = "INSTALLMENT_PAYMENT",
            actorUserId = "uid_tariq",
            amountMinor = 5000_00L,
            balanceAfterMinor = 30000_00L,
            currency = "BDT",
            note = "City Bank EMI #4",
            eventDateMillis = dateMillis(2026, 11, 15)
        ),
        LocalFinancialEventEntity(
            eventId = "evt_sav_dep_1",
            familyId = "fam_dhaka_01",
            parentRecordId = "sav_1",
            moduleType = "SAVINGS",
            eventType = "DEPOSIT",
            actorUserId = "uid_nadia",
            amountMinor = 10000_00L,
            balanceAfterMinor = 40000_00L,
            currency = "BDT",
            note = "Monthly reserve transfer",
            eventDateMillis = dateMillis(2026, 11, 16)
        )
    )

    @Test
    fun moneyCalculator_formatsBangladeshiTakaAndPdfFallbackCorrectly() {
        assertEquals("৳", MoneyCalculator.currencySymbol("BDT"))
        assertEquals("৳1,250.50", MoneyCalculator.formatMinorUnits(1250_50L, "BDT"))
        assertEquals("+৳1,250.50", MoneyCalculator.formatMinorUnits(1250_50L, "BDT", "+"))
        assertEquals("-৳500.00", MoneyCalculator.formatMinorUnits(-500_00L, "BDT"))
        assertEquals("৳1,250.50", MoneyCalculator.formatForPdf(1250_50L, "BDT", supportsTakaGlyph = true))
        assertEquals("BDT 1,250.50", MoneyCalculator.formatForPdf(1250_50L, "BDT", supportsTakaGlyph = false))
    }

    @Test
    fun reportEngine_coversAll11TypesAndMatchesDashboardTotalsExactly() {
        assertEquals(11, ReportType.entries.size)

        val dashboardSnapshot = FinancialAnalyticsEngine.buildSnapshot(
            familyId = bdtFamily.familyId,
            currencyCode = "BDT",
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            financialEvents = emptyList(),
            members = members,
            filterSpec = AnalyticsFilterSpec(preset = AnalyticsDateFilter.THIS_MONTH),
            nowMillis = anchorMillis,
            zoneId = zone
        )

        val monthlyReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = emptyList(),
            filterSpec = ReportFilterSpec(
                reportType = ReportType.MONTHLY,
                periodScope = ReportPeriodScope.THIS_MONTH,
                anchorMillis = anchorMillis
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )

        assertNull(monthlyReport.filterError)
        assertEquals(dashboardSnapshot.summary.totalIncomeMinor, monthlyReport.totalIncomeMinor)
        assertEquals(dashboardSnapshot.summary.totalExpenseMinor, monthlyReport.totalExpenseMinor)
        assertEquals(dashboardSnapshot.summary.periodNetCashFlowMinor, monthlyReport.netCashFlowMinor)
        assertEquals(dashboardSnapshot.summary.netWorthMinor, monthlyReport.summary.netWorthMinor)

        // Investment profit (4,500 BDT) and loss (1,500 BDT) must remain strictly separated
        assertEquals(4500_00L, monthlyReport.investmentsSummary.totalProfitMinor)
        assertEquals(1500_00L, monthlyReport.investmentsSummary.totalLossMinor)

        // Category filter narrows transactions and recalculates totals via MoneyCalculator
        val foodOnlyReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = emptyList(),
            filterSpec = ReportFilterSpec(
                reportType = ReportType.EXPENSE,
                periodScope = ReportPeriodScope.THIS_MONTH,
                anchorMillis = anchorMillis,
                selectedCategories = setOf("Food")
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )
        assertEquals(12450_50L, foodOnlyReport.totalExpenseMinor)
        assertEquals(0L, foodOnlyReport.totalIncomeMinor)
        assertEquals(1, foodOnlyReport.transactions.size)

        // Invalid custom date range sets error and clears period transactions
        val invalidRangeReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = emptyList(),
            filterSpec = ReportFilterSpec(
                reportType = ReportType.CUSTOM_RANGE,
                periodScope = ReportPeriodScope.CUSTOM,
                anchorMillis = anchorMillis,
                customStartMillis = dateMillis(2026, 11, 20),
                customEndMillis = dateMillis(2026, 11, 5)
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )
        assertNotNull(invalidRangeReport.filterError)
        assertTrue(invalidRangeReport.transactions.isEmpty())
    }

    @Test
    fun largeReport_paginatesAll72RowsAcrossMultiplePagesAndBuildsValidPdfBytes() {
        val largeTransactions = (1..72).map { idx ->
            LocalTransactionEntity(
                recordId = "tx_bulk_$idx",
                familyId = bdtFamily.familyId,
                createdByUserId = if (idx % 2 == 0) "uid_tariq" else "uid_nadia",
                memberId = if (idx % 2 == 0) "uid_tariq" else "uid_nadia",
                amount = 150.0 + idx,
                amountMinor = (150L + idx) * 100L,
                currency = "BDT",
                category = if (idx % 5 == 0) "Salary" else "Food",
                description = "Household entry #$idx",
                transactionType = if (idx % 5 == 0) TransactionType.INCOME.name else TransactionType.EXPENSE.name,
                occurredAtMillis = anchorMillis - (idx * 3600_000L),
                idempotencyKey = "k_bulk_$idx"
            )
        }

        val largeReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = largeTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = emptyList(),
            filterSpec = ReportFilterSpec(
                reportType = ReportType.COMPLETE_STATEMENT,
                periodScope = ReportPeriodScope.ALL_TIME,
                anchorMillis = anchorMillis
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )

        val plannedPages = FamilyReportPdfDocumentBuilder.planPages(largeReport)
        assertTrue("Expected multi-page pagination for 72 rows", plannedPages.size >= 3)

        val txChunks = plannedPages.flatMap { it.blocks }
            .filterIsInstance<PdfBlock.TransactionTableChunk>()
        assertTrue("Expected multiple transaction table chunks", txChunks.size >= 2)
        assertTrue(txChunks.drop(1).all { it.isContinuation })
        assertEquals(0, txChunks.first().startIndex)
        assertEquals(72, txChunks.last().endExclusive)

        val pdfResult = FamilyReportPdfDocumentBuilder.generatePdfBytes(largeReport)
        assertEquals(plannedPages.size, pdfResult.pageCount)
        assertEquals(72, pdfResult.transactionRowCount)
        assertTrue(pdfResult.suggestedFileName.startsWith("FamilyFinance_Rahman_Family_Complete_Statement"))
        val header = pdfResult.bytes.copyOfRange(0, 7).decodeToString()
        assertTrue("Expected real PDF file header, got $header", header.startsWith("%PDF-1."))
        val pdfText = pdfResult.bytes.toString(Charsets.ISO_8859_1)
        assertTrue("Expected PDF EOF marker", pdfText.contains("%%EOF"))

        // Verify specialized reports (LOAN, SAVINGS, INVESTMENT, YEARLY) and Complete Statement include charts, module ledgers & events
        val completeReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = sampleEvents,
            filterSpec = ReportFilterSpec(
                reportType = ReportType.COMPLETE_STATEMENT,
                periodScope = ReportPeriodScope.THIS_MONTH,
                anchorMillis = anchorMillis
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )
        val completeBlocks = FamilyReportPdfDocumentBuilder.planPages(completeReport).flatMap { it.blocks }
        assertTrue(completeBlocks.any { it is PdfBlock.CashFlowChart })
        assertTrue(completeBlocks.any { it is PdfBlock.CategoryChart })
        assertTrue(completeBlocks.any { it is PdfBlock.MemberExpenseChart })
        assertTrue(completeBlocks.any { it is PdfBlock.LoanTableChunk })
        assertTrue(completeBlocks.any { it is PdfBlock.SavingsTableChunk })
        assertTrue(completeBlocks.any { it is PdfBlock.InvestmentTableChunk })
        assertTrue(completeBlocks.any { it is PdfBlock.BudgetTableChunk })
        assertTrue(completeBlocks.any { it is PdfBlock.FinancialEventTableChunk })
        assertTrue(completeBlocks.any { it is PdfBlock.TransactionTableChunk })

        val loanReport = FinancialReportEngine.buildReport(
            family = bdtFamily,
            members = members,
            transactions = sampleTransactions,
            loans = sampleLoans,
            savings = sampleSavings,
            investments = sampleInvestments,
            budgets = sampleBudgets,
            financialEvents = sampleEvents,
            filterSpec = ReportFilterSpec(
                reportType = ReportType.LOAN,
                periodScope = ReportPeriodScope.THIS_MONTH,
                anchorMillis = anchorMillis
            ),
            generatedAtMillis = anchorMillis,
            zoneId = zone
        )
        val loanBlocks = FamilyReportPdfDocumentBuilder.planPages(loanReport).flatMap { it.blocks }
        assertTrue(loanBlocks.any { it is PdfBlock.CashFlowChart })
        assertTrue(loanBlocks.any { it is PdfBlock.LoanTableChunk })
        assertTrue(loanBlocks.any { it is PdfBlock.FinancialEventTableChunk })
        assertTrue(loanBlocks.any { it is PdfBlock.TransactionTableChunk })
        assertEquals(1, FamilyReportPdfDocumentBuilder.filteredModuleEvents(loanReport).size)

        // All 11 ReportTypes produce valid PDF bytes without throwing
        ReportType.entries.forEach { reportType ->
            val scopedReport = FinancialReportEngine.buildReport(
                family = bdtFamily,
                members = members,
                transactions = sampleTransactions,
                loans = sampleLoans,
                savings = sampleSavings,
                investments = sampleInvestments,
                budgets = sampleBudgets,
                financialEvents = sampleEvents,
                filterSpec = ReportFilterSpec(
                    reportType = reportType,
                    periodScope = FinancialReportEngine.resolvePeriodScopeForReportType(
                        reportType,
                        ReportPeriodScope.THIS_MONTH
                    ),
                    anchorMillis = anchorMillis,
                    customStartMillis = dateMillis(2026, 11, 1),
                    customEndMillis = dateMillis(2026, 11, 20)
                ),
                generatedAtMillis = anchorMillis,
                zoneId = zone
            )
            val generated = FamilyReportPdfDocumentBuilder.generatePdfBytes(scopedReport)
            assertTrue("Expected valid PDF bytes for $reportType", generated.bytes.size > 100)
            assertTrue("Expected >=1 page for $reportType", generated.pageCount >= 1)
        }
    }

    @Test
    fun reportsScreen_rendersBdtStatementAllowsFilteringAndOpensPdfPreviewDialog() {
        var refreshedPeriod: String? = null
        composeRule.setContent {
            FamilyFinanceTheme {
                ReportsScreen(
                    activeFamily = bdtFamily,
                    cachedReports = emptyList(),
                    transactions = sampleTransactions,
                    loans = sampleLoans,
                    savings = sampleSavings,
                    investments = sampleInvestments,
                    budgets = sampleBudgets,
                    syncStatusLabel = "Synced",
                    onRefreshReport = { refreshedPeriod = it },
                    onNotifyMessage = {},
                    onNavigateToFamilyManagement = {},
                    members = members,
                    financialEvents = sampleEvents
                )
            }
        }

        composeRule.onNodeWithText("Rahman Family", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("preview_pdf_button").assertIsDisplayed()
        composeRule.onNodeWithTag("download_pdf_button").assertIsDisplayed()
        composeRule.onNodeWithTag("print_pdf_button").assertIsDisplayed()
        composeRule.onNodeWithTag("share_pdf_button").assertIsDisplayed()

        // Filter by Food category
        composeRule.onNodeWithTag("report_cat_food").performScrollTo().performClick()
        composeRule.onAllNodesWithText("৳12,450.50", substring = true)[0].performScrollTo().assertIsDisplayed()

        // Reset & switch to Complete Statement
        composeRule.onNodeWithTag("report_cat_all").performScrollTo().performClick()
        composeRule.onNodeWithTag("report_type_row")
            .performScrollTo()
            .performScrollToNode(hasTestTag("report_type_complete_statement"))
        composeRule.onNodeWithTag("report_type_complete_statement").performClick()

        // Member expense breakdown and module ledgers are displayed
        composeRule.onNodeWithText("Member-wise Expense Breakdown").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Savings Goals Included (1)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Budgets & Limits (1)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Loan, Savings & Investment Events (2)").performScrollTo().assertIsDisplayed()

        // Trigger encrypted cache refresh
        composeRule.onNodeWithTag("refresh_cached_report_button").performScrollTo().performClick()
        assertNotNull(refreshedPeriod)

        // Open Preview PDF dialog & verify export controls + pagination
        composeRule.onNodeWithTag("preview_pdf_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("pdf_preview_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_page_indicator").assertTextContains("Page 1", substring = true)
        composeRule.onNodeWithTag("preview_share_pdf_button").assertIsDisplayed()
        composeRule.onNodeWithTag("close_pdf_preview_button").performClick()
    }
}
