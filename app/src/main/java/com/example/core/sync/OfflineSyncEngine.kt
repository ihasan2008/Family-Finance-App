package com.example.core.sync

import com.example.core.firebase.FirestoreService
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.local.abstraction.LocalEntityCipherMapper
import com.example.core.model.AuditAction
import com.example.core.model.AuditLogDoc
import com.example.core.model.BudgetDoc
import com.example.core.model.BudgetScope
import com.example.core.model.ChatMessageDoc
import com.example.core.model.ChatMessageType
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.FamilyNotificationDoc
import com.example.core.model.InvestmentAssetType
import com.example.core.model.InvestmentDoc
import com.example.core.model.LoanDirection
import com.example.core.model.LoanDoc
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecordStatus
import com.example.core.model.SavingsDoc
import com.example.core.model.SyncState
import com.example.core.model.TransactionDoc
import com.example.core.model.TransactionType
import com.example.data.repository.toFirestoreDoc
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.Date

sealed interface RemoteWriteOutcome {
    data class Success(val confirmedVersion: Int) : RemoteWriteOutcome
    data class DuplicateIdempotent(val confirmedVersion: Int) : RemoteWriteOutcome
    data class VersionConflict(
        val remoteVersion: Int,
        val remoteSummary: String,
        val remotePayloadJson: String
    ) : RemoteWriteOutcome
    data class RemoteDeleted(val remoteSummary: String = "Record deleted in Cloud Firestore") : RemoteWriteOutcome
    data class TransientFailure(val message: String) : RemoteWriteOutcome
}

interface RemoteSyncGateway {
    suspend fun executeCreate(
        familyId: String,
        collection: String,
        docId: String,
        idempotencyKey: String,
        payload: Map<String, Any>,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome

    suspend fun executeUpdateVersionChecked(
        familyId: String,
        collection: String,
        docId: String,
        expectedBaseVersion: Int,
        nextVersion: Int,
        payload: Map<String, Any>,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome

    suspend fun executeFieldUpdate(
        familyId: String,
        collection: String,
        docId: String,
        fields: Map<String, Any>,
        auditId: String? = null,
        auditPayload: Map<String, Any>? = null
    ): RemoteWriteOutcome = executeUpdateVersionChecked(
        familyId = familyId,
        collection = collection,
        docId = docId,
        expectedBaseVersion = 0,
        nextVersion = 1,
        payload = fields,
        auditId = auditId,
        auditPayload = auditPayload
    )

    suspend fun executeDelete(
        familyId: String,
        collection: String,
        docId: String,
        expectedBaseVersion: Int,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome
}

class FirestoreSyncGateway(
    private val db: FirebaseFirestore,
    private val firestoreService: FirestoreService,
    private val codecMapper: LocalEntityCipherMapper = LocalEntityCipherMapper()
) : RemoteSyncGateway {

    override suspend fun executeCreate(
        familyId: String,
        collection: String,
        docId: String,
        idempotencyKey: String,
        payload: Map<String, Any>,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome {
        return try {
            // Check cross-device duplicate idempotency key on versioned financial collections before creating
            if (idempotencyKey.length >= 8 && collection in VERSIONED_COLLECTIONS) {
                val dupSnap = runCatching {
                    db.collection(collection)
                        .whereEqualTo("familyId", familyId)
                        .whereEqualTo("idempotencyKey", idempotencyKey)
                        .limit(1)
                        .get()
                        .await()
                }.getOrNull()
                val existingDup = dupSnap?.documents?.firstOrNull()
                if (existingDup != null && existingDup.exists()) {
                    val ver = (existingDup.getLong("syncVersion") ?: 1L).toInt().coerceAtLeast(1)
                    return RemoteWriteOutcome.DuplicateIdempotent(ver)
                }
            }

            val ref = db.collection(collection).document(docId)
            val confirmedVer = db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (snap.exists()) {
                    val existingVer = (snap.getLong("syncVersion") ?: 1L).toInt().coerceAtLeast(1)
                    return@runTransaction existingVer
                }
                tx.set(ref, payload)
                if (auditId != null && auditPayload != null) {
                    tx.set(db.collection("auditLogs").document(auditId), auditPayload)
                }
                (payload["syncVersion"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            }.await()
            RemoteWriteOutcome.Success(confirmedVer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Firestore write error (${e.code.name})")
        } catch (e: Exception) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Network unavailable")
        }
    }

    override suspend fun executeUpdateVersionChecked(
        familyId: String,
        collection: String,
        docId: String,
        expectedBaseVersion: Int,
        nextVersion: Int,
        payload: Map<String, Any>,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome {
        return try {
            val ref = db.collection(collection).document(docId)
            val outcome = db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) {
                    return@runTransaction RemoteWriteOutcome.RemoteDeleted()
                }
                val remoteVer = (snap.getLong("syncVersion") ?: 1L).toInt().coerceAtLeast(1)
                if (expectedBaseVersion > 0 && remoteVer > expectedBaseVersion) {
                    val (summary, jsonSnap) = extractRemoteConflictSnapshot(collection, familyId, snap)
                    return@runTransaction RemoteWriteOutcome.VersionConflict(
                        remoteVersion = remoteVer,
                        remoteSummary = summary,
                        remotePayloadJson = jsonSnap
                    )
                }
                val committedVer = maxOf(nextVersion, remoteVer + 1)
                val updatedMap = payload.toMutableMap().apply {
                    put("syncVersion", committedVer.toLong())
                }
                tx.update(ref, updatedMap)
                if (auditId != null && auditPayload != null) {
                    tx.set(db.collection("auditLogs").document(auditId), auditPayload)
                }
                RemoteWriteOutcome.Success(committedVer)
            }.await()
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Firestore update error (${e.code.name})")
        } catch (e: Exception) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Network unavailable")
        }
    }

    override suspend fun executeFieldUpdate(
        familyId: String,
        collection: String,
        docId: String,
        fields: Map<String, Any>,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome {
        return try {
            val ref = db.collection(collection).document(docId)
            db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) {
                    return@runTransaction RemoteWriteOutcome.Success(1)
                }
                tx.update(ref, fields)
                if (auditId != null && auditPayload != null) {
                    tx.set(db.collection("auditLogs").document(auditId), auditPayload)
                }
                RemoteWriteOutcome.Success(1)
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Firestore field update error (${e.code.name})")
        } catch (e: Exception) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Network unavailable")
        }
    }

    override suspend fun executeDelete(
        familyId: String,
        collection: String,
        docId: String,
        expectedBaseVersion: Int,
        auditId: String?,
        auditPayload: Map<String, Any>?
    ): RemoteWriteOutcome {
        return try {
            val ref = db.collection(collection).document(docId)
            val outcome = db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) {
                    return@runTransaction RemoteWriteOutcome.Success(expectedBaseVersion.coerceAtLeast(1))
                }
                val remoteVer = (snap.getLong("syncVersion") ?: 1L).toInt().coerceAtLeast(1)
                if (expectedBaseVersion > 0 && remoteVer > expectedBaseVersion) {
                    val (summary, jsonSnap) = extractRemoteConflictSnapshot(collection, familyId, snap)
                    return@runTransaction RemoteWriteOutcome.VersionConflict(
                        remoteVersion = remoteVer,
                        remoteSummary = summary,
                        remotePayloadJson = jsonSnap
                    )
                }
                tx.delete(ref)
                if (auditId != null && auditPayload != null) {
                    tx.set(db.collection("auditLogs").document(auditId), auditPayload)
                }
                RemoteWriteOutcome.Success(expectedBaseVersion.coerceAtLeast(1))
            }.await()
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Firestore delete error (${e.code.name})")
        } catch (e: Exception) {
            RemoteWriteOutcome.TransientFailure(e.message ?: "Network unavailable")
        }
    }

    private fun extractRemoteConflictSnapshot(
        collection: String,
        fallbackFamilyId: String,
        snap: DocumentSnapshot
    ): Pair<String, String> {
        return when (collection) {
            "transactions" -> snap.toObject(TransactionDoc::class.java)
                ?.toLocalEntity(fallbackFamilyId)
                ?.let { codecMapper.summarizeTransaction(it) to codecMapper.encodeTransactionSnapshot(it) }
                ?: ("Cloud transaction updated" to "")
            "loans" -> snap.toObject(LoanDoc::class.java)
                ?.toLocalEntity(fallbackFamilyId)
                ?.let { codecMapper.summarizeLoan(it) to codecMapper.encodeLoanSnapshot(it) }
                ?: ("Cloud loan updated" to "")
            "savings" -> snap.toObject(SavingsDoc::class.java)
                ?.toLocalEntity(fallbackFamilyId)
                ?.let { codecMapper.summarizeSavings(it) to codecMapper.encodeSavingsSnapshot(it) }
                ?: ("Cloud savings goal updated" to "")
            "investments" -> snap.toObject(InvestmentDoc::class.java)
                ?.toLocalEntity(fallbackFamilyId)
                ?.let { codecMapper.summarizeInvestment(it) to codecMapper.encodeInvestmentSnapshot(it) }
                ?: ("Cloud investment updated" to "")
            "budgets" -> snap.toObject(BudgetDoc::class.java)
                ?.toLocalEntity(fallbackFamilyId)
                ?.let { codecMapper.summarizeBudget(it) to codecMapper.encodeBudgetSnapshot(it) }
                ?: ("Cloud budget updated" to "")
            else -> ("Cloud record updated" to "")
        }
    }

    private companion object {
        val VERSIONED_COLLECTIONS = setOf("transactions", "loans", "savings", "investments", "budgets")
    }
}

/**
 * Orchestrates the 7-step offline-first synchronization pipeline:
 * 1. Detect connectivity & verified session
 * 2. Read pending outbox operations scoped to the active family
 * 3. Validate membership, permissions, family isolation, and financial invariants
 * 4. Execute idempotent, version-checked remote writes via [RemoteSyncGateway]
 * 5. Detect push conflicts and apply configured [ConflictResolutionPolicy]
 * 6. Update local encrypted Room records cleanly
 * 7. Mark synchronized entries + record immutable audit log events
 */
class OfflineSyncEngine(
    private val localStore: LocalDatabaseStore,
    private val gateway: RemoteSyncGateway,
    private val mapper: LocalEntityCipherMapper = LocalEntityCipherMapper()
) {
    suspend fun validateOutboxOperation(
        item: LocalSyncOutboxEntity,
        activeFamilyId: String,
        authenticatedUid: String
    ): OutboxValidationResult {
        if (item.familyId.isBlank() || item.familyId != activeFamilyId) {
            return OutboxValidationResult.Rejected(
                reason = "Blocked cross-family sync attempt (${item.familyId} != $activeFamilyId).",
                conflictType = ConflictType.CROSS_FAMILY_VIOLATION
            )
        }
        val family = localStore.getCachedFamily(activeFamilyId)
            ?: return OutboxValidationResult.Rejected(
                reason = "Family workspace $activeFamilyId is not cached or authorized locally.",
                conflictType = ConflictType.UNAUTHORIZED_ACTOR
            )
        if (authenticatedUid !in family.memberUids) {
            return OutboxValidationResult.Rejected(
                reason = "User $authenticatedUid is no longer an active member of ${family.name}.",
                conflictType = ConflictType.UNAUTHORIZED_ACTOR
            )
        }

        val member = localStore.getCachedMember(activeFamilyId, authenticatedUid)
        val isAdmin = family.isAdmin(authenticatedUid)

        return when (item.collectionName) {
            "chats" -> {
                val msg = localStore.getChatMessageById(item.documentId)
                if (item.operation == "READ_RECEIPT") {
                    return OutboxValidationResult.Valid
                }
                if (item.operation == "DELETE") {
                    if (msg != null && msg.senderUserId != authenticatedUid && !isAdmin) {
                        return OutboxValidationResult.Rejected(
                            reason = "Only the sender or Family Admin can delete this message.",
                            conflictType = ConflictType.UNAUTHORIZED_ACTOR
                        )
                    }
                    return OutboxValidationResult.Valid
                }
                if (item.operation == "UPDATE" && msg?.recordStatus in setOf("VOIDED", "ARCHIVED")) {
                    if (msg != null && msg.senderUserId != authenticatedUid && !isAdmin) {
                        return OutboxValidationResult.Rejected(
                            reason = "Only the sender or Family Admin can delete this message.",
                            conflictType = ConflictType.UNAUTHORIZED_ACTOR
                        )
                    }
                    return OutboxValidationResult.Valid
                }
                if (!family.memberChatEnabled) {
                    return OutboxValidationResult.Rejected(
                        reason = "Family Admin disabled family chat for ${family.name}.",
                        conflictType = ConflictType.UNAUTHORIZED_ACTOR
                    )
                }
                if (!isAdmin && member != null && !member.canParticipateInChat) {
                    return OutboxValidationResult.Rejected(
                        reason = "Chat permission was revoked for your member account.",
                        conflictType = ConflictType.UNAUTHORIZED_ACTOR
                    )
                }
                if (msg != null && msg.senderUserId != authenticatedUid) {
                    return OutboxValidationResult.Rejected(
                        reason = "Only the message sender may edit this message.",
                        conflictType = ConflictType.UNAUTHORIZED_ACTOR
                    )
                }
                if (msg != null && (msg.messageText.isBlank() || msg.messageText.length > 1000)) {
                    return OutboxValidationResult.Rejected(
                        reason = "Chat message length violates security bounds (1..1000 chars).",
                        conflictType = ConflictType.UNAUTHORIZED_ACTOR
                    )
                }
                OutboxValidationResult.Valid
            }
            "transactions" -> {
                val tx = localStore.getTransactionById(item.documentId)
                validateFinancialMutation(
                    item = item,
                    family = family,
                    isAdmin = isAdmin,
                    canCreateFlag = member?.canCreateRecords ?: true,
                    authenticatedUid = authenticatedUid,
                    recordFamilyId = tx?.familyId,
                    recordCreatedBy = tx?.createdByUserId,
                    invariantOk = tx == null || tx.effectiveAmountMinor() in 1L..100_000_000_000L,
                    invariantMessage = "Transaction amount must be greater than zero."
                )
            }
            "loans" -> {
                val loan = localStore.getLoanById(item.documentId)
                validateFinancialMutation(
                    item = item,
                    family = family,
                    isAdmin = isAdmin,
                    canCreateFlag = member?.canCreateRecords ?: true,
                    authenticatedUid = authenticatedUid,
                    recordFamilyId = loan?.familyId,
                    recordCreatedBy = loan?.createdByUserId,
                    invariantOk = loan == null || (
                        loan.effectiveAmountMinor() in 1L..100_000_000_000L &&
                            loan.effectiveRemainingMinor() in 0L..loan.effectiveAmountMinor()
                        ),
                    invariantMessage = "Loan remaining balance cannot exceed principal."
                )
            }
            "savings" -> {
                val sav = localStore.getSavingsById(item.documentId)
                validateFinancialMutation(
                    item = item,
                    family = family,
                    isAdmin = isAdmin,
                    canCreateFlag = member?.canCreateRecords ?: true,
                    authenticatedUid = authenticatedUid,
                    recordFamilyId = sav?.familyId,
                    recordCreatedBy = sav?.createdByUserId,
                    invariantOk = sav == null || (
                        sav.effectiveTargetMinor() in 1L..100_000_000_000L &&
                            sav.effectiveCurrentMinor() >= 0L
                        ),
                    invariantMessage = "Savings target must be positive and balance non-negative."
                )
            }
            "investments" -> {
                val inv = localStore.getInvestmentById(item.documentId)
                validateFinancialMutation(
                    item = item,
                    family = family,
                    isAdmin = isAdmin,
                    canCreateFlag = member?.canCreateRecords ?: true,
                    authenticatedUid = authenticatedUid,
                    recordFamilyId = inv?.familyId,
                    recordCreatedBy = inv?.createdByUserId,
                    invariantOk = inv == null || (
                        inv.effectivePrincipalMinor() >= 0L && inv.effectiveCurrentValueMinor() >= 0L
                        ),
                    invariantMessage = "Investment principal and current value cannot be negative."
                )
            }
            "budgets" -> {
                val bud = localStore.getBudgetById(item.documentId)
                validateFinancialMutation(
                    item = item,
                    family = family,
                    isAdmin = isAdmin,
                    canCreateFlag = member?.canCreateRecords ?: true,
                    authenticatedUid = authenticatedUid,
                    recordFamilyId = bud?.familyId,
                    recordCreatedBy = bud?.createdByUserId,
                    invariantOk = bud == null || bud.effectiveLimitMinor() in 1L..100_000_000_000L,
                    invariantMessage = "Budget limit must be greater than zero."
                )
            }
            else -> OutboxValidationResult.Valid
        }
    }

    private fun validateFinancialMutation(
        item: LocalSyncOutboxEntity,
        family: LocalFamilyEntity,
        isAdmin: Boolean,
        canCreateFlag: Boolean,
        authenticatedUid: String,
        recordFamilyId: String?,
        recordCreatedBy: String?,
        invariantOk: Boolean,
        invariantMessage: String
    ): OutboxValidationResult {
        if (recordFamilyId != null && recordFamilyId != family.familyId) {
            return OutboxValidationResult.Rejected(
                reason = "Record ${item.documentId} belongs to another family ($recordFamilyId).",
                conflictType = ConflictType.CROSS_FAMILY_VIOLATION
            )
        }
        if (item.operation == "CREATE") {
            if (!isAdmin && (!family.membersCanAddExpenses || !canCreateFlag)) {
                return OutboxValidationResult.Rejected(
                    reason = "Record creation permission was restricted by your Family Admin.",
                    conflictType = ConflictType.UNAUTHORIZED_ACTOR
                )
            }
        } else {
            if (recordCreatedBy != null && recordCreatedBy != authenticatedUid && !isAdmin) {
                return OutboxValidationResult.Rejected(
                    reason = "Only the record creator or a Family Admin may modify ${item.documentId}.",
                    conflictType = ConflictType.UNAUTHORIZED_ACTOR
                )
            }
        }
        if (!invariantOk) {
            return OutboxValidationResult.Rejected(
                reason = invariantMessage,
                conflictType = ConflictType.UNAUTHORIZED_ACTOR
            )
        }
        return OutboxValidationResult.Valid
    }

    suspend fun synchronize(
        authenticatedUid: String,
        family: LocalFamilyEntity,
        policy: ConflictResolutionPolicy,
        isConnectivityOnline: Boolean
    ): SyncRunSummary {
        val pending = localStore.getPendingOutboxForFamily(family.familyId)
        if (!isConnectivityOnline) {
            return SyncRunSummary(
                familyId = family.familyId,
                connectivityOnline = false,
                pendingReadCount = pending.size
            )
        }

        val memberUids = (family.memberUids + authenticatedUid).distinct().take(20)
        val adminUids = family.adminUids.ifEmpty { listOf(authenticatedUid) }.distinct().take(20)

        var validatedCount = 0
        var rejectedCount = 0
        var syncedCount = 0
        var conflictsDetected = 0
        var deferredErrorCount = 0

        for (item in pending) {
            when (val validation = validateOutboxOperation(item, family.familyId, authenticatedUid)) {
                is OutboxValidationResult.Rejected -> {
                    rejectedCount++
                    localStore.quarantineInvalidOutbox(
                        outboxItem = item,
                        conflictType = validation.conflictType,
                        reason = validation.reason,
                        auditEntry = LocalAuditLogEntity(
                            auditId = DatabaseIdentityHelper.generateAuditId(),
                            familyId = family.familyId,
                            actorUserId = authenticatedUid,
                            entityCollection = item.collectionName,
                            entityId = item.documentId,
                            action = AuditAction.SYNC.name,
                            summary = "Quarantined invalid sync op: ${validation.reason}".take(240),
                            deviceId = item.deviceId,
                            syncStatus = SyncState.ERROR.name
                        )
                    )
                    continue
                }
                OutboxValidationResult.Valid -> validatedCount++
            }

            val outcome = pushOutboxItem(item, authenticatedUid, memberUids, adminUids)
            when (outcome) {
                is RemoteWriteOutcome.Success -> {
                    localStore.markOutboxSynced(
                        collectionName = item.collectionName,
                        documentId = item.documentId,
                        syncedAtMillis = System.currentTimeMillis(),
                        confirmedServerVersion = outcome.confirmedVersion
                    )
                    syncedCount++
                }
                is RemoteWriteOutcome.DuplicateIdempotent -> {
                    localStore.markOutboxSynced(
                        collectionName = item.collectionName,
                        documentId = item.documentId,
                        syncedAtMillis = System.currentTimeMillis(),
                        confirmedServerVersion = outcome.confirmedVersion
                    )
                    syncedCount++
                }
                is RemoteWriteOutcome.VersionConflict -> {
                    conflictsDetected++
                    handlePushConflict(
                        item = item,
                        authenticatedUid = authenticatedUid,
                        memberUids = memberUids,
                        adminUids = adminUids,
                        conflictType = ConflictType.VERSION_MISMATCH,
                        remoteVersion = outcome.remoteVersion,
                        remoteSummary = outcome.remoteSummary,
                        remotePayloadJson = outcome.remotePayloadJson,
                        policy = policy
                    )?.let { resolvedSuccess ->
                        if (resolvedSuccess) syncedCount++
                    }
                }
                is RemoteWriteOutcome.RemoteDeleted -> {
                    conflictsDetected++
                    handlePushConflict(
                        item = item,
                        authenticatedUid = authenticatedUid,
                        memberUids = memberUids,
                        adminUids = adminUids,
                        conflictType = ConflictType.REMOTE_DELETED,
                        remoteVersion = item.baseSyncVersion,
                        remoteSummary = outcome.remoteSummary,
                        remotePayloadJson = "",
                        policy = policy
                    )?.let { resolvedSuccess ->
                        if (resolvedSuccess) syncedCount++
                    }
                }
                is RemoteWriteOutcome.TransientFailure -> {
                    deferredErrorCount++
                    localStore.markOutboxFailed(item, outcome.message)
                }
            }
        }

        return SyncRunSummary(
            familyId = family.familyId,
            connectivityOnline = true,
            pendingReadCount = pending.size,
            validatedCount = validatedCount,
            rejectedCount = rejectedCount,
            syncedCount = syncedCount,
            conflictsDetected = conflictsDetected,
            deferredErrorCount = deferredErrorCount
        )
    }

    private suspend fun handlePushConflict(
        item: LocalSyncOutboxEntity,
        authenticatedUid: String,
        memberUids: List<String>,
        adminUids: List<String>,
        conflictType: ConflictType,
        remoteVersion: Int,
        remoteSummary: String,
        remotePayloadJson: String,
        policy: ConflictResolutionPolicy
    ): Boolean? {
        val conflictId = DatabaseIdentityHelper.generateConflictId()
        val (localSummary, localPayloadJson) = buildLocalConflictSnapshot(item.collectionName, item.documentId)
        val conflict = LocalSyncConflictEntity(
            conflictId = conflictId,
            familyId = item.familyId,
            collectionName = item.collectionName,
            documentId = item.documentId,
            operationId = item.operationId,
            conflictType = conflictType.name,
            localVersion = item.syncVersion,
            remoteVersion = remoteVersion,
            localSummary = localSummary,
            remoteSummary = remoteSummary,
            localPayloadJson = localPayloadJson,
            remotePayloadJson = remotePayloadJson,
            policy = policy.name,
            status = ConflictStatus.UNRESOLVED.name
        )
        val audit = LocalAuditLogEntity(
            auditId = DatabaseIdentityHelper.generateAuditId(),
            familyId = item.familyId,
            actorUserId = authenticatedUid,
            entityCollection = item.collectionName,
            entityId = item.documentId,
            action = AuditAction.SYNC.name,
            summary = "Sync conflict (${conflictType.label}) local v${item.syncVersion} vs cloud v$remoteVersion".take(240),
            deviceId = item.deviceId,
            syncStatus = SyncState.CONFLICT.name
        )

        return when (policy) {
            ConflictResolutionPolicy.MANUAL_REVIEW -> {
                localStore.recordSyncConflict(conflict, audit, removeFromOutbox = true)
                null
            }
            ConflictResolutionPolicy.SERVER_WINS -> {
                localStore.recordSyncConflict(conflict, audit, removeFromOutbox = false)
                localStore.resolveConflict(
                    conflictId = conflictId,
                    chosenPolicy = ConflictResolutionPolicy.SERVER_WINS,
                    actorUserId = authenticatedUid,
                    remoteVersionOverride = remoteVersion
                )
                false
            }
            ConflictResolutionPolicy.CLIENT_WINS -> {
                localStore.recordSyncConflict(conflict, audit, removeFromOutbox = false)
                localStore.resolveConflict(
                    conflictId = conflictId,
                    chosenPolicy = ConflictResolutionPolicy.CLIENT_WINS,
                    actorUserId = authenticatedUid,
                    remoteVersionOverride = remoteVersion
                )
                // Retry once immediately with bumped version matching remoteVersion
                val refreshed = localStore.getOutboxForDocument(item.collectionName, item.documentId) ?: return false
                when (val retryOutcome = pushOutboxItem(refreshed, authenticatedUid, memberUids, adminUids)) {
                    is RemoteWriteOutcome.Success -> {
                        localStore.markOutboxSynced(
                            collectionName = refreshed.collectionName,
                            documentId = refreshed.documentId,
                            syncedAtMillis = System.currentTimeMillis(),
                            confirmedServerVersion = retryOutcome.confirmedVersion
                        )
                        true
                    }
                    is RemoteWriteOutcome.DuplicateIdempotent -> {
                        localStore.markOutboxSynced(
                            collectionName = refreshed.collectionName,
                            documentId = refreshed.documentId,
                            syncedAtMillis = System.currentTimeMillis(),
                            confirmedServerVersion = retryOutcome.confirmedVersion
                        )
                        true
                    }
                    is RemoteWriteOutcome.TransientFailure -> {
                        localStore.markOutboxFailed(refreshed, retryOutcome.message)
                        false
                    }
                    else -> false
                }
            }
        }
    }

    private suspend fun buildLocalConflictSnapshot(
        collection: String,
        docId: String
    ): Pair<String, String> {
        return when (collection) {
            "transactions" -> localStore.getTransactionById(docId)
                ?.let { mapper.summarizeTransaction(it) to mapper.encodeTransactionSnapshot(it) }
                ?: ("Local transaction $docId" to "")
            "loans" -> localStore.getLoanById(docId)
                ?.let { mapper.summarizeLoan(it) to mapper.encodeLoanSnapshot(it) }
                ?: ("Local loan $docId" to "")
            "savings" -> localStore.getSavingsById(docId)
                ?.let { mapper.summarizeSavings(it) to mapper.encodeSavingsSnapshot(it) }
                ?: ("Local savings goal $docId" to "")
            "investments" -> localStore.getInvestmentById(docId)
                ?.let { mapper.summarizeInvestment(it) to mapper.encodeInvestmentSnapshot(it) }
                ?: ("Local investment $docId" to "")
            "budgets" -> localStore.getBudgetById(docId)
                ?.let { mapper.summarizeBudget(it) to mapper.encodeBudgetSnapshot(it) }
                ?: ("Local budget $docId" to "")
            else -> ("Local $collection/$docId" to "")
        }
    }

    private suspend fun pushOutboxItem(
        item: LocalSyncOutboxEntity,
        authenticatedUid: String,
        memberUids: List<String>,
        adminUids: List<String>
    ): RemoteWriteOutcome {
        val auditId = DatabaseIdentityHelper.generateAuditId()
        fun auditMap(action: AuditAction, summary: String, afterHash: String? = null): Map<String, Any> =
            AuditLogDoc(
                auditId = auditId,
                familyId = item.familyId,
                actorUserId = authenticatedUid,
                entityCollection = item.collectionName,
                entityId = item.documentId,
                action = action.name,
                summary = summary.take(240),
                afterHash = afterHash,
                deviceId = item.deviceId.takeIf { it.isNotBlank() },
                memberUids = memberUids,
                adminUids = adminUids
            ).toCreateMap()

        if (item.operation == "DELETE") {
            return gateway.executeDelete(
                familyId = item.familyId,
                collection = item.collectionName,
                docId = item.documentId,
                expectedBaseVersion = item.baseSyncVersion,
                auditId = auditId,
                auditPayload = auditMap(AuditAction.DELETE, "Synchronized offline deletion (${item.collectionName})")
            )
        }

        return when (item.collectionName) {
            "transactions" -> {
                val tx = localStore.getTransactionById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(item.syncVersion)
                val doc = tx.toFirestoreDoc(memberUids, adminUids)
                if (item.operation == "UPDATE") {
                    gateway.executeUpdateVersionChecked(
                        familyId = tx.familyId,
                        collection = "transactions",
                        docId = tx.recordId,
                        expectedBaseVersion = item.baseSyncVersion,
                        nextVersion = tx.syncVersion,
                        payload = doc.toUpdateMap(tx.occurredAtMillis, tx.nextRecurrenceAtMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline update (${tx.category})", item.payloadHash)
                    )
                } else {
                    gateway.executeCreate(
                        familyId = tx.familyId,
                        collection = "transactions",
                        docId = tx.recordId,
                        idempotencyKey = tx.idempotencyKey,
                        payload = doc.toCreateMap(tx.occurredAtMillis, tx.nextRecurrenceAtMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline transaction (${tx.category})", item.payloadHash)
                    )
                }
            }
            "loans" -> {
                val loan = localStore.getLoanById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(item.syncVersion)
                val doc = loan.toFirestoreDoc(memberUids, adminUids)
                if (item.operation == "UPDATE") {
                    gateway.executeUpdateVersionChecked(
                        familyId = loan.familyId,
                        collection = "loans",
                        docId = loan.recordId,
                        expectedBaseVersion = item.baseSyncVersion,
                        nextVersion = loan.syncVersion,
                        payload = doc.toUpdateMap(loan.dueDateMillis, loan.startDateMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline loan update (${loan.counterparty})", item.payloadHash)
                    )
                } else {
                    gateway.executeCreate(
                        familyId = loan.familyId,
                        collection = "loans",
                        docId = loan.recordId,
                        idempotencyKey = loan.idempotencyKey,
                        payload = doc.toCreateMap(loan.dueDateMillis, loan.startDateMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline loan (${loan.counterparty})", item.payloadHash)
                    )
                }
            }
            "savings" -> {
                val sav = localStore.getSavingsById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(item.syncVersion)
                val doc = sav.toFirestoreDoc(memberUids, adminUids)
                if (item.operation == "UPDATE") {
                    gateway.executeUpdateVersionChecked(
                        familyId = sav.familyId,
                        collection = "savings",
                        docId = sav.recordId,
                        expectedBaseVersion = item.baseSyncVersion,
                        nextVersion = sav.syncVersion,
                        payload = doc.toUpdateMap(sav.targetDateMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline savings update (${sav.goalName})", item.payloadHash)
                    )
                } else {
                    gateway.executeCreate(
                        familyId = sav.familyId,
                        collection = "savings",
                        docId = sav.recordId,
                        idempotencyKey = sav.idempotencyKey,
                        payload = doc.toCreateMap(sav.targetDateMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline savings goal (${sav.goalName})", item.payloadHash)
                    )
                }
            }
            "investments" -> {
                val inv = localStore.getInvestmentById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(item.syncVersion)
                val doc = inv.toFirestoreDoc(memberUids, adminUids)
                if (item.operation == "UPDATE") {
                    gateway.executeUpdateVersionChecked(
                        familyId = inv.familyId,
                        collection = "investments",
                        docId = inv.recordId,
                        expectedBaseVersion = item.baseSyncVersion,
                        nextVersion = inv.syncVersion,
                        payload = doc.toUpdateMap(inv.investedAtMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline investment update (${inv.assetName})", item.payloadHash)
                    )
                } else {
                    gateway.executeCreate(
                        familyId = inv.familyId,
                        collection = "investments",
                        docId = inv.recordId,
                        idempotencyKey = inv.idempotencyKey,
                        payload = doc.toCreateMap(inv.investedAtMillis),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline investment (${inv.assetName})", item.payloadHash)
                    )
                }
            }
            "budgets" -> {
                val bud = localStore.getBudgetById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(item.syncVersion)
                val doc = bud.toFirestoreDoc(memberUids, adminUids)
                if (item.operation == "UPDATE") {
                    gateway.executeUpdateVersionChecked(
                        familyId = bud.familyId,
                        collection = "budgets",
                        docId = bud.recordId,
                        expectedBaseVersion = item.baseSyncVersion,
                        nextVersion = bud.syncVersion,
                        payload = doc.toUpdateMap(),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline budget update (${bud.category})", item.payloadHash)
                    )
                } else {
                    gateway.executeCreate(
                        familyId = bud.familyId,
                        collection = "budgets",
                        docId = bud.recordId,
                        idempotencyKey = bud.idempotencyKey,
                        payload = doc.toCreateMap(),
                        auditId = auditId,
                        auditPayload = auditMap(AuditAction.SYNC, "Synced offline budget (${bud.category})", item.payloadHash)
                    )
                }
            }
            "financialEvents" -> {
                val evt = localStore.getFinancialEventById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(1)
                gateway.executeCreate(
                    familyId = evt.familyId,
                    collection = "financialEvents",
                    docId = evt.eventId,
                    idempotencyKey = "",
                    payload = evt.toFirestoreDoc(memberUids).toCreateMap(evt.eventDateMillis),
                    auditId = null,
                    auditPayload = null
                )
            }
            "notifications" -> {
                val ntf = localStore.getNotificationById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(1)
                if (item.operation == "UPDATE") {
                    gateway.executeFieldUpdate(
                        familyId = ntf.familyId,
                        collection = "notifications",
                        docId = ntf.notificationId,
                        fields = mapOf(
                            "acknowledged" to ntf.acknowledged,
                            "readByUids" to ntf.readByUids.distinct().take(20),
                            "recordStatus" to RecordStatus.fromRaw(ntf.recordStatus).name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    val payload = FamilyNotificationDoc(
                        notificationId = ntf.notificationId,
                        familyId = ntf.familyId,
                        recipientUserId = ntf.recipientUserId,
                        createdByUserId = ntf.createdByUserId,
                        title = ntf.title,
                        body = ntf.body,
                        notificationType = ntf.notificationType,
                        relatedRecordId = ntf.relatedRecordId,
                        deepLinkModule = ntf.deepLinkModule,
                        acknowledged = ntf.acknowledged,
                        readByUids = ntf.readByUids,
                        recordStatus = ntf.recordStatus,
                        memberUids = memberUids
                    ).toCreateMap()
                    gateway.executeCreate(
                        familyId = ntf.familyId,
                        collection = "notifications",
                        docId = ntf.notificationId,
                        idempotencyKey = "",
                        payload = payload,
                        auditId = null,
                        auditPayload = null
                    )
                }
            }
            "chats" -> {
                val msg = localStore.getChatMessageById(item.documentId)
                    ?: return RemoteWriteOutcome.Success(1)
                when (item.operation) {
                    "READ_RECEIPT" -> {
                        val readerUids = msg.readByUids.ifEmpty { listOf(authenticatedUid) }.distinct().take(20)
                        gateway.executeFieldUpdate(
                            familyId = msg.familyId,
                            collection = "chats",
                            docId = msg.messageId,
                            fields = mapOf(
                                "readByUids" to FieldValue.arrayUnion(*readerUids.toTypedArray()),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    "UPDATE" -> {
                        val payload = if (msg.recordStatus in setOf(RecordStatus.VOIDED.name, RecordStatus.ARCHIVED.name)) {
                            mapOf<String, Any>(
                                "messageText" to "[Message deleted]",
                                "recordStatus" to RecordStatus.fromRaw(msg.recordStatus).name,
                                "deletedAt" to Timestamp(Date(msg.deletedAtMillis ?: System.currentTimeMillis())),
                                "deletedByUserId" to (msg.deletedByUserId ?: authenticatedUid),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        } else {
                            mapOf<String, Any>(
                                "messageText" to msg.messageText.trim().take(1000),
                                "editedAt" to Timestamp(Date(msg.editedAtMillis ?: System.currentTimeMillis())),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        }
                        gateway.executeUpdateVersionChecked(
                            familyId = msg.familyId,
                            collection = "chats",
                            docId = msg.messageId,
                            expectedBaseVersion = item.baseSyncVersion,
                            nextVersion = msg.syncVersion,
                            payload = payload,
                            auditId = auditId,
                            auditPayload = auditMap(
                                AuditAction.SYNC,
                                if (msg.isVoided) "Synced deleted message" else "Synced edited message"
                            )
                        )
                    }
                    else -> {
                        val payload = ChatMessageDoc(
                            messageId = msg.messageId,
                            familyId = msg.familyId,
                            senderUserId = msg.senderUserId,
                            senderDisplayName = msg.senderDisplayName,
                            senderPhotoUrl = msg.senderPhotoUrl,
                            senderRoleLabel = msg.senderRoleLabel.takeIf { it.isNotBlank() },
                            messageText = msg.messageText,
                            messageType = ChatMessageType.fromRaw(msg.messageType).name,
                            relatedRecordId = msg.relatedRecordId,
                            recordStatus = msg.recordStatus,
                            readByUids = msg.readByUids.ifEmpty { listOf(msg.senderUserId) },
                            syncVersion = msg.syncVersion.toLong().coerceAtLeast(1L),
                            memberUids = memberUids
                        ).toCreateMap(expiresAtMillis = msg.expiresAtMillis)
                        gateway.executeCreate(
                            familyId = msg.familyId,
                            collection = "chats",
                            docId = msg.messageId,
                            idempotencyKey = "",
                            payload = payload,
                            auditId = null,
                            auditPayload = null
                        )
                    }
                }
            }
            else -> RemoteWriteOutcome.Success(item.syncVersion)
        }
    }
}

internal fun LocalTransactionEntity.toFirestoreDoc(
    memberUids: List<String>,
    adminUids: List<String>
): TransactionDoc = TransactionDoc(
    recordId = recordId,
    transactionId = recordId,
    familyId = familyId,
    createdByUserId = createdByUserId,
    memberId = effectiveMemberId(),
    amount = MoneyCalculator.minorUnitsToDouble(effectiveAmountMinor(), currency),
    amountMinor = effectiveAmountMinor(),
    currency = currency,
    category = category,
    description = description,
    recordStatus = recordStatus,
    transactionType = transactionType,
    paymentMethod = paymentMethod,
    recurrenceFrequency = recurrenceFrequency,
    recurringParentId = recurringParentId,
    isRecurringTemplate = isRecurringTemplate,
    idempotencyKey = idempotencyKey,
    syncVersion = syncVersion.toLong(),
    deviceId = deviceId,
    memberUids = memberUids,
    adminUids = adminUids
)

internal fun TransactionDoc.toLocalEntity(fallbackFamilyId: String): LocalTransactionEntity {
    val recId = recordId.ifBlank { id }
    val resolvedMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)
    val ver = syncVersion.toInt().coerceAtLeast(1)
    return LocalTransactionEntity(
        recordId = recId,
        familyId = familyId.ifBlank { fallbackFamilyId },
        createdByUserId = createdByUserId,
        memberId = memberId.ifBlank { createdByUserId },
        amount = MoneyCalculator.minorUnitsToDouble(resolvedMinor, currency),
        amountMinor = resolvedMinor,
        currency = currency,
        category = category,
        description = description,
        recordStatus = RecordStatus.fromRaw(recordStatus).name,
        transactionType = TransactionType.fromRaw(transactionType).name,
        paymentMethod = paymentMethod,
        occurredAtMillis = occurredAt?.toDate()?.time ?: System.currentTimeMillis(),
        recurrenceFrequency = RecurrenceFrequency.fromRaw(recurrenceFrequency).name,
        nextRecurrenceAtMillis = nextRecurrenceAt?.toDate()?.time,
        recurringParentId = recurringParentId,
        isRecurringTemplate = isRecurringTemplate,
        idempotencyKey = idempotencyKey.ifBlank { recId },
        syncStatus = SyncState.SYNCED.name,
        syncVersion = ver,
        baseSyncVersion = ver,
        deviceId = deviceId ?: "",
        lastSyncedAtMillis = System.currentTimeMillis(),
        createdAtMillis = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtMillis = updatedAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}

internal fun LoanDoc.toLocalEntity(fallbackFamilyId: String): LocalLoanEntity {
    val recId = recordId.ifBlank { id }
    val principalMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency)
    val remainingMinor = (if (remainingBalanceMinor > 0L || remainingBalance == 0.0) {
        remainingBalanceMinor
    } else {
        MoneyCalculator.doubleToMinorUnits(remainingBalance, currency)
    }).coerceIn(0L, maxOf(principalMinor, 0L))
    val totalInst = totalInstallments.toInt().coerceIn(1, 600)
    val paidInst = paidInstallments.toInt().coerceIn(0, totalInst)
    val instMinor = if (installmentAmountMinor > 0L) installmentAmountMinor else if (principalMinor > 0L) principalMinor / totalInst else 0L
    val ver = syncVersion.toInt().coerceAtLeast(1)
    return LocalLoanEntity(
        recordId = recId,
        familyId = familyId.ifBlank { fallbackFamilyId },
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(principalMinor, currency),
        amountMinor = principalMinor,
        remainingBalance = MoneyCalculator.minorUnitsToDouble(remainingMinor, currency),
        remainingBalanceMinor = remainingMinor,
        interestRateApr = interestRateApr,
        currency = currency,
        category = category,
        description = description,
        recordStatus = RecordStatus.fromRaw(recordStatus).name,
        loanDirection = LoanDirection.fromRaw(loanDirection).name,
        counterparty = counterparty,
        borrowerName = borrowerName,
        borrowerContact = borrowerContact,
        lenderName = lenderName,
        lenderContact = lenderContact,
        totalInstallments = totalInst,
        paidInstallments = paidInst,
        installmentAmountMinor = instMinor,
        startDateMillis = startDate?.toDate()?.time ?: createdAt?.toDate()?.time,
        dueDateMillis = dueDate?.toDate()?.time,
        idempotencyKey = idempotencyKey.ifBlank { recId },
        syncStatus = SyncState.SYNCED.name,
        syncVersion = ver,
        baseSyncVersion = ver,
        deviceId = deviceId ?: "",
        lastSyncedAtMillis = System.currentTimeMillis(),
        createdAtMillis = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtMillis = updatedAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}

internal fun SavingsDoc.toLocalEntity(fallbackFamilyId: String): LocalSavingsEntity {
    val recId = recordId.ifBlank { id }
    val currentMinor = if (amountMinor > 0L || amount == 0.0) amountMinor.coerceAtLeast(0L) else MoneyCalculator.doubleToMinorUnits(amount, currency)
    val targetMinor = if (targetAmountMinor > 0L) targetAmountMinor else MoneyCalculator.doubleToMinorUnits(targetAmount, currency).coerceAtLeast(1L)
    val ver = syncVersion.toInt().coerceAtLeast(1)
    return LocalSavingsEntity(
        recordId = recId,
        familyId = familyId.ifBlank { fallbackFamilyId },
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(currentMinor, currency),
        amountMinor = currentMinor,
        targetAmount = MoneyCalculator.minorUnitsToDouble(targetMinor, currency),
        targetAmountMinor = targetMinor,
        currency = currency,
        category = category,
        description = description,
        goalName = goalName,
        recordStatus = RecordStatus.fromRaw(recordStatus).name,
        targetDateMillis = targetDate?.toDate()?.time,
        idempotencyKey = idempotencyKey.ifBlank { recId },
        syncStatus = SyncState.SYNCED.name,
        syncVersion = ver,
        baseSyncVersion = ver,
        deviceId = deviceId ?: "",
        lastSyncedAtMillis = System.currentTimeMillis(),
        createdAtMillis = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtMillis = updatedAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}

internal fun InvestmentDoc.toLocalEntity(fallbackFamilyId: String): LocalInvestmentEntity {
    val recId = recordId.ifBlank { id }
    val principalMinor = if (amountMinor > 0L || amount == 0.0) amountMinor.coerceAtLeast(0L) else MoneyCalculator.doubleToMinorUnits(amount, currency)
    val currentMinor = if (currentValueMinor > 0L || currentValue == 0.0) currentValueMinor.coerceAtLeast(0L) else MoneyCalculator.doubleToMinorUnits(currentValue, currency)
    val investedMillis = investedAt?.toDate()?.time ?: createdAt?.toDate()?.time ?: System.currentTimeMillis()
    val ver = syncVersion.toInt().coerceAtLeast(1)
    return LocalInvestmentEntity(
        recordId = recId,
        familyId = familyId.ifBlank { fallbackFamilyId },
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(principalMinor, currency),
        amountMinor = principalMinor,
        currentValue = MoneyCalculator.minorUnitsToDouble(currentMinor, currency),
        currentValueMinor = currentMinor,
        units = units,
        currency = currency,
        category = category,
        description = description,
        assetName = assetName,
        assetType = InvestmentAssetType.fromRaw(assetType).name,
        symbol = symbol,
        investedAtMillis = investedMillis,
        recordStatus = RecordStatus.fromRaw(recordStatus).name,
        idempotencyKey = idempotencyKey.ifBlank { recId },
        syncStatus = SyncState.SYNCED.name,
        syncVersion = ver,
        baseSyncVersion = ver,
        deviceId = deviceId ?: "",
        lastSyncedAtMillis = System.currentTimeMillis(),
        createdAtMillis = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtMillis = updatedAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}

internal fun BudgetDoc.toLocalEntity(fallbackFamilyId: String): LocalBudgetEntity {
    val recId = recordId.ifBlank { id }
    val limitMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, currency).coerceAtLeast(1L)
    val spentMinor = if (spentAmountMinor > 0L || spentAmount == 0.0) spentAmountMinor.coerceAtLeast(0L) else MoneyCalculator.doubleToMinorUnits(spentAmount, currency)
    val ver = syncVersion.toInt().coerceAtLeast(1)
    return LocalBudgetEntity(
        recordId = recId,
        familyId = familyId.ifBlank { fallbackFamilyId },
        createdByUserId = createdByUserId,
        amount = MoneyCalculator.minorUnitsToDouble(limitMinor, currency),
        amountMinor = limitMinor,
        spentAmount = MoneyCalculator.minorUnitsToDouble(spentMinor, currency),
        spentAmountMinor = spentMinor,
        alertThresholdPercent = alertThresholdPercent.toInt().coerceIn(10, 100),
        currency = currency,
        category = category,
        description = description,
        periodKey = periodKey,
        budgetScope = BudgetScope.fromRaw(budgetScope).name,
        recordStatus = RecordStatus.fromRaw(recordStatus).name,
        idempotencyKey = idempotencyKey.ifBlank { recId },
        syncStatus = SyncState.SYNCED.name,
        syncVersion = ver,
        baseSyncVersion = ver,
        deviceId = deviceId ?: "",
        lastSyncedAtMillis = System.currentTimeMillis(),
        createdAtMillis = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtMillis = updatedAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}
