package com.example.core.reports

import com.example.core.analytics.AnalyticsDateFilter
import com.example.core.analytics.AnalyticsFilterSpec
import com.example.core.analytics.DashboardKpiSummary
import com.example.core.analytics.ExpenseCategorySlice
import com.example.core.analytics.FinancialAnalyticsEngine
import com.example.core.analytics.MemberExpenseBar
import com.example.core.analytics.MonthlyCashFlowPoint
import com.example.core.analytics.YearlyCashFlowPoint
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.ExpenseCategory
import com.example.core.model.IncomeCategory
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.BudgetPeriodSummary
import com.example.ui.screens.modules.BudgetSummaryCalculator
import com.example.ui.screens.modules.EvaluatedInvestment
import com.example.ui.screens.modules.EvaluatedLoan
import com.example.ui.screens.modules.EvaluatedSavingsGoal
import com.example.ui.screens.modules.InvestmentsPortfolioSummary
import com.example.ui.screens.modules.InvestmentsSummaryCalculator
import com.example.ui.screens.modules.LoansPortfolioSummary
import com.example.ui.screens.modules.LoansSummaryCalculator
import com.example.ui.screens.modules.SavingsPortfolioSummary
import com.example.ui.screens.modules.SavingsSummaryCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ReportType(
    val displayName: String,
    val shortLabel: String,
    val subtitle: String,
    val isTimePreset: Boolean
) {
    DAILY(
        displayName = "Daily Report",
        shortLabel = "Daily",
        subtitle = "Single-day household income, expenses & transactions",
        isTimePreset = true
    ),
    WEEKLY(
        displayName = "Weekly Report",
        shortLabel = "Weekly",
        subtitle = "Monday–Sunday cash flow & category distribution",
        isTimePreset = true
    ),
    MONTHLY(
        displayName = "Monthly Report",
        shortLabel = "Monthly",
        subtitle = "Calendar month cash flow, budgets & wealth snapshot",
        isTimePreset = true
    ),
    YEARLY(
        displayName = "Yearly Report",
        shortLabel = "Yearly",
        subtitle = "12-month performance trend & annual totals",
        isTimePreset = true
    ),
    CUSTOM_RANGE(
        displayName = "Custom Date Range Report",
        shortLabel = "Custom",
        subtitle = "Custom start & end date financial breakdown",
        isTimePreset = true
    ),
    INCOME(
        displayName = "Income Report",
        shortLabel = "Income",
        subtitle = "Household income sources, inflows & trends",
        isTimePreset = false
    ),
    EXPENSE(
        displayName = "Expense Report",
        shortLabel = "Expense",
        subtitle = "Spending categories, member breakdown & budgets",
        isTimePreset = false
    ),
    LOAN(
        displayName = "Loan Report",
        shortLabel = "Loans",
        subtitle = "Borrowed debt, lent receivables, installments & due dates",
        isTimePreset = false
    ),
    SAVINGS(
        displayName = "Savings Report",
        shortLabel = "Savings",
        subtitle = "Savings goal progress, targets & deposit/withdrawal ledger",
        isTimePreset = false
    ),
    INVESTMENT(
        displayName = "Investment Report",
        shortLabel = "Investments",
        subtitle = "Holdings principal, current value & separated profit/loss",
        isTimePreset = false
    ),
    COMPLETE_STATEMENT(
        displayName = "Complete Family Financial Statement",
        shortLabel = "Complete Statement",
        subtitle = "Full household cash flow, assets, debt, charts & transactions",
        isTimePreset = false
    )
}

enum class ReportPeriodScope(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    THIS_YEAR("This Year"),
    ALL_TIME("All Time"),
    CUSTOM("Custom Range")
}

data class ReportFilterSpec(
    val reportType: ReportType = ReportType.MONTHLY,
    val periodScope: ReportPeriodScope = ReportPeriodScope.THIS_MONTH,
    val anchorMillis: Long = System.currentTimeMillis(),
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null,
    val selectedCategories: Set<String> = emptySet()
)

data class ReportTransactionRow(
    val recordId: String,
    val occurredAtMillis: Long,
    val dateText: String,
    val transactionType: String,
    val category: String,
    val memberName: String,
    val description: String,
    val amountMinor: Long,
    val currency: String
)

data class FamilyReportData(
    val familyId: String,
    val familyName: String,
    val currency: String,
    val reportType: ReportType,
    val periodScope: ReportPeriodScope,
    val periodLabel: String,
    val dateRangeText: String,
    val generatedAtMillis: Long,
    val generationDateText: String,
    val selectedCategories: Set<String>,
    val filterError: String?,
    val summary: DashboardKpiSummary,
    val incomeCategories: List<ExpenseCategorySlice>,
    val expenseCategories: List<ExpenseCategorySlice>,
    val monthlySeries: List<MonthlyCashFlowPoint>,
    val yearlySeries: List<YearlyCashFlowPoint>,
    val memberExpenses: List<MemberExpenseBar>,
    val loansSummary: LoansPortfolioSummary,
    val savingsSummary: SavingsPortfolioSummary,
    val investmentsSummary: InvestmentsPortfolioSummary,
    val budgetPeriodSummary: BudgetPeriodSummary,
    val loans: List<EvaluatedLoan>,
    val savingsGoals: List<EvaluatedSavingsGoal>,
    val investments: List<EvaluatedInvestment>,
    val savingsAndLoanEvents: List<LocalFinancialEventEntity>,
    val transactions: List<ReportTransactionRow>,
    val allAvailableCategories: List<String>
) {
    val totalIncomeMinor: Long get() = summary.totalIncomeMinor
    val totalExpenseMinor: Long get() = summary.totalExpenseMinor
    val netCashFlowMinor: Long get() = summary.periodNetCashFlowMinor
    val isCategoryFiltered: Boolean get() = selectedCategories.isNotEmpty()

    val includeCashFlowSummary: Boolean
        get() = reportType in setOf(
            ReportType.DAILY,
            ReportType.WEEKLY,
            ReportType.MONTHLY,
            ReportType.YEARLY,
            ReportType.CUSTOM_RANGE,
            ReportType.INCOME,
            ReportType.EXPENSE,
            ReportType.COMPLETE_STATEMENT
        )

    val includeLoansSection: Boolean
        get() = reportType == ReportType.LOAN || reportType == ReportType.COMPLETE_STATEMENT

    val includeSavingsSection: Boolean
        get() = reportType == ReportType.SAVINGS || reportType == ReportType.COMPLETE_STATEMENT

    val includeInvestmentsSection: Boolean
        get() = reportType == ReportType.INVESTMENT || reportType == ReportType.COMPLETE_STATEMENT

    val includeBudgetsSection: Boolean
        get() = reportType in setOf(
            ReportType.MONTHLY,
            ReportType.EXPENSE,
            ReportType.COMPLETE_STATEMENT
        )
}

/**
 * Authoritative reporting computation engine built directly over [FinancialAnalyticsEngine],
 * [MoneyCalculator], and module calculators so PDFs, report previews, and the Dashboard always agree.
 */
object FinancialReportEngine {

    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val genTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy • h:mm a", Locale.US)

    val allKnownCategories: List<String> =
        (IncomeCategory.allNames + ExpenseCategory.allNames).distinct()

    fun resolvePeriodScopeForReportType(
        reportType: ReportType,
        currentScope: ReportPeriodScope
    ): ReportPeriodScope = when (reportType) {
        ReportType.DAILY -> ReportPeriodScope.TODAY
        ReportType.WEEKLY -> ReportPeriodScope.THIS_WEEK
        ReportType.MONTHLY -> ReportPeriodScope.THIS_MONTH
        ReportType.YEARLY -> ReportPeriodScope.THIS_YEAR
        ReportType.CUSTOM_RANGE -> ReportPeriodScope.CUSTOM
        else -> currentScope
    }

    fun stepAnchorMillis(
        scope: ReportPeriodScope,
        currentAnchorMillis: Long,
        delta: Int,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val date = Instant.ofEpochMilli(currentAnchorMillis).atZone(zoneId).toLocalDate()
        val shifted = when (scope) {
            ReportPeriodScope.TODAY -> date.plusDays(delta.toLong())
            ReportPeriodScope.THIS_WEEK -> date.plusWeeks(delta.toLong())
            ReportPeriodScope.THIS_MONTH -> date.plusMonths(delta.toLong())
            ReportPeriodScope.THIS_YEAR -> date.plusYears(delta.toLong())
            ReportPeriodScope.ALL_TIME, ReportPeriodScope.CUSTOM -> date
        }
        return shifted.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
    }

    fun buildReport(
        family: LocalFamilyEntity,
        members: List<LocalFamilyMemberEntity>,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        budgets: List<LocalBudgetEntity>,
        financialEvents: List<LocalFinancialEventEntity>,
        filterSpec: ReportFilterSpec = ReportFilterSpec(),
        generatedAtMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): FamilyReportData {
        val cleanCurrency = family.currencyCode.trim().uppercase(Locale.US).ifBlank { "USD" }
        val effectiveScope = resolvePeriodScopeForReportType(filterSpec.reportType, filterSpec.periodScope)
        val analyticsFilter = toAnalyticsFilterSpec(effectiveScope, filterSpec)

        val (rangeStart, rangeEnd, dateRangeText, periodLabel, customError) =
            resolveWindowMetadata(effectiveScope, analyticsFilter, filterSpec.anchorMillis, zoneId)

        // Base snapshot from FinancialAnalyticsEngine guarantees 1:1 Dashboard parity
        val dashboardFilter = analyticsFilter ?: AnalyticsFilterSpec(preset = AnalyticsDateFilter.THIS_YEAR)
        val baseSnapshot = FinancialAnalyticsEngine.buildSnapshot(
            familyId = family.familyId,
            currencyCode = cleanCurrency,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = financialEvents,
            members = members,
            filterSpec = dashboardFilter,
            nowMillis = filterSpec.anchorMillis,
            zoneId = zoneId
        )

        val activeFamilyTransactions = transactions.filter { tx ->
            tx.familyId == family.familyId &&
                !tx.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }

        val dynamicCategories = activeFamilyTransactions
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
        val availableCategories = (allKnownCategories + dynamicCategories).distinct()

        val timeFilteredTransactions = if (customError != null) {
            emptyList()
        } else {
            activeFamilyTransactions.filter { tx ->
                (rangeStart == null || tx.occurredAtMillis >= rangeStart) &&
                    (rangeEnd == null || tx.occurredAtMillis <= rangeEnd)
            }
        }

        val normalizedSelected = filterSpec.selectedCategories
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()

        val categoryFilteredTx = if (normalizedSelected.isEmpty()) {
            timeFilteredTransactions
        } else {
            timeFilteredTransactions.filter { tx ->
                tx.category.trim().lowercase(Locale.US) in normalizedSelected
            }
        }

        val typeScopedTransactions = when (filterSpec.reportType) {
            ReportType.INCOME -> categoryFilteredTx.filter {
                it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
            }
            ReportType.EXPENSE -> categoryFilteredTx.filter {
                it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true)
            }
            else -> categoryFilteredTx
        }.sortedByDescending { it.occurredAtMillis }

        val incomeTx = categoryFilteredTx.filter {
            it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
        }
        val expenseTx = categoryFilteredTx.filter {
            it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true)
        }

        val familyLoans = loans.filter {
            it.familyId == family.familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familySavings = savings.filter {
            it.familyId == family.familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familyInvestments = investments.filter {
            it.familyId == family.familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familyBudgets = budgets.filter {
            it.familyId == family.familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }

        val loansSummary = LoansSummaryCalculator.summarize(familyLoans, generatedAtMillis)
        val savingsSummary = SavingsSummaryCalculator.summarize(familySavings)
        val investmentsSummary = InvestmentsSummaryCalculator.summarize(familyInvestments)

        val anchorPeriodKey = BudgetSummaryCalculator.periodKeyForMillis(filterSpec.anchorMillis)
        val budgetPeriodSummary = BudgetSummaryCalculator.summarizeForPeriod(
            budgets = familyBudgets,
            expenseTransactions = activeFamilyTransactions.filter {
                it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true)
            },
            periodKey = anchorPeriodKey
        )

        // When ALL_TIME or category filter is active, compute period totals deterministically via MoneyCalculator;
        // otherwise reuse baseSnapshot.summary directly so numbers match Dashboard bit-for-bit.
        val resolvedSummary = if (effectiveScope != ReportPeriodScope.ALL_TIME && normalizedSelected.isEmpty()) {
            baseSnapshot.summary
        } else {
            val incMinor = MoneyCalculator.sumMinorUnits(incomeTx.map { it.effectiveAmountMinor() })
            val expMinor = MoneyCalculator.sumMinorUnits(expenseTx.map { it.effectiveAmountMinor() })
            val netMinor = incMinor - expMinor
            val ratePct = if (incMinor > 0L) {
                BigDecimal.valueOf(netMinor)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(incMinor), 0, RoundingMode.HALF_UP)
                    .toInt()
                    .coerceIn(-999, 100)
            } else 0
            baseSnapshot.summary.copy(
                periodNetCashFlowMinor = netMinor,
                totalIncomeMinor = incMinor,
                totalExpenseMinor = expMinor,
                savingsRatePercent = ratePct,
                incomeCount = incomeTx.size,
                expenseCount = expenseTx.size
            )
        }

        val memberLookup = members.associate { it.userId to it.displayName.ifBlank { it.userId } }

        val incomeCategories = buildCategorySlices(incomeTx, resolvedSummary.totalIncomeMinor, "Other Income")
        val expenseCategories = buildCategorySlices(expenseTx, resolvedSummary.totalExpenseMinor, "Other Expenses")
        val memberExpenses = buildMemberExpenses(expenseTx, resolvedSummary.totalExpenseMinor, memberLookup)

        val filteredEvents = financialEvents
            .filter { it.familyId == family.familyId }
            .filter { evt ->
                (rangeStart == null || evt.eventDateMillis >= rangeStart) &&
                    (rangeEnd == null || evt.eventDateMillis <= rangeEnd)
            }
            .sortedByDescending { it.eventDateMillis }

        val transactionRows = typeScopedTransactions.map { tx ->
            val d = Instant.ofEpochMilli(tx.occurredAtMillis).atZone(zoneId).toLocalDate()
            val uid = tx.effectiveMemberId()
            ReportTransactionRow(
                recordId = tx.recordId,
                occurredAtMillis = tx.occurredAtMillis,
                dateText = d.format(shortDateFormatter),
                transactionType = tx.transactionType,
                category = tx.category,
                memberName = memberLookup[uid] ?: "Member (${uid.take(6)})",
                description = tx.description.ifBlank { tx.category },
                amountMinor = tx.effectiveAmountMinor(),
                currency = cleanCurrency
            )
        }

        val generationDateText = Instant.ofEpochMilli(generatedAtMillis)
            .atZone(zoneId)
            .format(genTimestampFormatter)

        return FamilyReportData(
            familyId = family.familyId,
            familyName = family.name.ifBlank { "Family Workspace" },
            currency = cleanCurrency,
            reportType = filterSpec.reportType,
            periodScope = effectiveScope,
            periodLabel = periodLabel,
            dateRangeText = dateRangeText,
            generatedAtMillis = generatedAtMillis,
            generationDateText = generationDateText,
            selectedCategories = filterSpec.selectedCategories,
            filterError = customError ?: baseSnapshot.filterError,
            summary = resolvedSummary,
            incomeCategories = incomeCategories,
            expenseCategories = expenseCategories,
            monthlySeries = baseSnapshot.monthlySeries,
            yearlySeries = baseSnapshot.yearlySeries,
            memberExpenses = memberExpenses,
            loansSummary = loansSummary,
            savingsSummary = savingsSummary,
            investmentsSummary = investmentsSummary,
            budgetPeriodSummary = budgetPeriodSummary,
            loans = loansSummary.evaluatedLoans,
            savingsGoals = savingsSummary.goals,
            investments = investmentsSummary.holdings,
            savingsAndLoanEvents = filteredEvents,
            transactions = transactionRows,
            allAvailableCategories = availableCategories
        )
    }

    private fun toAnalyticsFilterSpec(
        scope: ReportPeriodScope,
        filterSpec: ReportFilterSpec
    ): AnalyticsFilterSpec? = when (scope) {
        ReportPeriodScope.TODAY -> AnalyticsFilterSpec(AnalyticsDateFilter.TODAY)
        ReportPeriodScope.THIS_WEEK -> AnalyticsFilterSpec(AnalyticsDateFilter.THIS_WEEK)
        ReportPeriodScope.THIS_MONTH -> AnalyticsFilterSpec(AnalyticsDateFilter.THIS_MONTH)
        ReportPeriodScope.THIS_YEAR -> AnalyticsFilterSpec(AnalyticsDateFilter.THIS_YEAR)
        ReportPeriodScope.CUSTOM -> AnalyticsFilterSpec(
            preset = AnalyticsDateFilter.CUSTOM,
            customStartMillis = filterSpec.customStartMillis,
            customEndMillis = filterSpec.customEndMillis
        )
        ReportPeriodScope.ALL_TIME -> null
    }

    private data class WindowMetadata(
        val startMillis: Long?,
        val endMillis: Long?,
        val dateRangeText: String,
        val periodLabel: String,
        val error: String?
    )

    private fun resolveWindowMetadata(
        scope: ReportPeriodScope,
        analyticsFilter: AnalyticsFilterSpec?,
        anchorMillis: Long,
        zoneId: ZoneId
    ): WindowMetadata {
        val anchorDate = Instant.ofEpochMilli(anchorMillis).atZone(zoneId).toLocalDate()
        if (scope == ReportPeriodScope.ALL_TIME || analyticsFilter == null) {
            return WindowMetadata(
                startMillis = null,
                endMillis = null,
                dateRangeText = "All Recorded Dates",
                periodLabel = "All Time",
                error = null
            )
        }
        val (startMillis, endMillis) = FinancialAnalyticsEngine.dateRangeForFilter(
            filter = analyticsFilter,
            nowMillis = anchorMillis,
            zoneId = zoneId
        )
        if (scope == ReportPeriodScope.CUSTOM && startMillis != null && endMillis != null && startMillis > endMillis) {
            return WindowMetadata(
                startMillis = startMillis,
                endMillis = endMillis,
                dateRangeText = "Invalid date range",
                periodLabel = "Custom Date Range",
                error = "Start date must be on or before end date."
            )
        }
        val startDateText = startMillis?.let {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().format(shortDateFormatter)
        } ?: "Earliest"
        val endDateText = endMillis?.let {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().format(shortDateFormatter)
        } ?: "Present"

        val label = when (scope) {
            ReportPeriodScope.TODAY -> anchorDate.format(shortDateFormatter)
            ReportPeriodScope.THIS_WEEK -> "Week of $startDateText"
            ReportPeriodScope.THIS_MONTH -> YearMonth.from(anchorDate).format(monthYearFormatter)
            ReportPeriodScope.THIS_YEAR -> "Year ${anchorDate.year}"
            ReportPeriodScope.CUSTOM -> "$startDateText – $endDateText"
            ReportPeriodScope.ALL_TIME -> "All Time"
        }
        val rangeText = if (startDateText == endDateText) startDateText else "$startDateText – $endDateText"
        return WindowMetadata(
            startMillis = startMillis,
            endMillis = endMillis,
            dateRangeText = rangeText,
            periodLabel = label,
            error = null
        )
    }

    private fun buildCategorySlices(
        items: List<LocalTransactionEntity>,
        totalMinor: Long,
        fallbackCategory: String
    ): List<ExpenseCategorySlice> {
        if (items.isEmpty()) return emptyList()
        val denominator = if (totalMinor > 0L) totalMinor else MoneyCalculator.sumMinorUnits(items.map { it.effectiveAmountMinor() })
        if (denominator <= 0L) return emptyList()
        return items.groupBy { it.category.trim().ifBlank { fallbackCategory } }
            .map { (cat, rows) ->
                val sum = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() })
                val sharePct = BigDecimal.valueOf(sum)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                    .toInt()
                    .coerceIn(0, 100)
                ExpenseCategorySlice(
                    category = cat,
                    amountMinor = sum,
                    shareFraction = MoneyCalculator.percentageShare(sum, denominator),
                    sharePercent = sharePct,
                    transactionCount = rows.size
                )
            }
            .sortedByDescending { it.amountMinor }
    }

    private fun buildMemberExpenses(
        expenses: List<LocalTransactionEntity>,
        totalExpenseMinor: Long,
        memberLookup: Map<String, String>
    ): List<MemberExpenseBar> {
        if (expenses.isEmpty() || totalExpenseMinor <= 0L) return emptyList()
        return expenses.groupBy { it.effectiveMemberId() }
            .map { (uid, rows) ->
                val sum = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() })
                val sharePct = BigDecimal.valueOf(sum)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalExpenseMinor), 0, RoundingMode.HALF_UP)
                    .toInt()
                    .coerceIn(0, 100)
                val displayName = memberLookup[uid] ?: "Member (${uid.take(6)})"
                MemberExpenseBar(
                    memberId = uid,
                    memberName = displayName,
                    expenseMinor = sum,
                    shareFraction = MoneyCalculator.percentageShare(sum, totalExpenseMinor),
                    sharePercent = sharePct,
                    transactionCount = rows.size
                )
            }
            .sortedByDescending { it.expenseMinor }
    }
}
