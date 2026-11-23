package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.model.BudgetScope
import com.example.core.model.ExpenseCategory
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.WarningAmber
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetEditorDialog(
    currencyCode: String,
    defaultPeriodKey: String,
    existingBudget: LocalBudgetEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        description: String
    ) -> Unit
) {
    val isEditing = existingBudget != null
    val periods = remember { BudgetSummaryCalculator.recentPeriodKeys(12) }
    var budgetScope by remember(existingBudget) {
        mutableStateOf(existingBudget?.let { BudgetScope.fromRaw(it.budgetScope) } ?: BudgetScope.CATEGORY)
    }
    var category by remember(existingBudget) {
        mutableStateOf(
            existingBudget?.category?.takeIf { it != "All Categories" } ?: ExpenseCategory.FOOD.displayName
        )
    }
    var periodKey by remember(existingBudget, defaultPeriodKey) {
        mutableStateOf(existingBudget?.periodKey ?: defaultPeriodKey)
    }
    var limitInput by remember(existingBudget) {
        mutableStateOf(existingBudget?.let { MoneyCalculator.minorUnitsToInputString(it.effectiveLimitMinor(), currencyCode) } ?: "")
    }
    var thresholdFloat by remember(existingBudget) {
        mutableFloatStateOf((existingBudget?.alertThresholdPercent ?: 80).toFloat())
    }
    var description by remember(existingBudget) { mutableStateOf(existingBudget?.description ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 700.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isEditing) "Edit Budget Limit" else "Create Budget Limit",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close_dialog))
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BudgetScope.entries.forEachIndexed { idx, scope ->
                        SegmentedButton(
                            selected = budgetScope == scope,
                            onClick = { budgetScope = scope },
                            shape = SegmentedButtonDefaults.itemShape(idx, BudgetScope.entries.size),
                            modifier = Modifier.testTag("budget_scope_${scope.name.lowercase()}")
                        ) {
                            Text(scope.label)
                        }
                    }
                }

                Text("Month", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    periods.forEach { period ->
                        FilterChip(
                            selected = periodKey == period,
                            onClick = { periodKey = period },
                            label = { Text(BudgetSummaryCalculator.formatPeriodLabel(period)) }
                        )
                    }
                }

                if (budgetScope == BudgetScope.CATEGORY) {
                    Text("Expense Category", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExpenseCategory.allNames.forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { limitInput = it; error = null },
                    label = { Text("Spending Limit ($currencyCode) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("budget_limit_input")
                )

                val thresholdInt = thresholdFloat.roundToInt().coerceIn(50, 100)
                Column {
                    Text("Budget Alert Threshold: $thresholdInt%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = thresholdFloat,
                        onValueChange = { thresholdFloat = it },
                        valueRange = 50f..100f,
                        steps = 9
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Budget Notes (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("save_budget_button"),
                        onClick = {
                            val limitRes = MoneyCalculator.parseDecimalToMinorUnits(limitInput, currencyCode)
                            if (limitRes.isFailure) {
                                error = limitRes.exceptionOrNull()?.message
                                return@Button
                            }
                            onSave(
                                budgetScope,
                                if (budgetScope == BudgetScope.MONTHLY_OVERALL) "All Categories" else category,
                                periodKey,
                                limitRes.getOrThrow(),
                                thresholdInt,
                                description
                            )
                        }
                    ) {
                        Text(if (isEditing) "Update Budget" else "Save Budget")
                    }
                }
            }
        }
    }
}

@Composable
fun BudgetDetailDialog(
    evaluated: EvaluatedBudget,
    events: List<LocalFinancialEventEntity>,
    memberLookup: Map<String, String>,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val budget = evaluated.entity
    val currency = budget.currency.ifBlank { "USD" }
    val statusColor = when (evaluated.alertLevel) {
        BudgetAlertLevel.OK -> IncomeGreen
        BudgetAlertLevel.WARNING -> WarningAmber
        BudgetAlertLevel.EXCEEDED -> ExpenseCoral
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 700.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${budget.category} (${BudgetSummaryCalculator.formatPeriodLabel(budget.periodKey)})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close_dialog))
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Remaining Budget", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (evaluated.overspentMinor > 0L) {
                                "Exceeded by ${MoneyCalculator.formatMinorUnits(evaluated.overspentMinor, currency)}"
                            } else {
                                MoneyCalculator.formatMinorUnits(evaluated.remainingMinor, currency)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        Text("Spent: ${MoneyCalculator.formatMinorUnits(evaluated.spentMinor, currency)} of ${MoneyCalculator.formatMinorUnits(evaluated.limitMinor, currency)} (${evaluated.usagePercent}%)", style = MaterialTheme.typography.bodySmall)
                        Text("Scope: ${evaluated.scope.label} • Alert Threshold: ${budget.alertThresholdPercent}%", style = MaterialTheme.typography.bodySmall)
                        if (budget.description.isNotBlank()) Text("Notes: ${budget.description}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (canManage) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onEdit, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Edit Budget")
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete budget", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModuleEventHistorySection(
                    title = "Budget & Alert History",
                    events = events,
                    currencyCode = currency,
                    memberLookup = memberLookup
                )
            }
        }
    }
}
