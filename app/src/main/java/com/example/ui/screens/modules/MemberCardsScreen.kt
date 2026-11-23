package com.example.ui.screens.modules

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.AppModule
import com.example.core.model.DigitalMemberCardData
import com.example.core.model.FamilyRole
import com.example.core.model.MemberAccountEngine
import com.example.core.model.MemberLedgerApprovalStatus
import com.example.core.model.MemberLedgerDatePreset
import com.example.core.model.MemberLedgerEntry
import com.example.core.model.MemberLedgerFilter
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MemberProfileViewData
import com.example.core.model.MemberSortOption
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.TransactionType
import com.example.ui.components.SectionHeader
import com.example.ui.screens.family.ContributeFromPersonalDialog
import com.example.ui.screens.transactions.TransactionEditorDialog
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneySmallStyle
import com.example.ui.theme.WarningAmber

@Composable
fun MemberCardsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    personalFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    memberCards: List<LocalMemberCardEntity>,
    transactions: List<LocalTransactionEntity>,
    loans: List<LocalLoanEntity>,
    savings: List<LocalSavingsEntity>,
    investments: List<LocalInvestmentEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    syncStatusLabel: String,
    isBusy: Boolean,
    onUpdatePrivacy: (MemberPrivacySettings) -> Unit,
    onRecordAdminAudit: (DigitalMemberCardData) -> Unit,
    onCreateTransaction: (TransactionType, Long, String, String, String, String?, Long, RecurrenceFrequency) -> Unit,
    onContributeFromPersonal: (targetFamilyId: String, amountMinor: Long, category: String, description: String) -> Unit,
    onSyncNow: () -> Unit,
    onNotifyMessage: (String) -> Unit,
    onNavigateToModule: (AppModule) -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Member Cards",
            moduleSubtitle = "Create or join a family workspace to issue Digital Member Cards and individual financial ledgers.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val viewerIsAdmin = remember(activeFamily, currentUserId) { activeFamily.isAdmin(currentUserId) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(MemberSortOption.ROLE_ADMIN_FIRST) }
    var selectedMemberUid by remember(activeFamily.familyId) { mutableStateOf<String?>(null) }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        onNotifyMessage("$label copied to clipboard.")
    }

    val directoryCards = remember(
        activeFamily,
        activeMembers,
        memberCards,
        transactions,
        loans,
        savings,
        investments,
        currentUserId,
        viewerIsAdmin,
        searchQuery,
        sortOption
    ) {
        MemberAccountEngine.buildDirectoryCards(
            family = activeFamily,
            members = activeMembers,
            memberCards = memberCards,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            viewerUid = currentUserId,
            viewerIsAdmin = viewerIsAdmin,
            searchQuery = searchQuery,
            sortOption = sortOption
        )
    }

    val allCardsUnfiltered = remember(
        activeFamily,
        activeMembers,
        memberCards,
        transactions,
        loans,
        savings,
        investments,
        currentUserId,
        viewerIsAdmin
    ) {
        MemberAccountEngine.buildDirectoryCards(
            family = activeFamily,
            members = activeMembers,
            memberCards = memberCards,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            viewerUid = currentUserId,
            viewerIsAdmin = viewerIsAdmin
        )
    }

    val selectedCard = remember(selectedMemberUid, allCardsUnfiltered) {
        selectedMemberUid?.let { uid -> allCardsUnfiltered.firstOrNull { it.userId == uid } }
    }

    if (selectedCard != null) {
        BackHandler { selectedMemberUid = null }
        LaunchedEffect(selectedCard.userId, viewerIsAdmin) {
            if (viewerIsAdmin && selectedCard.userId != currentUserId) {
                onRecordAdminAudit(selectedCard)
            }
        }
        val rawLedger = remember(
            activeFamily,
            selectedCard.userId,
            activeMembers,
            transactions,
            loans,
            savings,
            investments,
            financialEvents
        ) {
            MemberAccountEngine.buildMemberLedger(
                family = activeFamily,
                memberUserId = selectedCard.userId,
                members = activeMembers,
                transactions = transactions,
                loans = loans,
                savings = savings,
                investments = investments,
                financialEvents = financialEvents
            )
        }
        val profileData = remember(selectedCard, currentUserId, viewerIsAdmin, rawLedger) {
            MemberAccountEngine.buildMemberProfileView(
                card = selectedCard,
                viewerUid = currentUserId,
                viewerIsAdmin = viewerIsAdmin,
                allLedgerEntries = rawLedger
            )
        }
        MemberProfileScreenContent(
            currentUserId = currentUserId,
            activeFamily = activeFamily,
            personalFamily = personalFamily,
            activeMembers = activeMembers,
            profile = profileData,
            isBusy = isBusy,
            onBack = { selectedMemberUid = null },
            onCopyValue = ::copyToClipboard,
            onUpdatePrivacy = onUpdatePrivacy,
            onCreateTransaction = onCreateTransaction,
            onContributeFromPersonal = onContributeFromPersonal,
            onNavigateToModule = onNavigateToModule,
            modifier = modifier
        )
        return
    }

    val familyTotalBalanceMinor = allCardsUnfiltered.firstOrNull()?.rawSummary?.familyTotalBalanceMinor ?: 0L

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        val cols = if (maxWidth >= 740.dp) 2 else 1

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1040.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Digital Member Cards (${allCardsUnfiltered.size})",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${activeFamily.name} • Family Balance: ${
                                    MoneyCalculator.formatMinorUnits(familyTotalBalanceMinor, activeFamily.currencyCode)
                                } • $syncStatusLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("member_cards_sync_button")
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Sync")
                        }
                    }

                    ModuleFinancialRuleBanner(
                        text = "Workspace & Balance Rules: Member balances are calculated strictly from validated transactions, loans, savings, investments & personal contributions — never manually edited. Phone numbers, passwords, and date of birth are never exposed."
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by member name or 16-char Member ID") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("member_cards_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MemberSortOption.entries.forEach { option ->
                    FilterChip(
                        selected = sortOption == option,
                        onClick = { sortOption = option },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("member_sort_${option.name.lowercase()}")
                    )
                }
            }

            if (directoryCards.isEmpty()) {
                ModuleEmptyRecordsCard(
                    icon = Icons.Outlined.Badge,
                    title = "No matching member cards",
                    subtitle = "Clear your search filter or sync active members for ${activeFamily.name}."
                )
            } else {
                directoryCards.chunked(cols).forEach { rowCards ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowCards.forEach { card ->
                            DigitalMemberCardVisual(
                                card = card,
                                isCurrentUser = card.userId == currentUserId,
                                onClick = { selectedMemberUid = card.userId },
                                onCopyMemberId = { id -> copyToClipboard("Member ID", id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(cols - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberProfileScreenContent(
    currentUserId: String,
    activeFamily: LocalFamilyEntity,
    personalFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    profile: MemberProfileViewData,
    isBusy: Boolean,
    onBack: () -> Unit,
    onCopyValue: (label: String, value: String) -> Unit,
    onUpdatePrivacy: (MemberPrivacySettings) -> Unit,
    onCreateTransaction: (TransactionType, Long, String, String, String, String?, Long, RecurrenceFrequency) -> Unit,
    onContributeFromPersonal: (targetFamilyId: String, amountMinor: Long, category: String, description: String) -> Unit,
    onNavigateToModule: (AppModule) -> Unit,
    modifier: Modifier = Modifier
) {
    val card = profile.card
    val summary = card.rawSummary
    val currency = summary.currencyCode

    var ledgerFilter by remember { mutableStateOf(MemberLedgerFilter.ALL) }
    var datePreset by remember { mutableStateOf(MemberLedgerDatePreset.ALL_TIME) }
    var customStartMillis by remember { mutableStateOf<Long?>(null) }
    var customEndMillis by remember { mutableStateOf<Long?>(null) }
    var ledgerSearch by remember { mutableStateOf("") }

    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var inspectedEntry by remember { mutableStateOf<MemberLedgerEntry?>(null) }
    var quickTxType by remember { mutableStateOf<TransactionType?>(null) }
    var showContributeDialog by remember { mutableStateOf(false) }

    val filteredLedger = remember(
        profile.ledgerEntries,
        ledgerFilter,
        datePreset,
        customStartMillis,
        customEndMillis,
        ledgerSearch
    ) {
        MemberAccountEngine.filterLedgerEntries(
            entries = profile.ledgerEntries,
            typeFilter = ledgerFilter,
            datePreset = datePreset,
            customStartMillis = customStartMillis,
            customEndMillis = customEndMillis,
            searchQuery = ledgerSearch
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 980.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top bar within Profile
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("back_to_member_cards_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back_to_member_cards),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("All Cards")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!profile.isViewerOwner && profile.isViewerAdmin && profile.canViewerAuditDetails) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Admin Audit Logged", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (profile.isViewerOwner) {
                        OutlinedButton(
                            onClick = { showPrivacyDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("member_privacy_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PrivacyTip,
                                contentDescription = stringResource(R.string.cd_member_card_privacy),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Privacy")
                        }
                    }
                }
            }

            // 1. Identity Card (Strict PII exclusion)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        MemberAvatar(
                            fullName = card.fullName,
                            photoUrl = card.photoUrl,
                            size = 64.dp
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = card.fullName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${card.familyName} • ${if (card.role == FamilyRole.ADMIN) "Admin" else "Member"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Status: ${card.membershipStatus} • Joined ${formatModuleDate(card.joinedAtMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "16-CHARACTER MEMBER ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = card.cardMemberId,
                                    style = MoneySmallStyle.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            IconButton(
                                onClick = { onCopyValue("Member ID", card.cardMemberId) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_member_card_id")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_member_card_id),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 2. Individual Financial Summary (all categories strictly separate)
            SectionHeader(
                title = "Individual Financial Account",
                subtitle = "Loans, savings, contributions & investment P&L remain strictly separate"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacyAwareKpiTile(
                    label = "Available Balance",
                    visible = profile.canSeeBalance,
                    value = MoneyCalculator.formatMinorUnits(summary.availableBalanceMinor, currency),
                    supporting = "Family Total: ${MoneyCalculator.formatMinorUnits(summary.familyTotalBalanceMinor, currency)}",
                    accentColor = if (summary.availableBalanceMinor >= 0L) IncomeGreen else ExpenseCoral,
                    modifier = Modifier.weight(1f)
                )
                PrivacyAwareKpiTile(
                    label = "Family Contributions",
                    visible = profile.canSeeContributions,
                    value = MoneyCalculator.formatMinorUnits(summary.familyContributionsMinor, currency),
                    supporting = "Tracked separately from income",
                    accentColor = InfoTeal,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacyAwareKpiTile(
                    label = "Total Income",
                    visible = profile.canSeeIncomeExpense,
                    value = MoneyCalculator.formatMinorUnits(summary.totalIncomeMinor, currency),
                    supporting = "Ordinary non-loan income",
                    accentColor = IncomeGreen,
                    modifier = Modifier.weight(1f)
                )
                PrivacyAwareKpiTile(
                    label = "Total Expenses",
                    visible = profile.canSeeIncomeExpense,
                    value = MoneyCalculator.formatMinorUnits(summary.totalExpenseMinor, currency),
                    supporting = "Ordinary non-repayment spend",
                    accentColor = ExpenseCoral,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacyAwareKpiTile(
                    label = "Loans Given (Out)",
                    visible = profile.canSeeLoans,
                    value = MoneyCalculator.formatMinorUnits(summary.outstandingLoansGivenMinor, currency),
                    supporting = "Outstanding lent principal",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                PrivacyAwareKpiTile(
                    label = "Loans Received (In)",
                    visible = profile.canSeeLoans,
                    value = MoneyCalculator.formatMinorUnits(summary.outstandingLoansReceivedMinor, currency),
                    supporting = "Outstanding borrowed (not income)",
                    accentColor = WarningAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacyAwareKpiTile(
                    label = "Savings Balance",
                    visible = profile.canSeeSavings,
                    value = MoneyCalculator.formatMinorUnits(summary.savingsBalanceMinor, currency),
                    supporting = "Transfers not new income",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                PrivacyAwareKpiTile(
                    label = "Investment Principal",
                    visible = profile.canSeeInvestments,
                    value = MoneyCalculator.formatMinorUnits(summary.investmentPrincipalMinor, currency),
                    supporting = "Cost basis kept separate",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            val pnlAccent = if (summary.investmentNetProfitLossMinor >= 0L) IncomeGreen else ExpenseCoral
            PrivacyAwareKpiTile(
                label = "Investment Profit / Loss (Separate from Principal)",
                visible = profile.canSeeInvestments,
                value = if (summary.investmentNetProfitLossMinor >= 0L) {
                    "+${MoneyCalculator.formatMinorUnits(summary.investmentNetProfitLossMinor, currency)}"
                } else {
                    MoneyCalculator.formatMinorUnits(summary.investmentNetProfitLossMinor, currency)
                },
                supporting = "Gross Profit: ${MoneyCalculator.formatMinorUnits(summary.investmentProfitMinor, currency)} • Gross Loss: ${
                    MoneyCalculator.formatMinorUnits(summary.investmentLossMinor, currency)
                }",
                accentColor = pnlAccent,
                modifier = Modifier.fillMaxWidth()
            )

            // 3. Validated Record Actions (never direct manual balance edit)
            if (profile.isViewerOwner || profile.isViewerAdmin) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Validated Financial Record Entry",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Balances cannot be edited manually. Add validated records synced through SQLite & Firestore:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { quickTxType = TransactionType.INCOME },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("member_add_income_button")
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Income")
                            }
                            OutlinedButton(
                                onClick = { quickTxType = TransactionType.EXPENSE },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("member_add_expense_button")
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Expense")
                            }
                            if (!activeFamily.isPersonal && personalFamily != null && profile.isViewerOwner) {
                                OutlinedButton(
                                    onClick = { showContributeDialog = true },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("member_contribute_personal_button")
                                ) {
                                    Icon(
                                        Icons.Outlined.Diversity3,
                                        contentDescription = stringResource(R.string.cd_add_member_contribution),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("Contribute")
                                }
                            }
                            TextButton(onClick = { onNavigateToModule(AppModule.LOANS) }) { Text("Loans") }
                            TextButton(onClick = { onNavigateToModule(AppModule.SAVINGS) }) { Text("Savings") }
                            TextButton(onClick = { onNavigateToModule(AppModule.INVESTMENTS) }) { Text("Investments") }
                        }
                    }
                }
            }

            // 4. Individual Financial Ledger
            SectionHeader(
                title = "Individual Financial Ledger",
                subtitle = if (profile.canSeeHistory) {
                    "${filteredLedger.size} of ${profile.ledgerEntries.size} entries shown"
                } else {
                    "History restricted by member privacy settings"
                }
            )

            if (!profile.canSeeHistory) {
                ModuleEmptyRecordsCard(
                    icon = Icons.Outlined.Lock,
                    title = "Ledger Hidden by Privacy Settings",
                    subtitle = "${card.fullName} has restricted transaction history visibility on their Member Card."
                )
            } else {
                OutlinedTextField(
                    value = ledgerSearch,
                    onValueChange = { ledgerSearch = it },
                    placeholder = { Text("Search by title, Transaction ID, Reference ID, or member") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ledgerSearch.isNotEmpty()) {
                            IconButton(onClick = { ledgerSearch = "" }) {
                                Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("member_ledger_search_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemberLedgerFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = ledgerFilter == filter,
                            onClick = { ledgerFilter = filter },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("ledger_filter_${filter.name.lowercase()}")
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemberLedgerDatePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = datePreset == preset,
                            onClick = {
                                if (preset == MemberLedgerDatePreset.CUSTOM) {
                                    showDateRangeDialog = true
                                } else {
                                    datePreset = preset
                                }
                            },
                            label = {
                                Text(
                                    if (preset == MemberLedgerDatePreset.CUSTOM && customStartMillis != null) {
                                        "Custom (${formatModuleDate(customStartMillis!!)})"
                                    } else {
                                        preset.label
                                    }
                                )
                            },
                            modifier = Modifier.testTag("ledger_date_${preset.name.lowercase()}")
                        )
                    }
                }

                if (filteredLedger.isEmpty()) {
                    ModuleEmptyRecordsCard(
                        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                        title = "No individual ledger entries found",
                        subtitle = "Adjust the category or date filters, or add a validated transaction above."
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        filteredLedger.forEach { entry ->
                            MemberLedgerEntryCard(
                                entry = entry,
                                onClick = { inspectedEntry = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPrivacyDialog) {
        MemberCardPrivacySettingsDialog(
            initialSettings = card.privacy,
            isBusy = isBusy,
            onDismiss = { showPrivacyDialog = false },
            onSave = { updated ->
                showPrivacyDialog = false
                onUpdatePrivacy(updated)
            }
        )
    }

    if (showDateRangeDialog) {
        MemberLedgerDateRangeDialog(
            initialStartMillis = customStartMillis,
            initialEndMillis = customEndMillis,
            onDismiss = { showDateRangeDialog = false },
            onApply = { start, end ->
                customStartMillis = start
                customEndMillis = end
                datePreset = MemberLedgerDatePreset.CUSTOM
                showDateRangeDialog = false
            }
        )
    }

    inspectedEntry?.let { entry ->
        MemberLedgerEntryDetailsDialog(
            entry = entry,
            onCopyId = onCopyValue,
            onDismiss = { inspectedEntry = null }
        )
    }

    quickTxType?.let { txType ->
        TransactionEditorDialog(
            transactionType = txType,
            currencyCode = activeFamily.currencyCode,
            currentUserId = card.userId,
            members = activeMembers,
            existingTransaction = null,
            isBusy = isBusy,
            onDismiss = { quickTxType = null },
            onSave = { amountMinor, category, description, memberId, paymentMethod, occurredAtMillis, recurrence, _ ->
                quickTxType = null
                onCreateTransaction(
                    txType,
                    amountMinor,
                    category,
                    description,
                    memberId.ifBlank { card.userId },
                    paymentMethod,
                    occurredAtMillis,
                    recurrence
                )
            }
        )
    }

    if (showContributeDialog && !activeFamily.isPersonal) {
        ContributeFromPersonalDialog(
            targetFamilyName = activeFamily.name,
            currencyCode = activeFamily.currencyCode,
            onDismiss = { showContributeDialog = false },
            onConfirm = { amountMinor, category, description ->
                showContributeDialog = false
                onContributeFromPersonal(activeFamily.familyId, amountMinor, category, description)
            }
        )
    }
}

@Composable
private fun PrivacyAwareKpiTile(
    label: String,
    visible: Boolean,
    value: String,
    supporting: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    ModuleKpiTile(
        label = label,
        value = if (visible) value else "Hidden",
        supporting = if (visible) supporting else "Restricted by member privacy",
        accentColor = if (visible) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun MemberLedgerEntryCard(
    entry: MemberLedgerEntry,
    onClick: () -> Unit
) {
    val statusColor = when (entry.approvalStatus) {
        MemberLedgerApprovalStatus.APPROVED -> IncomeGreen
        MemberLedgerApprovalStatus.PENDING_REVIEW -> WarningAmber
        MemberLedgerApprovalStatus.VOIDED -> ExpenseCoral
        MemberLedgerApprovalStatus.SETTLED -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("member_ledger_item_${entry.transactionId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.type.label} • ${formatMemberLedgerDateTime(entry.occurredAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = MoneyCalculator.formatMinorUnits(entry.amountMinor, entry.currencyCode),
                        style = MoneySmallStyle.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = entry.approvalStatus.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Member: ${entry.relatedMemberName} • Ref: ${entry.referenceId.take(14)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ModuleSyncStatusChip(syncStatus = entry.syncStatus)
            }
        }
    }
}
