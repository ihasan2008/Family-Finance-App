package com.example.ui.screens.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.ExpenseCategory
import com.example.core.model.IncomeCategory
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecurrenceScheduleHelper
import com.example.core.model.TransactionType
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.MoneySmallStyle
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorDialog(
    transactionType: TransactionType,
    currencyCode: String,
    currentUserId: String,
    members: List<LocalFamilyMemberEntity>,
    existingTransaction: LocalTransactionEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        amountMinor: Long,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency,
        keepRecurringActive: Boolean
    ) -> Unit
) {
    val isEditing = existingTransaction != null
    val categories = remember(transactionType) {
        if (transactionType == TransactionType.INCOME) IncomeCategory.allNames
        else ExpenseCategory.allNames
    }
    val paymentMethods = remember(transactionType) {
        if (transactionType == TransactionType.INCOME) {
            listOf("Bank Transfer", "Direct Deposit", "Cash", "UPI / Wallet", "Check")
        } else {
            listOf("Debit Card", "Credit Card", "Bank Transfer", "Cash", "UPI / Wallet")
        }
    }

    var amountInput by remember(existingTransaction) {
        mutableStateOf(
            existingTransaction?.let {
                MoneyCalculator.minorUnitsToInputString(it.effectiveAmountMinor(), currencyCode)
            } ?: ""
        )
    }
    var selectedCategory by remember(existingTransaction, categories) {
        mutableStateOf(
            existingTransaction?.category?.takeIf { it in categories } ?: categories.first()
        )
    }
    var descriptionInput by remember(existingTransaction) {
        mutableStateOf(existingTransaction?.description ?: "")
    }
    var selectedMemberId by remember(existingTransaction, currentUserId) {
        mutableStateOf(
            existingTransaction?.effectiveMemberId()?.ifBlank { currentUserId } ?: currentUserId
        )
    }
    var selectedPaymentMethod by remember(existingTransaction) {
        mutableStateOf(existingTransaction?.paymentMethod ?: paymentMethods.first())
    }
    var occurredAtMillis by remember(existingTransaction) {
        mutableLongStateOf(existingTransaction?.occurredAtMillis ?: System.currentTimeMillis())
    }
    val initialFreq = remember(existingTransaction) {
        RecurrenceFrequency.fromRaw(existingTransaction?.recurrenceFrequency)
    }
    var recurrenceEnabled by remember(existingTransaction) {
        mutableStateOf(initialFreq != RecurrenceFrequency.NONE)
    }
    var recurrenceFrequency by remember(existingTransaction) {
        mutableStateOf(if (initialFreq == RecurrenceFrequency.NONE) RecurrenceFrequency.MONTHLY else initialFreq)
    }
    var keepRecurringActive by remember(existingTransaction) {
        mutableStateOf(existingTransaction?.isRecurringTemplate ?: true)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var attemptedSubmit by remember { mutableStateOf(false) }

    val parsedAmountResult = remember(amountInput, currencyCode) {
        MoneyCalculator.parseDecimalToMinorUnits(amountInput, currencyCode)
    }
    val amountMinor = parsedAmountResult.getOrNull()
    val amountError = if (attemptedSubmit || amountInput.isNotBlank()) {
        parsedAmountResult.exceptionOrNull()?.message
    } else null

    val effectiveFreq = if (recurrenceEnabled) recurrenceFrequency else RecurrenceFrequency.NONE
    val nextDuePreview = remember(occurredAtMillis, effectiveFreq, keepRecurringActive) {
        if (effectiveFreq != RecurrenceFrequency.NONE && keepRecurringActive) {
            RecurrenceScheduleHelper.computeNextOccurrenceMillis(occurredAtMillis, effectiveFreq)
        } else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEditing) "Edit ${transactionType.label}" else "Add ${transactionType.label}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Decimal-safe $currencyCode ledger entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close dialog")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { raw ->
                            val filtered = raw.filter { it.isDigit() || it == '.' }
                            if (filtered.count { it == '.' } <= 1) {
                                amountInput = filtered
                            }
                        },
                        label = { Text("Amount ($currencyCode)") },
                        prefix = { Text("${MoneyCalculator.currencySymbol(currencyCode)} ") },
                        placeholder = { Text("0.00") },
                        isError = amountError != null,
                        supportingText = {
                            if (amountError != null) {
                                Text(amountError)
                            } else if (amountMinor != null) {
                                Text(
                                    "${MoneyCalculator.formatMinorUnits(amountMinor, currencyCode)} ($amountMinor minor units)",
                                    style = MoneySmallStyle
                                )
                            } else {
                                Text("Stored strictly as integer minor currency units")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tx_amount_input")
                    )

                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            val selected = selectedCategory == category
                            FilterChip(
                                selected = selected,
                                onClick = { selectedCategory = category },
                                label = { Text(category) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.testTag("tx_category_${category.lowercase().replace(" ", "_")}")
                            )
                        }
                    }

                    Text("Transaction Date", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("tx_date_picker_button")
                    ) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(TransactionFilterEngine.formatDateOnly(occurredAtMillis))
                    }

                    OutlinedTextField(
                        value = descriptionInput,
                        onValueChange = { if (it.length <= 300) descriptionInput = it },
                        label = { Text("Custom Description") },
                        placeholder = { Text("e.g., Monthly groceries, Q4 consulting invoice") },
                        supportingText = { Text("${descriptionInput.length}/300 characters") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tx_description_input")
                    )

                    Text("Family Member", style = MaterialTheme.typography.labelLarge)
                    val memberChoices = remember(members, currentUserId) {
                        if (members.any { it.userId == currentUserId }) {
                            members
                        } else {
                            members + LocalFamilyMemberEntity(
                                familyId = "",
                                userId = currentUserId,
                                displayName = "You ($currentUserId)",
                                role = "MEMBER"
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        memberChoices.forEach { member ->
                            val selected = selectedMemberId == member.userId
                            FilterChip(
                                selected = selected,
                                onClick = { selectedMemberId = member.userId },
                                label = { Text(member.displayName.ifBlank { member.userId }) },
                                modifier = Modifier.testTag("tx_member_${member.userId}")
                            )
                        }
                    }

                    Text("Payment Method", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        paymentMethods.forEach { method ->
                            FilterChip(
                                selected = selectedPaymentMethod == method,
                                onClick = { selectedPaymentMethod = method },
                                label = { Text(method) }
                            )
                        }
                    }

                    if (existingTransaction?.recurringParentId.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                        Icon(
                                            Icons.Outlined.Repeat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column {
                                            Text("Recurring Transaction", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Auto-generate future occurrences cleanly",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = recurrenceEnabled,
                                        onCheckedChange = { recurrenceEnabled = it },
                                        modifier = Modifier.testTag("tx_recurring_switch")
                                    )
                                }

                                if (recurrenceEnabled) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        RecurrenceFrequency.entries
                                            .filter { it != RecurrenceFrequency.NONE }
                                            .forEach { freq ->
                                                FilterChip(
                                                    selected = recurrenceFrequency == freq,
                                                    onClick = { recurrenceFrequency = freq },
                                                    label = { Text(freq.label) },
                                                    modifier = Modifier.testTag("tx_freq_${freq.name.lowercase()}")
                                                )
                                            }
                                    }
                                    if (isEditing) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Schedule Active", style = MaterialTheme.typography.bodySmall)
                                            Switch(
                                                checked = keepRecurringActive,
                                                onCheckedChange = { keepRecurringActive = it }
                                            )
                                        }
                                    }
                                    if (nextDuePreview != null) {
                                        Text(
                                            text = "Next scheduled occurrence: ${TransactionFilterEngine.formatDateOnly(nextDuePreview)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            attemptedSubmit = true
                            val minor = amountMinor
                            if (minor != null && minor > 0L) {
                                onSave(
                                    minor,
                                    selectedCategory,
                                    descriptionInput.trim(),
                                    selectedMemberId,
                                    selectedPaymentMethod,
                                    occurredAtMillis,
                                    effectiveFreq,
                                    if (effectiveFreq == RecurrenceFrequency.NONE) false else keepRecurringActive
                                )
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("tx_save_button")
                    ) {
                        Text(if (isEditing) "Confirm Update" else "Save ${transactionType.label}")
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val zone = ZoneId.systemDefault()
        val initialUtcMillis = remember(occurredAtMillis) {
            Instant.ofEpochMilli(occurredAtMillis).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialUtcMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    return utcTimeMillis <= System.currentTimeMillis() + 86_400_000L
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedUtc ->
                            val pickedDate = Instant.ofEpochMilli(selectedUtc)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val localTime = Instant.ofEpochMilli(occurredAtMillis)
                                .atZone(zone)
                                .toLocalTime()
                            val combined = pickedDate.atTime(localTime).atZone(zone).toInstant().toEpochMilli()
                            occurredAtMillis = minOf(combined, System.currentTimeMillis())
                        }
                        showDatePicker = false
                    }
                ) { Text("Apply Date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun TransactionDetailsDialog(
    transaction: LocalTransactionEntity,
    memberDisplayName: String,
    canModify: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopiedMessage: (String) -> Unit
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val isIncome = transaction.transactionType == TransactionType.INCOME.name
    val freq = RecurrenceFrequency.fromRaw(transaction.recurrenceFrequency)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Transaction Details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${transaction.transactionType} • ${transaction.category}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close details")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = MoneyCalculator.formatMinorUnits(
                                    amountMinor = transaction.effectiveAmountMinor(),
                                    currencyCode = transaction.currency,
                                    prefixSign = if (isIncome) "+" else "-"
                                ),
                                style = MoneyMediumStyle,
                                fontWeight = FontWeight.Bold,
                                color = if (isIncome) IncomeGreen else ExpenseCoral
                            )
                            Text(
                                text = "${transaction.effectiveAmountMinor()} minor units (${transaction.currency}) • ${transaction.recordStatus}",
                                style = MoneySmallStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Transaction ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(transaction.recordId, style = MoneySmallStyle)
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(transaction.recordId))
                                onCopiedMessage("Transaction ID copied to clipboard")
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_transaction_id),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DetailMetadataRow("Family ID", transaction.familyId, mono = true)
                    DetailMetadataRow("Member", "$memberDisplayName (${transaction.effectiveMemberId()})")
                    DetailMetadataRow("Category", transaction.category)
                    DetailMetadataRow("Transaction Date", TransactionFilterEngine.formatDateTime(transaction.occurredAtMillis))
                    DetailMetadataRow(
                        "Description",
                        transaction.description.ifBlank { "No custom description provided" }
                    )
                    DetailMetadataRow("Payment Method", transaction.paymentMethod ?: "Unspecified")

                    if (freq != RecurrenceFrequency.NONE) {
                        val statusText = if (transaction.isRecurringTemplate) "Active (${freq.label})" else "Paused (${freq.label})"
                        DetailMetadataRow("Recurring Schedule", statusText)
                        transaction.nextRecurrenceAtMillis?.let {
                            DetailMetadataRow("Next Due Date", TransactionFilterEngine.formatDateOnly(it))
                        }
                    } else if (!transaction.recurringParentId.isNullOrBlank()) {
                        DetailMetadataRow("Recurring Parent ID", transaction.recurringParentId, mono = true)
                    }

                    DetailMetadataRow("Created At", TransactionFilterEngine.formatDateTime(transaction.createdAtMillis))
                    DetailMetadataRow("Updated At", TransactionFilterEngine.formatDateTime(transaction.updatedAtMillis))
                    DetailMetadataRow("Sync Status", "${transaction.syncStatus} (v${transaction.syncVersion})")
                    DetailMetadataRow("Idempotency Key", transaction.idempotencyKey.take(20) + "…", mono = true)

                    if (!canModify) {
                        Text(
                            text = "Only the transaction creator or a Family Admin can edit or delete this record.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (canModify) {
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ExpenseCoral),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("tx_details_delete_button")
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Delete")
                        }
                        Button(
                            onClick = onEdit,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("tx_details_edit_button")
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Edit")
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetadataRow(
    label: String,
    value: String,
    mono: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) MoneySmallStyle else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DeleteTransactionConfirmDialog(
    transaction: LocalTransactionEntity,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = ExpenseCoral
            )
        },
        title = { Text("Delete Transaction?") },
        text = {
            val formatted = MoneyCalculator.formatMinorUnits(transaction.effectiveAmountMinor(), transaction.currency)
            Text(
                "This will permanently delete the ${transaction.transactionType.lowercase()} entry " +
                    "\"${transaction.category}\" ($formatted) from your local SQLite database and Cloud Firestore, " +
                    "and record an audit log entry."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = ExpenseCoral),
                modifier = Modifier.testTag("tx_confirm_delete_button")
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDateRangeDialog(
    initialStartMillis: Long?,
    initialEndMillis: Long?,
    onDismiss: () -> Unit,
    onApplyRange: (Long?, Long?) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMillis,
        initialSelectedEndDateMillis = initialEndMillis
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val localStart = state.selectedStartDateMillis?.let { utc ->
                        Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    val localEnd = state.selectedEndDateMillis?.let { utc ->
                        Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    onApplyRange(localStart, localEnd)
                }
            ) {
                Text("Apply Range")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                .padding(top = 8.dp)
        )
    }
}
