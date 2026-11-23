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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.model.FinancialModuleType
import com.example.core.model.InvestmentAssetType
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyMediumStyle

@Composable
fun InvestmentsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    investments: List<LocalInvestmentEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onCreateInvestment: (
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        investedAtMillis: Long,
        description: String
    ) -> Unit,
    onUpdateInvestment: (
        recordId: String,
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        investedAtMillis: Long,
        description: String,
        note: String
    ) -> Unit,
    onDeleteInvestment: (recordId: String) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Investment Management",
            moduleSubtitle = "Create or join a family workspace to track investment principal, current valuation, separate profit and loss, and performance history.",
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

    var selectedType by remember { mutableStateOf<InvestmentAssetType?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingInvestment by remember { mutableStateOf<LocalInvestmentEntity?>(null) }
    var inspectingInvestment by remember { mutableStateOf<LocalInvestmentEntity?>(null) }
    var deletingInvestment by remember { mutableStateOf<LocalInvestmentEntity?>(null) }

    val summary = remember(investments) { InvestmentsSummaryCalculator.summarize(investments) }
    val investmentEvents = remember(financialEvents) {
        financialEvents.filter { it.moduleType == FinancialModuleType.INVESTMENTS.name }
    }
    val visibleHoldings = remember(summary, selectedType) {
        if (selectedType == null) summary.holdings
        else summary.holdings.filter { InvestmentAssetType.fromRaw(it.entity.assetType) == selectedType }
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
                item(key = "investments_header") {
                    ModuleHeaderBanner(
                        title = "Investments & Performance",
                        familyName = activeFamily.name,
                        syncStatusLabel = syncStatusLabel,
                        canCreate = canCreate,
                        addButtonLabel = "Add Investment",
                        addTestTag = "add_investment_button",
                        onAddClick = { showCreateDialog = true },
                        onSyncNow = onSyncNow
                    )
                }

                item(key = "investments_rule_banner") {
                    ModuleFinancialRuleBanner(
                        text = "Accounting Integrity: Principal invested is tracked separately from unrealized gains. Portfolio Profit and Portfolio Loss are computed and displayed separately without hiding losing positions."
                    )
                }

                item(key = "investments_kpis") {
                    if (isWide) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModuleKpiTile(
                                label = "Principal Invested",
                                value = MoneyCalculator.formatMinorUnits(summary.totalPrincipalMinor, currency),
                                supporting = "${summary.holdings.size} holding(s)",
                                accentColor = InfoTeal,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Current Value",
                                value = MoneyCalculator.formatMinorUnits(summary.totalCurrentValueMinor, currency),
                                supporting = "Market valuation",
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Total Profit",
                                value = MoneyCalculator.formatMinorUnits(summary.totalProfitMinor, currency, prefixSign = "+"),
                                supporting = "Gaining holdings only",
                                accentColor = IncomeGreen,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Total Loss",
                                value = MoneyCalculator.formatMinorUnits(summary.totalLossMinor, currency, prefixSign = "-"),
                                supporting = "Declining holdings only",
                                accentColor = ExpenseCoral,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Principal Invested",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalPrincipalMinor, currency),
                                    supporting = "${summary.holdings.size} holding(s)",
                                    accentColor = InfoTeal,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Current Value",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalCurrentValueMinor, currency),
                                    supporting = "Total valuation",
                                    accentColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Total Profit",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalProfitMinor, currency, prefixSign = "+"),
                                    supporting = "Separate profit",
                                    accentColor = IncomeGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Total Loss",
                                    value = MoneyCalculator.formatMinorUnits(summary.totalLossMinor, currency, prefixSign = "-"),
                                    supporting = "Separate loss",
                                    accentColor = ExpenseCoral,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                item(key = "investments_type_filter") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == null,
                            onClick = { selectedType = null },
                            label = { Text("All Types") }
                        )
                        InvestmentAssetType.entries.forEach { assetType ->
                            FilterChip(
                                selected = selectedType == assetType,
                                onClick = { selectedType = assetType },
                                label = { Text(assetType.label) }
                            )
                        }
                    }
                }

                if (visibleHoldings.isEmpty()) {
                    item(key = "investments_empty") {
                        ModuleEmptyRecordsCard(
                            icon = Icons.AutoMirrored.Outlined.ShowChart,
                            title = "No Investments Recorded",
                            subtitle = "Add a stock, ETF, mutual fund, bond, or property investment to track principal vs valuation history."
                        )
                    }
                } else {
                    items(visibleHoldings, key = { it.entity.recordId }) { holding ->
                        val canManage = isAdmin || holding.entity.createdByUserId == currentUserId
                        InvestmentCardItem(
                            item = holding,
                            currency = currency,
                            canManage = canManage,
                            onInspect = { inspectingInvestment = holding.entity },
                            onUpdateValuation = { editingInvestment = holding.entity }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        InvestmentEditorDialog(
            currencyCode = currency,
            existingInvestment = null,
            isBusy = isBusy,
            onDismiss = { showCreateDialog = false },
            onSave = { name, type, symbol, principal, current, units, dateMillis, desc, _ ->
                onCreateInvestment(name, type, symbol, principal, current, units, dateMillis, desc)
                showCreateDialog = false
            }
        )
    }

    editingInvestment?.let { inv ->
        InvestmentEditorDialog(
            currencyCode = inv.currency.ifBlank { currency },
            existingInvestment = inv,
            isBusy = isBusy,
            onDismiss = { editingInvestment = null },
            onSave = { name, type, symbol, principal, current, units, dateMillis, desc, note ->
                onUpdateInvestment(inv.recordId, name, type, symbol, principal, current, units, dateMillis, desc, note)
                editingInvestment = null
            }
        )
    }

    inspectingInvestment?.let { selected ->
        val latest = investments.firstOrNull { it.recordId == selected.recordId } ?: selected
        val canManage = isAdmin || latest.createdByUserId == currentUserId
        InvestmentDetailDialog(
            investment = latest,
            events = investmentEvents.filter { it.parentRecordId == latest.recordId },
            memberLookup = memberLookup,
            canManage = canManage,
            onDismiss = { inspectingInvestment = null },
            onEdit = { inspectingInvestment = null; editingInvestment = latest },
            onDelete = { inspectingInvestment = null; deletingInvestment = latest }
        )
    }

    deletingInvestment?.let { inv ->
        ModuleDeleteConfirmDialog(
            title = "Delete Investment",
            message = "Delete \"${inv.assetName}\" (Principal ${MoneyCalculator.formatMinorUnits(inv.effectivePrincipalMinor(), inv.currency)})? This removes the holding and logs an audit entry.",
            isBusy = isBusy,
            testTag = "confirm_delete_investment_button",
            onDismiss = { deletingInvestment = null },
            onConfirm = {
                onDeleteInvestment(inv.recordId)
                deletingInvestment = null
            }
        )
    }
}

@Composable
private fun InvestmentCardItem(
    item: EvaluatedInvestment,
    currency: String,
    canManage: Boolean,
    onInspect: () -> Unit,
    onUpdateValuation: () -> Unit
) {
    val inv = item.entity
    val typeLabel = InvestmentAssetType.fromRaw(inv.assetType).label
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .testTag("investment_card_${inv.recordId}"),
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
                                text = typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        inv.symbol?.takeIf { it.isNotBlank() }?.let { sym ->
                            Text(sym, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        ModuleSyncStatusChip(inv.syncStatus)
                    }
                    Text(
                        text = inv.assetName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("Principal: ${MoneyCalculator.formatMinorUnits(item.principalMinor, currency)}")
                            inv.units?.let { append(" • $it units") }
                            append(" • Invested ${formatModuleDate(inv.investedAtMillis)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = MoneyCalculator.formatMinorUnits(item.currentValueMinor, currency),
                        style = MoneyMediumStyle,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.returnPercentText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.lossMinor > 0L) ExpenseCoral else IncomeGreen
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = IncomeGreen.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "Profit: ${MoneyCalculator.formatMinorUnits(item.profitMinor, currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = IncomeGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = ExpenseCoral.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "Loss: ${MoneyCalculator.formatMinorUnits(item.lossMinor, currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ExpenseCoral,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (canManage) {
                    OutlinedButton(
                        onClick = onUpdateValuation,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("investment_update_button_${inv.recordId}")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Update")
                    }
                }
            }
        }
    }
}
