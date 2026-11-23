package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalProposalVoteHistoryEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.abstraction.ProposalExecutionOutcome
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.ApprovalDecisionEngine
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.AuditAction
import com.example.core.model.DecisionCategory
import com.example.core.model.EligibleVoterRule
import com.example.core.model.FamilyRole
import com.example.core.model.LoanDirection
import com.example.core.model.ProposalStatus
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.TransactionType
import com.example.core.model.VoteAuditActionType
import com.example.core.model.VoteBallotInput
import com.example.core.model.VoteChoice
import com.example.core.model.VoterEligibilityCandidate
import com.example.ui.screens.modules.FamilyDecisionsScreen
import com.example.ui.theme.FamilyFinanceTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Prompt15FamilyDecisionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore

    private val familyId = "FAM_PROMPT15_TEST"
    private val otherFamilyId = "FAM_OTHER_ISO"
    private val adminUid = "uid_admin"
    private val memberUid2 = "uid_member_2"
    private val memberUid3 = "uid_member_3"
    private val memberUid4 = "uid_member_4"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FamilyFinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SqliteRoomDatabaseStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFamilyAndMembers() {
        val now = 1_760_000_000_000L
        db.familyDao().upsertFamily(
            LocalFamilyEntity(
                familyId = familyId,
                name = "Haque Household",
                ownerId = adminUid,
                currencyCode = "BDT",
                memberUidsCsv = listOf(adminUid, memberUid2, memberUid3, memberUid4).joinToString(","),
                adminUidsCsv = adminUid,
                updatedAtMillis = now
            )
        )
        db.familyMemberDao().upsertMembers(
            listOf(
                LocalFamilyMemberEntity(
                    familyId = familyId,
                    userId = adminUid,
                    displayName = "Tareq Haque",
                    role = FamilyRole.ADMIN.name,
                    canCreateRecords = true,
                    joinedAtMillis = now
                ),
                LocalFamilyMemberEntity(
                    familyId = familyId,
                    userId = memberUid2,
                    displayName = "Nadia Haque",
                    role = FamilyRole.MEMBER.name,
                    canCreateRecords = true,
                    joinedAtMillis = now
                ),
                LocalFamilyMemberEntity(
                    familyId = familyId,
                    userId = memberUid3,
                    displayName = "Zayed Haque",
                    role = FamilyRole.MEMBER.name,
                    canCreateRecords = false,
                    joinedAtMillis = now
                ),
                LocalFamilyMemberEntity(
                    familyId = familyId,
                    userId = memberUid4,
                    displayName = "Samira Haque",
                    role = FamilyRole.MEMBER.name,
                    canCreateRecords = true,
                    joinedAtMillis = now
                )
            )
        )
    }

    private fun buildProposal(
        proposalId: String,
        category: DecisionCategory = DecisionCategory.LARGE_EXPENSE,
        amountMinor: Long = 1_500_000L, // 15,000.00 BDT
        status: ProposalStatus = ProposalStatus.PENDING,
        ruleMode: ApprovalRuleMode = ApprovalRuleMode.MAJORITY,
        minApprovalPercent: Int = 60,
        minQuorumRequired: Int = 1,
        allowCreatorVote: Boolean = true,
        allowVoteChangeBeforeDeadline: Boolean = true,
        eligibleUids: List<String> = listOf(adminUid, memberUid2, memberUid3, memberUid4),
        targetSavingsId: String? = null,
        deadlineMillis: Long = System.currentTimeMillis() + 72L * 3600_000L,
        famId: String = familyId
    ): LocalProposalEntity {
        val now = System.currentTimeMillis()
        return LocalProposalEntity(
            proposalId = proposalId,
            familyId = famId,
            title = "Proposal $proposalId",
            category = category.name,
            description = "Family decision test description",
            reason = "Planned budget allocation",
            amountMinor = amountMinor,
            currency = "BDT",
            relatedParty = "Dhaka Solar Ltd",
            targetSavingsId = targetSavingsId,
            proposedDateMillis = now - 60_000L,
            votingDeadlineMillis = deadlineMillis,
            proposerUid = adminUid,
            proposerName = "Tareq Haque",
            status = status.name,
            ruleMode = ruleMode.name,
            minApprovalPercent = minApprovalPercent,
            minQuorumRequired = minQuorumRequired,
            allowCreatorVote = allowCreatorVote,
            allowVoteChangeBeforeDeadline = allowVoteChangeBeforeDeadline,
            eligibleVoterRule = EligibleVoterRule.ALL_ACTIVE_MEMBERS.name,
            eligibleVoterUidsCsv = eligibleUids.joinToString(","),
            approvedCount = 0,
            rejectedCount = 0,
            createdAtMillis = now,
            updatedAtMillis = now
        )
    }

    @Test
    fun draftAndPendingProposalCreation_neverAltersFinancialBalances() = runTest {
        seedFamilyAndMembers()

        val draft = buildProposal("prop_draft_1", status = ProposalStatus.DRAFT)
        val pending = buildProposal("prop_pending_1", status = ProposalStatus.PENDING)
        store.saveProposal(draft, null)
        store.saveProposal(pending, null)

        assertTrue(db.transactionDao().observeByFamily(familyId).first().isEmpty())
        assertTrue(db.loanDao().observeByFamily(familyId).first().isEmpty())
        assertTrue(db.savingsDao().observeByFamily(familyId).first().isEmpty())
        assertTrue(db.investmentDao().observeByFamily(familyId).first().isEmpty())
        assertTrue(db.financialEventDao().observeByFamily(familyId).first().isEmpty())
    }

    @Test
    fun majorityApprovalRule_requiresStrictMajorityOver50Percent() {
        val eligible = listOf(adminUid, memberUid2, memberUid3, memberUid4)
        val now = 1_760_000_000_000L
        val deadline = now + 48L * 3600_000L

        // 2 of 4 approve = 50% -> still PENDING (majority needs 3 of 4)
        val twoApprove = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligible,
            votes = listOf(
                VoteBallotInput(adminUid, VoteChoice.APPROVE),
                VoteBallotInput(memberUid2, VoteChoice.APPROVE)
            ),
            ruleMode = ApprovalRuleMode.MAJORITY,
            minApprovalPercent = 51,
            minQuorumRequired = 1,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(3, twoApprove.requiredApprovals)
        assertEquals(ProposalStatus.PENDING, twoApprove.nextStatus)

        // 3 of 4 approve -> APPROVED
        val threeApprove = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligible,
            votes = listOf(
                VoteBallotInput(adminUid, VoteChoice.APPROVE),
                VoteBallotInput(memberUid2, VoteChoice.APPROVE),
                VoteBallotInput(memberUid3, VoteChoice.APPROVE)
            ),
            ruleMode = ApprovalRuleMode.MAJORITY,
            minApprovalPercent = 51,
            minQuorumRequired = 1,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(ProposalStatus.APPROVED, threeApprove.nextStatus)
        assertEquals(75, threeApprove.approvalPercentOfEligible)
    }

    @Test
    fun unanimousApprovalRule_singleRejectionImmediatelyRejects() {
        val eligible = listOf(adminUid, memberUid2, memberUid3)
        val now = 1_760_000_000_000L
        val deadline = now + 48L * 3600_000L

        val rejected = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligible,
            votes = listOf(
                VoteBallotInput(adminUid, VoteChoice.APPROVE),
                VoteBallotInput(memberUid2, VoteChoice.REJECT)
            ),
            ruleMode = ApprovalRuleMode.UNANIMOUS,
            minApprovalPercent = 100,
            minQuorumRequired = 1,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(ProposalStatus.REJECTED, rejected.nextStatus)
        assertEquals("REJECTED", rejected.finalDecision)
    }

    @Test
    fun minPercentageAndQuorumRules_enforcedAccurately() {
        val eligible = listOf(adminUid, memberUid2, memberUid3, memberUid4)
        val now = 1_760_000_000_000L
        val deadline = now + 48L * 3600_000L

        // 60% of 4 = ceil(2.4) = 3; quorum = 3. 2 approves + 1 reject -> max possible approves is 3, stays PENDING
        val inProgress = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligible,
            votes = listOf(
                VoteBallotInput(adminUid, VoteChoice.APPROVE),
                VoteBallotInput(memberUid2, VoteChoice.APPROVE),
                VoteBallotInput(memberUid3, VoteChoice.REJECT)
            ),
            ruleMode = ApprovalRuleMode.MIN_PERCENTAGE,
            minApprovalPercent = 60,
            minQuorumRequired = 3,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(3, inProgress.requiredApprovals)
        assertEquals(ProposalStatus.PENDING, inProgress.nextStatus)

        // 2 approves + 2 rejects -> max possible approves is 2 < 3 -> REJECTED
        val impossible = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligible,
            votes = listOf(
                VoteBallotInput(adminUid, VoteChoice.APPROVE),
                VoteBallotInput(memberUid2, VoteChoice.APPROVE),
                VoteBallotInput(memberUid3, VoteChoice.REJECT),
                VoteBallotInput(memberUid4, VoteChoice.REJECT)
            ),
            ruleMode = ApprovalRuleMode.MIN_PERCENTAGE,
            minApprovalPercent = 60,
            minQuorumRequired = 3,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(ProposalStatus.REJECTED, impossible.nextStatus)
    }

    @Test
    fun expiredProposal_closesAtDeadlineAndBlocksExecution() = runTest {
        seedFamilyAndMembers()
        val now = 1_760_000_000_000L
        val deadline = now - 5_000L
        val tally = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = listOf(adminUid, memberUid2, memberUid3),
            votes = listOf(VoteBallotInput(adminUid, VoteChoice.APPROVE)),
            ruleMode = ApprovalRuleMode.MAJORITY,
            minApprovalPercent = 60,
            minQuorumRequired = 1,
            votingDeadlineMillis = deadline,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )
        assertEquals(ProposalStatus.EXPIRED, tally.nextStatus)

        val expiredEntity = buildProposal(
            proposalId = "prop_expired_1",
            status = ProposalStatus.EXPIRED,
            deadlineMillis = deadline
        )
        store.saveProposal(expiredEntity, null)
        val execOutcome = store.executeApprovedProposalAtomicLocal(
            proposalId = "prop_expired_1",
            actorUid = adminUid,
            actorName = "Tareq Haque",
            deviceId = "test_dev",
            nowMillis = now,
            enqueueOutbox = true
        )
        assertTrue(execOutcome is ProposalExecutionOutcome.Failure)
        assertTrue(db.transactionDao().observeByFamily(familyId).first().isEmpty())
    }

    @Test
    fun duplicateVotes_deduplicatedAndVoteChangeAuditedInHistory() = runTest {
        seedFamilyAndMembers()
        val now = System.currentTimeMillis()
        val proposal = buildProposal(
            proposalId = "prop_vote_dedup",
            allowVoteChangeBeforeDeadline = true
        )
        store.saveProposal(proposal, null)

        // Same voter votes APPROVE then changes to REJECT -> only 1 ballot row exists in proposal_votes, 2 entries in history
        val vote1 = LocalProposalVoteEntity(
            proposalId = proposal.proposalId,
            voterUid = memberUid2,
            familyId = familyId,
            voterName = "Nadia Haque",
            choice = VoteChoice.APPROVE.name,
            votedAtMillis = now,
            updatedAtMillis = now
        )
        store.saveVoteAndUpdateProposalAtomic(
            vote = vote1,
            historyEntry = LocalProposalVoteHistoryEntity(
                historyId = "vh_1",
                proposalId = proposal.proposalId,
                familyId = familyId,
                actorUid = memberUid2,
                actorName = "Nadia Haque",
                actionType = VoteAuditActionType.VOTE_CAST.name,
                newChoice = VoteChoice.APPROVE.name,
                createdAtMillis = now
            ),
            updatedProposal = proposal.copy(approvedCount = 1, rejectedCount = 0)
        )

        val voteChanged = vote1.copy(choice = VoteChoice.REJECT.name, updatedAtMillis = now + 1000L)
        store.saveVoteAndUpdateProposalAtomic(
            vote = voteChanged,
            historyEntry = LocalProposalVoteHistoryEntity(
                historyId = "vh_2",
                proposalId = proposal.proposalId,
                familyId = familyId,
                actorUid = memberUid2,
                actorName = "Nadia Haque",
                actionType = VoteAuditActionType.VOTE_CHANGED.name,
                previousChoice = VoteChoice.APPROVE.name,
                newChoice = VoteChoice.REJECT.name,
                createdAtMillis = now + 1000L
            ),
            updatedProposal = proposal.copy(approvedCount = 0, rejectedCount = 1)
        )

        val storedVotes = store.getVotesForProposal(proposal.proposalId)
        assertEquals("Primary key (proposalId, voterUid) prevents duplicate ballots", 1, storedVotes.size)
        assertEquals(VoteChoice.REJECT.name, storedVotes.single().choice)

        val history = db.proposalVoteHistoryDao().observeByProposal(proposal.proposalId).first()
        assertEquals(2, history.size)
        assertEquals(VoteAuditActionType.VOTE_CHANGED.name, history.first().actionType)
    }

    @Test
    fun creatorVotingExclusion_excludesProposerWhenDisabled() {
        val candidates = listOf(
            VoterEligibilityCandidate(adminUid, FamilyRole.ADMIN.name, canCreateRecords = true),
            VoterEligibilityCandidate(memberUid2, FamilyRole.MEMBER.name, canCreateRecords = true),
            VoterEligibilityCandidate(memberUid3, FamilyRole.MEMBER.name, canCreateRecords = false, isSuspended = false),
            VoterEligibilityCandidate(memberUid4, FamilyRole.MEMBER.name, canCreateRecords = true, isSuspended = true)
        )

        val eligibleNoCreator = ApprovalDecisionEngine.resolveEligibleVoters(
            candidates = candidates,
            proposerUid = adminUid,
            eligibleVoterRule = EligibleVoterRule.ALL_ACTIVE_MEMBERS,
            specificEligibleUids = emptyList(),
            allowCreatorVote = false
        )
        assertFalse(adminUid in eligibleNoCreator)
        assertFalse(memberUid4 in eligibleNoCreator) // suspended excluded
        assertEquals(listOf(memberUid2, memberUid3), eligibleNoCreator)

        val recordsOnly = ApprovalDecisionEngine.resolveEligibleVoters(
            candidates = candidates,
            proposerUid = adminUid,
            eligibleVoterRule = EligibleVoterRule.RECORDS_AUTHORIZED_MEMBERS,
            specificEligibleUids = emptyList(),
            allowCreatorVote = true
        )
        assertTrue(adminUid in recordsOnly)
        assertTrue(memberUid2 in recordsOnly)
        assertFalse(memberUid3 in recordsOnly) // canCreateRecords == false
    }

    @Test
    fun nonApprovedProposal_cannotExecute_andRejectedCannotBeForceApproved() = runTest {
        seedFamilyAndMembers()
        val now = System.currentTimeMillis()
        val rejected = buildProposal("prop_rejected_1", status = ProposalStatus.REJECTED)
        store.saveProposal(rejected, null)

        val res = store.executeApprovedProposalAtomicLocal(
            proposalId = "prop_rejected_1",
            actorUid = adminUid,
            actorName = "Tareq Haque",
            deviceId = "dev_1",
            nowMillis = now,
            enqueueOutbox = false
        )
        assertTrue(res is ProposalExecutionOutcome.Failure)
        assertEquals(ProposalStatus.REJECTED.name, store.getProposalById("prop_rejected_1")?.status)
    }

    @Test
    fun executeApprovedProposals_acrossAllCategories_executesOnceAndCreatesLinkedRecords() = runTest {
        seedFamilyAndMembers()
        val now = System.currentTimeMillis()

        // Seed savings goal for WITHDRAW_SAVINGS
        val savingsId = "sav_emergency_1"
        db.savingsDao().upsert(
            LocalSavingsEntity(
                recordId = savingsId,
                familyId = familyId,
                createdByUserId = adminUid,
                amount = 50_000.0,
                amountMinor = 5_000_000L, // 50,000.00 BDT
                targetAmount = 100_000.0,
                targetAmountMinor = 10_000_000L,
                currency = "BDT",
                category = "Emergency Fund",
                description = "Family Emergency Reserve",
                goalName = "Emergency Fund",
                recordStatus = RecordStatus.ACTIVE.name,
                idempotencyKey = "idem_sav_seed",
                syncStatus = SyncState.SYNCED.name,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )

        // 1. TAKE_LOAN -> BORROWED loan + DISBURSEMENT event
        store.saveProposal(
            buildProposal("p_take_loan", category = DecisionCategory.TAKE_LOAN, amountMinor = 2_000_000L, status = ProposalStatus.APPROVED),
            null
        )
        val takeOutcome = store.executeApprovedProposalAtomicLocal("p_take_loan", adminUid, "Tareq Haque", "dev", now, true)
        assertTrue(takeOutcome is ProposalExecutionOutcome.Executed)
        val takeLoan = db.loanDao().observeByFamily(familyId).first().first { it.idempotencyKey == "proposal_exec_p_take_loan" }
        assertEquals(LoanDirection.BORROWED.name, takeLoan.loanDirection)
        assertEquals(2_000_000L, takeLoan.effectiveAmountMinor())

        // Verify idempotency: second execution fails
        val secondAttempt = store.executeApprovedProposalAtomicLocal("p_take_loan", adminUid, "Tareq Haque", "dev", now + 1000L, true)
        assertTrue(secondAttempt is ProposalExecutionOutcome.Failure)

        // 2. GIVE_LOAN -> LENT loan
        store.saveProposal(
            buildProposal("p_give_loan", category = DecisionCategory.GIVE_LOAN, amountMinor = 500_000L, status = ProposalStatus.APPROVED),
            null
        )
        val giveOutcome = store.executeApprovedProposalAtomicLocal("p_give_loan", adminUid, "Tareq Haque", "dev", now, true)
        assertTrue(giveOutcome is ProposalExecutionOutcome.Executed)
        val giveLoan = db.loanDao().observeByFamily(familyId).first().first { it.idempotencyKey == "proposal_exec_p_give_loan" }
        assertEquals(LoanDirection.LENT.name, giveLoan.loanDirection)

        // 3. MAKE_INVESTMENT -> investment + INITIAL_INVESTMENT event
        store.saveProposal(
            buildProposal("p_invest", category = DecisionCategory.MAKE_INVESTMENT, amountMinor = 3_000_000L, status = ProposalStatus.APPROVED),
            null
        )
        val invOutcome = store.executeApprovedProposalAtomicLocal("p_invest", adminUid, "Tareq Haque", "dev", now, true)
        assertTrue(invOutcome is ProposalExecutionOutcome.Executed)
        val inv = db.investmentDao().observeByFamily(familyId).first().single()
        assertEquals(3_000_000L, inv.effectivePrincipalMinor())

        // 4. WITHDRAW_SAVINGS -> deducts balance + records WITHDRAWAL event; excessive amount fails
        store.saveProposal(
            buildProposal("p_sav_ok", category = DecisionCategory.WITHDRAW_SAVINGS, amountMinor = 1_200_000L, targetSavingsId = savingsId, status = ProposalStatus.APPROVED),
            null
        )
        val savOutcome = store.executeApprovedProposalAtomicLocal("p_sav_ok", adminUid, "Tareq Haque", "dev", now, true)
        assertTrue(savOutcome is ProposalExecutionOutcome.Executed)
        val updatedSav = store.getSavingsById(savingsId)
        assertEquals(3_800_000L, updatedSav?.effectiveCurrentMinor())

        store.saveProposal(
            buildProposal("p_sav_too_high", category = DecisionCategory.WITHDRAW_SAVINGS, amountMinor = 9_900_000L, targetSavingsId = savingsId, status = ProposalStatus.APPROVED),
            null
        )
        val savFail = store.executeApprovedProposalAtomicLocal("p_sav_too_high", adminUid, "Tareq Haque", "dev", now, true)
        assertTrue(savFail is ProposalExecutionOutcome.Failure)
        assertEquals(3_800_000L, store.getSavingsById(savingsId)?.effectiveCurrentMinor())

        // 5. LARGE_EXPENSE, LARGE_PURCHASE, OTHER_CONFIGURABLE -> EXPENSE transactions
        listOf(
            "p_exp" to DecisionCategory.LARGE_EXPENSE,
            "p_pur" to DecisionCategory.LARGE_PURCHASE,
            "p_oth" to DecisionCategory.OTHER_CONFIGURABLE
        ).forEach { (id, cat) ->
            store.saveProposal(buildProposal(id, category = cat, amountMinor = 400_000L, status = ProposalStatus.APPROVED), null)
            val txOut = store.executeApprovedProposalAtomicLocal(id, adminUid, "Tareq Haque", "dev", now, true)
            assertTrue(txOut is ProposalExecutionOutcome.Executed)
        }
        val txs = db.transactionDao().observeByFamily(familyId).first()
        assertEquals(3, txs.size)
        assertTrue(txs.all { it.transactionType == TransactionType.EXPENSE.name })

        // Verify Audit Logs & Outbox entries were written
        val audits = db.auditLogDao().observeRecentByFamily(familyId, 30).first()
        assertTrue(audits.size >= 7)
    }

    @Test
    fun cancelProposalAtomic_marksCancelledAndWritesAudit() = runTest {
        seedFamilyAndMembers()
        val now = System.currentTimeMillis()
        val pending = buildProposal("prop_cancel_1", status = ProposalStatus.PENDING)
        store.saveProposal(pending, null)

        store.cancelProposalAtomicLocal(
            updatedProposal = pending.copy(
                status = ProposalStatus.CANCELLED.name,
                finalDecision = ProposalStatus.CANCELLED.name,
                cancelReason = "Vendor quote expired before purchase",
                cancelledByUid = adminUid,
                updatedAtMillis = now
            ),
            historyEntry = LocalProposalVoteHistoryEntity(
                historyId = "vh_cancel_1",
                proposalId = pending.proposalId,
                familyId = familyId,
                actorUid = adminUid,
                actorName = "Tareq Haque",
                actionType = VoteAuditActionType.PROPOSAL_CANCELLED.name,
                note = "Vendor quote expired before purchase",
                createdAtMillis = now
            ),
            auditEntry = LocalAuditLogEntity(
                auditId = "aud_cancel_1",
                familyId = familyId,
                actorUserId = adminUid,
                entityCollection = "proposals",
                entityId = pending.proposalId,
                action = AuditAction.UPDATE.name,
                summary = "Cancelled proposal",
                deviceId = "dev_1",
                createdAtMillis = now
            )
        )

        val loaded = store.getProposalById("prop_cancel_1")
        assertNotNull(loaded)
        assertEquals(ProposalStatus.CANCELLED.name, loaded?.status)
        assertEquals("Vendor quote expired before purchase", loaded?.cancelReason)
    }

    @Test
    fun familyIsolation_purgeFamilyCache_leavesOtherFamilyIntact() = runTest {
        seedFamilyAndMembers()
        val p1 = buildProposal("p_iso_1", famId = familyId)
        val p2 = buildProposal("p_iso_2", famId = otherFamilyId)
        store.saveProposal(p1, null)
        store.saveProposal(p2, null)
        store.saveApprovalSettings(LocalApprovalSettingsEntity(familyId = familyId))
        store.saveApprovalSettings(LocalApprovalSettingsEntity(familyId = otherFamilyId))

        store.purgeFamilyCache(familyId)

        assertNull(store.getProposalById("p_iso_1"))
        assertNull(store.getApprovalSettings(familyId))
        assertNotNull(store.getProposalById("p_iso_2"))
        assertNotNull(store.getApprovalSettings(otherFamilyId))
    }

    @Test
    fun composeDecisionsScreen_displaysProposalsOpensDetailAndTriggersVote() {
        val now = System.currentTimeMillis()
        val pending = buildProposal("prop_ui_1", status = ProposalStatus.PENDING)
        val settings = LocalApprovalSettingsEntity(familyId = familyId)
        val family = LocalFamilyEntity(
            familyId = familyId,
            name = "Haque Household",
            ownerId = adminUid,
            currencyCode = "BDT",
            memberUidsCsv = "$adminUid,$memberUid2",
            adminUidsCsv = adminUid,
            updatedAtMillis = now
        )
        val members = listOf(
            LocalFamilyMemberEntity(
                familyId = familyId,
                userId = adminUid,
                displayName = "Tareq Haque",
                role = FamilyRole.ADMIN.name,
                canCreateRecords = true,
                joinedAtMillis = now
            ),
            LocalFamilyMemberEntity(
                familyId = familyId,
                userId = memberUid2,
                displayName = "Nadia Haque",
                role = FamilyRole.MEMBER.name,
                canCreateRecords = true,
                joinedAtMillis = now
            )
        )

        var selectedId by mutableStateOf<String?>(null)
        var votedChoice: VoteChoice? = null

        composeRule.setContent {
            FamilyFinanceTheme {
                FamilyDecisionsScreen(
                    currentUserId = adminUid,
                    activeFamily = family,
                    activeMembers = members,
                    approvalSettings = settings,
                    proposals = listOf(pending),
                    allVotes = emptyList(),
                    allHistory = emptyList(),
                    savingsGoals = emptyList(),
                    selectedProposalId = selectedId,
                    isBusy = false,
                    onSelectProposal = { selectedId = it },
                    onSaveApprovalSettings = { _, _, _, _, _, _, _, _, _, _ -> },
                    onSaveOrSubmitProposal = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDeleteDraftProposal = { _, _ -> },
                    onCastOrChangeVote = { _, choice, _ -> votedChoice = choice },
                    onCancelProposal = { _, _ -> },
                    onExecuteApprovedProposal = {},
                    onNavigateToModule = {},
                    onNavigateToFamilyManagement = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("decisions_list").performScrollToNode(hasTestTag("proposal_card_prop_ui_1"))
        composeRule.onNodeWithTag("proposal_card_prop_ui_1").assertIsDisplayed()
        composeRule.onNodeWithText("Proposal prop_ui_1").assertIsDisplayed()
        composeRule.onNodeWithTag("proposal_card_prop_ui_1").performClick()
        composeRule.waitForIdle()
        assertEquals("prop_ui_1", selectedId)

        composeRule.onNodeWithTag("decision_detail_list").performScrollToNode(hasTestTag("vote_approve_button"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vote_approve_button").assertIsDisplayed().performClick()
        assertEquals(VoteChoice.APPROVE, votedChoice)
    }
}
