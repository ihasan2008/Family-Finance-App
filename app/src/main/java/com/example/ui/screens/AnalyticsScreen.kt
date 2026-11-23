package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.core.model.AppModule
import com.example.core.model.MoneyCalculator
import com.example.core.model.UiState
import com.example.ui.components.AnalyticsChartCard
import com.example.ui.components.ExpenseCategoryDonutChart
import com.example.ui.components.InvestmentPerformanceChart
import com.example.ui.components.MemberExpenseHorizontalChart
import com.example.ui.components.MonthlyCashFlowNetChart
import com.example.ui.components.MonthlyIncomeExpenseBarChart
import com.example.ui.components.SavingsGrowthLineChart
import com.example.ui.components.YearlyIncomeExpenseBarChart
import com.example.ui.screens.modules.ModuleDatePickerRow
import com.example.ui.screens.modules.ModuleFinancialRuleBanner
import com.example.ui.screens.modules.ModuleNoFamilyState
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyDisplayStyle
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.WarningAmber

@Composable
fun AnalyticsScreen(
    familiesState: UiState<List<LocalFamilyEntity>>,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    transactions: List<LocalTransactionEntity>,
    loans: List<LocalLoanEntity>,
    savings: List<LocalSavingsEntity>,
    investments: List<LocalInvestmentEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    onSyncNow: () -> Unit,
    onNavigateToModule: (AppModule) -> Unit,
    modifier: Modifier = Modifier
) {
    when (familiesState) {
        is UiState.Loading -> {
            AnalyticsLoadingState(modifier = modifier)
            return
        }
        is UiState.Error -> {
            AnalyticsErrorState(
                message = familiesState.message,
                actionLabel = "Retry Sync",
                onAction = onSyncNow,
                modifier = modifier
            )
            return
        }
        is UiState.Success -> Unit
    }

    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Financial Analytics",
            moduleSubtitle = "Create or join a family workspace to inspect real-time Net Worth, Cash Flow, Savings Growth, and Investment charts.",
            onNavigateToFamilyManagement = { onNavigateToModule(AppModule.FAMILY_MANAGEMENT) },
            modifier = modifier
        )
        return
    }

    var filterSpec by remember(activeFamily.familyId) {
        mutableStateOf(AnalyticsFilterSpec(preset = AnalyticsDateFilter.THIS_MONTH))
    }

    val snapshot = remember(
        activeFamily.familyId,
        activeFamily.currencyCode,
        transactions,
        loans,
        savings,
        investments,
        financialEvents,
        activeMembers,
        filterSpec
    ) {
        FinancialAnalyticsEngine.buildSnapshot(
            familyId = activeFamily.familyId,
            currencyCode = activeFamily.currencyCode,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = financialEvents,
            members = activeMembers,
            filterSpec = filterSpec
        )
    }

    val currency = snapshot.currency
    val summary = snapshot.summary

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        val isWide = maxWidth >= 840.dp
        val isMedium = maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1240.dp)
                .padding(horizontal = if (isMedium) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header + Filter Bar
            AnalyticsHeaderAndFiltersCard(
                familyName = activeFamily.name,
                syncStatusLabel = syncStatusLabel,
                filterSpec = filterSpec,
                onFilterChanged = { filterSpec = it },
                onSyncNow = onSyncNow
            )

            // Custom range validation error state
            if (snapshot.filterError != null) {
                AnalyticsInlineErrorCard(
                    message = snapshot.filterError,
                    onReset = { filterSpec = AnalyticsFilterSpec(AnalyticsDateFilter.THIS_MONTH) }
                )
            }

            // Net Worth & Total Family Balance Hero + 5 KPI Grid
            AnalyticsSummarySection(
                summary = summary,
                currency = currency,
                filterLabel = filterSpec.preset.label,
                isWide = isWide,
                isMedium = isMedium,
                onNavigateToModule = onNavigateToModule
            )

            ModuleFinancialRuleBanner(
                text = "Unified financial engine: loan principal/repayments & savings transfers never inflate Income or Expenses; investment profit (+${MoneyCalculator.formatMinorUnits(summary.investmentProfitMinor, currency)}) and loss (-${MoneyCalculator.formatMinorUnits(summary.investmentLossMinor, currency)}) stay strictly separated from principal."
            )

            if (snapshot.totalRecordCount == 0) {
                AnalyticsEmptyWorkspaceCard(onNavigateToModule = onNavigateToModule)
            }

            // Responsive 7-Chart Grid
            val hasMonthlyData = snapshot.monthlySeries.any { it.incomeMinor > 0L || it.expenseMinor > 0L }
            val hasYearlyData = snapshot.yearlySeries.any { it.incomeMinor > 0L || it.expenseMinor > 0L }

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnalyticsChartCard(
                        title = "1. Monthly Income vs Expense (${snapshot.referenceYear})",
                        subtitle = "Verified monthly cash inflows vs. outflows",
                        isEmpty = !hasMonthlyData,
                        emptyIcon = Icons.Outlined.BarChart,
                        testTag = "chart_monthly_income_expense",
                        modifier = Modifier.weight(1f)
                    ) {
                        MonthlyIncomeExpenseBarChart(data = snapshot.monthlySeries, currency = currency)
                    }
                    AnalyticsChartCard(
                        title = "7. Monthly Cash Flow (${snapshot.referenceYear})",
                        subtitle = "Net monthly surplus / deficit above & below zero",
                        isEmpty = !hasMonthlyData,
                        emptyIcon = Icons.Outlined.Timeline,
                        testTag = "chart_monthly_cash_flow",
                        modifier = Modifier.weight(1f)
                    ) {
                        MonthlyCashFlowNetChart(data = snapshot.monthlySeries, currency = currency)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnalyticsChartCard(
                        title = "2. Yearly Income vs Expense",
                        subtitle = "Multi-year cash flow comparison",
                        isEmpty = !hasYearlyData,
                        emptyIcon = Icons.Outlined.CalendarMonth,
                        testTag = "chart_yearly_income_expense",
                        modifier = Modifier.weight(1f)
                    ) {
                        YearlyIncomeExpenseBarChart(data = snapshot.yearlySeries, currency = currency)
                    }
                    AnalyticsChartCard(
                        title = "3. Expense Category Distribution",
                        subtitle = "${filterSpec.preset.label} breakdown across ${snapshot.expenseCategories.size} category(ies)",
                        isEmpty = snapshot.expenseCategories.isEmpty(),
                        emptyIcon = Icons.Outlined.PieChart,
                        emptyTitle = "No expenses in ${filterSpec.preset.label}",
                        testTag = "chart_expense_categories",
                        modifier = Modifier.weight(1f)
                    ) {
                        ExpenseCategoryDonutChart(slices = snapshot.expenseCategories, currency = currency)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnalyticsChartCard(
                        title = "4. Savings Growth",
                        subtitle = "Cumulative goal reserves over recorded deposits & withdrawals",
                        isEmpty = snapshot.savingsGrowthSeries.isEmpty(),
                        emptyIcon = Icons.Outlined.Savings,
                        emptyTitle = "No savings activity yet",
                        testTag = "chart_savings_growth",
                        modifier = Modifier.weight(1f)
                    ) {
                        SavingsGrowthLineChart(points = snapshot.savingsGrowthSeries, currency = currency)
                    }
                    AnalyticsChartCard(
                        title = "5. Investment Performance",
                        subtitle = "Principal cost basis vs. current market valuation",
                        isEmpty = snapshot.investmentSeries.isEmpty(),
                        emptyIcon = Icons.AutoMirrored.Outlined.ShowChart,
                        emptyTitle = "No investment holdings yet",
                        testTag = "chart_investment_performance",
                        modifier = Modifier.weight(1f)
                    ) {
                        InvestmentPerformanceChart(items = snapshot.investmentSeries, currency = currency)
                    }
                }

                AnalyticsChartCard(
                    title = "6. Member-wise Expenses (${filterSpec.preset.label})",
                    subtitle = "Verified household spending attributed per family member",
                    isEmpty = snapshot.memberExpenses.isEmpty(),
                    emptyIcon = Icons.Outlined.Groups,
                    emptyTitle = "No member expenses in ${filterSpec.preset.label}",
                    testTag = "chart_member_expenses",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MemberExpenseHorizontalChart(members = snapshot.memberExpenses, currency = currency)
                }
            } else {
                AnalyticsChartCard(
                    title = "1. Monthly Income vs Expense (${snapshot.referenceYear})",
                    subtitle = "Verified monthly cash inflows vs. outflows",
                    isEmpty = !hasMonthlyData,
                    emptyIcon = Icons.Outlined.BarChart,
                    testTag = "chart_monthly_income_expense"
                ) {
                    MonthlyIncomeExpenseBarChart(data = snapshot.monthlySeries, currency = currency)
                }
                AnalyticsChartCard(
                    title = "2. Yearly Income vs Expense",
                    subtitle = "Multi-year cash flow comparison",
                    isEmpty = !hasYearlyData,
                    emptyIcon = Icons.Outlined.CalendarMonth,
                    testTag = "chart_yearly_income_expense"
                ) {
                    YearlyIncomeExpenseBarChart(data = snapshot.yearlySeries, currency = currency)
                }
                AnalyticsChartCard(
                    title = "3. Expense Category Distribution",
                    subtitle = "${filterSpec.preset.label} breakdown across ${snapshot.expenseCategories.size} category(ies)",
                    isEmpty = snapshot.expenseCategories.isEmpty(),
                    emptyIcon = Icons.Outlined.PieChart,
                    emptyTitle = "No expenses in ${filterSpec.preset.label}",
                    testTag = "chart_expense_categories"
                ) {
                    ExpenseCategoryDonutChart(slices = snapshot.expenseCategories, currency = currency)
                }
                AnalyticsChartCard(
                    title = "4. Savings Growth",
                    subtitle = "Cumulative goal reserves over recorded deposits & withdrawals",
                    isEmpty = snapshot.savingsGrowthSeries.isEmpty(),
                    emptyIcon = Icons.Outlined.Savings,
                    emptyTitle = "No savings activity yet",
                    testTag = "chart_savings_growth"
                ) {
                    SavingsGrowthLineChart(points = snapshot.savingsGrowthSeries, currency = currency)
                }
                AnalyticsChartCard(
                    title = "5. Investment Performance",
                    subtitle = "Principal cost basis vs. current market valuation",
                    isEmpty = snapshot.investmentSeries.isEmpty(),
                    emptyIcon = Icons.AutoMirrored.Outlined.ShowChart,
                    emptyTitle = "No investment holdings yet",
                    testTag = "chart_investment_performance"
                ) {
                    InvestmentPerformanceChart(items = snapshot.investmentSeries, currency = currency)
                }
                AnalyticsChartCard(
                    title = "6. Member-wise Expenses (${filterSpec.preset.label})",
                    subtitle = "Verified household spending attributed per family member",
                    isEmpty = snapshot.memberExpenses.isEmpty(),
                    emptyIcon = Icons.Outlined.Groups,
                    emptyTitle = "No member expenses in ${filterSpec.preset.label}",
                    testTag = "chart_member_expenses"
                ) {
                    MemberExpenseHorizontalChart(members = snapshot.memberExpenses, currency = currency)
                }
                AnalyticsChartCard(
                    title = "7. Monthly Cash Flow (${snapshot.referenceYear})",
                    subtitle = "Net monthly surplus / deficit above & below zero",
                    isEmpty = !hasMonthlyData,
                    emptyIcon = Icons.Outlined.Timeline,
                    testTag = "chart_monthly_cash_flow"
                ) {
                    MonthlyCashFlowNetChart(data = snapshot.monthlySeries, currency = currency)
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AnalyticsHeaderAndFiltersCard(
    familyName: String,
    syncStatusLabel: String,
    filterSpec: AnalyticsFilterSpec,
    onFilterChanged: (AnalyticsFilterSpec) -> Unit,
    onSyncNow: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Financial Dashboard & Analytics",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$familyName • $syncStatusLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onSyncNow,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("analytics_sync_button")
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsDateFilter.entries.forEach { preset ->
                    FilterChip(
                        selected = filterSpec.preset == preset,
                        onClick = {
                            if (preset == AnalyticsDateFilter.CUSTOM) {
                                val now = System.currentTimeMillis()
                                onFilterChanged(
                                    filterSpec.copy(
                                        preset = preset,
                                        customStartMillis = filterSpec.customStartMillis ?: (now - 30L * 24L * 3600_000L),
                                        customEndMillis = filterSpec.customEndMillis ?: now
                                    )
                                )
                            } else {
                                onFilterChanged(filterSpec.copy(preset = preset))
                            }
                        },
                        label = { Text(preset.label) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("analytics_filter_${preset.name.lowercase()}")
                    )
                }
            }

            AnimatedVisibility(visible = filterSpec.preset == AnalyticsDateFilter.CUSTOM) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModuleDatePickerRow(
                        label = "Start Date",
                        selectedMillis = filterSpec.customStartMillis,
                        allowClear = false,
                        testTag = "analytics_custom_start_picker",
                        onDateSelected = { picked ->
                            onFilterChanged(filterSpec.copy(customStartMillis = picked))
                        }
                    )
                    ModuleDatePickerRow(
                        label = "End Date",
                        selectedMillis = filterSpec.customEndMillis,
                        allowClear = false,
                        testTag = "analytics_custom_end_picker",
                        onDateSelected = { picked ->
                            onFilterChanged(filterSpec.copy(customEndMillis = picked))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSummarySection(
    summary: com.example.core.analytics.DashboardKpiSummary,
    currency: String,
    filterLabel: String,
    isWide: Boolean,
    isMedium: Boolean,
    onNavigateToModule: (AppModule) -> Unit
) {
    val heroCard: @Composable (Modifier) -> Unit = { mod ->
        Card(
            modifier = mod.testTag("analytics_net_worth_card"),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                text = "Net Worth",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "Live Ledger",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = MoneyCalculator.formatMinorUnits(summary.netWorthMinor, currency),
                        style = MoneyDisplayStyle,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("analytics_net_worth_value")
                    )

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Family Balance",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            )
                            Text(
                                text = MoneyCalculator.formatMinorUnits(summary.totalFamilyBalanceMinor, currency),
                                style = MoneyMediumStyle.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("analytics_family_balance_value")
                            )
                        }
                    }

                    Text(
                        text = "Balance + Savings + Investments + Lent − Borrowed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }

    val kpiCards: List<@Composable (Modifier) -> Unit> = listOf(
        { m ->
            AnalyticsKpiCard(
                title = "Total Income ($filterLabel)",
                value = MoneyCalculator.formatMinorUnits(summary.totalIncomeMinor, currency),
                supporting = "${summary.incomeCount} entry(ies) • Savings rate ${summary.savingsRatePercent}%",
                icon = Icons.Outlined.ArrowDownward,
                accentColor = IncomeGreen,
                onClick = { onNavigateToModule(AppModule.INCOME) },
                testTag = "analytics_kpi_income",
                modifier = m
            )
        },
        { m ->
            AnalyticsKpiCard(
                title = "Total Expenses ($filterLabel)",
                value = MoneyCalculator.formatMinorUnits(summary.totalExpenseMinor, currency),
                supporting = "${summary.expenseCount} entry(ies) • Net ${MoneyCalculator.formatMinorUnits(summary.periodNetCashFlowMinor, currency)}",
                icon = Icons.Outlined.ArrowUpward,
                accentColor = ExpenseCoral,
                onClick = { onNavigateToModule(AppModule.EXPENSE) },
                testTag = "analytics_kpi_expense",
                modifier = m
            )
        },
        { m ->
            AnalyticsKpiCard(
                title = "Total Savings",
                value = MoneyCalculator.formatMinorUnits(summary.totalSavingsMinor, currency),
                supporting = "${summary.savingsCompletionPercent}% of ${MoneyCalculator.formatMinorUnits(summary.totalSavingsTargetMinor, currency)} target",
                icon = Icons.Outlined.Savings,
                accentColor = InfoTeal,
                onClick = { onNavigateToModule(AppModule.SAVINGS) },
                testTag = "analytics_kpi_savings",
                modifier = m
            )
        },
        { m ->
            AnalyticsKpiCard(
                title = "Outstanding Loans",
                value = MoneyCalculator.formatMinorUnits(summary.outstandingLoansBorrowedMinor, currency),
                supporting = buildString {
                    append("Lent: ${MoneyCalculator.formatMinorUnits(summary.outstandingLoansLentMinor, currency)}")
                    if (summary.overdueLoansCount > 0) append(" • ${summary.overdueLoansCount} overdue")
                },
                icon = Icons.Outlined.AccountBalance,
                accentColor = if (summary.outstandingLoansBorrowedMinor > 0L) WarningAmber else IncomeGreen,
                onClick = { onNavigateToModule(AppModule.LOANS) },
                testTag = "analytics_kpi_loans",
                modifier = m
            )
        },
        { m ->
            AnalyticsKpiCard(
                title = "Investment Value",
                value = MoneyCalculator.formatMinorUnits(summary.investmentValueMinor, currency),
                supporting = "+${MoneyCalculator.formatMinorUnits(summary.investmentProfitMinor, currency)} / -${MoneyCalculator.formatMinorUnits(summary.investmentLossMinor, currency)}",
                icon = Icons.AutoMirrored.Outlined.TrendingUp,
                accentColor = if (summary.investmentProfitMinor >= summary.investmentLossMinor) IncomeGreen else ExpenseCoral,
                onClick = { onNavigateToModule(AppModule.INVESTMENTS) },
                testTag = "analytics_kpi_investments",
                modifier = m
            )
        }
    )

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            heroCard(Modifier.weight(1.1f))
            Column(
                modifier = Modifier.weight(1.5f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    kpiCards[0](Modifier.weight(1f))
                    kpiCards[1](Modifier.weight(1f))
                    kpiCards[2](Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    kpiCards[3](Modifier.weight(1f))
                    kpiCards[4](Modifier.weight(1f))
                }
            }
        }
    } else {
        heroCard(Modifier.fillMaxWidth())
        val cols = if (isMedium) 3 else 2
        kpiCards.chunked(cols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { card -> card(Modifier.weight(1f)) }
                repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AnalyticsKpiCard(
    title: String,
    value: String,
    supporting: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MoneyMediumStyle.copy(fontWeight = FontWeight.Bold),
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AnalyticsLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag("analytics_loading_indicator"))
            Text(
                text = "Loading encrypted family financial ledger...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnalyticsErrorState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .testTag("analytics_error_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                Text("Unable to load analytics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onAction, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsInlineErrorCard(
    message: String,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analytics_filter_error_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.minimumInteractiveComponentSize()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun AnalyticsEmptyWorkspaceCard(
    onNavigateToModule: (AppModule) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analytics_empty_workspace_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Your family ledger is ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Start adding Income, Expenses, Savings goals, Loans, or Investments and all 7 charts will populate automatically with decimal-safe calculations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onNavigateToModule(AppModule.INCOME) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Add Income")
                }
                OutlinedButton(onClick = { onNavigateToModule(AppModule.EXPENSE) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Add Expense")
                }
                OutlinedButton(onClick = { onNavigateToModule(AppModule.SAVINGS) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Savings Goal")
                }
                OutlinedButton(onClick = { onNavigateToModule(AppModule.INVESTMENTS) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Investment")
                }
            }
        }
    }
}
