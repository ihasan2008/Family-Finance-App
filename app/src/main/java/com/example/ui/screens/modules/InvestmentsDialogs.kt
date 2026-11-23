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
import com.example.core.local.LocalInvestmentEntity
import com.example.core.model.InvestmentAssetType
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvestmentEditorDialog(
    currencyCode: String,
    existingInvestment: LocalInvestmentEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        investedAtMillis: Long,
        description: String,
        valuationNote: String
    ) -> Unit
) {
    val isEditing = existingInvestment != null
    var assetName by remember(existingInvestment) { mutableStateOf(existingInvestment?.assetName ?: "") }
    var assetType by remember(existingInvestment) {
        mutableStateOf(existingInvestment?.let { InvestmentAssetType.fromRaw(it.assetType) } ?: InvestmentAssetType.ETF)
    }
    var symbol by remember(existingInvestment) { mutableStateOf(existingInvestment?.symbol ?: "") }
    var principalInput by remember(existingInvestment) {
        mutableStateOf(existingInvestment?.let { MoneyCalculator.minorUnitsToInputString(it.effectivePrincipalMinor(), currencyCode) } ?: "")
    }
    var currentInput by remember(existingInvestment) {
        mutableStateOf(existingInvestment?.let { MoneyCalculator.minorUnitsToInputString(it.effectiveCurrentValueMinor(), currencyCode) } ?: "")
    }
    var unitsInput by remember(existingInvestment) { mutableStateOf(existingInvestment?.units?.toString() ?: "") }
    var investedAtMillis by remember(existingInvestment) {
        mutableLongStateOf(existingInvestment?.investedAtMillis ?: System.currentTimeMillis())
    }
    var description by remember(existingInvestment) { mutableStateOf(existingInvestment?.description ?: "") }
    var valuationNote by remember { mutableStateOf("") }
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
                        text = if (isEditing) "Update Investment" else "Add Investment",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close_dialog))
                    }
                }

                OutlinedTextField(
                    value = assetName,
                    onValueChange = { assetName = it; error = null },
                    label = { Text("Investment Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("investment_name_input")
                )

                Text("Investment Type", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InvestmentAssetType.entries.forEach { type ->
                        FilterChip(
                            selected = assetType == type,
                            onClick = { assetType = type },
                            label = { Text(type.label) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it },
                        label = { Text("Ticker / Symbol") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unitsInput,
                        onValueChange = { unitsInput = it; error = null },
                        label = { Text("Units / Shares") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = principalInput,
                    onValueChange = { principalInput = it; error = null },
                    label = { Text("Principal Amount ($currencyCode) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("investment_principal_input")
                )

                OutlinedTextField(
                    value = currentInput,
                    onValueChange = { currentInput = it; error = null },
                    label = { Text("Current Market Value ($currencyCode, defaults to principal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("investment_current_input")
                )

                ModuleDatePickerRow(
                    label = "Investment Date",
                    selectedMillis = investedAtMillis,
                    allowClear = false,
                    testTag = "investment_date_button",
                    onDateSelected = { picked -> if (picked != null) investedAtMillis = picked }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Account / Strategy Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isEditing) {
                    OutlinedTextField(
                        value = valuationNote,
                        onValueChange = { valuationNote = it },
                        label = { Text("Performance History Note (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("save_investment_button"),
                        onClick = {
                            if (assetName.isBlank()) {
                                error = "Enter an investment name."
                                return@Button
                            }
                            val principalRes = MoneyCalculator.parseDecimalToMinorUnits(principalInput, currencyCode)
                            if (principalRes.isFailure) {
                                error = principalRes.exceptionOrNull()?.message
                                return@Button
                            }
                            val principalMinor = principalRes.getOrThrow()
                            val currentMinor = if (currentInput.isBlank()) principalMinor else {
                                val curRes = MoneyCalculator.parseDecimalToMinorUnits(currentInput, currencyCode, allowZero = true)
                                if (curRes.isFailure) {
                                    error = curRes.exceptionOrNull()?.message
                                    return@Button
                                }
                                curRes.getOrThrow()
                            }
                            val parsedUnits = unitsInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                            if (unitsInput.isNotBlank() && (parsedUnits == null || parsedUnits < 0.0)) {
                                error = "Units must be zero or positive."
                                return@Button
                            }
                            onSave(
                                assetName, assetType, symbol.trim().takeIf { it.isNotEmpty() },
                                principalMinor, currentMinor, parsedUnits, investedAtMillis, description, valuationNote
                            )
                        }
                    ) {
                        Text(if (isEditing) "Save Valuation" else "Add Investment")
                    }
                }
            }
        }
    }
}

@Composable
fun InvestmentDetailDialog(
    investment: LocalInvestmentEntity,
    events: List<LocalFinancialEventEntity>,
    memberLookup: Map<String, String>,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val currency = investment.currency.ifBlank { "USD" }
    val principal = investment.effectivePrincipalMinor()
    val current = investment.effectiveCurrentValueMinor()
    val profit = investment.profitMinor()
    val loss = investment.lossMinor()

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
                    Text(investment.assetName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                        Text("Current Value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(MoneyCalculator.formatMinorUnits(current, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Principal Invested: ${MoneyCalculator.formatMinorUnits(principal, currency)}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Profit: ${MoneyCalculator.formatMinorUnits(profit, currency)}", style = MaterialTheme.typography.bodyMedium, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                            Text("Loss: ${MoneyCalculator.formatMinorUnits(loss, currency)}", style = MaterialTheme.typography.bodyMedium, color = ExpenseCoral, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Type: ${InvestmentAssetType.fromRaw(investment.assetType).label} • Invested ${formatModuleDate(investment.investedAtMillis)}", style = MaterialTheme.typography.bodySmall)
                        if (investment.description.isNotBlank()) Text("Notes: ${investment.description}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (canManage) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onEdit, modifier = Modifier.weight(1f).minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Update Value / Edit")
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete investment", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModuleEventHistorySection(
                    title = "Performance History",
                    events = events,
                    currencyCode = currency,
                    memberLookup = memberLookup
                )
            }
        }
    }
}
