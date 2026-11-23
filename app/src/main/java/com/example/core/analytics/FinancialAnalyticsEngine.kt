package com.example.core.analytics

import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.FinancialModuleType
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.InvestmentsSummaryCalculator
import com.example.ui.screens.modules.LoansSummaryCalculator
import com.example.ui.screens.modules.SavingsSummaryCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AnalyticsDateFilter(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    THIS_YEAR("This Year"),
    CUSTOM("Custom Range")
}

data class AnalyticsFilterSpec(
    val preset: AnalyticsDateFilter = AnalyticsDateFilter.THIS_MONTH,
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null
)

data class DashboardKpiSummary(
    val totalFamilyBalanceMinor: Long = 0L,
    val periodNetCashFlowMinor: Long = 0L,
    val totalIncomeMinor: Long = 0L,
    val totalExpenseMinor: Long = 0L,
    val totalSavingsMinor: Long = 0L,
    val totalSavingsTargetMinor: Long = 0L,
    val savingsCompletionPercent: Int = 0,
    val outstandingLoansBorrowedMinor: Long = 0L,
    val outstandingLoansLentMinor: Long = 0L,
    val overdueLoansCount: Int = 0,
    val investmentValueMinor: Long = 0L,
    val investmentPrincipalMinor: Long = 0L,
    val investmentProfitMinor: Long = 0L,
    val investmentLossMinor: Long = 0L,
    val netWorthMinor: Long = 0L,
    val savingsRatePercent: Int = 0,
    val incomeCount: Int = 0,
    val expenseCount: Int = 0
)

data class MonthlyCashFlowPoint(
    val yearMonth: String,
    val label: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long
)

data class YearlyCashFlowPoint(
    val year: Int,
    val label: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long
)

data class ExpenseCategorySlice(
    val category: String,
    val amountMinor: Long,
    val shareFraction: Float,
    val sharePercent: Int,
    val transactionCount: Int
)

data class SavingsGrowthPoint(
    val label: String,
    val fullDateLabel: String,
    val cumulativeSavingsMinor: Long,
    val deltaMinor: Long,
    val eventCount: Int
)

data class InvestmentPerformanceBar(
    val recordId: String,
    val label: String,
    val assetType: String,
    val principalMinor: Long,
    val currentValueMinor: Long,
    val profitMinor: Long,
    val lossMinor: Long,
    val returnPercentText: String
)

data class MemberExpenseBar(
    val memberId: String,
    val memberName: String,
    val expenseMinor: Long,
    val shareFraction: Float,
    val sharePercent: Int,
    val transactionCount: Int
)

data class AnalyticsDashboardSnapshot(
    val familyId: String,
    val currency: String,
    val filterSpec: AnalyticsFilterSpec,
    val referenceYear: Int,
    val summary: DashboardKpiSummary,
    val monthlySeries: List<MonthlyCashFlowPoint>,
    val yearlySeries: List<YearlyCashFlowPoint>,
    val expenseCategories: List<ExpenseCategorySlice>,
    val savingsGrowthSeries: List<SavingsGrowthPoint>,
    val investmentSeries: List<InvestmentPerformanceBar>,
    val memberExpenses: List<MemberExpenseBar>,
    val filterError: String? = null,
    val totalRecordCount: Int = 0
)

/**
 * Single authoritative decimal-safe Financial Calculation Engine shared across the
 * Home Dashboard, Interactive Analytics Dashboard, and Offline Reports.
 *
 * Strictly enforces accounting separation:
 * - Only verified active INCOME & EXPENSE transactions feed Income, Expenses, and Cash Flow.
 * - Loan principal & loan repayments are tracked in Loans and never counted as Income/Expense.
 * - Savings deposits/withdrawals are balance transfers and never counted as new Income/Expense.
 * - Investment principal, profit, and loss are computed separately.
 */
object FinancialAnalyticsEngine {

    private val shortMonthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
    private val ymFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun dateRangeForFilter(
        filter: AnalyticsFilterSpec,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long?, Long?> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        return when (filter.preset) {
            AnalyticsDateFilter.TODAY -> {
                val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            AnalyticsDateFilter.THIS_WEEK -> {
                val startOfWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
                val start = startOfWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = startOfWeek.plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            AnalyticsDateFilter.THIS_MONTH -> {
                val first = today.withDayOfMonth(1)
                val start = first.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = first.plusMonths(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            AnalyticsDateFilter.THIS_YEAR -> {
                val first = LocalDate.of(today.year, 1, 1)
                val start = first.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = first.plusYears(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            AnalyticsDateFilter.CUSTOM -> {
                val start = filter.customStartMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
                        .atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
                val end = filter.customEndMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
                        .plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                }
                start to end
            }
        }
    }

    fun buildSnapshot(
        familyId: String,
        currencyCode: String,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        financialEvents: List<LocalFinancialEventEntity>,
        members: List<LocalFamilyMemberEntity>,
        filterSpec: AnalyticsFilterSpec = AnalyticsFilterSpec(),
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AnalyticsDashboardSnapshot {
        val cleanCurrency = currencyCode.trim().uppercase(Locale.US).ifBlank { "USD" }
        val (rangeStart, rangeEnd) = dateRangeForFilter(filterSpec, nowMillis, zoneId)
        val filterError = if (
            filterSpec.preset == AnalyticsDateFilter.CUSTOM &&
            rangeStart != null &&
            rangeEnd != null &&
            rangeStart > rangeEnd
        ) {
            "Custom start date must be on or before the end date."
        } else {
            null
        }

        // Strict family isolation & exclude VOIDED transactions
        val activeTransactions = transactions.filter { tx ->
            tx.familyId == familyId &&
                !tx.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familyLoans = loans.filter {
            it.familyId == familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familySavings = savings.filter {
            it.familyId == familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familyInvestments = investments.filter {
            it.familyId == familyId && !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true)
        }
        val familyEvents = financialEvents.filter { it.familyId == familyId }

        val allTimeIncomeMinor = MoneyCalculator.sumMinorUnits(
            activeTransactions
                .filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )
        val allTimeExpenseMinor = MoneyCalculator.sumMinorUnits(
            activeTransactions
                .filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )
        val totalFamilyBalanceMinor = allTimeIncomeMinor - allTimeExpenseMinor

        val filteredTransactions = if (filterError != null) {
            emptyList()
        } else {
            activeTransactions.filter { tx ->
                (rangeStart == null || tx.occurredAtMillis >= rangeStart) &&
                    (rangeEnd == null || tx.occurredAtMillis <= rangeEnd)
            }
        }

        val filteredIncomeTx = filteredTransactions.filter {
            it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
        }
        val filteredExpenseTx = filteredTransactions.filter {
            it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true)
        }

        val periodIncomeMinor = MoneyCalculator.sumMinorUnits(filteredIncomeTx.map { it.effectiveAmountMinor() })
        val periodExpenseMinor = MoneyCalculator.sumMinorUnits(filteredExpenseTx.map { it.effectiveAmountMinor() })
        val periodNetCashFlowMinor = periodIncomeMinor - periodExpenseMinor

        val savingsRatePct = if (periodIncomeMinor > 0L) {
            BigDecimal.valueOf(periodNetCashFlowMinor)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(periodIncomeMinor), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(-999, 100)
        } else 0

        val loansSummary = LoansSummaryCalculator.summarize(familyLoans, nowMillis)
        val savingsSummary = SavingsSummaryCalculator.summarize(familySavings)
        val investmentsSummary = InvestmentsSummaryCalculator.summarize(familyInvestments)

        val savingsPct = if (savingsSummary.totalTargetMinor > 0L) {
            BigDecimal.valueOf(savingsSummary.totalSavedMinor)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(savingsSummary.totalTargetMinor), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 999)
        } else 0

        // Net Worth = Liquid Cash Balance + Savings + Investment Current Value + Lent Receivable - Borrowed Debt
        val netWorthMinor = totalFamilyBalanceMinor +
            savingsSummary.totalSavedMinor +
            investmentsSummary.totalCurrentValueMinor +
            loansSummary.remainingLentMinor -
            loansSummary.remainingBorrowedMinor

        val summary = DashboardKpiSummary(
            totalFamilyBalanceMinor = totalFamilyBalanceMinor,
            periodNetCashFlowMinor = periodNetCashFlowMinor,
            totalIncomeMinor = periodIncomeMinor,
            totalExpenseMinor = periodExpenseMinor,
            totalSavingsMinor = savingsSummary.totalSavedMinor,
            totalSavingsTargetMinor = savingsSummary.totalTargetMinor,
            savingsCompletionPercent = savingsPct,
            outstandingLoansBorrowedMinor = loansSummary.remainingBorrowedMinor,
            outstandingLoansLentMinor = loansSummary.remainingLentMinor,
            overdueLoansCount = loansSummary.overdueCount,
            investmentValueMinor = investmentsSummary.totalCurrentValueMinor,
            investmentPrincipalMinor = investmentsSummary.totalPrincipalMinor,
            investmentProfitMinor = investmentsSummary.totalProfitMinor,
            investmentLossMinor = investmentsSummary.totalLossMinor,
            netWorthMinor = netWorthMinor,
            savingsRatePercent = savingsRatePct,
            incomeCount = filteredIncomeTx.size,
            expenseCount = filteredExpenseTx.size
        )

        val referenceMillis = when (filterSpec.preset) {
            AnalyticsDateFilter.CUSTOM -> filterSpec.customEndMillis ?: nowMillis
            else -> nowMillis
        }
        val referenceYear = Instant.ofEpochMilli(referenceMillis).atZone(zoneId).toLocalDate().year

        val monthlySeries = buildMonthlySeries(activeTransactions, referenceYear, zoneId)
        val yearlySeries = buildYearlySeries(activeTransactions, referenceYear, zoneId)
        val expenseCategories = buildExpenseCategories(filteredExpenseTx, periodExpenseMinor)
        val savingsGrowthSeries = buildSavingsGrowthSeries(
            savings = familySavings,
            events = familyEvents,
            totalSavedMinor = savingsSummary.totalSavedMinor,
            rangeStart = if (filterSpec.preset == AnalyticsDateFilter.CUSTOM && filterError == null) rangeStart else null,
            rangeEnd = if (filterSpec.preset == AnalyticsDateFilter.CUSTOM && filterError == null) rangeEnd else null,
            zoneId = zoneId
        )
        val investmentSeries = buildInvestmentSeries(investmentsSummary.holdings)
        val memberLookup = members.associate { it.userId to it.displayName.ifBlank { it.userId } }
        val memberExpenses = buildMemberExpenses(filteredExpenseTx, periodExpenseMinor, memberLookup)

        return AnalyticsDashboardSnapshot(
            familyId = familyId,
            currency = cleanCurrency,
            filterSpec = filterSpec,
            referenceYear = referenceYear,
            summary = summary,
            monthlySeries = monthlySeries,
            yearlySeries = yearlySeries,
            expenseCategories = expenseCategories,
            savingsGrowthSeries = savingsGrowthSeries,
            investmentSeries = investmentSeries,
            memberExpenses = memberExpenses,
            filterError = filterError,
            totalRecordCount = activeTransactions.size + familyLoans.size + familySavings.size + familyInvestments.size
        )
    }

    private fun buildMonthlySeries(
        transactions: List<LocalTransactionEntity>,
        targetYear: Int,
        zoneId: ZoneId
    ): List<MonthlyCashFlowPoint> {
        val byMonth = transactions.groupBy { tx ->
            YearMonth.from(Instant.ofEpochMilli(tx.occurredAtMillis).atZone(zoneId))
        }
        return (1..12).map { month ->
            val ym = YearMonth.of(targetYear, month)
            val rows = byMonth[ym].orEmpty()
            val income = MoneyCalculator.sumMinorUnits(
                rows.filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
                    .map { it.effectiveAmountMinor() }
            )
            val expense = MoneyCalculator.sumMinorUnits(
                rows.filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }
                    .map { it.effectiveAmountMinor() }
            )
            MonthlyCashFlowPoint(
                yearMonth = ym.format(ymFormatter),
                label = ym.atDay(1).format(shortMonthFormatter),
                incomeMinor = income,
                expenseMinor = expense,
                netMinor = income - expense
            )
        }
    }

    private fun buildYearlySeries(
        transactions: List<LocalTransactionEntity>,
        referenceYear: Int,
        zoneId: ZoneId
    ): List<YearlyCashFlowPoint> {
        if (transactions.isEmpty()) return emptyList()
        val byYear = transactions.groupBy { tx ->
            Instant.ofEpochMilli(tx.occurredAtMillis).atZone(zoneId).toLocalDate().year
        }
        val years = (byYear.keys + referenceYear).distinct().sorted().takeLast(5)
        return years.map { year ->
            val rows = byYear[year].orEmpty()
            val income = MoneyCalculator.sumMinorUnits(
                rows.filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
                    .map { it.effectiveAmountMinor() }
            )
            val expense = MoneyCalculator.sumMinorUnits(
                rows.filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }
                    .map { it.effectiveAmountMinor() }
            )
            YearlyCashFlowPoint(
                year = year,
                label = year.toString(),
                incomeMinor = income,
                expenseMinor = expense,
                netMinor = income - expense
            )
        }
    }

    private fun buildExpenseCategories(
        expenses: List<LocalTransactionEntity>,
        totalExpenseMinor: Long
    ): List<ExpenseCategorySlice> {
        if (expenses.isEmpty() || totalExpenseMinor <= 0L) return emptyList()
        return expenses.groupBy { it.category.trim().ifBlank { "Other Expenses" } }
            .map { (cat, rows) ->
                val sum = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() })
                val sharePct = BigDecimal.valueOf(sum)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalExpenseMinor), 0, RoundingMode.HALF_UP)
                    .toInt()
                    .coerceIn(0, 100)
                ExpenseCategorySlice(
                    category = cat,
                    amountMinor = sum,
                    shareFraction = MoneyCalculator.percentageShare(sum, totalExpenseMinor),
                    sharePercent = sharePct,
                    transactionCount = rows.size
                )
            }
            .sortedByDescending { it.amountMinor }
    }

    private fun buildSavingsGrowthSeries(
        savings: List<LocalSavingsEntity>,
        events: List<LocalFinancialEventEntity>,
        totalSavedMinor: Long,
        rangeStart: Long?,
        rangeEnd: Long?,
        zoneId: ZoneId
    ): List<SavingsGrowthPoint> {
        if (savings.isEmpty() && events.none { it.moduleType == FinancialModuleType.SAVINGS.name }) {
            return emptyList()
        }
        val savingsEvents = events
            .filter { it.moduleType.equals(FinancialModuleType.SAVINGS.name, ignoreCase = true) }
            .filter { evt ->
                (rangeStart == null || evt.eventDateMillis >= rangeStart) &&
                    (rangeEnd == null || evt.eventDateMillis <= rangeEnd)
            }
            .sortedBy { it.eventDateMillis }

        if (savingsEvents.isEmpty()) {
            if (totalSavedMinor <= 0L) return emptyList()
            val latestMillis = savings.maxOfOrNull { it.updatedAtMillis } ?: System.currentTimeMillis()
            val d = Instant.ofEpochMilli(latestMillis).atZone(zoneId).toLocalDate()
            return listOf(
                SavingsGrowthPoint(
                    label = d.format(shortDateFormatter),
                    fullDateLabel = d.format(fullDateFormatter),
                    cumulativeSavingsMinor = totalSavedMinor,
                    deltaMinor = totalSavedMinor,
                    eventCount = savings.size
                )
            )
        }

        // Group by local date so multiple deposits on the same day roll into clean timeline points
        val groupedByDate = savingsEvents.groupBy { evt ->
            Instant.ofEpochMilli(evt.eventDateMillis).atZone(zoneId).toLocalDate()
        }.toSortedMap()

        val dailySignedDeltas = groupedByDate.map { (date, dayEvents) ->
            val delta = dayEvents.sumOf { evt ->
                if (evt.eventType.equals("WITHDRAWAL", ignoreCase = true)) -evt.amountMinor
                else evt.amountMinor
            }
            Triple(date, delta, dayEvents.size)
        }

        // Anchor to totalSavedMinor when full unfiltered history is viewed so final point matches live balance
        val sumOfDeltas = dailySignedDeltas.sumOf { it.second }
        val baseline = if (rangeStart == null && rangeEnd == null) {
            (totalSavedMinor - sumOfDeltas).coerceAtLeast(0L)
        } else {
            0L
        }

        var running = baseline
        val points = mutableListOf<SavingsGrowthPoint>()
        if (baseline > 0L) {
            val firstDate = dailySignedDeltas.first().first.minusDays(1)
            points += SavingsGrowthPoint(
                label = "Start",
                fullDateLabel = firstDate.format(fullDateFormatter),
                cumulativeSavingsMinor = baseline,
                deltaMinor = baseline,
                eventCount = 0
            )
        }
        dailySignedDeltas.forEach { (date, delta, count) ->
            running = (running + delta).coerceAtLeast(0L)
            points += SavingsGrowthPoint(
                label = date.format(shortDateFormatter),
                fullDateLabel = date.format(fullDateFormatter),
                cumulativeSavingsMinor = running,
                deltaMinor = delta,
                eventCount = count
            )
        }
        return points.takeLast(12)
    }

    private fun buildInvestmentSeries(
        holdings: List<com.example.ui.screens.modules.EvaluatedInvestment>
    ): List<InvestmentPerformanceBar> {
        if (holdings.isEmpty()) return emptyList()
        return holdings
            .sortedByDescending { maxOf(it.principalMinor, it.currentValueMinor) }
            .take(10)
            .map { item ->
                val shortLabel = item.entity.symbol?.takeIf { it.isNotBlank() }
                    ?: item.entity.assetName.trim().take(12).ifBlank { "Asset" }
                InvestmentPerformanceBar(
                    recordId = item.entity.recordId,
                    label = shortLabel,
                    assetType = item.entity.assetType,
                    principalMinor = item.principalMinor,
                    currentValueMinor = item.currentValueMinor,
                    profitMinor = item.profitMinor,
                    lossMinor = item.lossMinor,
                    returnPercentText = item.returnPercentText
                )
            }
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
