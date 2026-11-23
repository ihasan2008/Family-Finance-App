package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.IncomeGreen

private val SavingsCategories = listOf(
    "Emergency Fund", "Education", "Home Reserve", "Travel", "Medical Reserve", "Vehicle", "General Savings"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavingsGoalEditorDialog(
    currencyCode: String,
    existingGoal: LocalSavingsEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        goalName: String,
        category: String,
        initialAmountMinor: Long,
        targetAmountMinor: Long,
        targetDateMillis: Long?,
        description: String
    ) -> Unit
) {
    val isEditing = existingGoal != null
    var goalName by remember(existingGoal) { mutableStateOf(existingGoal?.goalName ?: "") }
    var category by remember(existingGoal) {
        mutableStateOf(existingGoal?.category?.ifBlank { "Emergency Fund" } ?: "Emergency Fund")
    }
    var initialInput by remember(existingGoal) { mutableStateOf("") }
    var targetInput by remember(existingGoal) {
        mutableStateOf(existingGoal?.let { MoneyCalculator.minorUnitsToInputString(it.effectiveTargetMinor(), currencyCode) } ?: "")
    }
    var targetDateMillis by remember(existingGoal) { mutableStateOf(existingGoal?.targetDateMillis) }
    var description by remember(existingGoal) { mutableStateOf(existingGoal?.description ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 540.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 680.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isEditing) "Edit Savings Goal" else "Create Savings Goal",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close_dialog))
                    }
                }

                OutlinedTextField(
                    value = goalName,
                    onValueChange = { goalName = it; error = null },
                    label = { Text("Goal Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("savings_goal_name_input")
                )

                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SavingsCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                OutlinedTextField(
                    value = targetInput,
                    onValueChange = { targetInput = it; error = null },
                    label = { Text("Target Amount ($currencyCode) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("savings_target_input")
                )

                if (!isEditing) {
                    OutlinedTextField(
                        value = initialInput,
                        onValueChange = { initialInput = it; error = null },
                        label = { Text("Opening Deposit ($currencyCode, optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("savings_opening_input")
                    )
                }

                ModuleDatePickerRow(
                    label = "Target Date (Optional)",
                    selectedMillis = targetDateMillis,
                    allowClear = true,
                    testTag = "savings_target_date_button",
                    onDateSelected = { targetDateMillis = it }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Purpose / Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("save_savings_goal_button"),
                        onClick = {
                            if (goalName.isBlank()) {
                                error = "Enter a savings goal name."
                                return@Button
                            }
                            val targetRes = MoneyCalculator.parseDecimalToMinorUnits(targetInput, currencyCode)
                            if (targetRes.isFailure) {
                                error = targetRes.exceptionOrNull()?.message
                                return@Button
                            }
                            val initialMinor = if (isEditing || initialInput.isBlank()) 0L else {
                                val initRes = MoneyCalculator.parseDecimalToMinorUnits(initialInput, currencyCode, allowZero = true)
                                if (initRes.isFailure) {
                                    error = initRes.exceptionOrNull()?.message
                                    return@Button
                                }
                                initRes.getOrThrow()
                            }
                            onSave(goalName, category, initialMinor, targetRes.getOrThrow(), targetDateMillis, description)
                        }
                    ) {
                        Text(if (isEditing) "Update Goal" else "Create Goal")
                    }
                }
            }
        }
    }
}

@Composable
fun SavingsMoveDialog(
    goal: LocalSavingsEntity,
    isDeposit: Boolean,
    currencyCode: String,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, moveDateMillis: Long, note: String) -> Unit
) {
    val currentMinor = goal.effectiveCurrentMinor()
    var amountInput by remember { mutableStateOf("") }
    var dateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isDeposit) "Deposit to ${goal.goalName}" else "Withdraw from ${goal.goalName}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Current Balance: ${MoneyCalculator.formatMinorUnits(currentMinor, currencyCode)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Internal savings transfers do not inflate family Income.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it; error = null },
                    label = { Text(if (isDeposit) "Deposit Amount ($currencyCode)" else "Withdrawal Amount ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("savings_move_amount_input")
                )
                ModuleDatePickerRow(
                    label = "Transfer Date",
                    selectedMillis = dateMillis,
                    allowClear = false,
                    testTag = "savings_move_date_button",
                    onDateSelected = { picked -> if (picked != null) dateMillis = picked }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_savings_move_button"),
                        onClick = {
                            val parsed = MoneyCalculator.parseDecimalToMinorUnits(amountInput, currencyCode)
                            if (parsed.isFailure) {
                                error = parsed.exceptionOrNull()?.message
                                return@Button
                            }
                            val minor = parsed.getOrThrow()
                            if (!isDeposit && minor > currentMinor) {
                                error = "Withdrawal exceeds available balance (${MoneyCalculator.formatMinorUnits(currentMinor, currencyCode)})."
                                return@Button
                            }
                            onConfirm(minor, dateMillis, note)
                        }
                    ) {
                        Text(if (isDeposit) "Deposit" else "Withdraw")
                    }
                }
            }
        }
    }
}

@Composable
fun SavingsDetailDialog(
    goal: LocalSavingsEntity,
    events: List<LocalFinancialEventEntity>,
    memberLookup: Map<String, String>,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val currency = goal.currency.ifBlank { "USD" }
    val current = goal.effectiveCurrentMinor()
    val target = goal.effectiveTargetMinor()

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
                    Text(goal.goalName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                        Text("Saved Amount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(MoneyCalculator.formatMinorUnits(current, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = IncomeGreen)
                        Text("Target: ${MoneyCalculator.formatMinorUnits(target, currency)} • Category: ${goal.category}", style = MaterialTheme.typography.bodySmall)
                        goal.targetDateMillis?.let { Text("Target Date: ${formatModuleDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        if (goal.description.isNotBlank()) Text("Notes: ${goal.description}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (canManage) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDeposit, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Deposit")
                        }
                        OutlinedButton(onClick = onWithdraw, enabled = current > 0L, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Withdraw")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Edit Goal")
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete goal", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModuleEventHistorySection(
                    title = "Savings History",
                    events = events,
                    currencyCode = currency,
                    memberLookup = memberLookup
                )
            }
        }
    }
}
