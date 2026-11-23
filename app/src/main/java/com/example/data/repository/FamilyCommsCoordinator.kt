package com.example.data.repository

import com.example.core.comms.MeetBackendClient
import com.example.core.comms.MeetModerationResponse
import com.example.core.comms.MeetTokenResponse
import com.example.core.firebase.AuthBackendException
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.FirestoreService
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.model.CallConnectionQuality
import com.example.core.model.CallMediaType
import com.example.core.model.CallStatus
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.FamilyMeetingDoc
import com.example.core.model.FamilyNotificationDoc
import com.example.core.model.IceCandidateDoc
import com.example.core.model.MeetInviteHelper
import com.example.core.model.MeetingParticipantDoc
import com.example.core.model.MeetingParticipantState
import com.example.core.model.MeetingStatus
import com.example.core.model.NotificationType
import com.example.core.model.RecordStatus
import com.example.core.model.CallSessionDoc
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await

/**
 * Coordinates 1-to-1 WebRTC signaling (`/calls`, `/calls/{id}/iceCandidates`),
 * LiveKit group meeting metadata (`/meetings`, `/meetings/{id}/participants`),
 * local Room persistence, and push notification dispatch.
 */
class FamilyCommsCoordinator(
    private val firestoreService: FirestoreService,
    private val localStore: LocalDatabaseStore,
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    val meetBackend: MeetBackendClient = MeetBackendClient(auth = auth)
) {
    private val db: FirebaseFirestore get() = firestoreService.db

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required before accessing calls or meetings.")
    }

    fun observeLocalCallHistory(familyId: String, userId: String): Flow<List<LocalCallRecordEntity>> =
        localStore.observeCallHistory(familyId, userId)

    fun observeLocalMeetings(familyId: String): Flow<List<LocalMeetingEntity>> =
        localStore.observeMeetings(familyId)

    fun syncRemoteCalls(userId: String, familyId: String): Flow<List<CallSessionDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user call query." }
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("calls")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("participantUids", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING),
                clazz = CallSessionDoc::class.java,
                pathForLogs = "calls",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val resolvedId = doc.callId.ifBlank { doc.id }
                    LocalCallRecordEntity(
                        callId = resolvedId,
                        familyId = doc.familyId.ifBlank { familyId },
                        callerUid = doc.callerUid,
                        calleeUid = doc.calleeUid,
                        callerName = doc.callerName,
                        calleeName = doc.calleeName,
                        mediaType = CallMediaType.fromRaw(doc.mediaType).name,
                        status = CallStatus.fromRaw(doc.status).name,
                        networkQuality = CallConnectionQuality.fromRaw(doc.networkQuality).name,
                        durationSeconds = doc.durationSeconds.toInt().coerceAtLeast(0),
                        endedByUid = doc.endedByUid,
                        startedAtMillis = doc.startedAt?.toDate()?.time,
                        endedAtMillis = doc.endedAt?.toDate()?.time,
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteCallHistory(familyId, entities)
            }
        )
    }

    fun syncRemoteIceCandidates(userId: String, callId: String): Flow<List<IceCandidateDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("calls")
                    .document(callId)
                    .collection("iceCandidates")
                    .whereArrayContains("participantUids", uid)
                    .orderBy("createdAt", Query.Direction.ASCENDING),
                clazz = IceCandidateDoc::class.java,
                pathForLogs = "calls/$callId/iceCandidates",
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    fun syncRemoteMeetings(userId: String, familyId: String): Flow<List<FamilyMeetingDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        require(familyId.isNotBlank())
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("meetings")
                    .whereEqualTo("familyId", familyId)
                    .whereArrayContains("memberUids", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING),
                clazz = FamilyMeetingDoc::class.java,
                pathForLogs = "meetings",
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { docs ->
                val entities = docs.map { doc ->
                    val resolvedId = doc.meetingId.ifBlank { doc.id }
                    LocalMeetingEntity(
                        meetingId = resolvedId,
                        familyId = doc.familyId.ifBlank { familyId },
                        roomName = doc.roomName,
                        title = doc.title,
                        hostUid = doc.hostUid,
                        hostName = doc.hostName,
                        inviteCode = doc.inviteCode,
                        inviteLink = doc.inviteLink,
                        status = MeetingStatus.fromRaw(doc.status).name,
                        isScheduled = doc.isScheduled,
                        scheduledForMillis = doc.scheduledFor?.toDate()?.time,
                        startedAtMillis = doc.startedAt?.toDate()?.time,
                        endedAtMillis = doc.endedAt?.toDate()?.time,
                        endedByUid = doc.endedByUid,
                        audioEnabledDefault = doc.audioEnabledDefault,
                        videoEnabledDefault = doc.videoEnabledDefault,
                        invitedUidsCsv = doc.invitedUids.joinToString(","),
                        activeParticipantUidsCsv = doc.activeParticipantUids.joinToString(","),
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                localStore.reconcileRemoteMeetings(familyId, entities)
            }
        )
    }

    fun syncMeetingParticipants(userId: String, meetingId: String): Flow<List<MeetingParticipantDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("meetings")
                    .document(meetingId)
                    .collection("participants")
                    .whereArrayContains("memberUids", uid),
                clazz = MeetingParticipantDoc::class.java,
                pathForLogs = "meetings/$meetingId/participants",
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    suspend fun createOutgoingCallSession(
        callId: String,
        family: LocalFamilyEntity,
        callerName: String,
        callee: LocalFamilyMemberEntity,
        mediaType: CallMediaType,
        offerSdp: String,
        offerType: String
    ): Result<CallSessionDoc> {
        val uid = requireUserId()
        val session = CallSessionDoc(
            id = callId,
            callId = callId,
            familyId = family.familyId,
            callerUid = uid,
            calleeUid = callee.userId,
            callerName = callerName,
            calleeName = callee.displayName,
            participantUids = listOf(uid, callee.userId).distinct(),
            mediaType = mediaType.name,
            status = CallStatus.RINGING.name,
            offerSdp = offerSdp,
            offerType = offerType
        )
        localStore.saveCallRecord(
            LocalCallRecordEntity(
                callId = callId,
                familyId = family.familyId,
                callerUid = uid,
                calleeUid = callee.userId,
                callerName = callerName,
                calleeName = callee.displayName,
                mediaType = mediaType.name,
                status = CallStatus.RINGING.name
            )
        )
        val res = firestoreService.setDocument(
            docRef = db.collection("calls").document(callId),
            payload = session.toCreateMap(),
            pathForLogs = "calls/$callId"
        )
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Unable to start call signaling."))
        }

        val callLabel = if (mediaType == CallMediaType.VIDEO) "Video Call" else "Audio Call"
        emitCommsNotification(
            family = family,
            recipientUserId = callee.userId,
            targetPushUids = listOf(callee.userId),
            title = "Incoming $callLabel",
            body = "$callerName is calling you in ${family.name}.",
            notificationType = NotificationType.CALL_INCOMING,
            relatedRecordId = callId
        )
        return Result.success(session)
    }

    suspend fun publishOfferRenegotiation(
        callId: String,
        offerSdp: String,
        offerType: String,
        iceRestartCount: Long
    ): Result<Unit> {
        return firestoreService.updateDocument(
            docRef = db.collection("calls").document(callId),
            updates = mapOf(
                "offerSdp" to offerSdp.take(30_000),
                "offerType" to offerType,
                "iceRestartCount" to iceRestartCount.coerceIn(0L, 50L),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = "calls/$callId"
        )
    }

    suspend fun publishCallAnswer(
        callId: String,
        answerSdp: String,
        answerType: String
    ): Result<Unit> {
        return firestoreService.updateDocument(
            docRef = db.collection("calls").document(callId),
            updates = mapOf(
                "answerSdp" to answerSdp.take(30_000),
                "answerType" to answerType,
                "status" to CallStatus.ACTIVE.name,
                "startedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = "calls/$callId"
        )
    }

    suspend fun updateCallStatus(
        call: CallSessionDoc,
        family: LocalFamilyEntity?,
        newStatus: CallStatus,
        durationSeconds: Int,
        quality: CallConnectionQuality
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
        val callId = call.callId.ifBlank { call.id }
        val updates = mutableMapOf<String, Any>(
            "status" to newStatus.name,
            "networkQuality" to quality.name,
            "durationSeconds" to durationSeconds.coerceAtLeast(0).toLong(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (newStatus.isTerminal) {
            if (!uid.isNullOrBlank()) updates["endedByUid"] = uid
            updates["endedAt"] = FieldValue.serverTimestamp()
        } else if (newStatus == CallStatus.ACTIVE && call.startedAt == null) {
            updates["startedAt"] = FieldValue.serverTimestamp()
        }

        localStore.saveCallRecord(
            LocalCallRecordEntity(
                callId = callId,
                familyId = call.familyId,
                callerUid = call.callerUid,
                calleeUid = call.calleeUid,
                callerName = call.callerName,
                calleeName = call.calleeName,
                mediaType = call.mediaType,
                status = newStatus.name,
                networkQuality = quality.name,
                durationSeconds = durationSeconds.coerceAtLeast(0),
                endedByUid = if (newStatus.isTerminal) uid else call.endedByUid,
                startedAtMillis = call.startedAt?.toDate()?.time,
                endedAtMillis = if (newStatus.isTerminal) System.currentTimeMillis() else null,
                createdAtMillis = call.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            )
        )

        val res = firestoreService.updateDocument(
            docRef = db.collection("calls").document(callId),
            updates = updates,
            pathForLogs = "calls/$callId"
        )

        if (newStatus == CallStatus.MISSED && family != null) {
            val typeLabel = if (call.mediaType == CallMediaType.VIDEO.name) "video call" else "audio call"
            emitCommsNotification(
                family = family,
                recipientUserId = call.calleeUid,
                targetPushUids = listOf(call.calleeUid),
                title = "Missed $typeLabel",
                body = "Missed $typeLabel from ${call.callerName}.",
                notificationType = NotificationType.CALL_MISSED,
                relatedRecordId = callId
            )
        }
        return res
    }

    suspend fun addIceCandidate(
        callId: String,
        familyId: String,
        role: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidate: String,
        participantUids: List<String>
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val candId = MeetInviteHelper.generateCandidateId()
        val doc = IceCandidateDoc(
            candidateId = candId,
            callId = callId,
            familyId = familyId,
            senderUid = uid,
            role = role,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex.toLong(),
            candidate = candidate,
            participantUids = participantUids
        )
        return firestoreService.setDocument(
            docRef = db.collection("calls").document(callId).collection("iceCandidates").document(candId),
            payload = doc.toCreateMap(),
            pathForLogs = "calls/$callId/iceCandidates/$candId"
        )
    }

    suspend fun createMeeting(
        family: LocalFamilyEntity,
        members: List<LocalFamilyMemberEntity>,
        hostName: String,
        title: String,
        isScheduled: Boolean,
        scheduledForMillis: Long?,
        invitedUids: List<String>,
        audioEnabledDefault: Boolean,
        videoEnabledDefault: Boolean
    ): Result<FamilyMeetingDoc> {
        val uid = requireUserId()
        val meetingId = MeetInviteHelper.generateMeetingId()
        val roomName = MeetInviteHelper.buildRoomName(family.familyId, meetingId)
        val inviteCode = MeetInviteHelper.generateInviteCode()
        val inviteLink = MeetInviteHelper.buildInviteLink(family.familyId, meetingId, inviteCode)
        val cleanTitle = title.trim().ifBlank { "${family.name} Family Meet" }.take(100)
        val status = if (isScheduled) MeetingStatus.SCHEDULED else MeetingStatus.LIVE
        val validInvited = (invitedUids + uid).distinct().filter { it in family.memberUids }

        val meetingDoc = FamilyMeetingDoc(
            id = meetingId,
            meetingId = meetingId,
            familyId = family.familyId,
            roomName = roomName,
            title = cleanTitle,
            hostUid = uid,
            hostName = hostName,
            inviteCode = inviteCode,
            inviteLink = inviteLink,
            status = status.name,
            isScheduled = isScheduled,
            audioEnabledDefault = audioEnabledDefault,
            videoEnabledDefault = videoEnabledDefault,
            invitedUids = validInvited,
            activeParticipantUids = emptyList(),
            memberUids = family.memberUids,
            adminUids = family.adminUids
        )

        localStore.saveMeeting(
            LocalMeetingEntity(
                meetingId = meetingId,
                familyId = family.familyId,
                roomName = roomName,
                title = cleanTitle,
                hostUid = uid,
                hostName = hostName,
                inviteCode = inviteCode,
                inviteLink = inviteLink,
                status = status.name,
                isScheduled = isScheduled,
                scheduledForMillis = scheduledForMillis,
                startedAtMillis = if (!isScheduled) System.currentTimeMillis() else null,
                audioEnabledDefault = audioEnabledDefault,
                videoEnabledDefault = videoEnabledDefault,
                invitedUidsCsv = validInvited.joinToString(","),
                activeParticipantUidsCsv = ""
            )
        )

        val res = firestoreService.commitBatch("meetings/$meetingId") { batch ->
            val meetingRef = db.collection("meetings").document(meetingId)
            batch.set(meetingRef, meetingDoc.toCreateMap(scheduledForMillis = scheduledForMillis))

            validInvited.forEach { targetUid ->
                val memberInfo = members.firstOrNull { it.userId == targetUid }
                val partName = when {
                    targetUid == uid -> hostName
                    !memberInfo?.displayName.isNullOrBlank() -> memberInfo!!.displayName
                    else -> "Family Member"
                }
                val partDoc = MeetingParticipantDoc(
                    participantUid = targetUid,
                    meetingId = meetingId,
                    familyId = family.familyId,
                    displayName = partName,
                    roleLabel = memberInfo?.relationLabel ?: memberInfo?.role,
                    isHost = targetUid == uid,
                    state = MeetingParticipantState.INVITED.name,
                    audioEnabled = audioEnabledDefault,
                    videoEnabled = videoEnabledDefault,
                    memberUids = family.memberUids
                )
                batch.set(meetingRef.collection("participants").document(targetUid), partDoc.toUpsertMap())
            }
        }
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Unable to create meeting."))
        }

        val notifyTargets = validInvited.filter { it != uid }
        if (notifyTargets.isNotEmpty()) {
            val type = if (isScheduled) NotificationType.MEETING_INVITE else NotificationType.MEETING_STARTED
            val body = if (isScheduled) {
                "$hostName scheduled \"$cleanTitle\" (Code: $inviteCode)."
            } else {
                "$hostName started \"$cleanTitle\". Join with code $inviteCode."
            }
            emitCommsNotification(
                family = family,
                recipientUserId = "ALL",
                targetPushUids = notifyTargets,
                title = if (isScheduled) "Scheduled Family Meet" else "Family Meet Live",
                body = body,
                notificationType = type,
                relatedRecordId = meetingId
            )
        }
        return Result.success(meetingDoc)
    }

    suspend fun resolveMeetingForJoin(
        activeFamily: LocalFamilyEntity,
        rawInput: String
    ): Result<FamilyMeetingDoc> {
        val uid = requireUserId()
        val parsed = MeetInviteHelper.parseInviteInput(rawInput)
        if (!parsed.familyId.isNullOrBlank() && parsed.familyId != activeFamily.familyId) {
            return Result.failure(
                SecurityException("Invite link belongs to another family workspace. Switch family first before joining.")
            )
        }

        return try {
            val docSnap = if (!parsed.meetingId.isNullOrBlank()) {
                db.collection("meetings").document(parsed.meetingId).get().await()
            } else {
                val querySnap = db.collection("meetings")
                    .whereEqualTo("familyId", activeFamily.familyId)
                    .whereEqualTo("inviteCode", parsed.codeOrIdToken)
                    .whereArrayContains("memberUids", uid)
                    .limit(1)
                    .get()
                    .await()
                querySnap.documents.firstOrNull()
            }

            if (docSnap == null || !docSnap.exists()) {
                return Result.failure(IllegalArgumentException("No matching meeting found for \"$rawInput\" in ${activeFamily.name}."))
            }
            val meeting = docSnap.toObject(FamilyMeetingDoc::class.java)
                ?: return Result.failure(IllegalStateException("Unable to decode meeting metadata."))
            if (meeting.familyId != activeFamily.familyId) {
                return Result.failure(SecurityException("Cross-family meeting join blocked."))
            }
            if (MeetingStatus.fromRaw(meeting.status).isTerminal) {
                return Result.failure(IllegalStateException("This family meeting has already ended."))
            }
            Result.success(meeting.copy(id = docSnap.id, meetingId = meeting.meetingId.ifBlank { docSnap.id }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Check local cache fallback in case device is offline
            val cached = parsed.meetingId?.let { localStore.getMeetingById(it) }
                ?: localStore.findMeetingByInviteCode(activeFamily.familyId, parsed.codeOrIdToken)
            if (cached != null) {
                Result.failure(IllegalStateException("Joining a LiveKit meeting requires an active internet connection."))
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun mintTokenAndJoinMetadata(
        family: LocalFamilyEntity,
        meeting: FamilyMeetingDoc,
        displayName: String,
        roleLabel: String?,
        audioEnabled: Boolean,
        videoEnabled: Boolean
    ): Result<MeetTokenResponse> {
        val uid = requireUserId()
        val meetingId = meeting.meetingId.ifBlank { meeting.id }
        val tokenRes = meetBackend.mintMeetToken(
            familyId = family.familyId,
            meetingId = meetingId,
            displayName = displayName
        )
        if (tokenRes.isFailure) return tokenRes

        val tokenPayload = tokenRes.getOrThrow()
        val meetingRef = db.collection("meetings").document(meetingId)
        val canSetLive = uid == meeting.hostUid || family.isAdmin(uid)
        val meetingUpdates = mutableMapOf<String, Any>(
            "activeParticipantUids" to FieldValue.arrayUnion(uid),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (canSetLive && meeting.status == MeetingStatus.SCHEDULED.name) {
            meetingUpdates["status"] = MeetingStatus.LIVE.name
            meetingUpdates["startedAt"] = FieldValue.serverTimestamp()
        }

        val partDoc = MeetingParticipantDoc(
            participantUid = uid,
            meetingId = meetingId,
            familyId = family.familyId,
            displayName = displayName,
            roleLabel = roleLabel,
            isHost = uid == meeting.hostUid || family.isAdmin(uid),
            state = MeetingParticipantState.JOINED.name,
            audioEnabled = audioEnabled,
            videoEnabled = videoEnabled,
            screenSharing = false,
            mutedByHost = false,
            removedByHost = false,
            memberUids = family.memberUids
        )

        firestoreService.updateDocument(meetingRef, meetingUpdates, "meetings/$meetingId")
        runCatching {
            meetingRef.collection("participants")
                .document(uid)
                .set(partDoc.toUpsertMap(joinedNow = true), SetOptions.merge())
                .await()
        }
        return Result.success(tokenPayload)
    }

    suspend fun updateMyMeetingMediaState(
        meetingId: String,
        audioEnabled: Boolean,
        videoEnabled: Boolean,
        screenSharing: Boolean
    ) {
        val uid = auth.currentUser?.uid ?: return
        firestoreService.updateDocument(
            docRef = db.collection("meetings").document(meetingId).collection("participants").document(uid),
            updates = mapOf(
                "audioEnabled" to audioEnabled,
                "videoEnabled" to videoEnabled,
                "screenSharing" to screenSharing,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = "meetings/$meetingId/participants/$uid"
        )
    }

    suspend fun markLeftMeeting(meetingId: String) {
        val uid = auth.currentUser?.uid ?: return
        firestoreService.updateDocument(
            docRef = db.collection("meetings").document(meetingId),
            updates = mapOf(
                "activeParticipantUids" to FieldValue.arrayRemove(uid),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = "meetings/$meetingId"
        )
        firestoreService.updateDocument(
            docRef = db.collection("meetings").document(meetingId).collection("participants").document(uid),
            updates = mapOf(
                "state" to MeetingParticipantState.LEFT.name,
                "audioEnabled" to false,
                "videoEnabled" to false,
                "screenSharing" to false,
                "leftAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = "meetings/$meetingId/participants/$uid"
        )
    }

    suspend fun muteParticipantAsHost(
        familyId: String,
        meetingId: String,
        targetUid: String,
        muted: Boolean,
        trackSid: String?
    ): Result<MeetModerationResponse> {
        return meetBackend.muteParticipant(
            familyId = familyId,
            meetingId = meetingId,
            targetUid = targetUid,
            muted = muted,
            trackSid = trackSid
        )
    }

    suspend fun removeParticipantAsHost(
        familyId: String,
        meetingId: String,
        targetUid: String
    ): Result<MeetModerationResponse> {
        return meetBackend.removeParticipant(
            familyId = familyId,
            meetingId = meetingId,
            targetUid = targetUid
        )
    }

    suspend fun endMeetingForEveryone(
        familyId: String,
        meetingId: String
    ): Result<MeetModerationResponse> {
        val remoteRes = meetBackend.endMeeting(familyId, meetingId)
        if (remoteRes.isSuccess) return remoteRes

        val err = remoteRes.exceptionOrNull()
        // If backend URL is not configured in local dev, allow host/admin Firestore status close directly
        if (err is AuthBackendException && err.code == "BACKEND_URL_NOT_CONFIGURED") {
            val uid = auth.currentUser?.uid.orEmpty()
            val direct = firestoreService.updateDocument(
                docRef = db.collection("meetings").document(meetingId),
                updates = mapOf(
                    "status" to MeetingStatus.ENDED.name,
                    "endedByUid" to uid,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "activeParticipantUids" to emptyList<String>(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                pathForLogs = "meetings/$meetingId"
            )
            return if (direct.isSuccess) {
                Result.success(
                    MeetModerationResponse(
                        ok = true,
                        meetingId = meetingId,
                        status = MeetingStatus.ENDED.name,
                        warning = "Ended in Firestore (Cloud Functions backend URL not configured for SFU room deletion)."
                    )
                )
            } else {
                Result.failure(direct.exceptionOrNull() ?: err)
            }
        }
        return remoteRes
    }

    private suspend fun emitCommsNotification(
        family: LocalFamilyEntity,
        recipientUserId: String,
        targetPushUids: List<String>,
        title: String,
        body: String,
        notificationType: NotificationType,
        relatedRecordId: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val notifId = DatabaseIdentityHelper.generateRecordId("ntf")
        val doc = FamilyNotificationDoc(
            notificationId = notifId,
            familyId = family.familyId,
            recipientUserId = recipientUserId,
            createdByUserId = uid,
            title = title,
            body = body,
            notificationType = notificationType.name,
            relatedRecordId = relatedRecordId,
            deepLinkModule = notificationType.defaultModuleName,
            acknowledged = false,
            readByUids = listOf(uid),
            recordStatus = RecordStatus.ACTIVE.name,
            memberUids = family.memberUids
        )
        localStore.saveNotification(
            LocalNotificationEntity(
                notificationId = notifId,
                familyId = family.familyId,
                recipientUserId = recipientUserId,
                createdByUserId = uid,
                title = title,
                body = body,
                notificationType = notificationType.name,
                relatedRecordId = relatedRecordId,
                deepLinkModule = notificationType.defaultModuleName,
                readByUidsCsv = uid
            )
        )
        firestoreService.setDocument(
            docRef = db.collection("notifications").document(notifId),
            payload = doc.toCreateMap(),
            pathForLogs = "notifications/$notifId"
        )
        if (meetBackend.isConfigured && targetPushUids.isNotEmpty()) {
            meetBackend.dispatchPush(
                familyId = family.familyId,
                targetUids = targetPushUids,
                title = title,
                body = body,
                notificationType = notificationType.name,
                deepLinkModule = notificationType.defaultModuleName,
                relatedRecordId = relatedRecordId
            )
        }
    }
}
