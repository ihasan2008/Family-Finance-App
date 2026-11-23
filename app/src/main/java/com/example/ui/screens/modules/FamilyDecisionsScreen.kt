package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalProposalVoteHistoryEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.model.AppModule
import com.example.core.model.ApprovalDecisionEngine
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.DecisionCategory
import com.example.core.model.EligibleVoterRule
import com.example.core.model.MoneyCalculator
import com.example.core.model.ProposalStatus
import com.example.core.model.VoteBallotInput
import com.example.core.model.VoteChoice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ProposalFilterTab(val label: String) {
    ALL("All"),
    NEED_MY_VOTE("My Vote Needed"),
    PENDING("Pending"),
    APPROVED("Approved"),
    EXECUTED("Executed"),
    CLOSED("Rejected / Expired"),
    DRAFTS("Drafts")
}

private val proposalDateFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

@Composable
fun FamilyDecisionsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    approvalSettings: LocalApprovalSettingsEntity?,
    proposals: List<LocalProposalEntity>,
    allVotes: List<LocalProposalVoteEntity>,
    allHistory: List<LocalProposalVoteHistoryEntity>,
    savingsGoals: List<LocalSavingsEntity>,
    selectedProposalId: String?,
    isBusy: Boolean,
    onSelectProposal: (String?) -> Unit,
    onSaveApprovalSettings: (
        ruleMode: ApprovalRuleMode,
        minApprovalPercent: Int,
        minQuorumCount: Int,
        defaultDeadlineHours: Int,
        allowCreatorVote: Boolean,
        allowVoteChangeBeforeDeadline: Boolean,
        eligibleVoterRule: EligibleVoterRule,
        specificEligibleUids: List<String>,
        requiredCategories: Set<DecisionCategory>,
        largeExpenseThresholdMinor: Long
    ) -> Unit,
    onSaveOrSubmitProposal: (
        existingDraft: LocalProposalEntity?,
        title: String,
        category: DecisionCategory,
        description: String,
        reason: String,
        amountMinor: Long,
        currency: String,
        relatedParty: String,
        supportingDocName: String?,
        supportingDocUri: String?,
        targetSavingsId: String?,
        proposedDateMillis: Long,
        votingDeadlineMillis: Long,
        submitNow: Boolean,
        onSaved: (LocalProposalEntity) -> Unit
    ) -> Unit,
    onDeleteDraftProposal: (String, () -> Unit) -> Unit,
    onCastOrChangeVote: (String, VoteChoice, String?) -> Unit,
    onCancelProposal: (String, String) -> Unit,
    onExecuteApprovedProposal: (String) -> Unit,
    onNavigateToModule: (AppModule) -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Approvals & Decisions",
            moduleSubtitle = "Create or join a family workspace to propose major financial decisions, vote transparently, and execute approved actions.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val isAdmin = remember(activeFamily, currentUserId) { activeFamily.isAdmin(currentUserId) }
    val callerMember = remember(activeMembers, currentUserId) {
        activeMembers.firstOrNull { it.userId == currentUserId }
    }
    val canCreateProposal = remember(activeFamily, callerMember, isAdmin, currentUserId) {
        ApprovalDecisionEngine.canCreateProposal(
            isAdmin = isAdmin,
            isSuspended = activeFamily.isSuspended(currentUserId),
            canCreateRecords = callerMember?.canCreateRecords ?: false
        )
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<LocalProposalEntity?>(null) }
    var showRulesDialog by remember { mutableStateOf(false) }

    val selectedProposal = remember(proposals, selectedProposalId) {
        selectedProposalId?.let { id -> proposals.firstOrNull { it.proposalId == id } }
    }

    if (selectedProposal != null) {
        val votesForSelected = remember(allVotes, selectedProposal.proposalId) {
            allVotes.filter { it.proposalId == selectedProposal.proposalId }
        }
        val historyForSelected = remember(allHistory, selectedProposal.proposalId) {
            allHistory.filter { it.proposalId == selectedProposal.proposalId }
                .sortedByDescending { it.createdAtMillis }
        }
        FamilyDecisionDetailScreen(
            currentUserId = currentUserId,
            activeFamily = activeFamily,
            activeMembers = activeMembers,
            savingsGoals = savingsGoals,
            proposal = selectedProposal,
            votes = votesForSelected,
            history = historyForSelected,
            isBusy = isBusy,
            onBack = { onSelectProposal(null) },
            onEditDraft = { draft ->
                editingDraft = draft
                showCreateDialog = true
            },
            onDeleteDraft = { draftId ->
                onDeleteDraftProposal(draftId) { onSelectProposal(null) }
            },
            onCastOrChangeVote = { choice, comment ->
                onCastOrChangeVote(selectedProposal.proposalId, choice, comment)
            },
            onCancelProposal = { reason ->
                onCancelProposal(selectedProposal.proposalId, reason)
            },
            onExecuteProposal = {
                onExecuteApprovedProposal(selectedProposal.proposalId)
            },
            onOpenTargetModule = onNavigateToModule,
            modifier = modifier
        )
    } else {
        var selectedFilter by remember { mutableStateOf(ProposalFilterTab.ALL) }
        var selectedCategoryFilter by remember { mutableStateOf<DecisionCategory?>(null) }

        val pendingCount = remember(proposals) { proposals.count { it.status == ProposalStatus.PENDING.name } }
        val approvedCount = remember(proposals) { proposals.count { it.status == ProposalStatus.APPROVED.name } }
        val executedCount = remember(proposals) { proposals.count { it.status == ProposalStatus.EXECUTED.name } }
        val myVotesPendingCount = remember(proposals, allVotes, currentUserId) {
            proposals.count { p ->
                p.status == ProposalStatus.PENDING.name &&
                    currentUserId in p.eligibleVoterUidList() &&
                    allVotes.none { v -> v.proposalId == p.proposalId && v.voterUid == currentUserId }
            }
        }

        val filteredProposals = remember(proposals, allVotes, selectedFilter, selectedCategoryFilter, currentUserId) {
            proposals.filter { p ->
                val matchesTab = when (selectedFilter) {
                    ProposalFilterTab.ALL -> true
                    ProposalFilterTab.NEED_MY_VOTE -> p.status == ProposalStatus.PENDING.name &&
                        currentUserId in p.eligibleVoterUidList() &&
                        allVotes.none { v -> v.proposalId == p.proposalId && v.voterUid == currentUserId }
                    ProposalFilterTab.PENDING -> p.status == ProposalStatus.PENDING.name
                    ProposalFilterTab.APPROVED -> p.status == ProposalStatus.APPROVED.name
                    ProposalFilterTab.EXECUTED -> p.status == ProposalStatus.EXECUTED.name
                    ProposalFilterTab.CLOSED -> p.status in setOf(
                        ProposalStatus.REJECTED.name,
                        ProposalStatus.EXPIRED.name,
                        ProposalStatus.CANCELLED.name
                    )
                    ProposalFilterTab.DRAFTS -> p.status == ProposalStatus.DRAFT.name &&
                        p.proposerUid == currentUserId
                }
                val matchesCategory = selectedCategoryFilter == null || p.category == selectedCategoryFilter!!.name
                matchesTab && matchesCategory
            }
        }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            floatingActionButton = {
                if (canCreateProposal) {
                    FloatingActionButton(
                        onClick = {
                            editingDraft = null
                            showCreateDialog = true
                        },
                        modifier = Modifier.testTag("new_proposal_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.cd_create_proposal)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 880.dp)
                        .testTag("decisions_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Family Approvals & Decisions",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Propose loans, investments, savings withdrawals & major purchases. Financial balances only change after approval and execution.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Summary KPI strip
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DecisionKpiCard(
                                label = "Pending",
                                value = "$pendingCount",
                                subtitle = if (myVotesPendingCount > 0) "$myVotesPendingCount need your vote" else "All caught up",
                                highlighted = myVotesPendingCount > 0,
                                modifier = Modifier.weight(1f)
                            )
                            DecisionKpiCard(
                                label = "Approved",
                                value = "$approvedCount",
                                subtitle = "Ready to execute",
                                highlighted = approvedCount > 0,
                                modifier = Modifier.weight(1f)
                            )
                            DecisionKpiCard(
                                label = "Executed",
                                value = "$executedCount",
                                subtitle = "Posted to ledgers",
                                highlighted = false,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Active Approval Rule Overview + Configure Button
                    item {
                        val effectiveSettings = approvalSettings ?: LocalApprovalSettingsEntity(familyId = activeFamily.familyId)
                        val ruleMode = ApprovalRuleMode.fromRaw(effectiveSettings.ruleMode)
                        val voterRule = EligibleVoterRule.fromRaw(effectiveSettings.eligibleVoterRule)
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Gavel,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column {
                                            Text(
                                                text = "Active Rule: ${ruleMode.label}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${voterRule.label} • Quorum ${effectiveSettings.minQuorumCount} • " +
                                                    if (ruleMode == ApprovalRuleMode.MIN_PERCENTAGE) {
                                                        "Min ${effectiveSettings.minApprovalPercent}%"
                                                    } else {
                                                        "${effectiveSettings.defaultDeadlineHours}h default window"
                                                    },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { showRulesDialog = true },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("approval_rules_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Tune,
                                            contentDescription = stringResource(R.string.cd_configure_approval_rules),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isAdmin) "Rules" else "View Rules")
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (canCreateProposal) {
                                        Button(
                                            onClick = {
                                                editingDraft = null
                                                showCreateDialog = true
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .minimumInteractiveComponentSize()
                                                .testTag("new_proposal_button")
                                        ) {
                                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("New Proposal")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Status filter chips
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProposalFilterTab.entries.forEach { tab ->
                                val label = if (tab == ProposalFilterTab.NEED_MY_VOTE && myVotesPendingCount > 0) {
                                    "${tab.label} ($myVotesPendingCount)"
                                } else {
                                    tab.label
                                }
                                FilterChip(
                                    selected = selectedFilter == tab,
                                    onClick = { selectedFilter = tab },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    // Category filter chips
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedCategoryFilter == null,
                                onClick = { selectedCategoryFilter = null },
                                label = { Text("All Categories") }
                            )
                            DecisionCategory.entries.forEach { cat ->
                                FilterChip(
                                    selected = selectedCategoryFilter == cat,
                                    onClick = {
                                        selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat
                                    },
                                    label = { Text(cat.shortLabel) }
                                )
                            }
                        }
                    }

                    if (filteredProposals.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.HowToVote,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "No proposals in this view",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Create a financial proposal to collect family votes before recording large loans, investments, savings withdrawals, or expenses.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredProposals, key = { it.proposalId }) { proposal ->
                            val votesForProposal = remember(allVotes, proposal.proposalId) {
                                allVotes.filter { it.proposalId == proposal.proposalId }
                            }
                            ProposalSummaryCard(
                                currentUserId = currentUserId,
                                proposal = proposal,
                                votes = votesForProposal,
                                onClick = { onSelectProposal(proposal.proposalId) }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (showCreateDialog) {
        val settings = approvalSettings ?: LocalApprovalSettingsEntity(familyId = activeFamily.familyId)
        ProposalFormDialog(
            activeFamily = activeFamily,
            settings = settings,
            savingsGoals = savingsGoals,
            existingDraft = editingDraft,
            isBusy = isBusy,
            onDismiss = {
                showCreateDialog = false
                editingDraft = null
            },
            onSaveOrSubmit = { title, cat, desc, reason, amountMinor, currency, party, docName, docUri, targetSavId, proposedMs, deadlineMs, submitNow ->
                onSaveOrSubmitProposal(
                    editingDraft,
                    title,
                    cat,
                    desc,
                    reason,
                    amountMinor,
                    currency,
                    party,
                    docName,
                    docUri,
                    targetSavId,
                    proposedMs,
                    deadlineMs,
                    submitNow
                ) { saved ->
                    showCreateDialog = false
                    editingDraft = null
                    onSelectProposal(saved.proposalId)
                }
            }
        )
    }

    if (showRulesDialog) {
        ApprovalRulesDialog(
            activeFamily = activeFamily,
            activeMembers = activeMembers,
            currentSettings = approvalSettings ?: LocalApprovalSettingsEntity(familyId = activeFamily.familyId),
            isAdmin = isAdmin,
            isBusy = isBusy,
            onDismiss = { showRulesDialog = false },
            onSaveRules = { mode, minPct, quorum, deadlineHrs, creatorVote, allowChange, voterRule, specificUids, reqCats, threshold ->
                onSaveApprovalSettings(
                    mode, minPct, quorum, deadlineHrs, creatorVote, allowChange, voterRule, specificUids, reqCats, threshold
                )
                showRulesDialog = false
            }
        )
    }
}

@Composable
private fun DecisionKpiCard(
    label: String,
    value: String,
    subtitle: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProposalSummaryCard(
    currentUserId: String,
    proposal: LocalProposalEntity,
    votes: List<LocalProposalVoteEntity>,
    onClick: () -> Unit
) {
    val category = remember(proposal.category) { DecisionCategory.fromRaw(proposal.category) }
    val status = remember(proposal.status) { ProposalStatus.fromRaw(proposal.status) }
    val nowMillis = System.currentTimeMillis()
    val tally = remember(proposal, votes) {
        ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = proposal.eligibleVoterUidList(),
            votes = votes.mapNotNull { VoteChoice.fromRaw(it.choice)?.let { c -> VoteBallotInput(it.voterUid, c) } },
            ruleMode = ApprovalRuleMode.fromRaw(proposal.ruleMode),
            minApprovalPercent = proposal.minApprovalPercent,
            minQuorumRequired = proposal.minQuorumRequired,
            votingDeadlineMillis = proposal.votingDeadlineMillis,
            nowMillis = nowMillis,
            currentStatus = status,
            existingFinalDecision = proposal.finalDecision
        )
    }
    val myVote = remember(votes, currentUserId) { votes.firstOrNull { it.voterUid == currentUserId } }
    val deadlineText = remember(proposal.votingDeadlineMillis) {
        proposalDateFmt.format(Instant.ofEpochMilli(proposal.votingDeadlineMillis).atZone(ZoneId.systemDefault()))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("proposal_card_${proposal.proposalId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = category.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                ProposalStatusPill(status = status)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = proposal.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "By ${proposal.proposerName}" +
                            if (proposal.relatedParty.isNotBlank()) " • ${proposal.relatedParty}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (status != ProposalStatus.DRAFT) {
                val progress = if (tally.totalEligible > 0) {
                    (tally.approvedCount.toFloat() / tally.totalEligible.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${tally.approvedCount}/${tally.totalEligible} approved (${tally.approvalPercentOfEligible}%) • ${tally.pendingCount} pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tally.isNearDeadline) {
                            Icon(
                                imageVector = Icons.Outlined.HourglassBottom,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = if (status == ProposalStatus.EXECUTED) "Executed" else "Due $deadlineText",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tally.isNearDeadline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (myVote != null) {
                    val c = VoteChoice.fromRaw(myVote.choice)
                    Text(
                        text = "Your vote: ${c?.label ?: myVote.choice}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (c == VoteChoice.APPROVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                } else if (status == ProposalStatus.PENDING && currentUserId in proposal.eligibleVoterUidList()) {
                    Text(
                        text = "Tap to review & vote",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (status == ProposalStatus.APPROVED && proposal.executedRecordId.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Approved — Ready to Execute",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Rule, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = ApprovalRuleMode.fromRaw(proposal.ruleMode).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProposalStatusPill(status: ProposalStatus) {
    val (bg, fg) = when (status) {
        ProposalStatus.DRAFT -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ProposalStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ProposalStatus.APPROVED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ProposalStatus.EXECUTED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ProposalStatus.REJECTED, ProposalStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ProposalStatus.EXPIRED -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
