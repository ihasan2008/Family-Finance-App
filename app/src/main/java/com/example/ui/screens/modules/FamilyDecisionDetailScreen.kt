package com.example.ui.screens.modules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
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
import com.example.core.model.MoneyCalculator
import com.example.core.model.ProposalStatus
import com.example.core.model.VoteAuditActionType
import com.example.core.model.VoteBallotInput
import com.example.core.model.VoteChoice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val detailDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

@Composable
fun FamilyDecisionDetailScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity,
    activeMembers: List<LocalFamilyMemberEntity>,
    savingsGoals: List<LocalSavingsEntity>,
    proposal: LocalProposalEntity,
    votes: List<LocalProposalVoteEntity>,
    history: List<LocalProposalVoteHistoryEntity>,
    isBusy: Boolean,
    onBack: () -> Unit,
    onEditDraft: (LocalProposalEntity) -> Unit,
    onDeleteDraft: (String) -> Unit,
    onCastOrChangeVote: (VoteChoice, String?) -> Unit,
    onCancelProposal: (String) -> Unit,
    onExecuteProposal: () -> Unit,
    onOpenTargetModule: (AppModule) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val isAdmin = remember(activeFamily, currentUserId) { activeFamily.isAdmin(currentUserId) }
    val category = remember(proposal.category) { DecisionCategory.fromRaw(proposal.category) }
    val status = remember(proposal.status) { ProposalStatus.fromRaw(proposal.status) }
    val ruleMode = remember(proposal.ruleMode) { ApprovalRuleMode.fromRaw(proposal.ruleMode) }
    val eligibleUids = remember(proposal.eligibleVoterUidsCsv) { proposal.eligibleVoterUidList() }
    val nowMillis = System.currentTimeMillis()
    val tally = remember(proposal, votes, nowMillis) {
        ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligibleUids,
            votes = votes.mapNotNull { VoteChoice.fromRaw(it.choice)?.let { c -> VoteBallotInput(it.voterUid, c) } },
            ruleMode = ruleMode,
            minApprovalPercent = proposal.minApprovalPercent,
            minQuorumRequired = proposal.minQuorumRequired,
            votingDeadlineMillis = proposal.votingDeadlineMillis,
            nowMillis = nowMillis,
            currentStatus = status,
            existingFinalDecision = proposal.finalDecision
        )
    }

    val myVote = remember(votes, currentUserId) { votes.firstOrNull { it.voterUid == currentUserId } }
    val myVoteChoice = remember(myVote) { VoteChoice.fromRaw(myVote?.choice) }
    val isSuspended = activeFamily.isSuspended(currentUserId)
    val isCreatorBlocked = !proposal.allowCreatorVote && proposal.proposerUid == currentUserId
    val isEligibleVoter = !isSuspended && currentUserId in eligibleUids && !isCreatorBlocked
    val deadlinePassed = nowMillis > proposal.votingDeadlineMillis
    val canCastInitialVote = status == ProposalStatus.PENDING && isEligibleVoter && myVote == null && !deadlinePassed
    val canChangeExistingVote = status == ProposalStatus.PENDING && isEligibleVoter && myVote != null &&
        proposal.allowVoteChangeBeforeDeadline && !deadlinePassed

    var voteComment by remember { mutableStateOf("") }
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReasonInput by remember { mutableStateOf("") }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .testTag("decision_detail_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Back Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_to_proposals)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = proposal.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${category.label} • Proposed by ${proposal.proposerName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ProposalStatusPill(status = status)
                }
            }

            // Financial Summary & Metadata Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = ruleMode.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (proposal.relatedParty.isNotBlank()) {
                            DetailRow(label = "Related Party / Org", value = proposal.relatedParty)
                        }
                        if (category == DecisionCategory.WITHDRAW_SAVINGS && !proposal.targetSavingsId.isNullOrBlank()) {
                            val goalName = savingsGoals.firstOrNull { it.recordId == proposal.targetSavingsId }?.goalName
                                ?: proposal.targetSavingsId.orEmpty()
                            DetailRow(label = "Savings Goal", value = goalName)
                        }
                        DetailRow(
                            label = "Proposed Date",
                            value = detailDateFmt.format(Instant.ofEpochMilli(proposal.proposedDateMillis).atZone(ZoneId.systemDefault()))
                        )
                        DetailRow(
                            label = "Voting Deadline",
                            value = detailDateFmt.format(Instant.ofEpochMilli(proposal.votingDeadlineMillis).atZone(ZoneId.systemDefault()))
                        )
                        if (proposal.reason.isNotBlank()) {
                            DetailRow(label = "Reason", value = proposal.reason)
                        }
                        if (proposal.description.isNotBlank()) {
                            DetailRow(label = "Description", value = proposal.description)
                        }
                        if (!proposal.supportingDocName.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = "Document: ${proposal.supportingDocName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (!proposal.cancelReason.isNullOrBlank()) {
                            DetailRow(label = "Cancellation Reason", value = proposal.cancelReason.orEmpty())
                        }
                    }
                }
            }

            // Voting Progress & Counts Card
            if (status != ProposalStatus.DRAFT) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Voting Progress",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Decision: ${tally.finalDecision ?: "In Progress"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            val progress = if (tally.totalEligible > 0) {
                                (tally.approvedCount.toFloat() / tally.totalEligible.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                VoteStatChip("Eligible", "${tally.totalEligible}", Modifier.weight(1f))
                                VoteStatChip("Approved", "${tally.approvedCount}", Modifier.weight(1f))
                                VoteStatChip("Rejected", "${tally.rejectedCount}", Modifier.weight(1f))
                                VoteStatChip("Pending", "${tally.pendingCount}", Modifier.weight(1f))
                            }

                            Text(
                                text = "Approval Rate: ${tally.approvalPercentOfEligible}% of eligible (${tally.approvalPercentOfCast}% of cast) • " +
                                    "Needs ${tally.requiredApprovals} approve & quorum ${tally.effectiveQuorum}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = when {
                                        myVoteChoice != null -> "Your Vote Status: ${myVoteChoice.label}" +
                                            if (canChangeExistingVote) " (you may change your vote before the deadline)" else ""
                                        isCreatorBlocked -> "Your Vote Status: Proposal creators cannot vote under current family rules."
                                        !isEligibleVoter -> "Your Vote Status: Observer only (not included in eligible voters rule)."
                                        status == ProposalStatus.PENDING -> "Your Vote Status: Pending — cast Approve or Reject below."
                                        else -> "Your Vote Status: No vote recorded before closing."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Draft Controls
            if (status == ProposalStatus.DRAFT && proposal.proposerUid == currentUserId) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onEditDraft(proposal) },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit / Submit")
                        }
                        OutlinedButton(
                            onClick = { onDeleteDraft(proposal.proposalId) },
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete Draft")
                        }
                    }
                }
            }

            // Voting Controls
            if (canCastInitialVote || canChangeExistingVote) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (canChangeExistingVote) "Change Your Vote Before Deadline" else "Cast Your Decision Vote",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = voteComment,
                                onValueChange = { voteComment = it },
                                label = { Text("Optional vote note for family audit log") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onCastOrChangeVote(VoteChoice.APPROVE, voteComment.takeIf { it.isNotBlank() })
                                        voteComment = ""
                                    },
                                    enabled = !isBusy && myVoteChoice != VoteChoice.APPROVE,
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("vote_approve_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ThumbUp,
                                        contentDescription = stringResource(R.string.cd_approve_proposal),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (myVoteChoice == VoteChoice.APPROVE) "Approved" else "Approve")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onCastOrChangeVote(VoteChoice.REJECT, voteComment.takeIf { it.isNotBlank() })
                                        voteComment = ""
                                    },
                                    enabled = !isBusy && myVoteChoice != VoteChoice.REJECT,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("vote_reject_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ThumbDown,
                                        contentDescription = stringResource(R.string.cd_reject_proposal),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (myVoteChoice == VoteChoice.REJECT) "Rejected" else "Reject")
                                }
                            }
                        }
                    }
                }
            }

            // Financial Execution CTA (Strictly APPROVED -> EXECUTED)
            if (status == ProposalStatus.APPROVED && proposal.executedRecordId.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Proposal Approved — Ready for Single Financial Execution",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Executing will atomically record ${MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency)} in ${category.executionCollection} once.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = onExecuteProposal,
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("execute_proposal_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_execute_proposal),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Execute Financial Action")
                            }
                        }
                    }
                }
            }

            // Executed confirmation badge
            if (status == ProposalStatus.EXECUTED) {
                item {
                    val targetModule = when (category) {
                        DecisionCategory.TAKE_LOAN, DecisionCategory.GIVE_LOAN -> AppModule.LOANS
                        DecisionCategory.MAKE_INVESTMENT -> AppModule.INVESTMENTS
                        DecisionCategory.WITHDRAW_SAVINGS -> AppModule.SAVINGS
                        DecisionCategory.LARGE_EXPENSE, DecisionCategory.LARGE_PURCHASE, DecisionCategory.OTHER_CONFIGURABLE -> AppModule.EXPENSE
                    }
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text(
                                        text = "Financially Executed",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Recorded in ${proposal.executedCollection ?: category.executionCollection} (${proposal.executedRecordId})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { onOpenTargetModule(targetModule) }) {
                                Text("Open ${targetModule.shortLabel}")
                            }
                        }
                    }
                }
            }

            // Admin Cancel Control (never overrides voting)
            if (isAdmin && (status == ProposalStatus.PENDING || status == ProposalStatus.DRAFT)) {
                item {
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        enabled = !isBusy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("admin_cancel_proposal_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Block,
                            contentDescription = stringResource(R.string.cd_cancel_proposal),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Admin: Cancel Proposal with Reason")
                    }
                }
            }

            // Member Votes Breakdown
            if (votes.isNotEmpty()) {
                item {
                    Text(
                        text = "Member Votes (${votes.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(votes, key = { "${it.proposalId}_${it.voterUid}" }) { vote ->
                    val choice = VoteChoice.fromRaw(vote.choice)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(vote.voterName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (!vote.comment.isNullOrBlank()) {
                                    Text(vote.comment.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = detailDateFmt.format(Instant.ofEpochMilli(vote.updatedAtMillis).atZone(ZoneId.systemDefault())),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (choice == VoteChoice.APPROVE) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            ) {
                                Text(
                                    text = choice?.label ?: vote.choice,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (choice == VoteChoice.APPROVE) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Immutable Voting Audit History
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Voting & Decision Audit History (${history.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            items(history, key = { it.historyId }) { entry ->
                val action = remember(entry.actionType) { VoteAuditActionType.fromRaw(entry.actionType) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${action.label} • ${entry.actorName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val delta = listOfNotNull(entry.previousChoice, entry.newChoice)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" -> ")
                    val details = listOfNotNull(delta, entry.note?.takeIf { it.isNotBlank() }).joinToString(" • ")
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = detailDateFmt.format(Instant.ofEpochMilli(entry.createdAtMillis).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Proposal", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Cancelling requires an explicit audit reason visible to all family members.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = cancelReasonInput,
                        onValueChange = { cancelReasonInput = it },
                        label = { Text("Cancellation Reason (min 5 chars)") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cancel_reason_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCancelProposal(cancelReasonInput.trim())
                        showCancelDialog = false
                        cancelReasonInput = ""
                    },
                    enabled = cancelReasonInput.trim().length >= 5,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Cancel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Back")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun VoteStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
