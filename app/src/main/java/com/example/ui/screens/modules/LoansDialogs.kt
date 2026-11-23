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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Payments
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
import com.example.core.local.LocalLoanEntity
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen

private val LoanCategories = listOf(
    "Personal Loan", "Home Mortgage", "Auto Loan", "Education Loan", "Medical Loan", "Family Support", "Business Loan"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoanEditorDialog(
    currencyCode: String,
    existingLoan: LocalLoanEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        direction: LoanDirection,
        principalMinor: Long,
        remainingMinor: Long,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        paidInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long,
        dueDateMillis: Long?,
        description: String
    ) -> Unit
) {
    val isEditing = existingLoan != null
    var direction by remember(existingLoan) {
        mutableStateOf(existingLoan?.let { LoanDirection.fromRaw(it.loanDirection) } ?: LoanDirection.BORROWED)
    }
    var principalInput by remember(existingLoan) {
        mutableStateOf(existingLoan?.let { MoneyCalculator.minorUnitsToInputString(it.effectiveAmountMinor(), currencyCode) } ?: "")
    }
    var remainingInput by remember(existingLoan) {
        mutableStateOf(existingLoan?.let { MoneyCalculator.minorUnitsToInputString(it.effectiveRemainingMinor(), currencyCode) } ?: "")
    }
    var category by remember(existingLoan) {
        mutableStateOf(existingLoan?.category?.ifBlank { "Personal Loan" } ?: "Personal Loan")
    }
    var borrowerName by remember(existingLoan) { mutableStateOf(existingLoan?.borrowerName ?: "") }
    var borrowerContact by remember(existingLoan) { mutableStateOf(existingLoan?.borrowerContact ?: "") }
    var lenderName by remember(existingLoan) { mutableStateOf(existingLoan?.lenderName ?: "") }
    var lenderContact by remember(existingLoan) { mutableStateOf(existingLoan?.lenderContact ?: "") }
    var aprInput by remember(existingLoan) { mutableStateOf(existingLoan?.interestRateApr?.toString() ?: "") }
    var totalInstInput by remember(existingLoan) { mutableStateOf((existingLoan?.totalInstallments ?: 12).toString()) }
    var paidInstInput by remember(existingLoan) { mutableStateOf((existingLoan?.paidInstallments ?: 0).toString()) }
    var instAmountInput by remember(existingLoan) {
        mutableStateOf(
            existingLoan?.installmentAmountMinor?.takeIf { it > 0L }
                ?.let { MoneyCalculator.minorUnitsToInputString(it, currencyCode) } ?: ""
        )
    }
    var startDateMillis by remember(existingLoan) {
        mutableLongStateOf(existingLoan?.startDateMillis ?: System.currentTimeMillis())
    }
    var dueDateMillis by remember(existingLoan) { mutableStateOf(existingLoan?.dueDateMillis) }
    var description by remember(existingLoan) { mutableStateOf(existingLoan?.description ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    .heightIn(max = 720.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditing) "Edit Loan" else "Record Loan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close_dialog))
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LoanDirection.entries.forEachIndexed { idx, dir ->
                        SegmentedButton(
                            selected = direction == dir,
                            onClick = { direction = dir },
                            shape = SegmentedButtonDefaults.itemShape(idx, LoanDirection.entries.size),
                            modifier = Modifier.testTag("loan_direction_${dir.name.lowercase()}")
                        ) {
                            Text(dir.label)
                        }
                    }
                }

                OutlinedTextField(
                    value = principalInput,
                    onValueChange = { principalInput = it; validationError = null },
                    label = { Text("Loan Principal ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("loan_principal_input")
                )

                if (isEditing) {
                    OutlinedTextField(
                        value = remainingInput,
                        onValueChange = { remainingInput = it; validationError = null },
                        label = { Text("Remaining Balance ($currencyCode)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoanCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                OutlinedTextField(
                    value = borrowerName,
                    onValueChange = { borrowerName = it; validationError = null },
                    label = { Text(if (direction == LoanDirection.LENT) "Borrower Name *" else "Borrower Name (Family/Self)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("loan_borrower_name_input")
                )
                OutlinedTextField(
                    value = borrowerContact,
                    onValueChange = { borrowerContact = it },
                    label = { Text("Borrower Phone / Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lenderName,
                    onValueChange = { lenderName = it; validationError = null },
                    label = { Text(if (direction == LoanDirection.BORROWED) "Lender Name *" else "Lender Name (Family/Self)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("loan_lender_name_input")
                )
                OutlinedTextField(
                    value = lenderContact,
                    onValueChange = { lenderContact = it },
                    label = { Text("Lender Phone / Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = totalInstInput,
                        onValueChange = { totalInstInput = it; validationError = null },
                        label = { Text("Installments") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("loan_installments_input")
                    )
                    if (isEditing) {
                        OutlinedTextField(
                            value = paidInstInput,
                            onValueChange = { paidInstInput = it; validationError = null },
                            label = { Text("Paid Count") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = aprInput,
                        onValueChange = { aprInput = it },
                        label = { Text("APR %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = instAmountInput,
                    onValueChange = { instAmountInput = it },
                    label = { Text("Installment Amount ($currencyCode, auto-split if blank)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ModuleDatePickerRow(
                    label = "Loan Start Date",
                    selectedMillis = startDateMillis,
                    allowClear = false,
                    testTag = "loan_start_date_button",
                    onDateSelected = { picked -> if (picked != null) startDateMillis = picked }
                )

                ModuleDatePickerRow(
                    label = "Due Date (Optional)",
                    selectedMillis = dueDateMillis,
                    allowClear = true,
                    testTag = "loan_due_date_button",
                    onDateSelected = { dueDateMillis = it }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Terms / Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                validationError?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("save_loan_button"),
                        onClick = {
                            val principalRes = MoneyCalculator.parseDecimalToMinorUnits(principalInput, currencyCode)
                            if (principalRes.isFailure) {
                                validationError = principalRes.exceptionOrNull()?.message
                                return@Button
                            }
                            val principalMinor = principalRes.getOrThrow()
                            val remainingMinor = if (isEditing) {
                                val remRes = MoneyCalculator.parseDecimalToMinorUnits(remainingInput, currencyCode, allowZero = true)
                                if (remRes.isFailure) {
                                    validationError = remRes.exceptionOrNull()?.message
                                    return@Button
                                }
                                val rem = remRes.getOrThrow()
                                if (rem > principalMinor) {
                                    validationError = "Remaining balance cannot exceed loan principal."
                                    return@Button
                                }
                                rem
                            } else principalMinor

                            if (direction == LoanDirection.BORROWED && lenderName.isBlank()) {
                                validationError = "Enter lender name for a borrowed loan."
                                return@Button
                            }
                            if (direction == LoanDirection.LENT && borrowerName.isBlank()) {
                                validationError = "Enter borrower name for a lent loan."
                                return@Button
                            }
                            val totalInst = totalInstInput.trim().toIntOrNull()?.takeIf { it in 1..600 }
                            if (totalInst == null) {
                                validationError = "Installments must be between 1 and 600."
                                return@Button
                            }
                            val paidInst = paidInstInput.trim().toIntOrNull()?.coerceIn(0, totalInst) ?: 0
                            val instMinor = if (instAmountInput.isBlank()) 0L else {
                                val instRes = MoneyCalculator.parseDecimalToMinorUnits(instAmountInput, currencyCode)
                                if (instRes.isFailure) {
                                    validationError = instRes.exceptionOrNull()?.message
                                    return@Button
                                }
                                instRes.getOrThrow()
                            }
                            val apr = aprInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                            if (aprInput.isNotBlank() && (apr == null || apr < 0.0 || apr > 200.0)) {
                                validationError = "APR must be between 0% and 200%."
                                return@Button
                            }
                            onSave(
                                direction, principalMinor, remainingMinor, category,
                                borrowerName, borrowerContact, lenderName, lenderContact,
                                apr, totalInst, paidInst, instMinor, startDateMillis, dueDateMillis, description
                            )
                        }
                    ) {
                        Text(if (isEditing) "Update Loan" else "Save Loan")
                    }
                }
            }
        }
    }
}

@Composable
fun LoanRepaymentDialog(
    loan: LocalLoanEntity,
    currencyCode: String,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (repaymentMinor: Long, paymentDateMillis: Long, note: String) -> Unit
) {
    val remaining = loan.effectiveRemainingMinor()
    val suggested = loan.installmentAmountMinor.takeIf { it in 1L..remaining } ?: remaining
    var amountInput by remember(loan) {
        mutableStateOf(MoneyCalculator.minorUnitsToInputString(suggested, currencyCode))
    }
    var paymentDateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("Installment #${loan.paidInstallments + 1}") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Record Loan Repayment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Remaining balance: ${MoneyCalculator.formatMinorUnits(remaining, currencyCode)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Principal repayments reduce loan balance and are NOT added as ordinary Expenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it; error = null },
                    label = { Text("Repayment Amount ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("repayment_amount_input")
                )
                ModuleDatePickerRow(
                    label = "Payment Date",
                    selectedMillis = paymentDateMillis,
                    allowClear = false,
                    testTag = "repayment_date_button",
                    onDateSelected = { picked -> if (picked != null) paymentDateMillis = picked }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Reference / Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_repayment_button"),
                        onClick = {
                            val parsed = MoneyCalculator.parseDecimalToMinorUnits(amountInput, currencyCode)
                            if (parsed.isFailure) {
                                error = parsed.exceptionOrNull()?.message
                                return@Button
                            }
                            val minor = parsed.getOrThrow()
                            if (minor > remaining) {
                                error = "Repayment exceeds remaining balance (${MoneyCalculator.formatMinorUnits(remaining, currencyCode)})."
                                return@Button
                            }
                            onConfirm(minor, paymentDateMillis, note)
                        }
                    ) {
                        Text("Confirm Repayment")
                    }
                }
            }
        }
    }
}

@Composable
fun LoanDetailDialog(
    loan: LocalLoanEntity,
    events: List<LocalFinancialEventEntity>,
    memberLookup: Map<String, String>,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onRepay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val direction = LoanDirection.fromRaw(loan.loanDirection)
    val currency = loan.currency.ifBlank { "USD" }
    val accent = if (direction == LoanDirection.BORROWED) ExpenseCoral else IncomeGreen
    val remaining = loan.effectiveRemainingMinor()

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
                    Text("${direction.label} • ${loan.category}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                        Text("Remaining Balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(MoneyCalculator.formatMinorUnits(remaining, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
                        Text("Principal: ${MoneyCalculator.formatMinorUnits(loan.effectiveAmountMinor(), currency)} • Status: ${loan.recordStatus}", style = MaterialTheme.typography.bodySmall)
                        Text("Borrower: ${loan.effectiveBorrowerName()} ${loan.borrowerContact.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Text("Lender: ${loan.effectiveLenderName()} ${loan.lenderContact.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Text("Installments: ${loan.paidInstallments}/${loan.totalInstallments}" + (loan.installmentAmountMinor.takeIf { it > 0 }?.let { " (${MoneyCalculator.formatMinorUnits(it, currency)}/ea)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        loan.startDateMillis?.let { Text("Start Date: ${formatModuleDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        loan.dueDateMillis?.let { Text("Due Date: ${formatModuleDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        if (loan.description.isNotBlank()) Text("Notes: ${loan.description}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (canManage) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (remaining > 0L) {
                            Button(onClick = onRepay, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                                Icon(Icons.Outlined.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Repay")
                            }
                        }
                        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Edit")
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete loan", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModuleEventHistorySection(
                    title = "Repayment History",
                    events = events,
                    currencyCode = currency,
                    memberLookup = memberLookup
                )
            }
        }
    }
}
