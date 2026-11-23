package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.analytics.ExpenseCategorySlice
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.TransactionType
import com.example.core.reports.FamilyReportData
import com.example.core.reports.ReportType
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.MoneySmallStyle
import com.example.ui.theme.WealthGoldTertiary
import kotlin.math.max

@Composable
fun ReportModuleSummariesCard(
    report: FamilyReportData,
    modifier: Modifier = Modifier
) {
    val currency = report.currency
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Savings, Loans & Investment Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Savings Summary
            Text(
                text = "Savings Summary (${report.savingsGoals.size} goal(s))",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            ReportStatLine(
                label = "Total Saved vs. Target",
                value = "${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalSavedMinor, currency)} / ${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalTargetMinor, currency)}",
                valueColor = InfoTeal
            )
            ReportStatLine(
                label = "Overall Goal Progress",
                value = "${report.summary.savingsCompletionPercent}% (${report.savingsSummary.completedCount} completed)",
                valueColor = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Loan Summary
            Text(
                text = "Loan Summary (${report.loans.size} record(s))",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            ReportStatLine(
                label = "Borrowed Remaining",
                value = MoneyCalculator.formatMinorUnits(report.loansSummary.remainingBorrowedMinor, currency),
                valueColor = ExpenseCoral
            )
            ReportStatLine(
                label = "Lent Receivable",
                value = MoneyCalculator.formatMinorUnits(report.loansSummary.remainingLentMinor, currency),
                valueColor = IncomeGreen
            )
            ReportStatLine(
                label = "Total Repaid / Overdue",
                value = "${MoneyCalculator.formatMinorUnits(report.loansSummary.totalRepaidMinor, currency)} • ${report.loansSummary.overdueCount} overdue",
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Investment Summary (Principal vs Profit vs Loss strictly separated)
            Text(
                text = "Investment Summary (${report.investments.size} holding(s))",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            ReportStatLine(
                label = "Principal Cost Basis",
                value = MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalPrincipalMinor, currency),
                valueColor = MaterialTheme.colorScheme.onSurface
            )
            ReportStatLine(
                label = "Current Portfolio Value",
                value = MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalCurrentValueMinor, currency),
                valueColor = InfoTeal
            )
            ReportStatLine(
                label = "Unrealized Profit (Separate)",
                value = MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalProfitMinor, currency, "+"),
                valueColor = IncomeGreen
            )
            ReportStatLine(
                label = "Unrealized Loss (Separate)",
                value = if (report.investmentsSummary.totalLossMinor > 0L) {
                    "-${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalLossMinor, currency)}"
                } else {
                    MoneyCalculator.formatMinorUnits(0L, currency)
                },
                valueColor = if (report.investmentsSummary.totalLossMinor > 0L) ExpenseCoral else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ReportStatLine(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MoneySmallStyle.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

@Composable
fun ReportVisualChartsCard(
    report: FamilyReportData,
    modifier: Modifier = Modifier
) {
    val currency = report.currency
    val hasMonthlyActivity = report.monthlySeries.any { it.incomeMinor > 0L || it.expenseMinor > 0L }
    val showIncomeCats = report.reportType == ReportType.INCOME ||
        (report.expenseCategories.isEmpty() && report.incomeCategories.isNotEmpty())
    val categorySlices = if (showIncomeCats) report.incomeCategories else report.expenseCategories

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Included PDF Charts Preview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (hasMonthlyActivity) {
                val maxMinor = remember(report.monthlySeries) {
                    max(1L, report.monthlySeries.maxOfOrNull { max(it.incomeMinor, it.expenseMinor) } ?: 1L)
                }
                Text(
                    text = "Monthly Income vs. Expense Trend",
                    style = MaterialTheme.typography.labelLarge
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .testTag("report_cashflow_chart")
                ) {
                    val count = report.monthlySeries.size.coerceAtLeast(1)
                    val groupW = size.width / count
                    val barW = (groupW * 0.34f).coerceIn(5f, 18f)
                    val chartH = size.height - 16f
                    drawLine(
                        color = androidx.compose.ui.graphics.Color(0xFFB5C9BF),
                        start = Offset(0f, chartH),
                        end = Offset(size.width, chartH),
                        strokeWidth = 2f
                    )
                    report.monthlySeries.forEachIndexed { idx, pt ->
                        val cx = groupW * (idx + 0.5f)
                        val ih = (pt.incomeMinor.toFloat() / maxMinor.toFloat()) * chartH
                        val eh = (pt.expenseMinor.toFloat() / maxMinor.toFloat()) * chartH
                        if (ih > 0f) {
                            drawRoundRect(
                                color = IncomeGreen,
                                topLeft = Offset(cx - barW - 1.5f, chartH - ih),
                                size = Size(barW, ih),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                        if (eh > 0f) {
                            drawRoundRect(
                                color = ExpenseCoral,
                                topLeft = Offset(cx + 1.5f, chartH - eh),
                                size = Size(barW, eh),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    report.monthlySeries.forEach { pt ->
                        Text(
                            text = pt.label.take(1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "Record income or expense transactions to populate the cash-flow bar chart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (report.reportType == ReportType.YEARLY && report.yearlySeries.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Annual Summary (${report.yearlySeries.size} year(s))",
                    style = MaterialTheme.typography.labelLarge
                )
                report.yearlySeries.forEach { yr ->
                    ReportStatLine(
                        label = "${yr.label} (Net ${MoneyCalculator.formatMinorUnits(yr.netMinor, currency, if (yr.netMinor > 0L) "+" else "")})",
                        value = "In ${MoneyCalculator.formatMinorUnits(yr.incomeMinor, currency)} / Out ${MoneyCalculator.formatMinorUnits(yr.expenseMinor, currency)}",
                        valueColor = if (yr.netMinor >= 0L) IncomeGreen else ExpenseCoral
                    )
                }
            }

            if (categorySlices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = if (showIncomeCats) "Income Category Distribution" else "Expense Category Distribution",
                    style = MaterialTheme.typography.labelLarge
                )
                CategorySlicesPreviewList(
                    slices = categorySlices.take(6),
                    currency = currency,
                    barColor = if (showIncomeCats) IncomeGreen else InfoTeal
                )
            }

            if (report.memberExpenses.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Member-wise Expense Breakdown",
                    style = MaterialTheme.typography.labelLarge
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report.memberExpenses.take(6).forEach { bar ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${bar.memberName} (${bar.transactionCount})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${MoneyCalculator.formatMinorUnits(bar.expenseMinor, currency)} (${bar.sharePercent}%)",
                                    style = MoneySmallStyle.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            LinearProgressIndicator(
                                progress = { bar.shareFraction.coerceIn(0f, 1f) },
                                color = WealthGoldTertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySlicesPreviewList(
    slices: List<ExpenseCategorySlice>,
    currency: String,
    barColor: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slices.forEach { slice ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${slice.category} (${slice.transactionCount})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${MoneyCalculator.formatMinorUnits(slice.amountMinor, currency)} (${slice.sharePercent}%)",
                        style = MoneySmallStyle.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                LinearProgressIndicator(
                    progress = { slice.shareFraction.coerceIn(0f, 1f) },
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
        }
    }
}

@Composable
fun ReportPaginatedLedgerCard(
    report: FamilyReportData,
    modifier: Modifier = Modifier
) {
    val pageSize = 10
    val totalRows = report.transactions.size
    val totalPages = ((totalRows + pageSize - 1) / pageSize).coerceAtLeast(1)
    var currentPage by remember(report.reportType, report.dateRangeText, totalRows, report.selectedCategories) {
        mutableIntStateOf(0)
    }
    val safePage = currentPage.coerceIn(0, totalPages - 1)
    val pageItems = remember(report.transactions, safePage) {
        val from = safePage * pageSize
        val to = minOf(totalRows, from + pageSize)
        if (from < to) report.transactions.subList(from, to) else emptyList()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Transaction History ($totalRows)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (totalRows > pageSize) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (safePage > 0) currentPage = safePage - 1 },
                            enabled = safePage > 0,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous rows")
                        }
                        Text(
                            text = "${safePage + 1}/$totalPages",
                            style = MaterialTheme.typography.labelMedium
                        )
                        IconButton(
                            onClick = { if (safePage < totalPages - 1) currentPage = safePage + 1 },
                            enabled = safePage < totalPages - 1,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next rows")
                        }
                    }
                }
            }

            if (pageItems.isEmpty()) {
                Text(
                    text = "No matching Income/Expense records in this period or category selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                pageItems.forEach { row ->
                    val isIncome = row.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${row.category} • ${row.memberName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${row.dateText} • ${row.description}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = MoneyCalculator.formatMinorUnits(
                                    row.amountMinor,
                                    row.currency,
                                    if (isIncome) "+" else "-"
                                ).let { raw ->
                                    if (!isIncome && !raw.startsWith("-")) "-$raw" else raw
                                },
                                style = MoneyMediumStyle.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isIncome) IncomeGreen else ExpenseCoral
                            )
                        }
                    }
                }
            }

            if (report.includeLoansSection && report.loans.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Loans Included (${report.loans.size})", style = MaterialTheme.typography.labelLarge)
                report.loans.take(5).forEach { loan ->
                    val dirLabel = if (loan.direction == LoanDirection.BORROWED) "Borrowed" else "Lent"
                    ReportStatLine(
                        label = "${loan.entity.counterparty} ($dirLabel • ${loan.entity.paidInstallments}/${loan.entity.totalInstallments})",
                        value = MoneyCalculator.formatMinorUnits(loan.remainingMinor, report.currency),
                        valueColor = if (loan.direction == LoanDirection.BORROWED) ExpenseCoral else IncomeGreen
                    )
                }
            }

            if (report.includeSavingsSection && report.savingsGoals.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Savings Goals Included (${report.savingsGoals.size})", style = MaterialTheme.typography.labelLarge)
                report.savingsGoals.take(5).forEach { goal ->
                    ReportStatLine(
                        label = "${goal.entity.goalName} (${goal.progressPercent}%)",
                        value = "${MoneyCalculator.formatMinorUnits(goal.currentMinor, report.currency)} / ${MoneyCalculator.formatMinorUnits(goal.targetMinor, report.currency)}",
                        valueColor = InfoTeal
                    )
                }
            }

            if (report.includeInvestmentsSection && report.investments.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Holdings Included (${report.investments.size})", style = MaterialTheme.typography.labelLarge)
                report.investments.take(5).forEach { inv ->
                    ReportStatLine(
                        label = "${inv.entity.assetName} (${inv.returnPercentText})",
                        value = MoneyCalculator.formatMinorUnits(inv.currentValueMinor, report.currency),
                        valueColor = WealthGoldTertiary
                    )
                }
            }

            val budgetRows = listOfNotNull(report.budgetPeriodSummary.overallBudget) +
                report.budgetPeriodSummary.categoryBudgets
            if (report.includeBudgetsSection && budgetRows.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Budgets & Limits (${budgetRows.size})", style = MaterialTheme.typography.labelLarge)
                budgetRows.take(5).forEach { bud ->
                    ReportStatLine(
                        label = "${bud.entity.category} (${bud.usagePercent}%)",
                        value = "${MoneyCalculator.formatMinorUnits(bud.spentMinor, report.currency)} / ${MoneyCalculator.formatMinorUnits(bud.limitMinor, report.currency)}",
                        valueColor = if (bud.usagePercent >= 100) ExpenseCoral else InfoTeal
                    )
                }
            }

            val moduleEvents = com.example.core.reports.FamilyReportPdfDocumentBuilder.filteredModuleEvents(report)
            if ((report.includeLoansSection || report.includeSavingsSection || report.includeInvestmentsSection) &&
                moduleEvents.isNotEmpty()
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Loan, Savings & Investment Events (${moduleEvents.size})", style = MaterialTheme.typography.labelLarge)
                moduleEvents.take(5).forEach { evt ->
                    ReportStatLine(
                        label = "${formatModuleDate(evt.eventDateMillis)} • ${evt.moduleType} (${evt.eventType.replace('_', ' ')})",
                        value = MoneyCalculator.formatMinorUnits(evt.amountMinor, report.currency),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
