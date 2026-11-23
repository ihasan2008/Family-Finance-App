package com.example.ui.screens.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.DateFilterPreset
import com.example.core.model.ExpenseCategory
import com.example.core.model.IncomeCategory
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.MoneyDisplayStyle
import com.example.ui.theme.MoneySmallStyle

private enum class LedgerViewTab(val label: String) {
    HISTORY("History"),
    RECURRING("Recurring Rules")
}

@Composable
fun IncomeExpenseScreen(
    transactionType: TransactionType,
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    transactions: List<LocalTransactionEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onCreateTransaction: (
        transactionType: TransactionType,
        amountMinor: Long,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency
    ) -> Unit,
    onUpdateTransaction: (
        recordId: String,
        amountMinor: Long,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency,
        keepRecurringActive: Boolean
    ) -> Unit,
    onDeleteTransaction: (recordId: String) -> Unit,
    onPostNextRecurringNow: (recordId: String) -> Unit,
    onToggleRecurringSchedule: (recordId: String, active: Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    onNotifyMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        NoFamilyLedgerState(
            transactionType = transactionType,
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val isIncome = transactionType == TransactionType.INCOME
    val accentColor = if (isIncome) IncomeGreen else ExpenseCoral
    val currency = activeFamily.currencyCode.ifBlank { "USD" }
    val isAdmin = activeFamily.isAdmin(currentUserId)
    val myMemberRecord = remember(activeMembers, currentUserId) {
        activeMembers.firstOrNull { it.userId == currentUserId }
    }
    val canCreate = isAdmin || (
        (myMemberRecord?.canCreateRecords ?: true) &&
            (isIncome || activeFamily.membersCanAddExpenses)
        )

    val memberLookup = remember(activeMembers) {
        activeMembers.associate { it.userId to it.displayName.ifBlank { it.userId } }
    }

    var filterState by remember(transactionType, activeFamily.familyId) {
        mutableStateOf(TransactionFilterState())
    }
    var activeTab by remember { mutableStateOf(LedgerViewTab.HISTORY) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showDateMenu by remember { mutableStateOf(false) }
    var showMemberMenu by remember { mutableStateOf(false) }
    var showCustomDateDialog by remember { mutableStateOf(false) }

    var editingTransaction by remember { mutableStateOf<LocalTransactionEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var inspectingTransaction by remember { mutableStateOf<LocalTransactionEntity?>(null) }
    var deletingTransaction by remember { mutableStateOf<LocalTransactionEntity?>(null) }

    val summary = remember(transactions, filterState, memberLookup, currency) {
        TransactionFilterEngine.filterAndSummarize(
            transactions = transactions,
            filterState = filterState,
            memberNameLookup = memberLookup,
            currencyCode = currency
        )
    }

    val availableCategories = remember(transactionType) {
        if (isIncome) IncomeCategory.allNames else ExpenseCategory.allNames
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp
        val isMedium = maxWidth >= 600.dp

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1240.dp),
                contentPadding = PaddingValues(
                    start = if (isMedium) 24.dp else 16.dp,
                    end = if (isMedium) 24.dp else 16.dp,
                    top = 16.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "kpi_header") {
                    LedgerSummarySection(
                        transactionType = transactionType,
                        familyName = activeFamily.name,
                        currency = currency,
                        filteredTotalMinor = summary.filteredTotalMinor,
                        filteredCount = summary.filteredTransactions.size,
                        thisMonthMinor = summary.thisMonthMinor,
                        activeRecurringCount = summary.activeRecurringCount,
                        syncStatusLabel = syncStatusLabel,
                        isWide = isWide,
                        canCreate = canCreate,
                        onAddClick = { showCreateDialog = true },
                        onSyncNow = onSyncNow
                    )
                }

                if (!canCreate) {
                    item(key = "permission_notice") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = "View-only access: A Family Admin has restricted creating ${transactionType.label.lowercase()} records for your role.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item(key = "tab_switcher") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        LedgerViewTab.entries.forEachIndexed { index, tab ->
                            SegmentedButton(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                shape = SegmentedButtonDefaults.itemShape(index, LedgerViewTab.entries.size),
                                modifier = Modifier.testTag("tx_tab_${tab.name.lowercase()}")
                            ) {
                                Text(
                                    if (tab == LedgerViewTab.RECURRING) {
                                        "${tab.label} (${summary.recurringTemplates.size})"
                                    } else {
                                        "${tab.label} (${summary.filteredTransactions.size})"
                                    }
                                )
                            }
                        }
                    }
                }

                if (activeTab == LedgerViewTab.HISTORY) {
                    item(key = "search_and_filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = filterState.searchQuery,
                                onValueChange = { filterState = filterState.copy(searchQuery = it) },
                                placeholder = { Text("Search category, description, member, ID, or amount") },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (filterState.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { filterState = filterState.copy(searchQuery = "") }) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = stringResource(R.string.cd_clear_search)
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tx_search_input")
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box {
                                    FilterChip(
                                        selected = !filterState.selectedCategory.isNullOrBlank(),
                                        onClick = { showCategoryMenu = true },
                                        label = { Text(filterState.selectedCategory ?: "Category: All") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        modifier = Modifier.testTag("tx_filter_category_chip")
                                    )
                                    DropdownMenu(
                                        expanded = showCategoryMenu,
                                        onDismissRequest = { showCategoryMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("All Categories") },
                                            onClick = {
                                                filterState = filterState.copy(selectedCategory = null)
                                                showCategoryMenu = false
                                            }
                                        )
                                        availableCategories.forEach { cat ->
                                            DropdownMenuItem(
                                                text = { Text(cat) },
                                                onClick = {
                                                    filterState = filterState.copy(selectedCategory = cat)
                                                    showCategoryMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Box {
                                    val dateLabel = if (filterState.datePreset == DateFilterPreset.CUSTOM &&
                                        filterState.customStartMillis != null &&
                                        filterState.customEndMillis != null
                                    ) {
                                        "${TransactionFilterEngine.formatDateOnly(filterState.customStartMillis!!)} – " +
                                            TransactionFilterEngine.formatDateOnly(filterState.customEndMillis!!)
                                    } else {
                                        "Date: ${filterState.datePreset.label}"
                                    }
                                    FilterChip(
                                        selected = filterState.datePreset != DateFilterPreset.ALL,
                                        onClick = { showDateMenu = true },
                                        label = { Text(dateLabel) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        modifier = Modifier.testTag("tx_filter_date_chip")
                                    )
                                    DropdownMenu(
                                        expanded = showDateMenu,
                                        onDismissRequest = { showDateMenu = false }
                                    ) {
                                        DateFilterPreset.entries.forEach { preset ->
                                            DropdownMenuItem(
                                                text = { Text(preset.label) },
                                                onClick = {
                                                    showDateMenu = false
                                                    if (preset == DateFilterPreset.CUSTOM) {
                                                        showCustomDateDialog = true
                                                    } else {
                                                        filterState = filterState.copy(
                                                            datePreset = preset,
                                                            customStartMillis = null,
                                                            customEndMillis = null
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Box {
                                    val selectedMemberLabel = filterState.selectedMemberId?.let { id ->
                                        memberLookup[id] ?: id
                                    } ?: "Member: All"
                                    FilterChip(
                                        selected = !filterState.selectedMemberId.isNullOrBlank(),
                                        onClick = { showMemberMenu = true },
                                        label = { Text(selectedMemberLabel) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        modifier = Modifier.testTag("tx_filter_member_chip")
                                    )
                                    DropdownMenu(
                                        expanded = showMemberMenu,
                                        onDismissRequest = { showMemberMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("All Family Members") },
                                            onClick = {
                                                filterState = filterState.copy(selectedMemberId = null)
                                                showMemberMenu = false
                                            }
                                        )
                                        activeMembers.forEach { member ->
                                            DropdownMenuItem(
                                                text = { Text(member.displayName.ifBlank { member.userId }) },
                                                onClick = {
                                                    filterState = filterState.copy(selectedMemberId = member.userId)
                                                    showMemberMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (filterState.hasActiveFilters) {
                                    TextButton(
                                        onClick = { filterState = TransactionFilterState() },
                                        modifier = Modifier.testTag("tx_clear_filters_button")
                                    ) {
                                        Icon(Icons.Outlined.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Clear")
                                    }
                                }
                            }
                        }
                    }

                    if (summary.categoryBreakdown.isNotEmpty()) {
                        item(key = "category_breakdown") {
                            CategoryBreakdownCard(
                                transactionType = transactionType,
                                currency = currency,
                                items = summary.categoryBreakdown.take(5)
                            )
                        }
                    }

                    if (summary.dateGroups.isEmpty()) {
                        item(key = "empty_history") {
                            EmptyLedgerCard(
                                transactionType = transactionType,
                                hasFilters = filterState.hasActiveFilters,
                                canCreate = canCreate,
                                onAddClick = { showCreateDialog = true },
                                onClearFilters = { filterState = TransactionFilterState() }
                            )
                        }
                    } else {
                        summary.dateGroups.forEach { group ->
                            item(key = "group_${group.dateLabel}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = group.dateLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = MoneyCalculator.formatMinorUnits(
                                            amountMinor = group.dayTotalMinor,
                                            currencyCode = currency,
                                            prefixSign = if (isIncome) "+" else "-"
                                        ),
                                        style = MoneySmallStyle,
                                        color = accentColor
                                    )
                                }
                            }
                            items(
                                items = group.items,
                                key = { it.recordId }
                            ) { tx ->
                                TransactionRowCard(
                                    tx = tx,
                                    memberName = memberLookup[tx.effectiveMemberId()] ?: tx.effectiveMemberId(),
                                    onClick = { inspectingTransaction = tx }
                                )
                            }
                        }
                    }
                } else {
                    if (summary.recurringTemplates.isEmpty()) {
                        item(key = "empty_recurring") {
                            EmptyRecurringCard(
                                transactionType = transactionType,
                                canCreate = canCreate,
                                onAddClick = { showCreateDialog = true }
                            )
                        }
                    } else {
                        items(
                            items = summary.recurringTemplates,
                            key = { "rec_${it.recordId}" }
                        ) { template ->
                            val canModify = template.createdByUserId == currentUserId || isAdmin
                            RecurringRuleCard(
                                template = template,
                                memberName = memberLookup[template.effectiveMemberId()] ?: template.effectiveMemberId(),
                                canModify = canModify,
                                isBusy = isBusy,
                                onInspect = { inspectingTransaction = template },
                                onPostNow = { onPostNextRecurringNow(template.recordId) },
                                onToggleActive = { active -> onToggleRecurringSchedule(template.recordId, active) }
                            )
                        }
                    }
                }
            }

            if (canCreate) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("add_transaction_fab")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.cd_add_transaction)
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        TransactionEditorDialog(
            transactionType = transactionType,
            currencyCode = currency,
            currentUserId = currentUserId,
            members = activeMembers,
            existingTransaction = null,
            isBusy = isBusy,
            onDismiss = { showCreateDialog = false },
            onSave = { amountMinor, category, description, memberId, paymentMethod, occurredAtMillis, frequency, _ ->
                onCreateTransaction(
                    transactionType,
                    amountMinor,
                    category,
                    description,
                    memberId,
                    paymentMethod,
                    occurredAtMillis,
                    frequency
                )
                showCreateDialog = false
            }
        )
    }

    editingTransaction?.let { target ->
        TransactionEditorDialog(
            transactionType = transactionType,
            currencyCode = currency,
            currentUserId = currentUserId,
            members = activeMembers,
            existingTransaction = target,
            isBusy = isBusy,
            onDismiss = { editingTransaction = null },
            onSave = { amountMinor, category, description, memberId, paymentMethod, occurredAtMillis, frequency, keepActive ->
                onUpdateTransaction(
                    target.recordId,
                    amountMinor,
                    category,
                    description,
                    memberId,
                    paymentMethod,
                    occurredAtMillis,
                    frequency,
                    keepActive
                )
                editingTransaction = null
            }
        )
    }

    inspectingTransaction?.let { target ->
        val canModify = target.createdByUserId == currentUserId || isAdmin
        TransactionDetailsDialog(
            transaction = target,
            memberDisplayName = memberLookup[target.effectiveMemberId()] ?: target.effectiveMemberId(),
            canModify = canModify,
            onDismiss = { inspectingTransaction = null },
            onEdit = {
                inspectingTransaction = null
                editingTransaction = target
            },
            onDelete = {
                inspectingTransaction = null
                deletingTransaction = target
            },
            onCopiedMessage = onNotifyMessage
        )
    }

    deletingTransaction?.let { target ->
        DeleteTransactionConfirmDialog(
            transaction = target,
            isBusy = isBusy,
            onDismiss = { deletingTransaction = null },
            onConfirmDelete = {
                onDeleteTransaction(target.recordId)
                deletingTransaction = null
            }
        )
    }

    if (showCustomDateDialog) {
        CustomDateRangeDialog(
            initialStartMillis = filterState.customStartMillis,
            initialEndMillis = filterState.customEndMillis,
            onDismiss = { showCustomDateDialog = false },
            onApplyRange = { startMillis, endMillis ->
                filterState = filterState.copy(
                    datePreset = DateFilterPreset.CUSTOM,
                    customStartMillis = startMillis,
                    customEndMillis = endMillis
                )
                showCustomDateDialog = false
            }
        )
    }
}

@Composable
private fun LedgerSummarySection(
    transactionType: TransactionType,
    familyName: String,
    currency: String,
    filteredTotalMinor: Long,
    filteredCount: Int,
    thisMonthMinor: Long,
    activeRecurringCount: Int,
    syncStatusLabel: String,
    isWide: Boolean,
    canCreate: Boolean,
    onAddClick: () -> Unit,
    onSyncNow: () -> Unit
) {
    val isIncome = transactionType == TransactionType.INCOME
    val accent = if (isIncome) IncomeGreen else ExpenseCoral
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$familyName • ${transactionType.label} Ledger",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = MoneyCalculator.formatMinorUnits(filteredTotalMinor, currency),
                        style = MoneyDisplayStyle,
                        color = accent,
                        modifier = Modifier.testTag("tx_filtered_total_value")
                    )
                    Text(
                        text = "$filteredCount verified record(s) • $syncStatusLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isWide && canCreate) {
                    Button(
                        onClick = onAddClick,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_transaction_header_button")
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add ${transactionType.label}")
                    }
                } else {
                    IconButton(
                        onClick = onSyncNow,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = "Synchronize transactions")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("This Month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = MoneyCalculator.formatMinorUnits(thisMonthMinor, currency),
                            style = MoneySmallStyle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Recurring Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "$activeRecurringCount schedule(s)",
                            style = MoneySmallStyle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(
    transactionType: TransactionType,
    currency: String,
    items: List<CategoryBreakdownItem>
) {
    val accent = if (transactionType == TransactionType.INCOME) IncomeGreen else ExpenseCoral
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Category Breakdown", style = MaterialTheme.typography.titleSmall)
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${item.category} (${item.count})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = MoneyCalculator.formatMinorUnits(item.totalMinor, currency),
                            style = MoneySmallStyle
                        )
                    }
                    LinearProgressIndicator(
                        progress = { item.share },
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionRowCard(
    tx: LocalTransactionEntity,
    memberName: String,
    onClick: () -> Unit
) {
    val isIncome = tx.transactionType == TransactionType.INCOME.name
    val accent = if (isIncome) IncomeGreen else ExpenseCoral
    val freq = RecurrenceFrequency.fromRaw(tx.recurrenceFrequency)

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("transaction_card_${tx.recordId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncome) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tx.category,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tx.description.isNotBlank()) {
                    Text(
                        text = tx.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = memberName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!tx.paymentMethod.isNullOrBlank()) {
                        Text(
                            text = "• ${tx.paymentMethod}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (freq != RecurrenceFrequency.NONE || !tx.recurringParentId.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.Repeat,
                            contentDescription = "Recurring",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = MoneyCalculator.formatMinorUnits(
                        amountMinor = tx.effectiveAmountMinor(),
                        currencyCode = tx.currency,
                        prefixSign = if (isIncome) "+" else "-"
                    ),
                    style = MoneySmallStyle,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = if (tx.syncStatus == SyncState.SYNCED.name) "Synced" else "Local Queue",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tx.syncStatus == SyncState.SYNCED.name) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
            }
        }
    }
}

@Composable
private fun RecurringRuleCard(
    template: LocalTransactionEntity,
    memberName: String,
    canModify: Boolean,
    isBusy: Boolean,
    onInspect: () -> Unit,
    onPostNow: () -> Unit,
    onToggleActive: (Boolean) -> Unit
) {
    val freq = RecurrenceFrequency.fromRaw(template.recurrenceFrequency)
    val isIncome = template.transactionType == TransactionType.INCOME.name
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${template.category} • ${freq.label}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = MoneyCalculator.formatMinorUnits(
                            amountMinor = template.effectiveAmountMinor(),
                            currencyCode = template.currency,
                            prefixSign = if (isIncome) "+" else "-"
                        ),
                        style = MoneySmallStyle,
                        fontWeight = FontWeight.Bold,
                        color = if (isIncome) IncomeGreen else ExpenseCoral
                    )
                    Text(
                        text = "Attributed to $memberName" +
                            (template.nextRecurrenceAtMillis?.let {
                                " • Next: ${TransactionFilterEngine.formatDateOnly(it)}"
                            } ?: " • Paused"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canModify) {
                    Switch(
                        checked = template.isRecurringTemplate,
                        onCheckedChange = onToggleActive,
                        enabled = !isBusy
                    )
                }
            }
            if (canModify && template.isRecurringTemplate) {
                OutlinedButton(
                    onClick = onPostNow,
                    enabled = !isBusy,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Post Next Occurrence Now")
                }
            }
        }
    }
}

@Composable
private fun EmptyLedgerCard(
    transactionType: TransactionType,
    hasFilters: Boolean,
    canCreate: Boolean,
    onAddClick: () -> Unit,
    onClearFilters: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = if (hasFilters) "No Matching ${transactionType.label} Entries"
                else "No ${transactionType.label} Recorded Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (hasFilters) {
                    "Try clearing your search, category, date, or member filters."
                } else {
                    "All records here come directly from your encrypted family SQLite & Firestore ledger — zero sample placeholder figures."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasFilters) {
                OutlinedButton(onClick = onClearFilters) { Text("Reset Filters") }
            } else if (canCreate) {
                Button(onClick = onAddClick, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Record First ${transactionType.label}")
                }
            }
        }
    }
}

@Composable
private fun EmptyRecurringCard(
    transactionType: TransactionType,
    canCreate: Boolean,
    onAddClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Repeat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "No Recurring ${transactionType.label} Rules",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Toggle Recurring Transaction when creating an entry to automate daily, weekly, monthly, or yearly schedules with duplicate protection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (canCreate) {
                OutlinedButton(onClick = onAddClick) {
                    Text("Create Recurring ${transactionType.label}")
                }
            }
        }
    }
}

@Composable
private fun NoFamilyLedgerState(
    transactionType: TransactionType,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Diversity3,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = "Active Family Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Create or join a family workspace so your ${transactionType.label.lowercase()} records stay isolated and synced per Family ID.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onNavigateToFamilyManagement,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Open Family Management")
                }
            }
        }
    }
}
