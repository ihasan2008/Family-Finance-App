package com.example.ui.screens.modules

import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.BudgetScope
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EvaluatedLoan(
    val entity: LocalLoanEntity,
    val direction: LoanDirection,
    val principalMinor: Long,
    val remainingMinor: Long,
    val repaidMinor: Long,
    val progress: Float,
    val isSettled: Boolean,
    val isOverdue: Boolean,
    val nextInstallmentDisplayMinor: Long
)

data class LoansPortfolioSummary(
    val totalBorrowedMinor: Long = 0L,
    val remainingBorrowedMinor: Long = 0L,
    val totalLentMinor: Long = 0L,
    val remainingLentMinor: Long = 0L,
    val totalRepaidMinor: Long = 0L,
    val activeCount: Int = 0,
    val settledCount: Int = 0,
    val overdueCount: Int = 0,
    val evaluatedLoans: List<EvaluatedLoan> = emptyList()
)

object LoansSummaryCalculator {
    fun summarize(
        loans: List<LocalLoanEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): LoansPortfolioSummary {
        val zone = ZoneId.systemDefault()
        val startOfTodayMillis = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        var totalBorrowed = 0L
        var remainingBorrowed = 0L
        var totalLent = 0L
        var remainingLent = 0L
        var totalRepaid = 0L
        var activeCount = 0
        var settledCount = 0
        var overdueCount = 0

        val evaluated = loans.map { loan ->
            val direction = LoanDirection.fromRaw(loan.loanDirection)
            val principal = loan.effectiveAmountMinor()
            val remaining = loan.effectiveRemainingMinor()
            val repaid = (principal - remaining).coerceAtLeast(0L)
            val settled = remaining == 0L || loan.recordStatus.equals(RecordStatus.SETTLED.name, ignoreCase = true)
            val overdue = !settled && loan.dueDateMillis != null && loan.dueDateMillis < startOfTodayMillis
            val progress = MoneyCalculator.percentageShare(repaid, principal)
            val suggestedNext = when {
                remaining <= 0L -> 0L
                loan.installmentAmountMinor > 0L -> minOf(loan.installmentAmountMinor, remaining)
                else -> remaining
            }

            if (direction == LoanDirection.BORROWED) {
                totalBorrowed += principal
                remainingBorrowed += remaining
            } else {
                totalLent += principal
                remainingLent += remaining
            }
            totalRepaid += repaid
            if (settled) settledCount++ else activeCount++
            if (overdue) overdueCount++

            EvaluatedLoan(
                entity = loan,
                direction = direction,
                principalMinor = principal,
                remainingMinor = remaining,
                repaidMinor = repaid,
                progress = progress,
                isSettled = settled,
                isOverdue = overdue,
                nextInstallmentDisplayMinor = suggestedNext
            )
        }

        return LoansPortfolioSummary(
            totalBorrowedMinor = totalBorrowed,
            remainingBorrowedMinor = remainingBorrowed,
            totalLentMinor = totalLent,
            remainingLentMinor = remainingLent,
            totalRepaidMinor = totalRepaid,
            activeCount = activeCount,
            settledCount = settledCount,
            overdueCount = overdueCount,
            evaluatedLoans = evaluated
        )
    }
}

data class EvaluatedSavingsGoal(
    val entity: LocalSavingsEntity,
    val currentMinor: Long,
    val targetMinor: Long,
    val remainingMinor: Long,
    val progress: Float,
    val progressPercent: Int,
    val isCompleted: Boolean
)

data class SavingsPortfolioSummary(
    val totalSavedMinor: Long = 0L,
    val totalTargetMinor: Long = 0L,
    val remainingToTargetMinor: Long = 0L,
    val overallProgress: Float = 0f,
    val completedCount: Int = 0,
    val activeCount: Int = 0,
    val goals: List<EvaluatedSavingsGoal> = emptyList()
)

object SavingsSummaryCalculator {
    fun summarize(goals: List<LocalSavingsEntity>): SavingsPortfolioSummary {
        var totalSaved = 0L
        var totalTarget = 0L
        var totalRemaining = 0L
        var completed = 0
        var active = 0

        val evaluated = goals.map { goal ->
            val current = goal.effectiveCurrentMinor()
            val target = goal.effectiveTargetMinor()
            val remaining = (target - current).coerceAtLeast(0L)
            val progress = MoneyCalculator.percentageShare(current, target)
            val pct = BigDecimal.valueOf(current)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(target.coerceAtLeast(1L)), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 999)
            val isCompleted = current >= target || goal.recordStatus.equals(RecordStatus.SETTLED.name, ignoreCase = true)

            totalSaved += current
            totalTarget += target
            totalRemaining += remaining
            if (isCompleted) completed++ else active++

            EvaluatedSavingsGoal(
                entity = goal,
                currentMinor = current,
                targetMinor = target,
                remainingMinor = remaining,
                progress = progress,
                progressPercent = pct,
                isCompleted = isCompleted
            )
        }

        return SavingsPortfolioSummary(
            totalSavedMinor = totalSaved,
            totalTargetMinor = totalTarget,
            remainingToTargetMinor = totalRemaining,
            overallProgress = MoneyCalculator.percentageShare(totalSaved, totalTarget),
            completedCount = completed,
            activeCount = active,
            goals = evaluated
        )
    }
}

data class EvaluatedInvestment(
    val entity: LocalInvestmentEntity,
    val principalMinor: Long,
    val currentValueMinor: Long,
    val profitMinor: Long,
    val lossMinor: Long,
    val returnPercentText: String
)

data class InvestmentsPortfolioSummary(
    val totalPrincipalMinor: Long = 0L,
    val totalCurrentValueMinor: Long = 0L,
    val totalProfitMinor: Long = 0L,
    val totalLossMinor: Long = 0L,
    val netGainOrLossMinor: Long = 0L,
    val holdings: List<EvaluatedInvestment> = emptyList()
)

object InvestmentsSummaryCalculator {
    /**
     * Enforces Prompt 08 rules:
     * - Investment principal is tracked separately from investment profit.
     * - Profit and Loss are aggregated strictly separately (never netted away before reporting).
     */
    fun summarize(investments: List<LocalInvestmentEntity>): InvestmentsPortfolioSummary {
        var totalPrincipal = 0L
        var totalCurrent = 0L
        var totalProfit = 0L
        var totalLoss = 0L

        val evaluated = investments.map { inv ->
            val principal = inv.effectivePrincipalMinor()
            val current = inv.effectiveCurrentValueMinor()
            val profit = (current - principal).coerceAtLeast(0L)
            val loss = (principal - current).coerceAtLeast(0L)

            totalPrincipal += principal
            totalCurrent += current
            totalProfit += profit
            totalLoss += loss

            val returnPctText = if (principal > 0L) {
                val delta = current - principal
                val pct = BigDecimal.valueOf(delta)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(principal), 1, RoundingMode.HALF_UP)
                val sign = if (delta > 0L) "+" else ""
                "$sign${pct.toPlainString()}%"
            } else {
                "0.0%"
            }

            EvaluatedInvestment(
                entity = inv,
                principalMinor = principal,
                currentValueMinor = current,
                profitMinor = profit,
                lossMinor = loss,
                returnPercentText = returnPctText
            )
        }

        return InvestmentsPortfolioSummary(
            totalPrincipalMinor = totalPrincipal,
            totalCurrentValueMinor = totalCurrent,
            totalProfitMinor = totalProfit,
            totalLossMinor = totalLoss,
            netGainOrLossMinor = totalCurrent - totalPrincipal,
            holdings = evaluated
        )
    }
}

enum class BudgetAlertLevel(val label: String) {
    OK("On Track"),
    WARNING("Approaching Limit"),
    EXCEEDED("Exceeded")
}

data class EvaluatedBudget(
    val entity: LocalBudgetEntity,
    val scope: BudgetScope,
    val limitMinor: Long,
    val spentMinor: Long,
    val remainingMinor: Long,
    val overspentMinor: Long,
    val usageFraction: Float,
    val usagePercent: Int,
    val alertLevel: BudgetAlertLevel
)

data class BudgetPeriodSummary(
    val periodKey: String,
    val overallBudget: EvaluatedBudget? = null,
    val categoryBudgets: List<EvaluatedBudget> = emptyList(),
    val totalMonthExpenseMinor: Long = 0L,
    val totalCategoryAllocatedMinor: Long = 0L,
    val warningCount: Int = 0,
    val exceededCount: Int = 0
)

data class BudgetHistoryRow(
    val periodKey: String,
    val overallLimitMinor: Long?,
    val categoryAllocatedTotalMinor: Long,
    val totalExpenseMinor: Long,
    val budgetCount: Int,
    val exceededCount: Int
)

object BudgetSummaryCalculator {
    private val periodFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

    fun currentPeriodKey(nowMillis: Long = System.currentTimeMillis()): String =
        periodKeyForMillis(nowMillis)

    fun periodKeyForMillis(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(periodFormatter)

    fun recentPeriodKeys(count: Int = 12, nowMillis: Long = System.currentTimeMillis()): List<String> {
        val current = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))
        return (0 until count).map { offset -> current.minusMonths(offset.toLong()).format(periodFormatter) }
    }

    fun formatPeriodLabel(periodKey: String): String = try {
        YearMonth.parse(periodKey, periodFormatter)
            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
    } catch (_: Exception) {
        periodKey
    }

    /**
     * Evaluates budgets for [periodKey] strictly against live `EXPENSE` transactions
     * in that calendar month (never double-counting Overall vs Category limits, and never
     * counting income, loans, or savings moves as budget spending).
     */
    fun summarizeForPeriod(
        budgets: List<LocalBudgetEntity>,
        expenseTransactions: List<LocalTransactionEntity>,
        periodKey: String
    ): BudgetPeriodSummary {
        val monthExpenses = expenseTransactions.filter { tx ->
            tx.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) &&
                !tx.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                periodKeyForMillis(tx.occurredAtMillis) == periodKey
        }
        val totalMonthExpense = MoneyCalculator.sumMinorUnits(monthExpenses.map { it.effectiveAmountMinor() })
        val spentByCategory = monthExpenses
            .groupBy { it.category.trim().lowercase(Locale.US) }
            .mapValues { (_, items) -> MoneyCalculator.sumMinorUnits(items.map { it.effectiveAmountMinor() }) }

        val periodBudgets = budgets.filter { it.periodKey == periodKey }
        val evaluated = periodBudgets.map { budget ->
            val scope = BudgetScope.fromRaw(budget.budgetScope)
            val limit = budget.effectiveLimitMinor()
            val spent = if (scope == BudgetScope.MONTHLY_OVERALL) {
                totalMonthExpense
            } else {
                spentByCategory[budget.category.trim().lowercase(Locale.US)] ?: 0L
            }
            val remaining = (limit - spent).coerceAtLeast(0L)
            val overspent = (spent - limit).coerceAtLeast(0L)
            val usagePercent = BigDecimal.valueOf(spent)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(limit.coerceAtLeast(1L)), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceAtLeast(0)
            val alertLevel = when {
                spent >= limit -> BudgetAlertLevel.EXCEEDED
                usagePercent >= budget.alertThresholdPercent.coerceIn(10, 100) -> BudgetAlertLevel.WARNING
                else -> BudgetAlertLevel.OK
            }
            EvaluatedBudget(
                entity = budget,
                scope = scope,
                limitMinor = limit,
                spentMinor = spent,
                remainingMinor = remaining,
                overspentMinor = overspent,
                usageFraction = MoneyCalculator.percentageShare(spent, limit),
                usagePercent = usagePercent,
                alertLevel = alertLevel
            )
        }

        val overallBudget = evaluated.firstOrNull { it.scope == BudgetScope.MONTHLY_OVERALL }
        val categoryBudgets = evaluated
            .filter { it.scope == BudgetScope.CATEGORY }
            .sortedByDescending { it.usagePercent }

        return BudgetPeriodSummary(
            periodKey = periodKey,
            overallBudget = overallBudget,
            categoryBudgets = categoryBudgets,
            totalMonthExpenseMinor = totalMonthExpense,
            totalCategoryAllocatedMinor = MoneyCalculator.sumMinorUnits(categoryBudgets.map { it.limitMinor }),
            warningCount = evaluated.count { it.alertLevel == BudgetAlertLevel.WARNING },
            exceededCount = evaluated.count { it.alertLevel == BudgetAlertLevel.EXCEEDED }
        )
    }

    fun buildHistory(
        budgets: List<LocalBudgetEntity>,
        expenseTransactions: List<LocalTransactionEntity>
    ): List<BudgetHistoryRow> {
        val periods = budgets.map { it.periodKey }.distinct().sortedDescending()
        return periods.map { key ->
            val periodSummary = summarizeForPeriod(budgets, expenseTransactions, key)
            BudgetHistoryRow(
                periodKey = key,
                overallLimitMinor = periodSummary.overallBudget?.limitMinor,
                categoryAllocatedTotalMinor = periodSummary.totalCategoryAllocatedMinor,
                totalExpenseMinor = periodSummary.totalMonthExpenseMinor,
                budgetCount = (if (periodSummary.overallBudget != null) 1 else 0) + periodSummary.categoryBudgets.size,
                exceededCount = periodSummary.exceededCount
            )
        }
    }
}
