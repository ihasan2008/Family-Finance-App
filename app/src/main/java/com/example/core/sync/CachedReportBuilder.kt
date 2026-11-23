package com.example.core.sync

import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.BudgetSummaryCalculator
import com.example.ui.screens.modules.InvestmentsSummaryCalculator
import com.example.ui.screens.modules.LoansSummaryCalculator
import com.example.ui.screens.modules.SavingsSummaryCalculator
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Generates encrypted-at-rest offline financial statement snapshots from local Room records,
 * strictly enforcing Prompt 07-08 accounting separation rules:
 * - Loans, savings transfers, and investment principal are never mixed into Income/Expense cash flow.
 * - Investment profit and loss are reported strictly separately from principal.
 */
object CachedReportBuilder {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun buildPayload(
        familyId: String,
        familyName: String,
        periodKey: String,
        currency: String,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        budgets: List<LocalBudgetEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): CachedFinancialReportPayload {
        val cleanCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val periodTx = transactions.filter { tx ->
            tx.familyId == familyId &&
                !tx.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                (periodKey == PERIOD_ALL || BudgetSummaryCalculator.periodKeyForMillis(tx.occurredAtMillis) == periodKey)
        }
        val incomeTx = periodTx.filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
        val expenseTx = periodTx.filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }

        val totalIncome = MoneyCalculator.sumMinorUnits(incomeTx.map { it.effectiveAmountMinor() })
        val totalExpense = MoneyCalculator.sumMinorUnits(expenseTx.map { it.effectiveAmountMinor() })
        val netCashFlow = totalIncome - totalExpense
        val savingsRatePct = if (totalIncome > 0L) {
            BigDecimal.valueOf((totalIncome - totalExpense))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalIncome), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(-999, 100)
        } else 0

        val familyLoans = loans.filter { it.familyId == familyId }
        val familySavings = savings.filter { it.familyId == familyId }
        val familyInvestments = investments.filter { it.familyId == familyId }
        val familyBudgets = budgets.filter { it.familyId == familyId }

        val loansSummary = LoansSummaryCalculator.summarize(familyLoans, nowMillis)
        val savingsSummary = SavingsSummaryCalculator.summarize(familySavings)
        val investmentsSummary = InvestmentsSummaryCalculator.summarize(familyInvestments)

        val effectiveBudgetPeriod = if (periodKey == PERIOD_ALL) {
            BudgetSummaryCalculator.currentPeriodKey(nowMillis)
        } else {
            periodKey
        }
        val budgetSummary = BudgetSummaryCalculator.summarizeForPeriod(
            budgets = familyBudgets,
            expenseTransactions = expenseTx,
            periodKey = effectiveBudgetPeriod
        )
        val budgetLimitMinor = budgetSummary.overallBudget?.limitMinor ?: budgetSummary.totalCategoryAllocatedMinor
        val budgetSpentMinor = budgetSummary.totalMonthExpenseMinor
        val budgetUtilPct = if (budgetLimitMinor > 0L) {
            BigDecimal.valueOf(budgetSpentMinor)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(budgetLimitMinor), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceAtLeast(0)
        } else 0

        val topExpenses = groupTopCategories(expenseTx, totalExpense)
        val topIncome = groupTopCategories(incomeTx, totalIncome)

        return CachedFinancialReportPayload(
            familyId = familyId,
            familyName = familyName,
            periodKey = periodKey,
            currency = cleanCurrency,
            totalIncomeMinor = totalIncome,
            totalExpenseMinor = totalExpense,
            netCashFlowMinor = netCashFlow,
            savingsRatePercent = savingsRatePct,
            budgetLimitMinor = budgetLimitMinor,
            budgetSpentMinor = budgetSpentMinor,
            budgetUtilizationPercent = budgetUtilPct,
            loanBorrowedRemainingMinor = loansSummary.remainingBorrowedMinor,
            loanLentRemainingMinor = loansSummary.remainingLentMinor,
            savingsCurrentTotalMinor = savingsSummary.totalSavedMinor,
            savingsTargetTotalMinor = savingsSummary.totalTargetMinor,
            investmentPrincipalMinor = investmentsSummary.totalPrincipalMinor,
            investmentCurrentValueMinor = investmentsSummary.totalCurrentValueMinor,
            investmentProfitMinor = investmentsSummary.totalProfitMinor,
            investmentLossMinor = investmentsSummary.totalLossMinor,
            topExpenseCategories = topExpenses,
            topIncomeCategories = topIncome,
            recordCount = periodTx.size + familyLoans.size + familySavings.size + familyInvestments.size + familyBudgets.size,
            generatedAtMillis = nowMillis
        )
    }

    fun toEntity(
        payload: CachedFinancialReportPayload,
        isOnlineSynced: Boolean = true
    ): LocalCachedReportEntity {
        val serialized = json.encodeToString(CachedFinancialReportPayload.serializer(), payload)
        return LocalCachedReportEntity(
            reportId = DatabaseIdentityHelper.generateReportId(payload.familyId, payload.periodKey),
            familyId = payload.familyId,
            periodKey = payload.periodKey,
            currency = payload.currency,
            reportJson = serialized,
            recordCount = payload.recordCount,
            sourceHash = DatabaseIdentityHelper.computePayloadHash(serialized),
            generatedAtMillis = payload.generatedAtMillis,
            syncStatus = if (isOnlineSynced) "CACHED_FROM_SYNCED" else "CACHED_OFFLINE_INCLUDES_PENDING"
        )
    }

    fun parsePayload(entity: LocalCachedReportEntity): CachedFinancialReportPayload? {
        return runCatching {
            json.decodeFromString(CachedFinancialReportPayload.serializer(), entity.reportJson)
        }.getOrNull()
    }

    private fun groupTopCategories(
        items: List<LocalTransactionEntity>,
        totalMinor: Long
    ): List<CachedReportCategoryRow> {
        if (items.isEmpty()) return emptyList()
        return items.groupBy { it.category.trim().ifBlank { "General" } }
            .map { (cat, rows) ->
                val sum = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() })
                val share = if (totalMinor > 0L) {
                    BigDecimal.valueOf(sum)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalMinor), 0, RoundingMode.HALF_UP)
                        .toInt()
                        .coerceIn(0, 100)
                } else 0
                CachedReportCategoryRow(category = cat, amountMinor = sum, sharePercent = share)
            }
            .sortedByDescending { it.amountMinor }
            .take(5)
    }

    const val PERIOD_ALL = "ALL"
}
