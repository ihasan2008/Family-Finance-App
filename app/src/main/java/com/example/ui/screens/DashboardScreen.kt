package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.core.model.AppModule
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import com.example.core.model.UiState
import com.example.ui.components.AnalyticsChartCard
import com.example.ui.components.FinancialStatCard
import com.example.ui.components.ModuleQuickCard
import com.example.ui.components.MonthlyIncomeExpenseBarChart
import com.example.ui.components.SectionHeader
import com.example.ui.screens.modules.BudgetAlertLevel
import com.example.ui.screens.modules.BudgetSummaryCalculator
import com.example.ui.screens.modules.LoansSummaryCalculator
import com.example.ui.screens.modules.SavingsSummaryCalculator
import com.example.ui.screens.modules.formatModuleDate
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.MoneyDisplayStyle
import com.example.ui.theme.MoneySmallStyle
import com.example.ui.theme.WarningAmber

@Composable
fun DashboardScreen(
    currentUserId: String,
    userDisplayName: String,
    familiesState: UiState<List<LocalFamilyEntity>>,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    transactions: List<LocalTransactionEntity>,
    loans: List<LocalLoanEntity>,
    savings: List<LocalSavingsEntity>,
    investments: List<LocalInvestmentEntity>,
    budgets: List<LocalBudgetEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    onSyncNow: () -> Unit,
    onNavigateToModule: (AppModule) -> Unit,
    onOpenAllModules: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember(activeFamily?.familyId) {
        mutableStateOf(AnalyticsDateFilter.THIS_MONTH)
    }

    val snapshot = remember(
        activeFamily?.familyId,
        activeFamily?.currencyCode,
        transactions,
        loans,
        savings,
        investments,
        financialEvents,
        activeMembers,
        selectedFilter
    ) {
        if (activeFamily == null) null
        else FinancialAnalyticsEngine.buildSnapshot(
            familyId = activeFamily.familyId,
            currencyCode = activeFamily.currencyCode,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = financialEvents,
            members = activeMembers,
            filterSpec = AnalyticsFilterSpec(preset = selectedFilter)
        )
    }

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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ActiveWorkspaceContextBanner(
                userDisplayName = userDisplayName,
                userId = currentUserId,
                activeFamily = activeFamily,
                syncStatusLabel = syncStatusLabel,
                onManageFamily = { onNavigateToModule(AppModule.FAMILY_MANAGEMENT) }
            )

            when (familiesState) {
                is UiState.Loading -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Text("Loading real-time family financial ledger...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is UiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(familiesState.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onSyncNow, modifier = Modifier.minimumInteractiveComponentSize()) {
                                Text("Retry")
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    if (activeFamily == null || snapshot == null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Create or join a family workspace",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Once your family workspace is active, your Net Worth, Income, Expenses, Savings, Loans, and Investment analytics appear here automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { onNavigateToModule(AppModule.FAMILY_MANAGEMENT) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("Setup Family Workspace")
                                }
                            }
                        }
                    } else {
                        val currency = snapshot.currency
                        val summary = snapshot.summary

                        // Quick date filter strip on Dashboard (Today / Week / Month / Year)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AnalyticsDateFilter.TODAY,
                                AnalyticsDateFilter.THIS_WEEK,
                                AnalyticsDateFilter.THIS_MONTH,
                                AnalyticsDateFilter.THIS_YEAR
                            ).forEach { preset ->
                                FilterChip(
                                    selected = selectedFilter == preset,
                                    onClick = { selectedFilter = preset },
                                    label = { Text(preset.label) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("dashboard_filter_${preset.name.lowercase()}")
                                )
                            }
                        }

                        // Hero Net Worth & Total Family Balance + Core KPIs
                        val statCards: List<@Composable (Modifier) -> Unit> = listOf(
                            { m ->
                                FinancialStatCard(
                                    title = "Total Income (${selectedFilter.label})",
                                    amount = MoneyCalculator.formatMinorUnits(summary.totalIncomeMinor, currency),
                                    deltaLabel = "${summary.incomeCount} entry(ies) • Rate ${summary.savingsRatePercent}%",
                                    icon = Icons.Outlined.ArrowDownward,
                                    positiveTrend = true,
                                    badgeLabel = "LIVE",
                                    onClick = { onNavigateToModule(AppModule.INCOME) },
                                    modifier = m
                                )
                            },
                            { m ->
                                FinancialStatCard(
                                    title = "Total Expenses (${selectedFilter.label})",
                                    amount = MoneyCalculator.formatMinorUnits(summary.totalExpenseMinor, currency),
                                    deltaLabel = "${summary.expenseCount} entry(ies) • Net ${MoneyCalculator.formatMinorUnits(summary.periodNetCashFlowMinor, currency)}",
                                    icon = Icons.Outlined.ArrowUpward,
                                    positiveTrend = summary.periodNetCashFlowMinor >= 0L,
                                    badgeLabel = "LIVE",
                                    onClick = { onNavigateToModule(AppModule.EXPENSE) },
                                    modifier = m
                                )
                            },
                            { m ->
                                FinancialStatCard(
                                    title = "Total Savings",
                                    amount = MoneyCalculator.formatMinorUnits(summary.totalSavingsMinor, currency),
                                    deltaLabel = "${summary.savingsCompletionPercent}% of ${MoneyCalculator.formatMinorUnits(summary.totalSavingsTargetMinor, currency)}",
                                    icon = Icons.Outlined.Savings,
                                    positiveTrend = true,
                                    badgeLabel = "LIVE",
                                    onClick = { onNavigateToModule(AppModule.SAVINGS) },
                                    modifier = m
                                )
                            },
                            { m ->
                                FinancialStatCard(
                                    title = "Investment Value",
                                    amount = MoneyCalculator.formatMinorUnits(summary.investmentValueMinor, currency),
                                    deltaLabel = "+${MoneyCalculator.formatMinorUnits(summary.investmentProfitMinor, currency)} / -${MoneyCalculator.formatMinorUnits(summary.investmentLossMinor, currency)}",
                                    icon = Icons.AutoMirrored.Outlined.TrendingUp,
                                    positiveTrend = summary.investmentProfitMinor >= summary.investmentLossMinor,
                                    badgeLabel = "LIVE",
                                    onClick = { onNavigateToModule(AppModule.INVESTMENTS) },
                                    modifier = m
                                )
                            }
                        )

                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                NetWorthHeroCard(
                                    activeFamilyName = activeFamily.name,
                                    netWorthFormatted = MoneyCalculator.formatMinorUnits(summary.netWorthMinor, currency),
                                    familyBalanceFormatted = MoneyCalculator.formatMinorUnits(summary.totalFamilyBalanceMinor, currency),
                                    outstandingLoansFormatted = MoneyCalculator.formatMinorUnits(summary.outstandingLoansBorrowedMinor, currency),
                                    modifier = Modifier.weight(1.1f),
                                    onAnalyticsClick = { onNavigateToModule(AppModule.ANALYTICS) }
                                )
                                Column(
                                    modifier = Modifier.weight(1.4f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        statCards[0](Modifier.weight(1f))
                                        statCards[1](Modifier.weight(1f))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        statCards[2](Modifier.weight(1f))
                                        statCards[3](Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            NetWorthHeroCard(
                                activeFamilyName = activeFamily.name,
                                netWorthFormatted = MoneyCalculator.formatMinorUnits(summary.netWorthMinor, currency),
                                familyBalanceFormatted = MoneyCalculator.formatMinorUnits(summary.totalFamilyBalanceMinor, currency),
                                outstandingLoansFormatted = MoneyCalculator.formatMinorUnits(summary.outstandingLoansBorrowedMinor, currency),
                                modifier = Modifier.fillMaxWidth(),
                                onAnalyticsClick = { onNavigateToModule(AppModule.ANALYTICS) }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    statCards[0](Modifier.weight(1f))
                                    statCards[1](Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    statCards[2](Modifier.weight(1f))
                                    statCards[3](Modifier.weight(1f))
                                }
                            }
                        }

                        // Real Monthly Income vs Expense Chart + Real Budgets
                        val hasMonthlyActivity = snapshot.monthlySeries.any { it.incomeMinor > 0L || it.expenseMinor > 0L }
                        val cashFlowCard: @Composable (Modifier) -> Unit = { m ->
                            AnalyticsChartCard(
                                title = "Monthly Income vs Expense (${snapshot.referenceYear})",
                                subtitle = "Verified household cash flow from encrypted local ledger",
                                isEmpty = !hasMonthlyActivity,
                                emptyTitle = "No transactions yet",
                                emptySubtitle = "Add an income or expense entry to view monthly trends.",
                                headerAction = {
                                    OutlinedButton(
                                        onClick = { onNavigateToModule(AppModule.ANALYTICS) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("7 Charts")
                                    }
                                },
                                modifier = m
                            ) {
                                MonthlyIncomeExpenseBarChart(data = snapshot.monthlySeries, currency = currency)
                            }
                        }

                        if (isMedium) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                cashFlowCard(Modifier.weight(1.15f))
                                RealBudgetsSectionCard(
                                    budgets = budgets,
                                    transactions = transactions,
                                    currency = currency,
                                    onOpenBudgets = { onNavigateToModule(AppModule.BUDGETS) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            cashFlowCard(Modifier.fillMaxWidth())
                            RealBudgetsSectionCard(
                                budgets = budgets,
                                transactions = transactions,
                                currency = currency,
                                onOpenBudgets = { onNavigateToModule(AppModule.BUDGETS) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Real Savings Goals + Real Loans & Recent Activity
                        if (isMedium) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                RealSavingsGoalsSectionCard(
                                    savings = savings,
                                    currency = currency,
                                    onOpenSavings = { onNavigateToModule(AppModule.SAVINGS) },
                                    modifier = Modifier.weight(1f)
                                )
                                RealLoansAndActivitySectionCard(
                                    loans = loans,
                                    transactions = transactions,
                                    members = activeMembers,
                                    currency = currency,
                                    onOpenLoans = { onNavigateToModule(AppModule.LOANS) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            RealSavingsGoalsSectionCard(
                                savings = savings,
                                currency = currency,
                                onOpenSavings = { onNavigateToModule(AppModule.SAVINGS) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            RealLoansAndActivitySectionCard(
                                loans = loans,
                                transactions = transactions,
                                members = activeMembers,
                                currency = currency,
                                onOpenLoans = { onNavigateToModule(AppModule.LOANS) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            SectionHeader(
                title = "All ${AppModule.entries.size} Modules",
                subtitle = "Navigate directly to any Family Finance module",
                actionLabel = "View All",
                onActionClick = onOpenAllModules
            )

            val featuredModules = listOf(
                AppModule.ANALYTICS,
                AppModule.INCOME,
                AppModule.EXPENSE,
                AppModule.LOANS,
                AppModule.SAVINGS,
                AppModule.BUDGETS
            )
            val cols = if (isWide) 3 else 2
            featuredModules.chunked(cols).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { module ->
                        ModuleQuickCard(
                            module = module,
                            onClick = { onNavigateToModule(module) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActiveWorkspaceContextBanner(
    userDisplayName: String,
    userId: String,
    activeFamily: LocalFamilyEntity?,
    syncStatusLabel: String,
    onManageFamily: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Welcome back, $userDisplayName",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (activeFamily != null) {
                        "Active Family: ${activeFamily.name} (${activeFamily.familyId}) • $syncStatusLabel"
                    } else {
                        "UID: ${userId.take(12)}... • No family selected yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onManageFamily)
                    .testTag("dashboard_manage_family_button"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Diversity3,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (activeFamily == null) "Setup Family" else "Families",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun NetWorthHeroCard(
    activeFamilyName: String?,
    netWorthFormatted: String,
    familyBalanceFormatted: String,
    outstandingLoansFormatted: String,
    onAnalyticsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
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
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            text = "${activeFamilyName ?: "Household"} Net Worth",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "Live Database",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = netWorthFormatted,
                    style = MoneyDisplayStyle,
                    color = MaterialTheme.colorScheme.onPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Family Balance: $familyBalanceFormatted",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
                        )
                        Text(
                            text = "Loans Outstanding: $outstandingLoansFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        )
                    }
                    Button(
                        onClick = onAnalyticsClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("dashboard_open_analytics_button")
                    ) {
                        Text("Analytics")
                    }
                }
            }
        }
    }
}

@Composable
private fun RealBudgetsSectionCard(
    budgets: List<LocalBudgetEntity>,
    transactions: List<LocalTransactionEntity>,
    currency: String,
    onOpenBudgets: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPeriod = remember { BudgetSummaryCalculator.currentPeriodKey() }
    val summary = remember(budgets, transactions, currentPeriod) {
        BudgetSummaryCalculator.summarizeForPeriod(budgets, transactions, currentPeriod)
    }
    val evaluatedList = remember(summary) {
        (listOfNotNull(summary.overallBudget) + summary.categoryBudgets).take(4)
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Monthly Budgets",
                subtitle = BudgetSummaryCalculator.formatPeriodLabel(currentPeriod),
                actionLabel = "Budgets",
                onActionClick = onOpenBudgets
            )
            if (evaluatedList.isEmpty()) {
                Text(
                    text = "No budgets configured for ${BudgetSummaryCalculator.formatPeriodLabel(currentPeriod)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                evaluatedList.forEach { item ->
                    val barColor = when (item.alertLevel) {
                        BudgetAlertLevel.EXCEEDED -> ExpenseCoral
                        BudgetAlertLevel.WARNING -> WarningAmber
                        BudgetAlertLevel.OK -> MaterialTheme.colorScheme.primary
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.entity.category,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${MoneyCalculator.formatMinorUnits(item.spentMinor, currency)} / ${MoneyCalculator.formatMinorUnits(item.limitMinor, currency)}",
                                style = MoneySmallStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { item.usageFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50)),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RealSavingsGoalsSectionCard(
    savings: List<LocalSavingsEntity>,
    currency: String,
    onOpenSavings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = remember(savings) { SavingsSummaryCalculator.summarize(savings) }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Family Savings Goals",
                subtitle = "${summary.goals.size} active/completed goal(s)",
                actionLabel = "Savings",
                onActionClick = onOpenSavings
            )
            if (summary.goals.isEmpty()) {
                Text(
                    text = "No savings goals created yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                summary.goals.take(3).forEach { goal ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = goal.entity.goalName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${goal.progressPercent}%",
                                style = MoneySmallStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { goal.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${MoneyCalculator.formatMinorUnits(goal.currentMinor, currency)} saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = goal.entity.targetDateMillis?.let { "Target ${formatModuleDate(it)}" }
                                    ?: "Target ${MoneyCalculator.formatMinorUnits(goal.targetMinor, currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RealLoansAndActivitySectionCard(
    loans: List<LocalLoanEntity>,
    transactions: List<LocalTransactionEntity>,
    members: List<LocalFamilyMemberEntity>,
    currency: String,
    onOpenLoans: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loansSummary = remember(loans) { LoansSummaryCalculator.summarize(loans) }
    val memberLookup = remember(members) { members.associate { it.userId to it.displayName.ifBlank { it.userId } } }
    val recentTx = remember(transactions) {
        transactions
            .filter { !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) }
            .sortedByDescending { it.occurredAtMillis }
            .take(4)
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Loans & Recent Activity",
                subtitle = "Live obligations & verified ledger feed",
                actionLabel = "Loans",
                onActionClick = onOpenLoans
            )
            if (loansSummary.evaluatedLoans.isEmpty()) {
                Text(
                    text = "No active or settled family loans recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                loansSummary.evaluatedLoans.take(2).forEach { loan ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountBalance,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "${loan.direction.label}: ${loan.entity.counterparty}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append("Remaining ${MoneyCalculator.formatMinorUnits(loan.remainingMinor, currency)}")
                                            loan.entity.dueDateMillis?.let { append(" • Due ${formatModuleDate(it)}") }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = MoneyCalculator.formatMinorUnits(loan.nextInstallmentDisplayMinor, currency),
                                style = MoneySmallStyle,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (recentTx.isEmpty()) {
                Text(
                    text = "No recent income or expense entries yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recentTx.forEach { tx ->
                    val isIncome = tx.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
                    val memberName = memberLookup[tx.effectiveMemberId()] ?: "Member"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isIncome) IncomeGreen else ExpenseCoral)
                            )
                            Column {
                                Text(
                                    text = tx.description.ifBlank { tx.category },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${tx.category} • $memberName • ${formatModuleDate(tx.occurredAtMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val sign = if (isIncome) "+" else "-"
                        Text(
                            text = "$sign${MoneyCalculator.formatMinorUnits(tx.effectiveAmountMinor(), currency)}",
                            style = MoneySmallStyle,
                            color = if (isIncome) IncomeGreen else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
