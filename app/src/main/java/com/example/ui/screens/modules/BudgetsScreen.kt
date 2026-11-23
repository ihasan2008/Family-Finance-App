package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.BudgetScope
import com.example.core.model.FinancialModuleType
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.WarningAmber

private enum class BudgetsViewTab(val label: String) {
    ACTIVE_MONTH("Active Budgets"),
    HISTORY("Budget History")
}

@Composable
fun BudgetsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    budgets: List<LocalBudgetEntity>,
    expenseTransactions: List<LocalTransactionEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onCreateBudget: (
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        description: String
    ) -> Unit,
    onUpdateBudget: (
        recordId: String,
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        description: String
    ) -> Unit,
    onDeleteBudget: (recordId: String) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Budget Management",
            moduleSubtitle = "Create or join a family workspace to set monthly and category spending limits backed by live Expense ledger totals.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val currency = activeFamily.currencyCode.ifBlank { "USD" }
    val isAdmin = activeFamily.isAdmin(currentUserId)
    val myMember = remember(activeMembers, currentUserId) {
        activeMembers.firstOrNull { it.userId == currentUserId }
    }
    val canCreate = isAdmin || (myMember?.canCreateRecords ?: true)
    val memberLookup = remember(activeMembers) {
        activeMembers.associate { it.userId to it.displayName.ifBlank { it.userId } }
    }

    val periodOptions = remember { BudgetSummaryCalculator.recentPeriodKeys(12) }
    var selectedPeriod by remember { mutableStateOf(BudgetSummaryCalculator.currentPeriodKey()) }
    var activeTab by remember { mutableStateOf(BudgetsViewTab.ACTIVE_MONTH) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<LocalBudgetEntity?>(null) }
    var inspectingBudget by remember { mutableStateOf<EvaluatedBudget?>(null) }
    var deletingBudget by remember { mutableStateOf<LocalBudgetEntity?>(null) }

    val periodSummary = remember(budgets, expenseTransactions, selectedPeriod) {
        BudgetSummaryCalculator.summarizeForPeriod(budgets, expenseTransactions, selectedPeriod)
    }
    val historyRows = remember(budgets, expenseTransactions) {
        BudgetSummaryCalculator.buildHistory(budgets, expenseTransactions)
    }
    val budgetEvents = remember(financialEvents) {
        financialEvents.filter { it.moduleType == FinancialModuleType.BUDGETS.name }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isMedium = maxWidth >= 600.dp
        val isWide = maxWidth >= 840.dp

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1200.dp),
                contentPadding = PaddingValues(
                    horizontal = if (isMedium) 24.dp else 16.dp,
                    vertical = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "budgets_header") {
                    ModuleHeaderBanner(
                        title = "Budgets & Spending Limits",
                        familyName = activeFamily.name,
                        syncStatusLabel = syncStatusLabel,
                        canCreate = canCreate,
                        addButtonLabel = "Create Monthly / Category Budget",
                        addTestTag = "add_budget_button",
                        onAddClick = { showCreateDialog = true },
                        onSyncNow = onSyncNow
                    )
                }

                item(key = "budgets_rule_banner") {
                    ModuleFinancialRuleBanner(
                        text = "Accounting Integrity: Spending is computed live from recorded Expenses for the selected month. Monthly Overall limits and Category limits are shown separately so funds are never double-counted."
                    )
                }

                item(key = "budgets_tab_switcher") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        BudgetsViewTab.entries.forEachIndexed { idx, tab ->
                            SegmentedButton(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                shape = SegmentedButtonDefaults.itemShape(idx, BudgetsViewTab.entries.size),
                                modifier = Modifier.testTag("budgets_tab_${tab.name.lowercase()}")
                            ) {
                                Text(tab.label)
                            }
                        }
                    }
                }

                if (activeTab == BudgetsViewTab.ACTIVE_MONTH) {
                    item(key = "budgets_period_selector") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            periodOptions.forEach { period ->
                                FilterChip(
                                    selected = selectedPeriod == period,
                                    onClick = { selectedPeriod = period },
                                    label = { Text(BudgetSummaryCalculator.formatPeriodLabel(period)) }
                                )
                            }
                        }
                    }

                    if (periodSummary.exceededCount > 0 || periodSummary.warningCount > 0) {
                        item(key = "budgets_alert_banner") {
                            val isCritical = periodSummary.exceededCount > 0
                            val alertColor = if (isCritical) ExpenseCoral else WarningAmber
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = alertColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, alertColor.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = alertColor)
                                    Text(
                                        text = buildString {
                                            if (periodSummary.exceededCount > 0) {
                                                append("${periodSummary.exceededCount} budget(s) exceeded limit. ")
                                            }
                                            if (periodSummary.warningCount > 0) {
                                                append("${periodSummary.warningCount} budget(s) reached alert threshold.")
                                            }
                                        }.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = alertColor
                                    )
                                }
                            }
                        }
                    }

                    item(key = "budgets_kpis") {
                        val overall = periodSummary.overallBudget
                        if (isWide) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Month Expenses",
                                    value = MoneyCalculator.formatMinorUnits(periodSummary.totalMonthExpenseMinor, currency),
                                    supporting = BudgetSummaryCalculator.formatPeriodLabel(selectedPeriod),
                                    accentColor = ExpenseCoral,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Monthly Cap Remaining",
                                    value = overall?.let { MoneyCalculator.formatMinorUnits(it.remainingMinor, currency) } ?: "Not Set",
                                    supporting = overall?.let { "Limit ${MoneyCalculator.formatMinorUnits(it.limitMinor, currency)}" } ?: "Add Monthly Overall limit",
                                    accentColor = IncomeGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Category Allocations",
                                    value = MoneyCalculator.formatMinorUnits(periodSummary.totalCategoryAllocatedMinor, currency),
                                    supporting = "${periodSummary.categoryBudgets.size} category limit(s)",
                                    accentColor = InfoTeal,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Month Expenses",
                                    value = MoneyCalculator.formatMinorUnits(periodSummary.totalMonthExpenseMinor, currency),
                                    supporting = BudgetSummaryCalculator.formatPeriodLabel(selectedPeriod),
                                    accentColor = ExpenseCoral,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Overall Cap Left",
                                    value = overall?.let { MoneyCalculator.formatMinorUnits(it.remainingMinor, currency) } ?: "Not Set",
                                    supporting = "${periodSummary.categoryBudgets.size} category caps",
                                    accentColor = IncomeGreen,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    periodSummary.overallBudget?.let { overall ->
                        item(key = "overall_budget_card") {
                            BudgetCardItem(
                                item = overall,
                                currency = currency,
                                onClick = { inspectingBudget = overall }
                            )
                        }
                    }

                    if (periodSummary.overallBudget == null && periodSummary.categoryBudgets.isEmpty()) {
                        item(key = "budgets_empty") {
                            ModuleEmptyRecordsCard(
                                icon = Icons.Outlined.PieChart,
                                title = "No Budgets for ${BudgetSummaryCalculator.formatPeriodLabel(selectedPeriod)}",
                                subtitle = "Set a Monthly Overall cap or Category spending limits (Food, Shopping, Bills, etc.) with custom alert thresholds."
                            )
                        }
                    } else {
                        items(periodSummary.categoryBudgets, key = { it.entity.recordId }) { item ->
                            BudgetCardItem(
                                item = item,
                                currency = currency,
                                onClick = { inspectingBudget = item }
                            )
                        }
                    }
                } else {
                    if (historyRows.isEmpty()) {
                        item(key = "budget_history_empty") {
                            ModuleEmptyRecordsCard(
                                icon = Icons.Outlined.PieChart,
                                title = "No Budget History Yet",
                                subtitle = "Monthly budget adherence and alert logs across periods will appear here."
                            )
                        }
                    } else {
                        items(historyRows, key = { it.periodKey }) { row ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPeriod = row.periodKey
                                        activeTab = BudgetsViewTab.ACTIVE_MONTH
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = BudgetSummaryCalculator.formatPeriodLabel(row.periodKey),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${row.budgetCount} budget(s) • ${row.exceededCount} exceeded",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (row.exceededCount > 0) ExpenseCoral else IncomeGreen
                                        )
                                    }
                                    Text(
                                        text = buildString {
                                            append("Actual Expenses: ${MoneyCalculator.formatMinorUnits(row.totalExpenseMinor, currency)}")
                                            row.overallLimitMinor?.let {
                                                append(" • Overall Cap: ${MoneyCalculator.formatMinorUnits(it, currency)}")
                                            }
                                        },
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
    }

    if (showCreateDialog) {
        BudgetEditorDialog(
            currencyCode = currency,
            defaultPeriodKey = selectedPeriod,
            existingBudget = null,
            isBusy = isBusy,
            onDismiss = { showCreateDialog = false },
            onSave = { scope, cat, period, limit, threshold, desc ->
                onCreateBudget(scope, cat, period, limit, threshold, desc)
                showCreateDialog = false
            }
        )
    }

    editingBudget?.let { bud ->
        BudgetEditorDialog(
            currencyCode = bud.currency.ifBlank { currency },
            defaultPeriodKey = bud.periodKey,
            existingBudget = bud,
            isBusy = isBusy,
            onDismiss = { editingBudget = null },
            onSave = { scope, cat, period, limit, threshold, desc ->
                onUpdateBudget(bud.recordId, scope, cat, period, limit, threshold, desc)
                editingBudget = null
            }
        )
    }

    inspectingBudget?.let { selected ->
        val latest = (listOfNotNull(periodSummary.overallBudget) + periodSummary.categoryBudgets)
            .firstOrNull { it.entity.recordId == selected.entity.recordId } ?: selected
        val canManage = isAdmin || latest.entity.createdByUserId == currentUserId
        BudgetDetailDialog(
            evaluated = latest,
            events = budgetEvents.filter { it.parentRecordId == latest.entity.recordId },
            memberLookup = memberLookup,
            canManage = canManage,
            onDismiss = { inspectingBudget = null },
            onEdit = { inspectingBudget = null; editingBudget = latest.entity },
            onDelete = { inspectingBudget = null; deletingBudget = latest.entity }
        )
    }

    deletingBudget?.let { bud ->
        ModuleDeleteConfirmDialog(
            title = "Delete Budget Limit",
            message = "Delete ${bud.periodKey} ${bud.category} budget (${MoneyCalculator.formatMinorUnits(bud.effectiveLimitMinor(), bud.currency)})?",
            isBusy = isBusy,
            testTag = "confirm_delete_budget_button",
            onDismiss = { deletingBudget = null },
            onConfirm = {
                onDeleteBudget(bud.recordId)
                deletingBudget = null
            }
        )
    }
}

@Composable
private fun BudgetCardItem(
    item: EvaluatedBudget,
    currency: String,
    onClick: () -> Unit
) {
    val budget = item.entity
    val statusColor = when (item.alertLevel) {
        BudgetAlertLevel.OK -> IncomeGreen
        BudgetAlertLevel.WARNING -> WarningAmber
        BudgetAlertLevel.EXCEEDED -> ExpenseCoral
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("budget_card_${budget.recordId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = statusColor.copy(alpha = 0.14f)) {
                            Text(
                                text = item.alertLevel.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = item.scope.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        ModuleSyncStatusChip(budget.syncStatus)
                    }
                    Text(
                        text = budget.category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.usagePercent}% used • Alert at ${budget.alertThresholdPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (item.overspentMinor > 0L) {
                            "Over ${MoneyCalculator.formatMinorUnits(item.overspentMinor, currency)}"
                        } else {
                            "${MoneyCalculator.formatMinorUnits(item.remainingMinor, currency)} left"
                        },
                        style = MoneyMediumStyle,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Text(
                        text = "${MoneyCalculator.formatMinorUnits(item.spentMinor, currency)} / ${MoneyCalculator.formatMinorUnits(item.limitMinor, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { item.usageFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }
    }
}
