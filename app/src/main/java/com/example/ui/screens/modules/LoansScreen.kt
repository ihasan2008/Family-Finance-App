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
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.example.core.local.LocalLoanEntity
import com.example.core.model.FinancialModuleType
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.WarningAmber

private enum class LoanFilterTab(val label: String) {
    ALL("All"),
    BORROWED("Borrowed"),
    LENT("Lent")
}

@Composable
fun LoansScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    loans: List<LocalLoanEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onCreateLoan: (
        loanDirection: LoanDirection,
        principalMinor: Long,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long,
        dueDateMillis: Long?,
        description: String
    ) -> Unit,
    onUpdateLoan: (
        recordId: String,
        loanDirection: LoanDirection,
        principalMinor: Long,
        remainingBalanceMinor: Long,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        paidInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long?,
        dueDateMillis: Long?,
        description: String
    ) -> Unit,
    onRecordRepayment: (recordId: String, repaymentMinor: Long, paymentDateMillis: Long, note: String) -> Unit,
    onDeleteLoan: (recordId: String) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Loan Management",
            moduleSubtitle = "Create or join a family workspace to track borrowed and lent money with installment schedules.",
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

    var filterTab by remember { mutableStateOf(LoanFilterTab.ALL) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingLoan by remember { mutableStateOf<LocalLoanEntity?>(null) }
    var repayingLoan by remember { mutableStateOf<LocalLoanEntity?>(null) }
    var inspectingLoan by remember { mutableStateOf<LocalLoanEntity?>(null) }
    var deletingLoan by remember { mutableStateOf<LocalLoanEntity?>(null) }

    val summary = remember(loans) { LoansSummaryCalculator.summarize(loans) }
    val loanEvents = remember(financialEvents) {
        financialEvents.filter { it.moduleType == FinancialModuleType.LOANS.name }
    }
    val visibleLoans = remember(summary, filterTab) {
        when (filterTab) {
            LoanFilterTab.ALL -> summary.evaluatedLoans
            LoanFilterTab.BORROWED -> summary.evaluatedLoans.filter { it.direction == LoanDirection.BORROWED }
            LoanFilterTab.LENT -> summary.evaluatedLoans.filter { it.direction == LoanDirection.LENT }
        }
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
                item(key = "loans_header") {
                    ModuleHeaderBanner(
                        title = "Loans & Installments",
                        familyName = activeFamily.name,
                        syncStatusLabel = syncStatusLabel,
                        canCreate = canCreate,
                        addButtonLabel = "Add Borrowed / Lent Loan",
                        addTestTag = "add_loan_button",
                        onAddClick = { showCreateDialog = true },
                        onSyncNow = onSyncNow
                    )
                }

                item(key = "loans_rule_banner") {
                    ModuleFinancialRuleBanner(
                        text = "Accounting Integrity: Borrowed loan principal is never counted as Income, and loan principal repayments are tracked in repayment history rather than ordinary Expenses."
                    )
                }

                item(key = "loans_kpis") {
                    if (isWide) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModuleKpiTile(
                                label = "Borrowed Remaining",
                                value = MoneyCalculator.formatMinorUnits(summary.remainingBorrowedMinor, currency),
                                supporting = "Of ${MoneyCalculator.formatMinorUnits(summary.totalBorrowedMinor, currency)}",
                                accentColor = ExpenseCoral,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Lent Receivable",
                                value = MoneyCalculator.formatMinorUnits(summary.remainingLentMinor, currency),
                                supporting = "Of ${MoneyCalculator.formatMinorUnits(summary.totalLentMinor, currency)}",
                                accentColor = IncomeGreen,
                                modifier = Modifier.weight(1f)
                            )
                            ModuleKpiTile(
                                label = "Total Repaid",
                                value = MoneyCalculator.formatMinorUnits(summary.totalRepaidMinor, currency),
                                supporting = "${summary.activeCount} active • ${summary.settledCount} settled",
                                accentColor = InfoTeal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModuleKpiTile(
                                    label = "Borrowed Balance",
                                    value = MoneyCalculator.formatMinorUnits(summary.remainingBorrowedMinor, currency),
                                    supporting = "Principal ${MoneyCalculator.formatMinorUnits(summary.totalBorrowedMinor, currency)}",
                                    accentColor = ExpenseCoral,
                                    modifier = Modifier.weight(1f)
                                )
                                ModuleKpiTile(
                                    label = "Lent Receivable",
                                    value = MoneyCalculator.formatMinorUnits(summary.remainingLentMinor, currency),
                                    supporting = "Principal ${MoneyCalculator.formatMinorUnits(summary.totalLentMinor, currency)}",
                                    accentColor = IncomeGreen,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            ModuleKpiTile(
                                label = "Repaid & Status",
                                value = MoneyCalculator.formatMinorUnits(summary.totalRepaidMinor, currency),
                                supporting = "${summary.activeCount} active • ${summary.overdueCount} overdue • ${summary.settledCount} settled",
                                accentColor = InfoTeal,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item(key = "loans_filter_tabs") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        LoanFilterTab.entries.forEachIndexed { idx, tab ->
                            SegmentedButton(
                                selected = filterTab == tab,
                                onClick = { filterTab = tab },
                                shape = SegmentedButtonDefaults.itemShape(idx, LoanFilterTab.entries.size),
                                modifier = Modifier.testTag("loans_filter_${tab.name.lowercase()}")
                            ) {
                                Text(tab.label)
                            }
                        }
                    }
                }

                if (visibleLoans.isEmpty()) {
                    item(key = "loans_empty") {
                        ModuleEmptyRecordsCard(
                            icon = Icons.Outlined.AccountBalance,
                            title = "No Loan Records Yet",
                            subtitle = "Add a borrowed or lent loan with borrower/lender details, installment count, and due date."
                        )
                    }
                } else {
                    items(visibleLoans, key = { it.entity.recordId }) { item ->
                        val canManage = isAdmin || item.entity.createdByUserId == currentUserId
                        LoanCardItem(
                            item = item,
                            currency = currency,
                            canManage = canManage,
                            onInspect = { inspectingLoan = item.entity },
                            onRepay = { repayingLoan = item.entity }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        LoanEditorDialog(
            currencyCode = currency,
            existingLoan = null,
            isBusy = isBusy,
            onDismiss = { showCreateDialog = false },
            onSave = { direction, principal, remaining, category, bName, bContact, lName, lContact, apr, totalInst, _, instMinor, startMillis, dueMillis, desc ->
                onCreateLoan(
                    direction, principal, category, bName, bContact, lName, lContact,
                    apr, totalInst, instMinor, startMillis, dueMillis, desc
                )
                showCreateDialog = false
            }
        )
    }

    editingLoan?.let { loan ->
        LoanEditorDialog(
            currencyCode = loan.currency.ifBlank { currency },
            existingLoan = loan,
            isBusy = isBusy,
            onDismiss = { editingLoan = null },
            onSave = { direction, principal, remaining, category, bName, bContact, lName, lContact, apr, totalInst, paidInst, instMinor, startMillis, dueMillis, desc ->
                onUpdateLoan(
                    loan.recordId, direction, principal, remaining, category, bName, bContact,
                    lName, lContact, apr, totalInst, paidInst, instMinor, startMillis, dueMillis, desc
                )
                editingLoan = null
            }
        )
    }

    repayingLoan?.let { loan ->
        LoanRepaymentDialog(
            loan = loan,
            currencyCode = loan.currency.ifBlank { currency },
            isBusy = isBusy,
            onDismiss = { repayingLoan = null },
            onConfirm = { amountMinor, dateMillis, note ->
                onRecordRepayment(loan.recordId, amountMinor, dateMillis, note)
                repayingLoan = null
            }
        )
    }

    inspectingLoan?.let { selected ->
        val latest = loans.firstOrNull { it.recordId == selected.recordId } ?: selected
        val canManage = isAdmin || latest.createdByUserId == currentUserId
        LoanDetailDialog(
            loan = latest,
            events = loanEvents.filter { it.parentRecordId == latest.recordId },
            memberLookup = memberLookup,
            canManage = canManage,
            onDismiss = { inspectingLoan = null },
            onRepay = {
                inspectingLoan = null
                repayingLoan = latest
            },
            onEdit = {
                inspectingLoan = null
                editingLoan = latest
            },
            onDelete = {
                inspectingLoan = null
                deletingLoan = latest
            }
        )
    }

    deletingLoan?.let { loan ->
        ModuleDeleteConfirmDialog(
            title = "Delete Loan Record",
            message = "Delete ${loan.counterparty} (${MoneyCalculator.formatMinorUnits(loan.effectiveAmountMinor(), loan.currency)})? This removes the loan and logs an audit entry.",
            isBusy = isBusy,
            testTag = "confirm_delete_loan_button",
            onDismiss = { deletingLoan = null },
            onConfirm = {
                onDeleteLoan(loan.recordId)
                deletingLoan = null
            }
        )
    }
}

@Composable
private fun LoanCardItem(
    item: EvaluatedLoan,
    currency: String,
    canManage: Boolean,
    onInspect: () -> Unit,
    onRepay: () -> Unit
) {
    val loan = item.entity
    val accent = if (item.direction == LoanDirection.BORROWED) ExpenseCoral else IncomeGreen
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .testTag("loan_card_${loan.recordId}"),
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
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = accent.copy(alpha = 0.14f)) {
                            Text(
                                text = item.direction.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (item.isSettled) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = IncomeGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Settled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IncomeGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else if (item.isOverdue) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = ExpenseCoral.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "Overdue",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ExpenseCoral,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        ModuleSyncStatusChip(loan.syncStatus)
                    }
                    Text(
                        text = "${loan.category} • ${loan.counterparty}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Borrower: ${loan.effectiveBorrowerName()} • Lender: ${loan.effectiveLenderName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = MoneyCalculator.formatMinorUnits(item.remainingMinor, currency),
                        style = MoneyMediumStyle,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Text(
                        text = "of ${MoneyCalculator.formatMinorUnits(item.principalMinor, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp),
                color = if (item.isSettled) IncomeGreen else accent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append("Installments: ${loan.paidInstallments}/${loan.totalInstallments}")
                        loan.dueDateMillis?.let { append(" • Due ${formatModuleDate(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (canManage && !item.isSettled) {
                    OutlinedButton(
                        onClick = onRepay,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("loan_repay_button_${loan.recordId}")
                    ) {
                        Icon(Icons.Outlined.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Repay")
                    }
                }
            }
        }
    }
}
