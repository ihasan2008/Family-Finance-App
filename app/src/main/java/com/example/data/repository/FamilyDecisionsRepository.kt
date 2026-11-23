package com.example.data.repository

import com.example.core.comms.MeetBackendClient
import com.example.core.firebase.AuthFunctionsClient
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.FirestoreService
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalProposalVoteHistoryEntity
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.local.abstraction.ProposalExecutionOutcome
import com.example.core.model.ApprovalDecisionEngine
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.ApprovalSettingsDoc
import com.example.core.model.AuditAction
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.DecisionCategory
import com.example.core.model.EligibleVoterRule
import com.example.core.model.FamilyProposalDoc
import com.example.core.model.MoneyCalculator
import com.example.core.model.NotificationType
import com.example.core.model.ProposalStatus
import com.example.core.model.ProposalTallySummary
import com.example.core.model.ProposalVoteDoc
import com.example.core.model.ProposalVoteHistoryDoc
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.model.VoteAuditActionType
import com.example.core.model.VoteBallotInput
import com.example.core.model.VoteChoice
import com.example.core.model.VoterEligibilityCandidate
import com.example.core.remote.OperationType
import com.example.core.remote.handleFirestoreError
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import java.util.Date

class FamilyDecisionsRepository(
    private val firestoreService: FirestoreService,
    private val localStore: LocalDatabaseStore,
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    private val functionsClient: AuthFunctionsClient = AuthFunctionsClient(auth),
    private val meetBackend: MeetBackendClient = MeetBackendClient(auth)
) {
    private val db: FirebaseFirestore get() = firestoreService.db

    private fun requireUid(): String =
        auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required.")

    fun observeApprovalSettings(familyId: String): Flow<LocalApprovalSettingsEntity?> =
        localStore.observeApprovalSettings(familyId)

    fun observeProposals(familyId: String): Flow<List<LocalProposalEntity>> =
        localStore.observeProposals(familyId)

    fun observeVotesForFamily(familyId: String): Flow<List<LocalProposalVoteEntity>> =
        localStore.observeVotesForFamily(familyId)

    fun observeProposalVotes(proposalId: String): Flow<List<LocalProposalVoteEntity>> =
        localStore.observeProposalVotes(proposalId)

    fun observeProposalHistory(proposalId: String): Flow<List<LocalProposalVoteHistoryEntity>> =
        localStore.observeProposalHistory(proposalId)

    fun observeAllHistoryForFamily(familyId: String): Flow<List<LocalProposalVoteHistoryEntity>> =
        localStore.observeAllDecisionHistoryForFamily(familyId)

    fun syncRemoteApprovalSettings(familyId: String): Flow<LocalApprovalSettingsEntity?> = flow {
        val docFlow = firestoreService.observeDocument(
            docRef = db.collection("families").document(familyId)
                .collection("approval_settings").document("default"),
            clazz = ApprovalSettingsDoc::class.java,
            pathForLogs = "families/$familyId/approval_settings/default",
            activeUidProvider = { auth.currentUser?.uid }
        ).onEach { doc ->
            if (doc != null) {
                localStore.reconcileRemoteApprovalSettings(
                    familyId = familyId,
                    remote = LocalApprovalSettingsEntity(
                        familyId = familyId,
                        ruleMode = doc.ruleMode,
                        minApprovalPercent = doc.minApprovalPercent,
                        minQuorumCount = doc.minQuorumCount,
                        defaultDeadlineHours = doc.defaultDeadlineHours,
                        allowCreatorVote = doc.allowCreatorVote,
                        allowVoteChangeBeforeDeadline = doc.allowVoteChangeBeforeDeadline,
                        eligibleVoterRule = doc.eligibleVoterRule,
                        specificEligibleUidsCsv = doc.specificEligibleUids.joinToString(","),
                        requiredCategoriesCsv = doc.requiredCategories.joinToString(","),
                        largeExpenseThresholdMinor = doc.largeExpenseThresholdMinor,
                        updatedByUid = doc.updatedByUid,
                        syncStatus = SyncState.SYNCED.name,
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                )
            }
        }
        docFlow.collect { doc ->
            emit(
                doc?.let {
                    LocalApprovalSettingsEntity(
                        familyId = familyId,
                        ruleMode = it.ruleMode,
                        minApprovalPercent = it.minApprovalPercent,
                        minQuorumCount = it.minQuorumCount,
                        defaultDeadlineHours = it.defaultDeadlineHours,
                        allowCreatorVote = it.allowCreatorVote,
                        allowVoteChangeBeforeDeadline = it.allowVoteChangeBeforeDeadline,
                        eligibleVoterRule = it.eligibleVoterRule,
                        specificEligibleUidsCsv = it.specificEligibleUids.joinToString(","),
                        requiredCategoriesCsv = it.requiredCategories.joinToString(","),
                        largeExpenseThresholdMinor = it.largeExpenseThresholdMinor,
                        updatedByUid = it.updatedByUid,
                        syncStatus = SyncState.SYNCED.name,
                        updatedAtMillis = it.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
            )
        }
    }

    fun syncRemoteProposals(userId: String, familyId: String): Flow<List<FamilyProposalDoc>> = flow {
        val query = db.collection("proposals")
            .whereEqualTo("familyId", familyId)
            .whereArrayContains("memberUids", userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(120)

        emitAll(
            firestoreService.observeQuery(
                query = query,
                clazz = FamilyProposalDoc::class.java,
                pathForLogs = "proposals?familyId=$familyId",
                activeUidProvider = { auth.currentUser?.uid }
            )
                .onEach { docs ->
                    val entities = docs.map { doc ->
                        val pid = doc.proposalId.ifBlank { doc.id }
                        LocalProposalEntity(
                            proposalId = pid,
                            familyId = doc.familyId.ifBlank { familyId },
                            title = doc.title,
                            category = doc.category,
                            description = doc.description,
                            reason = doc.reason,
                            amountMinor = doc.amountMinor,
                            currency = doc.currency.ifBlank { "BDT" },
                            relatedParty = doc.relatedParty,
                            supportingDocName = doc.supportingDocName,
                            supportingDocUri = doc.supportingDocUri,
                            targetSavingsId = doc.targetSavingsId,
                            proposedDateMillis = doc.proposedDate?.toDate()?.time ?: System.currentTimeMillis(),
                            votingDeadlineMillis = doc.votingDeadline?.toDate()?.time ?: System.currentTimeMillis(),
                            proposerUid = doc.proposerUid,
                            proposerName = doc.proposerName,
                            status = doc.status,
                            ruleMode = doc.ruleMode,
                            minApprovalPercent = doc.minApprovalPercent,
                            minQuorumRequired = doc.minQuorumRequired,
                            allowCreatorVote = doc.allowCreatorVote,
                            allowVoteChangeBeforeDeadline = doc.allowVoteChangeBeforeDeadline,
                            eligibleVoterRule = doc.eligibleVoterRule,
                            eligibleVoterUidsCsv = doc.eligibleVoterUids.joinToString(","),
                            approvedCount = doc.approvedCount,
                            rejectedCount = doc.rejectedCount,
                            finalDecision = doc.finalDecision,
                            cancelReason = doc.cancelReason,
                            cancelledByUid = doc.cancelledByUid,
                            executedRecordId = doc.executedRecordId,
                            executedCollection = doc.executedCollection,
                            executedByUid = doc.executedByUid,
                            executedAtMillis = doc.executedAt?.toDate()?.time,
                            decidedAtMillis = doc.decidedAt?.toDate()?.time,
                            syncStatus = SyncState.SYNCED.name,
                            createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                            updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                        )
                    }
                    localStore.reconcileRemoteProposals(familyId, entities)
                }
        )
    }

    fun syncRemoteVotesAndHistoryForProposal(proposalId: String): Flow<Unit> = flow {
        val votesQuery = db.collection("proposals").document(proposalId)
            .collection("votes")
            .limit(40)
        val histQuery = db.collection("proposals").document(proposalId)
            .collection("vote_history")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)

        runCatching {
            val vSnap = votesQuery.get().await()
            val votes = vSnap.toObjects(ProposalVoteDoc::class.java).map { v ->
                LocalProposalVoteEntity(
                    proposalId = proposalId,
                    voterUid = v.voterUid.ifBlank { v.id },
                    familyId = v.familyId,
                    voterName = v.voterName,
                    choice = v.choice,
                    comment = v.comment,
                    votedAtMillis = v.votedAt?.toDate()?.time ?: System.currentTimeMillis(),
                    updatedAtMillis = v.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                )
            }
            localStore.reconcileRemoteProposalVotes(proposalId, votes)

            val hSnap = histQuery.get().await()
            val history = hSnap.toObjects(ProposalVoteHistoryDoc::class.java).map { h ->
                LocalProposalVoteHistoryEntity(
                    historyId = h.historyId.ifBlank { h.id },
                    proposalId = proposalId,
                    familyId = h.familyId,
                    actorUid = h.actorUid,
                    actorName = h.actorName,
                    actionType = h.actionType,
                    previousChoice = h.previousChoice,
                    newChoice = h.newChoice,
                    note = h.note,
                    createdAtMillis = h.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                )
            }
            localStore.reconcileRemoteProposalHistory(proposalId, history)
        }

        emitAll(
            firestoreService.observeQuery(
                query = votesQuery,
                clazz = ProposalVoteDoc::class.java,
                pathForLogs = "proposals/$proposalId/votes",
                activeUidProvider = { auth.currentUser?.uid }
            )
                .onEach { list ->
                    val entities = list.map { v ->
                        LocalProposalVoteEntity(
                            proposalId = proposalId,
                            voterUid = v.voterUid.ifBlank { v.id },
                            familyId = v.familyId,
                            voterName = v.voterName,
                            choice = v.choice,
                            comment = v.comment,
                            votedAtMillis = v.votedAt?.toDate()?.time ?: System.currentTimeMillis(),
                            updatedAtMillis = v.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                        )
                    }
                    localStore.reconcileRemoteProposalVotes(proposalId, entities)
                }.let { vFlow ->
                    flow {
                        vFlow.collect {
                            runCatching {
                                val hSnap = histQuery.get().await()
                                val history = hSnap.toObjects(ProposalVoteHistoryDoc::class.java).map { h ->
                                    LocalProposalVoteHistoryEntity(
                                        historyId = h.historyId.ifBlank { h.id },
                                        proposalId = proposalId,
                                        familyId = h.familyId,
                                        actorUid = h.actorUid,
                                        actorName = h.actorName,
                                        actionType = h.actionType,
                                        previousChoice = h.previousChoice,
                                        newChoice = h.newChoice,
                                        note = h.note,
                                        createdAtMillis = h.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                    )
                                }
                                localStore.reconcileRemoteProposalHistory(proposalId, history)
                            }
                            emit(Unit)
                        }
                    }
                }
        )
    }

    suspend fun saveApprovalSettings(
        family: LocalFamilyEntity,
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
    ): Result<LocalApprovalSettingsEntity> {
        val callerUid = requireUid()
        if (!family.isAdmin(callerUid)) {
            return Result.failure(SecurityException("Only Family Admins can configure approval rules."))
        }
        val err = ApprovalDecisionEngine.validateApprovalRules(
            minApprovalPercent = minApprovalPercent,
            minQuorumCount = minQuorumCount,
            defaultDeadlineHours = defaultDeadlineHours,
            largeExpenseThresholdMinor = largeExpenseThresholdMinor
        )
        if (err != null) return Result.failure(IllegalArgumentException(err))

        val now = System.currentTimeMillis()
        val cleanedSpecific = specificEligibleUids.distinct().filter { it in family.memberUids }.take(20)
        val cats = (requiredCategories.ifEmpty { DecisionCategory.entries.toSet() }).map { it.name }

        val entity = LocalApprovalSettingsEntity(
            familyId = family.familyId,
            ruleMode = ruleMode.name,
            minApprovalPercent = minApprovalPercent,
            minQuorumCount = minQuorumCount,
            defaultDeadlineHours = defaultDeadlineHours,
            allowCreatorVote = allowCreatorVote,
            allowVoteChangeBeforeDeadline = allowVoteChangeBeforeDeadline,
            eligibleVoterRule = eligibleVoterRule.name,
            specificEligibleUidsCsv = cleanedSpecific.joinToString(","),
            requiredCategoriesCsv = cats.joinToString(","),
            largeExpenseThresholdMinor = largeExpenseThresholdMinor,
            updatedByUid = callerUid,
            syncStatus = SyncState.SYNCED.name,
            updatedAtMillis = now
        )

        if (functionsClient.isConfigured) {
            val remoteRes = functionsClient.configureApprovalRules(
                familyId = family.familyId,
                ruleMode = ruleMode.name,
                minApprovalPercent = minApprovalPercent,
                minQuorumCount = minQuorumCount,
                defaultDeadlineHours = defaultDeadlineHours,
                allowCreatorVote = allowCreatorVote,
                allowVoteChangeBeforeDeadline = allowVoteChangeBeforeDeadline,
                eligibleVoterRule = eligibleVoterRule.name,
                specificEligibleUids = cleanedSpecific,
                requiredCategories = cats,
                largeExpenseThresholdMinor = largeExpenseThresholdMinor
            )
            if (remoteRes.isSuccess) {
                localStore.saveApprovalSettings(entity)
                return Result.success(entity)
            }
        }

        return try {
            db.collection("families").document(family.familyId)
                .collection("approval_settings").document("default")
                .set(
                    mapOf(
                        "configId" to "default",
                        "familyId" to family.familyId,
                        "ruleMode" to ruleMode.name,
                        "minApprovalPercent" to minApprovalPercent,
                        "minQuorumCount" to minQuorumCount,
                        "defaultDeadlineHours" to defaultDeadlineHours,
                        "allowCreatorVote" to allowCreatorVote,
                        "allowVoteChangeBeforeDeadline" to allowVoteChangeBeforeDeadline,
                        "eligibleVoterRule" to eligibleVoterRule.name,
                        "specificEligibleUids" to cleanedSpecific,
                        "requiredCategories" to cats,
                        "largeExpenseThresholdMinor" to largeExpenseThresholdMinor,
                        "updatedByUid" to callerUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()

            localStore.saveApprovalSettings(entity)
            localStore.appendAuditLog(
                LocalAuditLogEntity(
                    auditId = DatabaseIdentityHelper.generateAuditId(),
                    familyId = family.familyId,
                    actorUserId = callerUid,
                    entityCollection = "approval_settings",
                    entityId = "default",
                    action = AuditAction.UPDATE.name,
                    summary = "Updated approval rules (${ruleMode.label}, quorum $minQuorumCount)",
                    deviceId = "",
                    syncStatus = SyncState.SYNCED.name,
                    createdAtMillis = now
                )
            )
            Result.success(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            localStore.saveApprovalSettings(entity.copy(syncStatus = SyncState.PENDING_UPDATE.name))
            Result.success(entity)
        }
    }

    suspend fun saveOrSubmitProposal(
        family: LocalFamilyEntity,
        members: List<LocalFamilyMemberEntity>,
        settings: LocalApprovalSettingsEntity?,
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
        proposerName: String
    ): Result<LocalProposalEntity> {
        val callerUid = requireUid()
        val callerMember = members.firstOrNull { it.userId == callerUid }
        val isAdmin = family.isAdmin(callerUid)
        val isSuspended = family.isSuspended(callerUid)
        if (!ApprovalDecisionEngine.canCreateProposal(
                isAdmin = isAdmin,
                isSuspended = isSuspended,
                canCreateRecords = callerMember?.canCreateRecords ?: false
            )
        ) {
            return Result.failure(SecurityException("Member is not authorized to create financial proposals."))
        }
        if (existingDraft != null && existingDraft.status != ProposalStatus.DRAFT.name) {
            return Result.failure(IllegalStateException("Submitted proposals cannot be edited directly."))
        }
        val validationError = ApprovalDecisionEngine.validateProposalInput(
            title = title,
            amountMinor = amountMinor,
            proposedDateMillis = proposedDateMillis,
            votingDeadlineMillis = votingDeadlineMillis,
            category = category,
            targetSavingsId = targetSavingsId
        )
        if (validationError != null) {
            return Result.failure(IllegalArgumentException(validationError))
        }

        val activeSettings = settings ?: LocalApprovalSettingsEntity(familyId = family.familyId)
        val ruleMode = ApprovalRuleMode.fromRaw(activeSettings.ruleMode)
        val voterRule = EligibleVoterRule.fromRaw(activeSettings.eligibleVoterRule)
        val suspendedSet = family.suspendedUids.toSet()

        val candidates = members.map {
            VoterEligibilityCandidate(
                userId = it.userId,
                role = it.role,
                canCreateRecords = it.canCreateRecords,
                isSuspended = it.userId in suspendedSet
            )
        }.ifEmpty {
            family.memberUids.map { uid ->
                VoterEligibilityCandidate(
                    userId = uid,
                    role = if (family.isAdmin(uid)) "ADMIN" else "MEMBER",
                    canCreateRecords = true,
                    isSuspended = uid in suspendedSet
                )
            }
        }

        val eligibleVoters = ApprovalDecisionEngine.resolveEligibleVoters(
            candidates = candidates,
            proposerUid = callerUid,
            eligibleVoterRule = voterRule,
            specificEligibleUids = activeSettings.specificEligibleUidList(),
            allowCreatorVote = activeSettings.allowCreatorVote
        )

        if (submitNow && eligibleVoters.isEmpty()) {
            return Result.failure(
                IllegalStateException("No eligible voters match the active family approval rules.")
            )
        }

        val effectiveQuorum = if (eligibleVoters.isEmpty()) {
            activeSettings.minQuorumCount.coerceAtLeast(1)
        } else {
            activeSettings.minQuorumCount.coerceIn(1, eligibleVoters.size)
        }

        val now = System.currentTimeMillis()
        val proposalId = existingDraft?.proposalId ?: DatabaseIdentityHelper.generateRecordId("prop")
        val status = if (submitNow) ProposalStatus.PENDING else ProposalStatus.DRAFT

        val entity = LocalProposalEntity(
            proposalId = proposalId,
            familyId = family.familyId,
            title = title.trim().take(120),
            category = category.name,
            description = description.trim().take(800),
            reason = reason.trim().take(500),
            amountMinor = amountMinor,
            currency = currency.trim().uppercase().ifBlank { family.currencyCode }.take(6),
            relatedParty = relatedParty.trim().take(120),
            supportingDocName = supportingDocName?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
            supportingDocUri = supportingDocUri?.trim()?.take(300)?.takeIf { it.isNotEmpty() },
            targetSavingsId = targetSavingsId?.takeIf { category == DecisionCategory.WITHDRAW_SAVINGS && it.isNotBlank() },
            proposedDateMillis = proposedDateMillis,
            votingDeadlineMillis = votingDeadlineMillis,
            proposerUid = callerUid,
            proposerName = proposerName.ifBlank { callerMember?.displayName ?: "Family Member" },
            status = status.name,
            ruleMode = ruleMode.name,
            minApprovalPercent = activeSettings.minApprovalPercent,
            minQuorumRequired = effectiveQuorum,
            allowCreatorVote = activeSettings.allowCreatorVote,
            allowVoteChangeBeforeDeadline = activeSettings.allowVoteChangeBeforeDeadline,
            eligibleVoterRule = voterRule.name,
            eligibleVoterUidsCsv = eligibleVoters.joinToString(","),
            approvedCount = 0,
            rejectedCount = 0,
            finalDecision = null,
            syncStatus = SyncState.SYNCED.name,
            createdAtMillis = existingDraft?.createdAtMillis ?: now,
            updatedAtMillis = now
        )

        val histEntry = LocalProposalVoteHistoryEntity(
            historyId = "vh_${now}_${callerUid.take(6)}",
            proposalId = proposalId,
            familyId = family.familyId,
            actorUid = callerUid,
            actorName = entity.proposerName,
            actionType = if (submitNow) VoteAuditActionType.PROPOSAL_SUBMITTED.name else VoteAuditActionType.PROPOSAL_CREATED.name,
            note = if (submitNow) "Submitted for family approval" else "Saved draft proposal",
            createdAtMillis = now
        )

        val remotePayload = mutableMapOf<String, Any?>(
            "proposalId" to entity.proposalId,
            "familyId" to entity.familyId,
            "title" to entity.title,
            "category" to entity.category,
            "description" to entity.description,
            "reason" to entity.reason,
            "amountMinor" to entity.amountMinor,
            "currency" to entity.currency,
            "relatedParty" to entity.relatedParty,
            "supportingDocName" to entity.supportingDocName,
            "supportingDocUri" to entity.supportingDocUri,
            "targetSavingsId" to entity.targetSavingsId,
            "proposedDate" to Timestamp(Date(entity.proposedDateMillis)),
            "votingDeadline" to Timestamp(Date(entity.votingDeadlineMillis)),
            "proposerUid" to entity.proposerUid,
            "proposerName" to entity.proposerName,
            "status" to entity.status,
            "ruleMode" to entity.ruleMode,
            "minApprovalPercent" to entity.minApprovalPercent,
            "minQuorumRequired" to entity.minQuorumRequired,
            "allowCreatorVote" to entity.allowCreatorVote,
            "allowVoteChangeBeforeDeadline" to entity.allowVoteChangeBeforeDeadline,
            "eligibleVoterRule" to entity.eligibleVoterRule,
            "eligibleVoterUids" to eligibleVoters,
            "approvedCount" to 0,
            "rejectedCount" to 0,
            "finalDecision" to null,
            "executedRecordId" to null,
            "memberUids" to family.memberUids,
            "adminUids" to family.adminUids,
            "createdAt" to Timestamp(Date(entity.createdAtMillis)),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        runCatching {
            val propRef = db.collection("proposals").document(proposalId)
            db.runTransaction { tx ->
                tx.set(propRef, remotePayload)
                tx.set(
                    propRef.collection("vote_history").document(histEntry.historyId),
                    mapOf(
                        "historyId" to histEntry.historyId,
                        "proposalId" to proposalId,
                        "familyId" to family.familyId,
                        "actorUid" to callerUid,
                        "actorName" to entity.proposerName,
                        "actionType" to histEntry.actionType,
                        "note" to histEntry.note,
                        "memberUids" to family.memberUids,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
        }.onFailure { err ->
            if (err is CancellationException) throw err
        }

        localStore.saveProposal(entity, histEntry)

        if (submitNow) {
            val formattedAmount = MoneyCalculator.formatMinorUnits(entity.amountMinor, entity.currency)
            dispatchProposalNotifications(
                family = family,
                recipientUids = eligibleVoters.filter { it != callerUid },
                actorUid = callerUid,
                notificationIdPrefix = "ntf_prop_${proposalId}",
                title = "Approval Requested: ${entity.title}",
                body = "${entity.proposerName} proposed $formattedAmount for ${category.label}. Vote before deadline.",
                type = NotificationType.PROPOSAL_SUBMITTED,
                proposalId = proposalId
            )
        }

        return Result.success(entity)
    }

    suspend fun deleteDraftProposal(family: LocalFamilyEntity, proposalId: String): Result<Unit> {
        val callerUid = requireUid()
        val existing = localStore.getProposalById(proposalId)
            ?: return Result.failure(IllegalArgumentException("Draft proposal not found."))
        if (existing.proposerUid != callerUid || existing.status != ProposalStatus.DRAFT.name) {
            return Result.failure(SecurityException("Only your own Draft proposals can be deleted."))
        }
        runCatching {
            db.collection("proposals").document(proposalId).delete().await()
        }
        localStore.deleteDraftProposalLocal(proposalId, family.familyId)
        return Result.success(Unit)
    }

    suspend fun castOrChangeVote(
        family: LocalFamilyEntity,
        proposal: LocalProposalEntity,
        choice: VoteChoice,
        comment: String?,
        voterName: String
    ): Result<ProposalTallySummary> {
        val callerUid = requireUid()
        if (family.isSuspended(callerUid)) {
            return Result.failure(SecurityException("Suspended family members cannot vote."))
        }
        val eligibleVoters = proposal.eligibleVoterUidList()
        if (callerUid !in eligibleVoters) {
            return Result.failure(SecurityException("You are not listed as an eligible voter for this proposal."))
        }
        if (!proposal.allowCreatorVote && proposal.proposerUid == callerUid) {
            return Result.failure(SecurityException("Proposal creator voting is disabled by family approval rules."))
        }
        if (proposal.status != ProposalStatus.PENDING.name) {
            return Result.failure(IllegalStateException("Voting is closed (${ProposalStatus.fromRaw(proposal.status).label})."))
        }

        val now = System.currentTimeMillis()
        if (now > proposal.votingDeadlineMillis) {
            val expired = proposal.copy(
                status = ProposalStatus.EXPIRED.name,
                finalDecision = ProposalStatus.EXPIRED.name,
                decidedAtMillis = now,
                updatedAtMillis = now
            )
            localStore.saveProposal(
                expired,
                LocalProposalVoteHistoryEntity(
                    historyId = "vh_exp_${proposal.proposalId}",
                    proposalId = proposal.proposalId,
                    familyId = family.familyId,
                    actorUid = callerUid,
                    actorName = voterName,
                    actionType = VoteAuditActionType.PROPOSAL_EXPIRED.name,
                    note = "Voting deadline passed",
                    createdAtMillis = now
                )
            )
            return Result.failure(IllegalStateException("Voting deadline has expired."))
        }

        val currentVotes = localStore.getVotesForProposal(proposal.proposalId)
        val existingVote = currentVotes.firstOrNull { it.voterUid == callerUid }
        if (existingVote != null && !proposal.allowVoteChangeBeforeDeadline) {
            return Result.failure(SecurityException("Vote changes are disabled by family approval settings."))
        }
        if (existingVote != null && existingVote.choice == choice.name) {
            return Result.failure(IllegalStateException("You have already voted ${choice.label} on this proposal."))
        }

        val updatedBallots = currentVotes.filterNot { it.voterUid == callerUid }
            .mapNotNull { VoteChoice.fromRaw(it.choice)?.let { c -> VoteBallotInput(it.voterUid, c) } } +
            VoteBallotInput(callerUid, choice)

        val tally = ApprovalDecisionEngine.evaluateTally(
            eligibleVoterUids = eligibleVoters,
            votes = updatedBallots,
            ruleMode = ApprovalRuleMode.fromRaw(proposal.ruleMode),
            minApprovalPercent = proposal.minApprovalPercent,
            minQuorumRequired = proposal.minQuorumRequired,
            votingDeadlineMillis = proposal.votingDeadlineMillis,
            nowMillis = now,
            currentStatus = ProposalStatus.PENDING
        )

        val cleanedComment = comment?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
        val voteEntity = LocalProposalVoteEntity(
            proposalId = proposal.proposalId,
            voterUid = callerUid,
            familyId = family.familyId,
            voterName = voterName.ifBlank { "Family Member" },
            choice = choice.name,
            comment = cleanedComment,
            votedAtMillis = existingVote?.votedAtMillis ?: now,
            updatedAtMillis = now
        )
        val histEntry = LocalProposalVoteHistoryEntity(
            historyId = "vh_${now}_${callerUid.take(6)}",
            proposalId = proposal.proposalId,
            familyId = family.familyId,
            actorUid = callerUid,
            actorName = voteEntity.voterName,
            actionType = if (existingVote != null) VoteAuditActionType.VOTE_CHANGED.name else VoteAuditActionType.VOTE_CAST.name,
            previousChoice = existingVote?.choice,
            newChoice = choice.name,
            note = cleanedComment,
            createdAtMillis = now
        )
        val updatedProposal = proposal.copy(
            approvedCount = tally.approvedCount,
            rejectedCount = tally.rejectedCount,
            status = tally.nextStatus.name,
            finalDecision = tally.finalDecision,
            decidedAtMillis = if (tally.nextStatus == ProposalStatus.APPROVED || tally.nextStatus == ProposalStatus.REJECTED) now else proposal.decidedAtMillis,
            updatedAtMillis = now
        )

        var cloudApplied = false
        if (functionsClient.isConfigured) {
            val rpcRes = functionsClient.submitProposalVoteRemote(
                familyId = family.familyId,
                proposalId = proposal.proposalId,
                choice = choice.name,
                comment = cleanedComment
            )
            if (rpcRes.isSuccess) {
                cloudApplied = true
            }
        }

        if (!cloudApplied) {
            runCatching {
                val propRef = db.collection("proposals").document(proposal.proposalId)
                db.runTransaction { tx ->
                    tx.set(
                        propRef.collection("votes").document(callerUid),
                        mapOf(
                            "voterUid" to callerUid,
                            "voterName" to voteEntity.voterName,
                            "proposalId" to proposal.proposalId,
                            "familyId" to family.familyId,
                            "choice" to choice.name,
                            "comment" to cleanedComment,
                            "memberUids" to family.memberUids,
                            "votedAt" to Timestamp(Date(voteEntity.votedAtMillis)),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.set(
                        propRef.collection("vote_history").document(histEntry.historyId),
                        mapOf(
                            "historyId" to histEntry.historyId,
                            "proposalId" to proposal.proposalId,
                            "familyId" to family.familyId,
                            "actorUid" to callerUid,
                            "actorName" to voteEntity.voterName,
                            "actionType" to histEntry.actionType,
                            "previousChoice" to histEntry.previousChoice,
                            "newChoice" to histEntry.newChoice,
                            "note" to histEntry.note,
                            "memberUids" to family.memberUids,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                    val updates = mutableMapOf<String, Any?>(
                        "approvedCount" to tally.approvedCount,
                        "rejectedCount" to tally.rejectedCount,
                        "status" to tally.nextStatus.name,
                        "finalDecision" to tally.finalDecision,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (tally.nextStatus == ProposalStatus.APPROVED || tally.nextStatus == ProposalStatus.REJECTED) {
                        updates["decidedAt"] = FieldValue.serverTimestamp()
                    }
                    tx.update(propRef, updates)
                }.await()
            }.onFailure { err ->
                if (err is CancellationException) throw err
            }
        }

        localStore.saveVoteAndUpdateProposalAtomic(
            vote = voteEntity,
            historyEntry = histEntry,
            updatedProposal = updatedProposal
        )

        if (tally.nextStatus == ProposalStatus.APPROVED || tally.nextStatus == ProposalStatus.REJECTED) {
            val outcomeType = if (tally.nextStatus == ProposalStatus.APPROVED) {
                NotificationType.PROPOSAL_APPROVED
            } else {
                NotificationType.PROPOSAL_REJECTED
            }
            val outcomeHist = LocalProposalVoteHistoryEntity(
                historyId = "vh_dec_${proposal.proposalId}_${tally.nextStatus.name.lowercase()}",
                proposalId = proposal.proposalId,
                familyId = family.familyId,
                actorUid = callerUid,
                actorName = voteEntity.voterName,
                actionType = if (tally.nextStatus == ProposalStatus.APPROVED) {
                    VoteAuditActionType.PROPOSAL_APPROVED.name
                } else {
                    VoteAuditActionType.PROPOSAL_REJECTED.name
                },
                note = "${tally.approvedCount} approved, ${tally.rejectedCount} rejected",
                createdAtMillis = now + 1L
            )
            localStore.reconcileRemoteProposalHistory(proposal.proposalId, listOf(outcomeHist))

            dispatchProposalNotifications(
                family = family,
                recipientUids = family.memberUids.filter { it != callerUid },
                actorUid = callerUid,
                notificationIdPrefix = "ntf_${tally.nextStatus.name.lowercase()}_${proposal.proposalId}",
                title = "Proposal ${tally.nextStatus.label}: ${proposal.title}",
                body = "${tally.approvedCount} approve, ${tally.rejectedCount} reject (${tally.approvalPercentOfEligible}% approval).",
                type = outcomeType,
                proposalId = proposal.proposalId
            )
        } else {
            dispatchProposalNotifications(
                family = family,
                recipientUids = listOf(proposal.proposerUid).filter { it != callerUid },
                actorUid = callerUid,
                notificationIdPrefix = "ntf_vote_${proposal.proposalId}_${callerUid}",
                title = "Vote Recorded: ${proposal.title}",
                body = "${voteEntity.voterName} voted ${choice.label} (${tally.approvedCount}/${tally.totalEligible} approved).",
                type = NotificationType.PROPOSAL_VOTE_CAST,
                proposalId = proposal.proposalId
            )
        }

        return Result.success(tally)
    }

    suspend fun cancelProposal(
        family: LocalFamilyEntity,
        proposal: LocalProposalEntity,
        reason: String,
        actorName: String
    ): Result<LocalProposalEntity> {
        val callerUid = requireUid()
        val isAdmin = family.isAdmin(callerUid)
        val isProposerDraft = proposal.proposerUid == callerUid && proposal.status == ProposalStatus.DRAFT.name
        if (!isAdmin && !isProposerDraft) {
            return Result.failure(SecurityException("Only Family Admins can cancel active proposals."))
        }
        if (proposal.status != ProposalStatus.DRAFT.name && proposal.status != ProposalStatus.PENDING.name) {
            return Result.failure(IllegalStateException("Cannot cancel proposal in status ${proposal.status}."))
        }
        val cleanReason = reason.trim()
        if (cleanReason.length < 5) {
            return Result.failure(IllegalArgumentException("Please provide a cancellation reason of at least 5 characters."))
        }

        val now = System.currentTimeMillis()
        val updated = proposal.copy(
            status = ProposalStatus.CANCELLED.name,
            finalDecision = ProposalStatus.CANCELLED.name,
            cancelReason = cleanReason.take(300),
            cancelledByUid = callerUid,
            decidedAtMillis = now,
            updatedAtMillis = now
        )
        val histEntry = LocalProposalVoteHistoryEntity(
            historyId = "vh_cancel_${proposal.proposalId}_$now",
            proposalId = proposal.proposalId,
            familyId = family.familyId,
            actorUid = callerUid,
            actorName = actorName.ifBlank { "Family Admin" },
            actionType = VoteAuditActionType.PROPOSAL_CANCELLED.name,
            note = cleanReason,
            createdAtMillis = now
        )
        val audit = LocalAuditLogEntity(
            auditId = DatabaseIdentityHelper.generateAuditId(),
            familyId = family.familyId,
            actorUserId = callerUid,
            entityCollection = "proposals",
            entityId = proposal.proposalId,
            action = AuditAction.STATUS_CHANGE.name,
            summary = "Cancelled proposal ${proposal.title}: $cleanReason".take(200),
            deviceId = "",
            syncStatus = SyncState.SYNCED.name,
            createdAtMillis = now
        )

        if (functionsClient.isConfigured) {
            functionsClient.cancelProposalRemote(family.familyId, proposal.proposalId, cleanReason)
        } else {
            runCatching {
                val propRef = db.collection("proposals").document(proposal.proposalId)
                db.runTransaction { tx ->
                    tx.update(
                        propRef,
                        mapOf(
                            "status" to ProposalStatus.CANCELLED.name,
                            "finalDecision" to ProposalStatus.CANCELLED.name,
                            "cancelReason" to cleanReason.take(300),
                            "cancelledByUid" to callerUid,
                            "decidedAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.set(
                        propRef.collection("vote_history").document(histEntry.historyId),
                        mapOf(
                            "historyId" to histEntry.historyId,
                            "proposalId" to proposal.proposalId,
                            "familyId" to family.familyId,
                            "actorUid" to callerUid,
                            "actorName" to histEntry.actorName,
                            "actionType" to histEntry.actionType,
                            "note" to cleanReason,
                            "memberUids" to family.memberUids,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            }
        }

        localStore.cancelProposalAtomicLocal(updated, histEntry, audit)
        return Result.success(updated)
    }

    suspend fun executeApprovedProposal(
        family: LocalFamilyEntity,
        proposal: LocalProposalEntity,
        actorName: String
    ): Result<ProposalExecutionOutcome.Executed> {
        val callerUid = requireUid()
        if (family.isSuspended(callerUid)) {
            return Result.failure(SecurityException("Suspended family members cannot execute transactions."))
        }
        if (!proposal.executedRecordId.isNullOrBlank() || proposal.status == ProposalStatus.EXECUTED.name) {
            return Result.failure(IllegalStateException("Proposal has already been executed."))
        }
        if (proposal.status != ProposalStatus.APPROVED.name) {
            return Result.failure(IllegalStateException("Only APPROVED proposals can be financially executed."))
        }

        val now = System.currentTimeMillis()
        val outcome = localStore.executeApprovedProposalAtomicLocal(
            proposalId = proposal.proposalId,
            actorUid = callerUid,
            actorName = actorName.ifBlank { "Family Member" },
            deviceId = "",
            nowMillis = now,
            enqueueOutbox = !functionsClient.isConfigured
        )

        return when (outcome) {
            is ProposalExecutionOutcome.Failure -> Result.failure(IllegalStateException(outcome.reason))
            is ProposalExecutionOutcome.Executed -> {
                if (functionsClient.isConfigured) {
                    functionsClient.executeProposalRemote(family.familyId, proposal.proposalId)
                } else {
                    runCatching {
                        db.collection("proposals").document(proposal.proposalId).update(
                            mapOf(
                                "status" to ProposalStatus.EXECUTED.name,
                                "finalDecision" to ProposalStatus.EXECUTED.name,
                                "executedRecordId" to outcome.executedRecordId,
                                "executedCollection" to outcome.executedCollection,
                                "executedByUid" to callerUid,
                                "executedAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }

                val formattedAmount = MoneyCalculator.formatMinorUnits(proposal.amountMinor, proposal.currency)
                dispatchProposalNotifications(
                    family = family,
                    recipientUids = family.memberUids.filter { it != callerUid },
                    actorUid = callerUid,
                    notificationIdPrefix = "ntf_exec_${proposal.proposalId}",
                    title = "Proposal Executed: ${proposal.title}",
                    body = "$formattedAmount was recorded in ${outcome.executedCollection} after family approval.",
                    type = NotificationType.PROPOSAL_EXECUTED,
                    proposalId = proposal.proposalId
                )
                Result.success(outcome)
            }
        }
    }

    suspend fun evaluateDeadlinesAndAlerts(
        family: LocalFamilyEntity,
        proposals: List<LocalProposalEntity>,
        votes: List<LocalProposalVoteEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val callerUid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        for (proposal in proposals) {
            if (proposal.status != ProposalStatus.PENDING.name) continue
            if (nowMillis > proposal.votingDeadlineMillis) {
                val expired = proposal.copy(
                    status = ProposalStatus.EXPIRED.name,
                    finalDecision = ProposalStatus.EXPIRED.name,
                    decidedAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
                localStore.saveProposal(
                    expired,
                    LocalProposalVoteHistoryEntity(
                        historyId = "vh_exp_${proposal.proposalId}",
                        proposalId = proposal.proposalId,
                        familyId = family.familyId,
                        actorUid = callerUid,
                        actorName = "System",
                        actionType = VoteAuditActionType.PROPOSAL_EXPIRED.name,
                        note = "Deadline elapsed without required approval",
                        createdAtMillis = nowMillis
                    )
                )
                runCatching {
                    db.collection("proposals").document(proposal.proposalId).update(
                        mapOf(
                            "status" to ProposalStatus.EXPIRED.name,
                            "finalDecision" to ProposalStatus.EXPIRED.name,
                            "decidedAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val expNotifId = "ntf_exp_${proposal.proposalId}_$callerUid"
                if (localStore.getNotificationById(expNotifId) == null) {
                    localStore.saveNotification(
                        LocalNotificationEntity(
                            notificationId = expNotifId,
                            familyId = family.familyId,
                            recipientUserId = callerUid,
                            createdByUserId = proposal.proposerUid,
                            title = "Voting Expired: ${proposal.title}",
                            body = "The voting deadline elapsed without reaching the required approval threshold.",
                            notificationType = NotificationType.PROPOSAL_EXPIRED.name,
                            deepLinkModule = "FAMILY_DECISIONS",
                            relatedRecordId = proposal.proposalId,
                            acknowledged = false,
                            recordStatus = RecordStatus.ACTIVE.name,
                            syncStatus = SyncState.SYNCED.name,
                            createdAtMillis = nowMillis,
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            } else {
                val remaining = proposal.votingDeadlineMillis - nowMillis
                if (remaining in 1..ApprovalDecisionEngine.NEAR_DEADLINE_WINDOW_MILLIS) {
                    val hasVoted = votes.any { it.proposalId == proposal.proposalId && it.voterUid == callerUid }
                    val isEligible = callerUid in proposal.eligibleVoterUidList()
                    if (isEligible && !hasVoted) {
                        val dlNotifId = "ntf_dl_${proposal.proposalId}_$callerUid"
                        if (localStore.getNotificationById(dlNotifId) == null) {
                            val hrsLeft = (remaining / 3_600_000L).coerceAtLeast(1L)
                            localStore.saveNotification(
                                LocalNotificationEntity(
                                    notificationId = dlNotifId,
                                    familyId = family.familyId,
                                    recipientUserId = callerUid,
                                    createdByUserId = proposal.proposerUid,
                                    title = "Deadline Soon: ${proposal.title}",
                                    body = "About ${hrsLeft}h left to cast your vote.",
                                    notificationType = NotificationType.PROPOSAL_NEAR_DEADLINE.name,
                                    deepLinkModule = "FAMILY_DECISIONS",
                                    relatedRecordId = proposal.proposalId,
                                    acknowledged = false,
                                    recordStatus = RecordStatus.ACTIVE.name,
                                    syncStatus = SyncState.SYNCED.name,
                                    createdAtMillis = nowMillis,
                                    updatedAtMillis = nowMillis
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun dispatchProposalNotifications(
        family: LocalFamilyEntity,
        recipientUids: List<String>,
        actorUid: String,
        notificationIdPrefix: String,
        title: String,
        body: String,
        type: NotificationType,
        proposalId: String
    ) {
        if (recipientUids.isEmpty()) return
        val now = System.currentTimeMillis()
        for (uid in recipientUids.distinct()) {
            val ntfId = "${notificationIdPrefix}_${uid.take(12)}"
            localStore.saveNotification(
                LocalNotificationEntity(
                    notificationId = ntfId,
                    familyId = family.familyId,
                    recipientUserId = uid,
                    createdByUserId = actorUid,
                    title = title.take(100),
                    body = body.take(400),
                    notificationType = type.name,
                    deepLinkModule = "FAMILY_DECISIONS",
                    relatedRecordId = proposalId,
                    acknowledged = false,
                    recordStatus = RecordStatus.ACTIVE.name,
                    syncStatus = SyncState.SYNCED.name,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
            runCatching {
                db.collection("notifications").document(ntfId).set(
                    mapOf(
                        "notificationId" to ntfId,
                        "familyId" to family.familyId,
                        "recipientUserId" to uid,
                        "createdByUserId" to actorUid,
                        "title" to title.take(100),
                        "body" to body.take(400),
                        "notificationType" to type.name,
                        "deepLinkModule" to "FAMILY_DECISIONS",
                        "relatedRecordId" to proposalId,
                        "acknowledged" to false,
                        "recordStatus" to RecordStatus.ACTIVE.name,
                        "memberUids" to family.memberUids,
                        "adminUids" to family.adminUids,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }
        runCatching {
            meetBackend.dispatchPush(
                familyId = family.familyId,
                targetUids = recipientUids.distinct(),
                title = title,
                body = body,
                notificationType = type.name,
                deepLinkModule = "FAMILY_DECISIONS",
                relatedRecordId = proposalId
            )
        }
    }
}
