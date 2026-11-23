package com.example.core.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.Serializable

enum class DecisionCategory(
    val label: String,
    val shortLabel: String,
    val executionCollection: String
) {
    TAKE_LOAN("Taking a Loan", "Take Loan", "loans"),
    GIVE_LOAN("Giving a Loan", "Give Loan", "loans"),
    MAKE_INVESTMENT("Making an Investment", "Investment", "investments"),
    WITHDRAW_SAVINGS("Withdrawing Family Savings", "Withdraw Savings", "savings"),
    LARGE_EXPENSE("Large Family Expense", "Large Expense", "transactions"),
    LARGE_PURCHASE("Large Family Purchase", "Large Purchase", "transactions"),
    OTHER_CONFIGURABLE("Other Financial Decision", "Other", "transactions");

    companion object {
        fun fromRaw(raw: String?): DecisionCategory =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: OTHER_CONFIGURABLE

        fun parseCsv(csv: String?): Set<DecisionCategory> {
            if (csv.isNullOrBlank()) return entries.toSet()
            return csv.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token -> entries.firstOrNull { it.name == token } }
                .toSet()
                .ifEmpty { entries.toSet() }
        }
    }
}

enum class ApprovalRuleMode(val label: String, val summary: String) {
    MAJORITY("Majority Approval", "More than 50% of eligible voters must approve"),
    UNANIMOUS("Unanimous Approval", "All eligible voters must approve with zero rejections"),
    MIN_PERCENTAGE("Minimum Approval %", "Configurable approval percentage of eligible voters");

    companion object {
        fun fromRaw(raw: String?): ApprovalRuleMode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: MAJORITY
    }
}

enum class EligibleVoterRule(val label: String) {
    ALL_ACTIVE_MEMBERS("All Active Members"),
    RECORDS_AUTHORIZED_MEMBERS("Record-Authorized Members"),
    ADMINS_ONLY("Family Admins Only"),
    SPECIFIC_MEMBERS("Selected Specific Members");

    companion object {
        fun fromRaw(raw: String?): EligibleVoterRule =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ALL_ACTIVE_MEMBERS
    }
}

enum class ProposalStatus(val label: String) {
    DRAFT("Draft"),
    PENDING("Pending"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    EXPIRED("Expired"),
    CANCELLED("Cancelled"),
    EXECUTED("Executed");

    val isTerminal: Boolean
        get() = this == APPROVED || this == REJECTED || this == EXPIRED || this == CANCELLED || this == EXECUTED

    val isVotingOpen: Boolean
        get() = this == PENDING

    companion object {
        fun fromRaw(raw: String?): ProposalStatus =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: PENDING
    }
}

enum class VoteChoice(val label: String) {
    APPROVE("Approve"),
    REJECT("Reject");

    companion object {
        fun fromRaw(raw: String?): VoteChoice? =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
    }
}

enum class VoteAuditActionType(val label: String) {
    PROPOSAL_CREATED("Proposal Created"),
    PROPOSAL_SUBMITTED("Submitted for Vote"),
    VOTE_CAST("Vote Cast"),
    VOTE_CHANGED("Vote Changed"),
    PROPOSAL_APPROVED("Proposal Approved"),
    PROPOSAL_REJECTED("Proposal Rejected"),
    PROPOSAL_EXPIRED("Voting Expired"),
    PROPOSAL_CANCELLED("Cancelled by Admin"),
    PROPOSAL_EXECUTED("Financially Executed");

    companion object {
        fun fromRaw(raw: String?): VoteAuditActionType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: VOTE_CAST
    }
}

data class ApprovalSettingsDoc(
    @DocumentId val id: String = "default",
    val configId: String = "default",
    val familyId: String = "",
    val ruleMode: String = ApprovalRuleMode.MAJORITY.name,
    val minApprovalPercent: Int = 60,
    val minQuorumCount: Int = 1,
    val defaultDeadlineHours: Int = 72,
    val allowCreatorVote: Boolean = true,
    val allowVoteChangeBeforeDeadline: Boolean = true,
    val eligibleVoterRule: String = EligibleVoterRule.ALL_ACTIVE_MEMBERS.name,
    val specificEligibleUids: List<String> = emptyList(),
    val requiredCategories: List<String> = DecisionCategory.entries.map { it.name },
    val largeExpenseThresholdMinor: Long = 500_000L, // 5,000.00 default
    val updatedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class FamilyProposalDoc(
    @DocumentId val id: String = "",
    val proposalId: String = "",
    val familyId: String = "",
    val title: String = "",
    val category: String = DecisionCategory.LARGE_EXPENSE.name,
    val description: String = "",
    val reason: String = "",
    val amountMinor: Long = 0L,
    val currency: String = "BDT",
    val relatedParty: String = "",
    val supportingDocName: String? = null,
    val supportingDocUri: String? = null,
    val targetSavingsId: String? = null,
    val proposedDate: Timestamp? = null,
    val votingDeadline: Timestamp? = null,
    val proposerUid: String = "",
    val proposerName: String = "",
    val status: String = ProposalStatus.PENDING.name,
    val ruleMode: String = ApprovalRuleMode.MAJORITY.name,
    val minApprovalPercent: Int = 60,
    val minQuorumRequired: Int = 1,
    val allowCreatorVote: Boolean = true,
    val allowVoteChangeBeforeDeadline: Boolean = true,
    val eligibleVoterRule: String = EligibleVoterRule.ALL_ACTIVE_MEMBERS.name,
    val eligibleVoterUids: List<String> = emptyList(),
    val approvedCount: Int = 0,
    val rejectedCount: Int = 0,
    val finalDecision: String? = null,
    val cancelReason: String? = null,
    val cancelledByUid: String? = null,
    val executedRecordId: String? = null,
    val executedCollection: String? = null,
    val executedByUid: String? = null,
    val executedAt: Timestamp? = null,
    val decidedAt: Timestamp? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ProposalVoteDoc(
    @DocumentId val id: String = "",
    val voterUid: String = "",
    val voterName: String = "",
    val proposalId: String = "",
    val familyId: String = "",
    val choice: String = VoteChoice.APPROVE.name,
    val comment: String? = null,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val votedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ProposalVoteHistoryDoc(
    @DocumentId val id: String = "",
    val historyId: String = "",
    val proposalId: String = "",
    val familyId: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val actionType: String = VoteAuditActionType.VOTE_CAST.name,
    val previousChoice: String? = null,
    val newChoice: String? = null,
    val note: String? = null,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class VoterEligibilityCandidate(
    val userId: String,
    val role: String,
    val canCreateRecords: Boolean,
    val isSuspended: Boolean = false
)

data class VoteBallotInput(
    val voterUid: String,
    val choice: VoteChoice
)

data class ProposalTallySummary(
    val totalEligible: Int,
    val effectiveQuorum: Int,
    val requiredApprovals: Int,
    val approvedCount: Int,
    val rejectedCount: Int,
    val pendingCount: Int,
    val totalCast: Int,
    val approvalPercentOfEligible: Int,
    val approvalPercentOfCast: Int,
    val nextStatus: ProposalStatus,
    val finalDecision: String?,
    val isNearDeadline: Boolean
)

data class DecisionExecutionDescriptor(
    val targetCollection: String,
    val loanDirection: LoanDirection? = null,
    val transactionType: TransactionType? = null,
    val idempotencyKey: String
)

@Serializable
data class DecisionActionResponse(
    val ok: Boolean = false,
    val proposalId: String? = null,
    val status: String? = null,
    val executedRecordId: String? = null,
    val executedCollection: String? = null,
    val message: String? = null
)

object ApprovalDecisionEngine {
    const val NEAR_DEADLINE_WINDOW_MILLIS = 24L * 60L * 60L * 1000L

    fun resolveEligibleVoters(
        candidates: List<VoterEligibilityCandidate>,
        proposerUid: String,
        eligibleVoterRule: EligibleVoterRule,
        specificEligibleUids: List<String>,
        allowCreatorVote: Boolean
    ): List<String> {
        val active = candidates.filter { it.userId.isNotBlank() && !it.isSuspended }
        val specificSet = specificEligibleUids.toSet()
        val matched = active.filter { candidate ->
            when (eligibleVoterRule) {
                EligibleVoterRule.ALL_ACTIVE_MEMBERS -> true
                EligibleVoterRule.RECORDS_AUTHORIZED_MEMBERS ->
                    candidate.role.equals(FamilyRole.ADMIN.name, ignoreCase = true) || candidate.canCreateRecords
                EligibleVoterRule.ADMINS_ONLY ->
                    candidate.role.equals(FamilyRole.ADMIN.name, ignoreCase = true)
                EligibleVoterRule.SPECIFIC_MEMBERS ->
                    candidate.userId in specificSet
            }
        }.map { it.userId }.distinct()

        return if (allowCreatorVote) matched else matched.filterNot { it == proposerUid }
    }

    fun requiredApprovalsForRule(
        eligibleCount: Int,
        ruleMode: ApprovalRuleMode,
        minApprovalPercent: Int,
        effectiveQuorum: Int
    ): Int {
        if (eligibleCount <= 0) return 1
        return when (ruleMode) {
            ApprovalRuleMode.UNANIMOUS -> eligibleCount
            ApprovalRuleMode.MIN_PERCENTAGE -> {
                val pct = minApprovalPercent.coerceIn(1, 100)
                val pctRequired = ((eligibleCount * pct) + 99) / 100
                maxOf(effectiveQuorum, pctRequired.coerceAtLeast(1))
            }
            ApprovalRuleMode.MAJORITY -> {
                val strictMajority = (eligibleCount / 2) + 1
                maxOf(effectiveQuorum, strictMajority)
            }
        }
    }

    fun evaluateTally(
        eligibleVoterUids: List<String>,
        votes: List<VoteBallotInput>,
        ruleMode: ApprovalRuleMode,
        minApprovalPercent: Int,
        minQuorumRequired: Int,
        votingDeadlineMillis: Long,
        nowMillis: Long,
        currentStatus: ProposalStatus,
        existingFinalDecision: String? = null
    ): ProposalTallySummary {
        val eligibleSet = eligibleVoterUids.distinct().toSet()
        val totalEligible = eligibleSet.size
        val deduped = linkedMapOf<String, VoteChoice>()
        votes.forEach { ballot ->
            if (ballot.voterUid in eligibleSet) {
                deduped[ballot.voterUid] = ballot.choice
            }
        }

        val approvedCount = deduped.values.count { it == VoteChoice.APPROVE }
        val rejectedCount = deduped.values.count { it == VoteChoice.REJECT }
        val totalCast = approvedCount + rejectedCount
        val pendingCount = (totalEligible - totalCast).coerceAtLeast(0)
        val effectiveQuorum = if (totalEligible == 0) 1 else minQuorumRequired.coerceAtLeast(1).coerceAtMost(totalEligible)
        val requiredApprovals = requiredApprovalsForRule(
            eligibleCount = totalEligible,
            ruleMode = ruleMode,
            minApprovalPercent = minApprovalPercent,
            effectiveQuorum = effectiveQuorum
        )
        val approvalPercentOfEligible =
            if (totalEligible == 0) 0 else kotlin.math.round((approvedCount * 100.0) / totalEligible).toInt()
        val approvalPercentOfCast =
            if (totalCast == 0) 0 else kotlin.math.round((approvedCount * 100.0) / totalCast).toInt()

        var nextStatus = currentStatus
        var finalDecision = existingFinalDecision

        if (currentStatus == ProposalStatus.PENDING) {
            if (nowMillis > votingDeadlineMillis) {
                nextStatus = ProposalStatus.EXPIRED
                finalDecision = ProposalStatus.EXPIRED.name
            } else if (totalEligible > 0) {
                when (ruleMode) {
                    ApprovalRuleMode.UNANIMOUS -> {
                        when {
                            rejectedCount > 0 -> {
                                nextStatus = ProposalStatus.REJECTED
                                finalDecision = ProposalStatus.REJECTED.name
                            }
                            approvedCount == totalEligible && approvedCount >= effectiveQuorum -> {
                                nextStatus = ProposalStatus.APPROVED
                                finalDecision = ProposalStatus.APPROVED.name
                            }
                            else -> {
                                nextStatus = ProposalStatus.PENDING
                                finalDecision = null
                            }
                        }
                    }
                    ApprovalRuleMode.MAJORITY,
                    ApprovalRuleMode.MIN_PERCENTAGE -> {
                        when {
                            approvedCount >= requiredApprovals && totalCast >= effectiveQuorum -> {
                                nextStatus = ProposalStatus.APPROVED
                                finalDecision = ProposalStatus.APPROVED.name
                            }
                            approvedCount + pendingCount < requiredApprovals ||
                                (pendingCount == 0 && (totalCast < effectiveQuorum || approvedCount < requiredApprovals)) -> {
                                nextStatus = ProposalStatus.REJECTED
                                finalDecision = ProposalStatus.REJECTED.name
                            }
                            else -> {
                                nextStatus = ProposalStatus.PENDING
                                finalDecision = null
                            }
                        }
                    }
                }
            }
        }

        val remainingMillis = votingDeadlineMillis - nowMillis
        val isNearDeadline = nextStatus == ProposalStatus.PENDING &&
            remainingMillis in 1..NEAR_DEADLINE_WINDOW_MILLIS

        return ProposalTallySummary(
            totalEligible = totalEligible,
            effectiveQuorum = effectiveQuorum,
            requiredApprovals = requiredApprovals,
            approvedCount = approvedCount,
            rejectedCount = rejectedCount,
            pendingCount = pendingCount,
            totalCast = totalCast,
            approvalPercentOfEligible = approvalPercentOfEligible,
            approvalPercentOfCast = approvalPercentOfCast,
            nextStatus = nextStatus,
            finalDecision = finalDecision,
            isNearDeadline = isNearDeadline
        )
    }

    fun buildExecutionDescriptor(proposalId: String, category: DecisionCategory): DecisionExecutionDescriptor {
        val idempotencyKey = "proposal_exec_$proposalId"
        return when (category) {
            DecisionCategory.TAKE_LOAN -> DecisionExecutionDescriptor(
                targetCollection = "loans",
                loanDirection = LoanDirection.BORROWED,
                idempotencyKey = idempotencyKey
            )
            DecisionCategory.GIVE_LOAN -> DecisionExecutionDescriptor(
                targetCollection = "loans",
                loanDirection = LoanDirection.LENT,
                idempotencyKey = idempotencyKey
            )
            DecisionCategory.MAKE_INVESTMENT -> DecisionExecutionDescriptor(
                targetCollection = "investments",
                idempotencyKey = idempotencyKey
            )
            DecisionCategory.WITHDRAW_SAVINGS -> DecisionExecutionDescriptor(
                targetCollection = "savings",
                idempotencyKey = idempotencyKey
            )
            DecisionCategory.LARGE_EXPENSE,
            DecisionCategory.LARGE_PURCHASE,
            DecisionCategory.OTHER_CONFIGURABLE -> DecisionExecutionDescriptor(
                targetCollection = "transactions",
                transactionType = TransactionType.EXPENSE,
                idempotencyKey = idempotencyKey
            )
        }
    }

    fun validateProposalInput(
        title: String,
        amountMinor: Long,
        proposedDateMillis: Long,
        votingDeadlineMillis: Long,
        category: DecisionCategory,
        targetSavingsId: String?
    ): String? {
        if (title.trim().length < 2) return "Proposal title must be at least 2 characters."
        if (amountMinor <= 0L) return "Proposal amount must be greater than 0."
        if (votingDeadlineMillis <= proposedDateMillis) return "Voting deadline must be after proposed date."
        if (category == DecisionCategory.WITHDRAW_SAVINGS && targetSavingsId.isNullOrBlank()) {
            return "Select a target family savings goal to withdraw from."
        }
        return null
    }

    fun validateApprovalRules(
        minApprovalPercent: Int,
        minQuorumCount: Int,
        defaultDeadlineHours: Int,
        largeExpenseThresholdMinor: Long
    ): String? {
        if (minApprovalPercent !in 1..100) return "Minimum approval percentage must be between 1% and 100%."
        if (minQuorumCount !in 1..20) return "Minimum voting quorum must be between 1 and 20."
        if (defaultDeadlineHours !in 1..720) return "Default deadline must be between 1 and 720 hours."
        if (largeExpenseThresholdMinor < 0L) return "Large expense threshold cannot be negative."
        return null
    }

    fun canCreateProposal(isAdmin: Boolean, isSuspended: Boolean, canCreateRecords: Boolean): Boolean {
        if (isSuspended) return false
        return isAdmin || canCreateRecords
    }
}
