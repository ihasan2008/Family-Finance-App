package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.model.FinancialModuleType
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyMediumStyle

@Composable
fun SavingsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    savingsGoals: List<LocalSavingsEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onCreateGoal: (
        goalName: String,
        category: String,
        initialAmountMinor: Long,
        targetAmountMinor: Long,
        targetDateMillis: Long?,
        description: String
    ) -> Unit,
    onUpdateGoal: (
        recordId: String,
        goalName: String,
        category: String,
        targetAmountMinor: Long,
        targetDateMillis: Long?,
        description: String
    ) -> Unit,
    onRecordMove: (recordId: String, moveAmountMinor: Long, isDeposit: Boolean, moveDateMillis: Long, note: String) -> Unit,
    onDeleteGoal: (recordId: String) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Savings Management",
            moduleSubtitle = "Create or join a family workspace to set shared savings goals, deposits, withdrawals, and target dates.",
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

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<LocalSavingsEntity?>(null) }
    var movingGoal by remember { mutableStateOf<Pair<LocalSavingsEntity, Boolean>?>(null) }
    var inspectingGoal by remember { mutableStateOf<LocalSavingsEntity?>(null) }
    var deletingGoal by remember { mutableStateOf<LocalSavingsEntity?>(null) }

    val summary = remember(savingsGoals) { SavingsSummaryCalculator.summarize(savingsGoals) }
    val savingsEvents = remember(financialEvents) {
        financialEvents.filter { it.moduleType == FinancialModuleType.SAVINGS.name }
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
                item(key = "savings_header") {
                    ModuleHeaderBanner(
                        title = "Family Savings Goals",
                        familyName = activeFamily.name,
                        syncStatusLabel = syncStatusLabel,
                        canCreate = canCreate,
                        addButtonLabel = "Create Savings Goal",
                        addTestTag = "add_savings_goal_button",
                        onAddClick = { showCreateDialog = true },
                        onSyncNow = onSyncNow
                    )
                }

                item(key = "savings_rule_banner") {
                    ModuleFinancialRuleBanner(
                        text = "Accounting Integrity: Moving money into or out of Savings updates goal reserves and savings history without double-counting it as new Income or Expenses."
                    )
                }

                item(key = "savings_kpis") {
                    val overallPct = (summary.overallProgress * 100f).toInt()
                    if (isWide) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModuleKpiTile(
                                label = "Total Saved",
                                value = MoneyCalculator.formatMinorUnits(summary.totalSavedMinor, currency),
                                supporting = "${summary.activeCount} active • ${summary.completedCount} reached",
                                accentColor = IncomeGreen,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Combined Target",
                                value = MoneyCalculator.formatMinorUnits(summary.totalTargetMinor, currency),
                                supporting = "$overallPct% overall goal progress",
                                accentColor = InfoTeal,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Remaining to Target",
                                value = MoneyCalculator.formatMinorUnits(summary.remainingToTargetMinor, currency),
                                supporting = "${savingsGoals.size} savings goal(s)",
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Total Saved",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalSavedMinor, currency),
                                    supporting = "$overallPct% of target",
                                    accentColor = IncomeGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Total Target",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalTargetMinor, currency),
                                    supporting = "${summary.completedCount} reached",
                                    accentColor = InfoTeal,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            ModuleKpiTile(
                                label = "Remaining to Goals",
                                value = MoneyCalculator.formatMinorUnits(summary.remainingToTargetMinor, currency),
                                supporting = "${summary.activeCount} active goal(s)",
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (summary.goals.isEmpty()) {
                    item(key = "savings_empty") {
                        ModuleEmptyRecordsCard(
                            icon = Icons.Outlined.Savings,
                            title = "No Savings Goals Yet",
                            subtitle = "Create a family savings goal (Emergency Fund, Education, Home Down Payment) with a target amount and date."
                        )
                    }
                } else {
                    items(summary.goals, key = { it.entity.recordId }) { item ->
                        val canManage = isAdmin || item.entity.createdByUserId == currentUserId
                        SavingsGoalCard(
                            item = item,
                            currency = currency,
                            canManage = canManage,
                            onInspect = { inspectingGoal = item.entity },
                            onDeposit = { movingGoal = item.entity to true },
                            onWithdraw = { movingGoal = item.entity to false }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SavingsGoalEditorDialog(
            currencyCode = currency,
            existingGoal = null,
            isBusy = isBusy,
            onDismiss = { showCreateDialog = false },
            onSave = { name, cat, initialMinor, targetMinor, targetDate, desc ->
                onCreateGoal(name, cat, initialMinor, targetMinor, targetDate, desc)
                showCreateDialog = false
            }
        )
    }

    editingGoal?.let { goal ->
        SavingsGoalEditorDialog(
            currencyCode = goal.currency.ifBlank { currency },
            existingGoal = goal,
            isBusy = isBusy,
            onDismiss = { editingGoal = null },
            onSave = { name, cat, _, targetMinor, targetDate, desc ->
                onUpdateGoal(goal.recordId, name, cat, targetMinor, targetDate, desc)
                editingGoal = null
            }
        )
    }

    movingGoal?.let { (goal, isDeposit) ->
        SavingsMoveDialog(
            goal = goal,
            isDeposit = isDeposit,
            currencyCode = goal.currency.ifBlank { currency },
            isBusy = isBusy,
            onDismiss = { movingGoal = null },
            onConfirm = { amountMinor, dateMillis, note ->
                onRecordMove(goal.recordId, amountMinor, isDeposit, dateMillis, note)
                movingGoal = null
            }
        )
    }

    inspectingGoal?.let { selected ->
        val latest = savingsGoals.firstOrNull { it.recordId == selected.recordId } ?: selected
        val canManage = isAdmin || latest.createdByUserId == currentUserId
        SavingsDetailDialog(
            goal = latest,
            events = savingsEvents.filter { it.parentRecordId == latest.recordId },
            memberLookup = memberLookup,
            canManage = canManage,
            onDismiss = { inspectingGoal = null },
            onDeposit = { inspectingGoal = null; movingGoal = latest to true },
            onWithdraw = { inspectingGoal = null; movingGoal = latest to false },
            onEdit = { inspectingGoal = null; editingGoal = latest },
            onDelete = { inspectingGoal = null; deletingGoal = latest }
        )
    }

    deletingGoal?.let { goal ->
        ModuleDeleteConfirmDialog(
            title = "Delete Savings Goal",
            message = "Delete \"${goal.goalName}\" (${MoneyCalculator.formatMinorUnits(goal.effectiveCurrentMinor(), goal.currency)})? This removes the goal and logs an audit entry.",
            isBusy = isBusy,
            testTag = "confirm_delete_savings_button",
            onDismiss = { deletingGoal = null },
            onConfirm = {
                onDeleteGoal(goal.recordId)
                deletingGoal = null
            }
        )
    }
}

@Composable
private fun SavingsGoalCard(
    item: EvaluatedSavingsGoal,
    currency: String,
    canManage: Boolean,
    onInspect: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit
) {
    val goal = item.entity
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .testTag("savings_card_${goal.recordId}"),
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
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = goal.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (item.isCompleted) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = IncomeGreen.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "Goal Reached",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IncomeGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        ModuleSyncStatusChip(goal.syncStatus)
                    }
                    Text(
                        text = goal.goalName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${item.progressPercent}% funded")
                            goal.targetDateMillis?.let { append(" • Target ${formatModuleDate(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = MoneyCalculator.formatMinorUnits(item.currentMinor, currency),
                        style = MoneyMediumStyle,
                        fontWeight = FontWeight.Bold,
                        color = IncomeGreen
                    )
                    Text(
                        text = "of ${MoneyCalculator.formatMinorUnits(item.targetMinor, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = IncomeGreen,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )

            if (canManage) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDeposit,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("savings_deposit_button_${goal.recordId}")
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Deposit")
                    }
                    OutlinedButton(
                        onClick = onWithdraw,
                        enabled = item.currentMinor > 0L,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("savings_withdraw_button_${goal.recordId}")
                    ) {
                        Icon(
                            Icons.Outlined.Remove,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (item.currentMinor > 0L) ExpenseCoral else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Withdraw")
                    }
                }
            }
        }
    }
}
