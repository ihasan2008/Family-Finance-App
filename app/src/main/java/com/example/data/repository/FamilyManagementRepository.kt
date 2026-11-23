package com.example.data.repository

import com.example.core.firebase.AuthService
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.FirestoreService
import com.example.core.local.FamilyDao
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.FamilyMemberDao
import com.example.core.local.LocalMyJoinRequestEntity
import com.example.core.local.MyJoinRequestDao
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.FamilyInvitationDoc
import com.example.core.model.FamilyInviteLookupDoc
import com.example.core.model.FamilyJoinRequestDoc
import com.example.core.model.FamilyMemberDoc
import com.example.core.model.FamilyNotificationDoc
import com.example.core.model.FamilyRole
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.InvitationStatus
import com.example.core.model.JoinRequestStatus
import com.example.core.model.NotificationType
import com.example.core.model.RecordStatus
import com.example.core.model.RecoveredFamilySummary
import com.example.core.model.RequestJoinResponse
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
import kotlinx.coroutines.tasks.await

class FamilyManagementRepository(
    private val firestoreService: FirestoreService,
    private val localDb: FamilyFinanceDatabase,
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth
) {
    private val db: FirebaseFirestore get() = firestoreService.db
    private val familyDao: FamilyDao = localDb.familyDao()
    private val memberDao: FamilyMemberDao = localDb.familyMemberDao()
    private val myJoinRequestDao: MyJoinRequestDao = localDb.myJoinRequestDao()

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required.")
    }

    fun observeMyJoinRequests(userId: String): Flow<List<LocalMyJoinRequestEntity>> =
        myJoinRequestDao.observeJoinRequestsForUser(userId)

    fun observeFamilyInvitations(userId: String, familyId: String): Flow<List<FamilyInvitationDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user invitation read." }
        val path = "families/$familyId/invitations"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("families").document(familyId).collection("invitations")
                    .orderBy("createdAt", Query.Direction.DESCENDING),
                clazz = FamilyInvitationDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    fun observePendingJoinRequests(userId: String, familyId: String): Flow<List<FamilyJoinRequestDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user join request read." }
        val path = "families/$familyId/join_requests"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("families").document(familyId).collection("join_requests")
                    .orderBy("requestedAt", Query.Direction.DESCENDING),
                clazz = FamilyJoinRequestDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    suspend fun createFamilyInvitation(
        familyId: String,
        familyName: String,
        targetRole: FamilyRole,
        inviteeHint: String?,
        expiresInHours: Int,
        maxUses: Int,
        authService: AuthService
    ): Result<CreateInvitationResponse> {
        val uid = requireUserId()
        val safeHours = expiresInHours.coerceIn(1, 168)
        val safeUses = maxUses.coerceIn(1, 10)
        val cleanHint = inviteeHint?.trim()?.take(80)?.takeIf { it.isNotEmpty() }

        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.createFamilyInvitation(
                familyId = familyId,
                targetRole = targetRole.name,
                inviteeHint = cleanHint,
                expiresInHours = safeHours,
                maxUses = safeUses
            )
        }

        val path = "families/$familyId/invitations"
        return try {
            val generated = FamilySecurityHelper.generateInvitationToken()
            val invitationId = FamilySecurityHelper.generateInvitationId()
            val expiresAtMillis = System.currentTimeMillis() + safeHours * 60L * 60L * 1000L

            val lookupPayload = FamilyInviteLookupDoc(
                tokenHash = generated.tokenHash,
                invitationId = invitationId,
                familyId = familyId,
                familyName = familyName,
                createdByUid = uid,
                targetRole = targetRole.name,
                inviteeHint = cleanHint,
                status = InvitationStatus.ACTIVE.name,
                maxUses = safeUses.toLong(),
                usedCount = 0L
            ).toCreateMap(expiresAtMillis)

            val invitationPayload = FamilyInvitationDoc(
                invitationId = invitationId,
                familyId = familyId,
                tokenHash = generated.tokenHash,
                token = generated.token,
                tokenPreview = generated.tokenPreview,
                targetRole = targetRole.name,
                inviteeHint = cleanHint,
                status = InvitationStatus.ACTIVE.name,
                maxUses = safeUses.toLong(),
                usedCount = 0L,
                createdByUid = uid
            ).toCreateMap(expiresAtMillis)

            val batchResult = firestoreService.commitBatch(path) { batch ->
                batch.set(db.collection("family_invites").document(generated.tokenHash), lookupPayload)
                batch.set(
                    db.collection("families").document(familyId).collection("invitations").document(invitationId),
                    invitationPayload
                )
            }
            if (batchResult.isFailure) {
                return Result.failure(batchResult.exceptionOrNull() ?: IllegalStateException("Failed to create invitation."))
            }

            runCatching {
                val memberUids = (familyDao.getFamilyById(familyId)?.memberUids.orEmpty() + uid).distinct().take(20)
                val notifId = DatabaseIdentityHelper.generateRecordId("ntf")
                val ntfPayload = FamilyNotificationDoc(
                    notificationId = notifId,
                    familyId = familyId,
                    recipientUserId = "ALL_MEMBERS",
                    createdByUserId = uid,
                    title = "Family Invitation Created",
                    body = "Invite ${generated.tokenPreview} created for ${targetRole.label}${cleanHint?.let { " ($it)" } ?: ""}.",
                    notificationType = NotificationType.FAMILY_INVITATION.name,
                    relatedRecordId = invitationId,
                    deepLinkModule = "FAMILY_MANAGEMENT",
                    acknowledged = false,
                    readByUids = listOf(uid),
                    recordStatus = RecordStatus.ACTIVE.name,
                    memberUids = memberUids
                ).toCreateMap()
                firestoreService.setDocument(db.collection("notifications").document(notifId), ntfPayload, "notifications/$notifId")
            }

            Result.success(
                CreateInvitationResponse(
                    ok = true,
                    invitationId = invitationId,
                    token = generated.token,
                    tokenPreview = generated.tokenPreview,
                    expiresAtMillis = expiresAtMillis
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun revokeInvitation(
        familyId: String,
        invitationId: String,
        tokenHash: String,
        authService: AuthService
    ): Result<Unit> {
        requireUserId()
        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.revokeFamilyInvitation(familyId, invitationId).map { Unit }
        }
        val path = "families/$familyId/invitations/$invitationId"
        return firestoreService.commitBatch(path) { batch ->
            batch.update(
                db.collection("families").document(familyId).collection("invitations").document(invitationId),
                mapOf(
                    "status" to InvitationStatus.REVOKED.name,
                    "token" to FieldValue.delete(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (tokenHash.isNotBlank()) {
                batch.update(
                    db.collection("family_invites").document(tokenHash),
                    mapOf(
                        "status" to InvitationStatus.REVOKED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
    }

    suspend fun requestJoinWithInvitation(
        rawInvitationToken: String,
        relationLabel: String,
        authService: AuthService
    ): Result<RequestJoinResponse> {
        val uid = requireUserId()
        val normalizedToken = FamilySecurityHelper.normalizeInvitationToken(rawInvitationToken)
        if (normalizedToken.startsWith("FAM_") || FamilySecurityHelper.isValidFamilyId12(normalizedToken)) {
            return Result.failure(
                IllegalArgumentException("Use the Family ID + Family Password tab to join with a 12-char Family ID, or enter an FFINV-... invitation token.")
            )
        }
        if (normalizedToken.length !in 12..64) {
            return Result.failure(
                IllegalArgumentException("Enter a valid invitation token (12–64 characters, e.g. FFINV-...).")
            )
        }

        val cleanRelation = relationLabel.trim().take(40).ifBlank { "Family Member" }
        val userDisplay = auth.currentUser?.displayName?.trim()?.take(80)?.ifBlank { null }
            ?: auth.currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
            ?: "Family Member"

        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.requestJoinWithInvitation(
                invitationToken = normalizedToken,
                relationLabel = cleanRelation,
                displayName = userDisplay
            ).onSuccess { resp ->
                myJoinRequestDao.upsertJoinRequest(
                    LocalMyJoinRequestEntity(
                        familyId = resp.familyId,
                        userId = uid,
                        familyName = resp.familyName,
                        tokenPreview = normalizedToken.take(10) + "••••",
                        relationLabel = cleanRelation,
                        status = JoinRequestStatus.PENDING_APPROVAL.name
                    )
                )
            }
        }

        val tokenHash = FamilySecurityHelper.hashInvitationToken(normalizedToken)
        val lookupPath = "family_invites/$tokenHash"
        return try {
            val inviteSnap = db.collection("family_invites").document(tokenHash).get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(IllegalArgumentException("Invitation token is invalid or unrecognized."))
            }
            val invite = inviteSnap.toObject(FamilyInviteLookupDoc::class.java)
                ?: return Result.failure(IllegalArgumentException("Unable to read invitation details."))

            if (invite.status != InvitationStatus.ACTIVE.name) {
                return Result.failure(IllegalStateException("This invitation is ${invite.status.lowercase()}."))
            }
            val expMillis = invite.expiresAt?.toDate()?.time ?: 0L
            if (expMillis <= System.currentTimeMillis()) {
                return Result.failure(IllegalStateException("This invitation token has expired. Ask the Family Admin for a fresh token."))
            }
            if (invite.usedCount >= invite.maxUses) {
                return Result.failure(IllegalStateException("This invitation token has reached its usage limit."))
            }

            val contactSummary = auth.currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
                ?: auth.currentUser?.email?.takeIf { it.isNotBlank() }
                ?: uid.take(12)

            val requestDocRef = db.collection("families").document(invite.familyId)
                .collection("join_requests").document(uid)

            val existingReq = runCatching { requestDocRef.get().await() }.getOrNull()
            if (existingReq != null && existingReq.exists()) {
                val status = existingReq.getString("status")
                if (status == JoinRequestStatus.PENDING_APPROVAL.name) {
                    return Result.success(
                        RequestJoinResponse(
                            ok = true,
                            familyId = invite.familyId,
                            familyName = invite.familyName,
                            status = JoinRequestStatus.PENDING_APPROVAL.name
                        )
                    )
                }
            }

            val payload = FamilyJoinRequestDoc(
                userId = uid,
                familyId = invite.familyId,
                displayName = userDisplay,
                contactSummary = contactSummary,
                relationLabel = cleanRelation,
                invitationId = invite.invitationId,
                tokenHash = tokenHash,
                requestedRole = invite.targetRole
            ).toCreateMap()

            val writeResult = firestoreService.setDocument(requestDocRef, payload, requestDocRef.path)
            if (writeResult.isFailure) {
                return Result.failure(writeResult.exceptionOrNull() ?: IllegalStateException("Unable to submit join request."))
            }

            myJoinRequestDao.upsertJoinRequest(
                LocalMyJoinRequestEntity(
                    familyId = invite.familyId,
                    userId = uid,
                    familyName = invite.familyName,
                    tokenPreview = normalizedToken.take(10) + "••••",
                    relationLabel = cleanRelation,
                    status = JoinRequestStatus.PENDING_APPROVAL.name
                )
            )

            Result.success(
                RequestJoinResponse(
                    ok = true,
                    familyId = invite.familyId,
                    familyName = invite.familyName,
                    status = JoinRequestStatus.PENDING_APPROVAL.name
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, lookupPath)
            Result.failure(e)
        }
    }

    suspend fun requestJoinByFamilyPassword(
        rawFamilyId: String,
        familyPassword: String,
        relationLabel: String,
        authService: AuthService
    ): Result<RequestJoinResponse> {
        val uid = requireUserId()
        val cleanFamilyId = FamilySecurityHelper.normalizeFamilyIdInput(rawFamilyId)
        if (!FamilySecurityHelper.isValidFamilyId12(cleanFamilyId)) {
            return Result.failure(IllegalArgumentException("Enter a valid 12-character Family ID (letters and numbers)."))
        }
        if (familyPassword.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter the Family Password shared by the Family Admin."))
        }
        val cleanRelation = relationLabel.trim().take(40).ifBlank { "Family Member" }
        val userDisplay = auth.currentUser?.displayName?.trim()?.take(80)?.ifBlank { null }
            ?: auth.currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
            ?: "Family Member"

        return authService.functionsClient.requestJoinByFamilyPassword(
            familyId = cleanFamilyId,
            familyPassword = familyPassword,
            relationLabel = cleanRelation,
            displayName = userDisplay
        ).onSuccess { resp ->
            myJoinRequestDao.upsertJoinRequest(
                LocalMyJoinRequestEntity(
                    familyId = resp.familyId,
                    userId = uid,
                    familyName = resp.familyName,
                    tokenPreview = "ID ${cleanFamilyId.take(4)}••••",
                    relationLabel = cleanRelation,
                    status = JoinRequestStatus.PENDING_APPROVAL.name
                )
            )
        }
    }

    suspend fun setMemberSuspension(
        familyId: String,
        targetUid: String,
        suspend: Boolean,
        authService: AuthService
    ): Result<Unit> {
        requireUserId()
        return authService.functionsClient.setMemberSuspension(familyId, targetUid, suspend).map {
            memberDao.getMember(familyId, targetUid)?.let { local ->
                memberDao.upsertMember(
                    local.copy(
                        memberStatus = if (suspend) "SUSPENDED" else "ACTIVE",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun changeFamilyPassword(
        familyId: String,
        currentFamilyPassword: String,
        newFamilyPassword: String,
        authService: AuthService
    ): Result<Unit> {
        requireUserId()
        if (currentFamilyPassword.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter the current Family Password."))
        }
        if (!com.example.core.model.AuthValidation.evaluateFamilyPasswordStrength(newFamilyPassword).isAcceptable) {
            return Result.failure(IllegalArgumentException("New Family Password must be 8+ characters with upper, lower, digit, and symbol."))
        }
        return authService.functionsClient.changeFamilyPassword(
            familyId = familyId,
            currentFamilyPassword = currentFamilyPassword,
            newFamilyPassword = newFamilyPassword
        ).map { Unit }
    }

    suspend fun refreshJoinRequestStatuses(userId: String, currentRequests: List<LocalMyJoinRequestEntity>) {
        val uid = auth.currentUser?.uid ?: return
        if (uid != userId) return
        for (req in currentRequests) {
            if (req.status == JoinRequestStatus.PENDING_APPROVAL.name) {
                runCatching {
                    val snap = db.collection("families").document(req.familyId)
                        .collection("join_requests").document(uid).get().await()
                    if (snap.exists()) {
                        val remoteStatus = snap.getString("status") ?: req.status
                        if (remoteStatus != req.status) {
                            myJoinRequestDao.upsertJoinRequest(
                                req.copy(status = remoteStatus, updatedAtMillis = System.currentTimeMillis())
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun reviewJoinRequest(
        familyId: String,
        requesterUid: String,
        approve: Boolean,
        authService: AuthService
    ): Result<Unit> {
        val adminUid = requireUserId()
        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.reviewJoinRequest(familyId, requesterUid, approve).map { Unit }
        }
        val path = "families/$familyId/join_requests/$requesterUid"
        return try {
            val famRef = db.collection("families").document(familyId)
            val reqRef = famRef.collection("join_requests").document(requesterUid)

            val reqSnap = reqRef.get().await()
            if (!reqSnap.exists()) {
                return Result.failure(IllegalStateException("Join request no longer exists."))
            }
            val reqData = reqSnap.toObject(FamilyJoinRequestDoc::class.java)
                ?: return Result.failure(IllegalStateException("Invalid join request data."))

            if (!approve) {
                return firestoreService.updateDocument(
                    docRef = reqRef,
                    updates = mapOf(
                        "status" to JoinRequestStatus.REJECTED.name,
                        "reviewedByUid" to adminUid,
                        "reviewedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    pathForLogs = path
                )
            }

            val famSnap = famRef.get().await()
            val currentMembers = (famSnap.get("memberUids") as? List<*>)?.mapNotNull { it as? String } ?: listOf(adminUid)
            val currentAdmins = (famSnap.get("adminUids") as? List<*>)?.mapNotNull { it as? String } ?: listOf(adminUid)
            if (currentMembers.size >= 20 && requesterUid !in currentMembers) {
                return Result.failure(IllegalStateException("Family has reached maximum member limit (20)."))
            }

            val assignedRole = FamilyRole.fromRaw(reqData.requestedRole)
            val nextMembers = (currentMembers + requesterUid).distinct()
            val nextAdmins = if (assignedRole == FamilyRole.ADMIN) {
                (currentAdmins + requesterUid).distinct()
            } else {
                currentAdmins
            }
            val memberChatEnabled = famSnap.getBoolean("memberChatEnabled") ?: true
            val membersCanAddExpenses = famSnap.getBoolean("membersCanAddExpenses") ?: true

            val existingMemberDocs = famRef.collection("members")
                .whereArrayContains("memberUids", adminUid)
                .get().await()

            val lookupRef = db.collection("family_invites").document(reqData.tokenHash)
            val lookupSnap = runCatching { lookupRef.get().await() }.getOrNull()
            val currentUsed = (lookupSnap?.get("usedCount") as? Number)?.toLong() ?: 0L
            val maxUses = (lookupSnap?.get("maxUses") as? Number)?.toLong() ?: 1L
            val nextUsed = (currentUsed + 1L).coerceAtMost(maxUses)
            val nextInviteStatus = if (nextUsed >= maxUses) InvitationStatus.USED.name else InvitationStatus.ACTIVE.name

            firestoreService.commitBatch(path) { batch ->
                batch.update(
                    famRef,
                    mapOf(
                        "memberUids" to nextMembers,
                        "adminUids" to nextAdmins,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                existingMemberDocs.documents.forEach { memberDoc ->
                    if (memberDoc.id != requesterUid) {
                        batch.update(
                            memberDoc.reference,
                            mapOf(
                                "memberUids" to nextMembers,
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
                batch.set(
                    famRef.collection("members").document(requesterUid),
                    FamilyMemberDoc(
                        userId = requesterUid,
                        familyId = familyId,
                        displayName = reqData.displayName,
                        role = assignedRole.name,
                        memberUids = nextMembers,
                        adminUids = nextAdmins,
                        relationLabel = reqData.relationLabel ?: "Member",
                        canCreateRecords = membersCanAddExpenses,
                        canViewSharedFinances = true,
                        canParticipateInChat = memberChatEnabled
                    ).toCreateMap()
                )
                batch.update(
                    reqRef,
                    mapOf(
                        "status" to JoinRequestStatus.APPROVED.name,
                        "reviewedByUid" to adminUid,
                        "reviewedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (lookupSnap != null && lookupSnap.exists()) {
                    batch.update(
                        lookupRef,
                        mapOf(
                            "usedCount" to nextUsed,
                            "status" to nextInviteStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.update(
                        famRef.collection("invitations").document(reqData.invitationId),
                        mapOf(
                            "usedCount" to nextUsed,
                            "status" to nextInviteStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.onSuccess {
                runCatching {
                    val notifId = DatabaseIdentityHelper.generateRecordId("ntf")
                    val ntfPayload = FamilyNotificationDoc(
                        notificationId = notifId,
                        familyId = familyId,
                        recipientUserId = "ALL_MEMBERS",
                        createdByUserId = adminUid,
                        title = "Invitation Approved",
                        body = "${reqData.displayName} joined the family as ${assignedRole.label}.",
                        notificationType = NotificationType.INVITATION_APPROVAL.name,
                        relatedRecordId = requesterUid,
                        deepLinkModule = "FAMILY_MANAGEMENT",
                        acknowledged = false,
                        readByUids = listOf(adminUid),
                        recordStatus = RecordStatus.ACTIVE.name,
                        memberUids = nextMembers.take(20)
                    ).toCreateMap()
                    firestoreService.setDocument(db.collection("notifications").document(notifId), ntfPayload, "notifications/$notifId")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateMemberRoleAndPermissions(
        familyId: String,
        targetUid: String,
        newRole: FamilyRole,
        canCreateRecords: Boolean,
        canViewSharedFinances: Boolean,
        canParticipateInChat: Boolean,
        authService: AuthService
    ): Result<Unit> {
        val adminUid = requireUserId()
        val path = "families/$familyId/members/$targetUid"
        return try {
            if (authService.functionsClient.isConfigured) {
                val res = authService.functionsClient.updateMemberRole(familyId, targetUid, newRole.name)
                if (res.isFailure) return res.map { Unit }
            }
            val famRef = db.collection("families").document(familyId)
            val famSnap = famRef.get().await()
            val ownerId = famSnap.getString("ownerId") ?: ""
            if (targetUid == ownerId && newRole != FamilyRole.ADMIN) {
                return Result.failure(IllegalStateException("Transfer Family Ownership before changing the primary Admin's role."))
            }
            val currentAdmins = (famSnap.get("adminUids") as? List<*>)?.mapNotNull { it as? String } ?: listOf(ownerId)
            val nextAdmins = if (newRole == FamilyRole.ADMIN) {
                (currentAdmins + targetUid).distinct()
            } else {
                currentAdmins.filter { it != targetUid || it == ownerId }
            }
            val membersSnap = famRef.collection("members")
                .whereArrayContains("memberUids", adminUid)
                .get().await()

            firestoreService.commitBatch(path) { batch ->
                batch.update(
                    famRef,
                    mapOf(
                        "adminUids" to nextAdmins,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                membersSnap.documents.forEach { doc ->
                    if (doc.id == targetUid) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "role" to newRole.name,
                                "adminUids" to nextAdmins,
                                "canCreateRecords" to canCreateRecords,
                                "canViewSharedFinances" to canViewSharedFinances,
                                "canParticipateInChat" to canParticipateInChat,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    } else {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun removeMember(
        familyId: String,
        targetUid: String,
        authService: AuthService
    ): Result<Unit> {
        val callerUid = requireUserId()
        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.removeFamilyMember(familyId, targetUid).map {
                memberDao.deleteMember(familyId, targetUid)
                if (targetUid == callerUid) {
                    familyDao.deleteFamilyById(familyId)
                }
            }
        }
        val path = "families/$familyId/members/$targetUid"
        return try {
            val famRef = db.collection("families").document(familyId)
            val famSnap = famRef.get().await()
            val ownerId = famSnap.getString("ownerId") ?: ""
            if (targetUid == ownerId) {
                return Result.failure(IllegalStateException("Primary Family Admin cannot be removed. Transfer Family Ownership first."))
            }
            val currentMembers = (famSnap.get("memberUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val currentAdmins = (famSnap.get("adminUids") as? List<*>)?.mapNotNull { it as? String } ?: listOf(ownerId)
            val nextMembers = currentMembers.filter { it != targetUid }
            val nextAdmins = currentAdmins.filter { it != targetUid }

            val isAdmin = callerUid == ownerId || callerUid in currentAdmins
            if (!isAdmin && callerUid != targetUid) {
                return Result.failure(IllegalAccessException("Only a Family Admin can remove members."))
            }

            val membersSnap = famRef.collection("members")
                .whereArrayContains("memberUids", callerUid)
                .get().await()

            val res = firestoreService.commitBatch(path) { batch ->
                batch.update(
                    famRef,
                    mapOf(
                        "memberUids" to nextMembers,
                        "adminUids" to nextAdmins,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                membersSnap.documents.forEach { doc ->
                    if (doc.id != targetUid) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "memberUids" to nextMembers,
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
                batch.delete(famRef.collection("members").document(targetUid))
            }
            if (res.isSuccess) {
                memberDao.deleteMember(familyId, targetUid)
                if (targetUid == callerUid) {
                    familyDao.deleteFamilyById(familyId)
                }
            }
            res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun transferOwnership(
        familyId: String,
        newOwnerUid: String,
        familyPassword: String,
        authService: AuthService
    ): Result<Unit> {
        val callerUid = requireUserId()
        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.transferFamilyOwnership(familyId, newOwnerUid, familyPassword).map { Unit }
        }
        val path = "families/$familyId"
        return try {
            val famRef = db.collection("families").document(familyId)
            val famSnap = famRef.get().await()
            val ownerId = famSnap.getString("ownerId") ?: ""
            if (ownerId != callerUid) {
                return Result.failure(IllegalAccessException("Only the primary Family Admin can transfer ownership."))
            }
            val currentMembers = (famSnap.get("memberUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            if (newOwnerUid !in currentMembers || newOwnerUid == callerUid) {
                return Result.failure(IllegalArgumentException("Select another active family member to receive ownership."))
            }
            val nextAdmins = listOf(newOwnerUid)

            val membersSnap = famRef.collection("members")
                .whereArrayContains("memberUids", callerUid)
                .get().await()

            firestoreService.commitBatch(path) { batch ->
                batch.update(
                    famRef,
                    mapOf(
                        "ownerId" to newOwnerUid,
                        "adminUids" to nextAdmins,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                membersSnap.documents.forEach { doc ->
                    when (doc.id) {
                        newOwnerUid -> batch.update(
                            doc.reference,
                            mapOf(
                                "role" to FamilyRole.ADMIN.name,
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        callerUid -> batch.update(
                            doc.reference,
                            mapOf(
                                "role" to FamilyRole.MEMBER.name,
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        else -> batch.update(
                            doc.reference,
                            mapOf(
                                "adminUids" to nextAdmins,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateFamilySettings(
        familyId: String,
        name: String,
        currencyCode: String,
        description: String?,
        memberChatEnabled: Boolean,
        membersCanAddExpenses: Boolean,
        budgetStartDay: Int,
        authService: AuthService,
        chatRetentionDays: Int = 90
    ): Result<Unit> {
        requireUserId()
        val cleanName = name.trim()
        if (cleanName.length !in 2..60) {
            return Result.failure(IllegalArgumentException("Family name must be between 2 and 60 characters."))
        }
        val cleanCurrency = currencyCode.trim().uppercase().take(6).ifBlank { "USD" }
        val cleanDesc = description?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        val safeDay = budgetStartDay.coerceIn(1, 28)
        val safeRetention = if (chatRetentionDays in setOf(0, 30, 90, 365)) chatRetentionDays else 90

        if (authService.functionsClient.isConfigured) {
            return authService.functionsClient.updateFamilySettings(
                familyId = familyId,
                name = cleanName,
                currencyCode = cleanCurrency,
                description = cleanDesc,
                memberChatEnabled = memberChatEnabled,
                membersCanAddExpenses = membersCanAddExpenses,
                budgetStartDay = safeDay,
                chatRetentionDays = safeRetention
            ).map { Unit }
        }

        val docRef = db.collection("families").document(familyId)
        val updates = mutableMapOf<String, Any>(
            "name" to cleanName,
            "currencyCode" to cleanCurrency,
            "description" to (cleanDesc ?: FieldValue.delete()),
            "requireAdminApproval" to true,
            "memberChatEnabled" to memberChatEnabled,
            "membersCanAddExpenses" to membersCanAddExpenses,
            "budgetStartDay" to safeDay.toLong(),
            "chatRetentionDays" to safeRetention.toLong(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return firestoreService.updateDocument(docRef, updates, docRef.path)
    }

    suspend fun recoverAdminFamilies(authService: AuthService): Result<List<RecoveredFamilySummary>> {
        val uid = requireUserId()
        if (authService.functionsClient.isConfigured) {
            val remoteRes = authService.functionsClient.recoverAdminFamilies()
            if (remoteRes.isSuccess) {
                return remoteRes.map { it.families }
            }
        }
        val path = "families"
        return try {
            val byOwner = db.collection("families").whereEqualTo("ownerId", uid).get().await()
            val byCreator = db.collection("families").whereEqualTo("createdByUserId", uid).get().await()
            val merged = linkedMapOf<String, RecoveredFamilySummary>()
            for (doc in byOwner.documents + byCreator.documents) {
                val ownerId = doc.getString("ownerId") ?: ""
                val creatorId = doc.getString("createdByUserId") ?: ownerId
                val members = (doc.get("memberUids") as? List<*>) ?: emptyList<Any>()
                merged[doc.id] = RecoveredFamilySummary(
                    familyId = doc.id,
                    name = doc.getString("name") ?: "Family Workspace",
                    currencyCode = doc.getString("currencyCode") ?: "USD",
                    ownerId = ownerId,
                    createdByUserId = creatorId,
                    memberCount = members.size.coerceAtLeast(1),
                    isCurrentOwner = ownerId == uid,
                    isOriginalCreator = creatorId == uid
                )
            }
            if (merged.isEmpty()) {
                familyDao.getAdminLinkedFamilies(uid).forEach { local ->
                    merged[local.familyId] = RecoveredFamilySummary(
                        familyId = local.familyId,
                        name = local.name,
                        currencyCode = local.currencyCode,
                        ownerId = local.ownerId,
                        createdByUserId = local.createdByUserId,
                        memberCount = local.memberUids.size,
                        isCurrentOwner = local.ownerId == uid,
                        isOriginalCreator = local.createdByUserId == uid
                    )
                }
            }
            Result.success(merged.values.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }
}
