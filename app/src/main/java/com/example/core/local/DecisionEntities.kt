package com.example.core.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.DecisionCategory
import com.example.core.model.EligibleVoterRule
import com.example.core.model.ProposalStatus
import com.example.core.model.SyncState

@Entity(tableName = "approval_settings")
data class LocalApprovalSettingsEntity(
    @PrimaryKey val familyId: String,
    val ruleMode: String = ApprovalRuleMode.MAJORITY.name,
    val minApprovalPercent: Int = 60,
    val minQuorumCount: Int = 1,
    val defaultDeadlineHours: Int = 72,
    val allowCreatorVote: Boolean = true,
    val allowVoteChangeBeforeDeadline: Boolean = true,
    val eligibleVoterRule: String = EligibleVoterRule.ALL_ACTIVE_MEMBERS.name,
    val specificEligibleUidsCsv: String = "",
    val requiredCategoriesCsv: String = DecisionCategory.entries.joinToString(",") { it.name },
    val largeExpenseThresholdMinor: Long = 500_000L,
    val updatedByUid: String = "",
    val syncStatus: String = SyncState.SYNCED.name,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun specificEligibleUidList(): List<String> =
        specificEligibleUidsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun requiredCategorySet(): Set<DecisionCategory> =
        DecisionCategory.parseCsv(requiredCategoriesCsv)
}

@Entity(
    tableName = "proposals",
    indices = [
        Index(value = ["familyId", "status", "votingDeadlineMillis"]),
        Index(value = ["familyId", "updatedAtMillis"])
    ]
)
data class LocalProposalEntity(
    @PrimaryKey val proposalId: String,
    val familyId: String,
    val title: String,
    val category: String,
    val description: String = "",
    val reason: String = "",
    val amountMinor: Long,
    val currency: String = "BDT",
    val relatedParty: String = "",
    val supportingDocName: String? = null,
    val supportingDocUri: String? = null,
    val targetSavingsId: String? = null,
    val proposedDateMillis: Long,
    val votingDeadlineMillis: Long,
    val proposerUid: String,
    val proposerName: String,
    val status: String = ProposalStatus.PENDING.name,
    val ruleMode: String = ApprovalRuleMode.MAJORITY.name,
    val minApprovalPercent: Int = 60,
    val minQuorumRequired: Int = 1,
    val allowCreatorVote: Boolean = true,
    val allowVoteChangeBeforeDeadline: Boolean = true,
    val eligibleVoterRule: String = EligibleVoterRule.ALL_ACTIVE_MEMBERS.name,
    val eligibleVoterUidsCsv: String = "",
    val approvedCount: Int = 0,
    val rejectedCount: Int = 0,
    val finalDecision: String? = null,
    val cancelReason: String? = null,
    val cancelledByUid: String? = null,
    val executedRecordId: String? = null,
    val executedCollection: String? = null,
    val executedByUid: String? = null,
    val executedAtMillis: Long? = null,
    val decidedAtMillis: Long? = null,
    val syncStatus: String = SyncState.SYNCED.name,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun eligibleVoterUidList(): List<String> =
        eligibleVoterUidsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(
    tableName = "proposal_votes",
    primaryKeys = ["proposalId", "voterUid"],
    indices = [
        Index(value = ["familyId", "proposalId"])
    ]
)
data class LocalProposalVoteEntity(
    val proposalId: String,
    val voterUid: String,
    val familyId: String,
    val voterName: String,
    val choice: String,
    val comment: String? = null,
    val votedAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "proposal_vote_history",
    indices = [
        Index(value = ["proposalId", "createdAtMillis"]),
        Index(value = ["familyId", "createdAtMillis"])
    ]
)
data class LocalProposalVoteHistoryEntity(
    @PrimaryKey val historyId: String,
    val proposalId: String,
    val familyId: String,
    val actorUid: String,
    val actorName: String,
    val actionType: String,
    val previousChoice: String? = null,
    val newChoice: String? = null,
    val note: String? = null,
    val createdAtMillis: Long
)
