package com.example.data.repository

import android.content.Context
import com.example.core.firebase.AuthService
import com.example.core.firebase.ConnectionMonitor
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.FirestoreService
import com.example.core.local.FamilyDao
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.FamilyMemberDao
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalUserProfileEntity
import com.example.core.local.ThemePreferencesStore
import com.example.core.local.UserProfileDao
import com.example.core.local.abstraction.LocalDatabaseStore
import com.example.core.local.abstraction.LocalEntityCipherMapper
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.local.security.AndroidKeyStoreCipher
import com.example.core.model.AccountStatus
import com.example.core.model.AuditAction
import com.example.core.model.AuthValidation
import com.example.core.model.DatabaseIdentityHelper
import com.example.core.model.DeviceSessionDoc
import com.example.core.model.DigitalMemberCardData
import com.example.core.model.FamilyDoc
import com.example.core.model.FamilyMemberDoc
import com.example.core.model.FamilyRole
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.FcmTokenDoc
import com.example.core.model.MemberCardDoc
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MembershipNoticeDoc
import com.example.core.model.PasskeyCredentialDoc
import com.example.core.model.SyncState
import com.example.core.model.ThemeMode
import com.example.core.model.UserProfileDoc
import com.example.core.model.WorkspaceMode
import com.example.core.remote.OperationType
import com.example.core.remote.handleFirestoreError
import com.example.core.sync.ConflictResolutionPolicy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await

class FamilyFinanceRepository(
    val firestoreService: FirestoreService,
    val localDb: FamilyFinanceDatabase,
    val prefsStore: ThemePreferencesStore,
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth
) {
    constructor(
        db: FirebaseFirestore,
        localDb: FamilyFinanceDatabase,
        prefsStore: ThemePreferencesStore,
        auth: FirebaseAuth = FirebaseServiceRegistry.auth,
        connectionMonitor: ConnectionMonitor? = null
    ) : this(
        firestoreService = FirestoreService(db = db, connectionMonitor = connectionMonitor),
        localDb = localDb,
        prefsStore = prefsStore,
        auth = auth
    )

    constructor(context: Context, connectionMonitor: ConnectionMonitor? = null) : this(
        db = FirebaseServiceRegistry.firestore(context),
        localDb = FamilyFinanceDatabase.getInstance(context.applicationContext),
        prefsStore = ThemePreferencesStore(context.applicationContext),
        auth = FirebaseServiceRegistry.auth,
        connectionMonitor = connectionMonitor
    )

    val localStore: LocalDatabaseStore = SqliteRoomDatabaseStore(localDb)
    val familyManagement = FamilyManagementRepository(firestoreService, localDb, auth)
    val dataArchitecture = FamilyDataArchitectureRepository(firestoreService, localStore, auth)
    val commsCoordinator = FamilyCommsCoordinator(firestoreService, localStore, auth)
    val decisions = FamilyDecisionsRepository(firestoreService, localStore, auth)

    private val db: FirebaseFirestore get() = firestoreService.db
    private val userProfileDao: UserProfileDao = localDb.userProfileDao()
    private val familyDao: FamilyDao = localDb.familyDao()
    private val memberDao: FamilyMemberDao = localDb.familyMemberDao()
    private val profileMapper = LocalEntityCipherMapper(AndroidKeyStoreCipher())

    val localThemeMode: Flow<ThemeMode> = prefsStore.themeMode
    val localActiveFamilyId: Flow<String?> = prefsStore.activeFamilyId
    val localWorkspaceMode: Flow<WorkspaceMode> = prefsStore.workspaceMode
    val currentSessionIdFlow: Flow<String?> = prefsStore.currentSessionId
    val conflictPolicyFlow: Flow<ConflictResolutionPolicy> = prefsStore.conflictPolicy.map {
        ConflictResolutionPolicy.fromRaw(it)
    }
    val notificationsEnabledFlow: Flow<Boolean> = prefsStore.notificationsEnabled

    suspend fun setWorkspaceMode(mode: WorkspaceMode) {
        prefsStore.setWorkspaceMode(mode)
    }

    suspend fun setConflictPolicy(policy: ConflictResolutionPolicy) {
        prefsStore.setConflictPolicy(policy.name)
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): Result<Unit> {
        prefsStore.setNotificationsEnabled(enabled)
        return registerFcmToken(notificationsEnabled = enabled)
    }

    suspend fun registerFcmToken(
        tokenOverride: String? = null,
        notificationsEnabled: Boolean? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val token = tokenOverride?.takeIf { it.isNotBlank() }
            ?: prefsStore.getCachedFcmToken()
            ?: return Result.success(Unit)
        if (!tokenOverride.isNullOrBlank()) {
            prefsStore.setCachedFcmToken(token)
        }
        val deviceId = prefsStore.getOrCreateDeviceId()
        val enabled = notificationsEnabled ?: prefsStore.isNotificationsEnabled()
        val payload = FcmTokenDoc(
            deviceId = deviceId,
            token = token,
            platform = "ANDROID",
            notificationsEnabled = enabled
        ).toUpsertMap()
        val docRef = db.collection("users").document(uid).collection("fcm_tokens").document(deviceId)
        return firestoreService.setDocument(docRef, payload, docRef.path)
    }

    suspend fun purgeFamilyCache(familyId: String) {
        localStore.purgeFamilyCache(familyId)
        if (familyId.isNotBlank() && prefsStore.activeFamilyId.firstOrNull() == familyId) {
            prefsStore.setActiveFamilyId(null)
        }
    }

    suspend fun purgeUnauthorizedFamilies(allowedFamilyIds: Set<String>) {
        localStore.purgeUnauthorizedFamilies(allowedFamilyIds)
    }

    suspend fun purgeAllLocalData() {
        localStore.purgeAllLocalData()
        prefsStore.setActiveFamilyId(null)
        prefsStore.setCurrentSessionId(null)
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated user session required before accessing Firestore.")
    }

    fun observeLocalUserProfile(userId: String): Flow<LocalUserProfileEntity?> =
        userProfileDao.observeUserProfile(userId).map { entity -> entity?.let(profileMapper::decrypt) }

    fun observeLocalFamilies(userId: String): Flow<List<LocalFamilyEntity>> =
        familyDao.observeFamiliesForUser(userId)

    fun observeLocalFamilyMembers(familyId: String): Flow<List<LocalFamilyMemberEntity>> =
        memberDao.observeMembersForFamily(familyId)

    fun syncRemoteMembershipNotices(userId: String): Flow<List<MembershipNoticeDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user notice read." }
        val path = "users/$uid/membership_notices"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("users").document(uid).collection("membership_notices")
                    .orderBy("updatedAt", Query.Direction.DESCENDING),
                clazz = MembershipNoticeDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    suspend fun dismissMembershipNotice(familyId: String): Result<Unit> {
        val uid = requireUserId()
        return try {
            db.collection("users").document(uid)
                .collection("membership_notices").document(familyId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid/membership_notices/$familyId")
            Result.failure(e)
        }
    }

    fun syncRemoteUserProfile(userId: String): Flow<UserProfileDoc?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user profile sync." }
        val path = "users/$uid"
        emitAll(
            firestoreService.observeDocument(
                docRef = db.collection("users").document(uid),
                clazz = UserProfileDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { remote ->
                if (remote != null) {
                    val existingLocal = userProfileDao.getUserProfile(uid)?.let(profileMapper::decrypt)
                    val resolvedPhone = remote.phoneNumber ?: existingLocal?.phoneNumber
                    val resolvedMemberId = remote.memberId.ifBlank {
                        AuthValidation.resolveMemberIdFromPhone(resolvedPhone.orEmpty())
                            .ifBlank { existingLocal?.memberId.orEmpty() }
                    }
                    val resolvedMaskedPhone = remote.maskedPhone
                        ?: resolvedPhone?.let { AuthValidation.maskPhoneNumber(it) }
                        ?: existingLocal?.maskedPhone
                    val resolvedDob = remote.dateOfBirth ?: existingLocal?.dateOfBirth
                    val plainEntity = LocalUserProfileEntity(
                        userId = remote.userId.ifBlank { uid },
                        displayName = remote.displayName,
                        email = remote.email,
                        phoneNumber = resolvedPhone,
                        memberId = resolvedMemberId,
                        maskedPhone = resolvedMaskedPhone,
                        dateOfBirth = resolvedDob,
                        accountStatus = AccountStatus.fromRaw(remote.accountStatus).name,
                        passwordConfigured = remote.passwordConfigured,
                        passkeyCount = remote.passkeyCount.toInt().coerceAtLeast(0),
                        newDeviceCheckEnabled = remote.newDeviceCheckEnabled,
                        photoUrl = remote.photoUrl,
                        activeFamilyId = remote.activeFamilyId,
                        themePreference = remote.themePreference,
                        registeredAtMillis = remote.registeredAt?.toDate()?.time
                            ?: remote.createdAt?.toDate()?.time
                            ?: existingLocal?.registeredAtMillis
                            ?: System.currentTimeMillis(),
                        updatedAtMillis = remote.updatedAt?.toDate()?.time
                            ?: System.currentTimeMillis()
                    )
                    userProfileDao.upsertUserProfile(profileMapper.encrypt(plainEntity))
                    remote.activeFamilyId?.let { prefsStore.setActiveFamilyId(it) }
                }
            }
        )
    }

    fun syncRemotePasskeys(userId: String): Flow<List<PasskeyCredentialDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user passkey read." }
        val path = "users/$uid/passkeys"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("users").document(uid).collection("passkeys")
                    .orderBy("createdAt", Query.Direction.DESCENDING),
                clazz = PasskeyCredentialDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    fun syncRemoteSessions(userId: String): Flow<List<DeviceSessionDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user session read." }
        val path = "users/$uid/sessions"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("users").document(uid).collection("sessions")
                    .orderBy("lastActiveAt", Query.Direction.DESCENDING),
                clazz = DeviceSessionDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            )
        )
    }

    fun syncRemoteFamilies(userId: String): Flow<List<FamilyDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user family query." }
        val path = "families"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection(path).whereArrayContains("memberUids", uid),
                clazz = FamilyDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { remoteList ->
                val entities = remoteList.map { doc ->
                    LocalFamilyEntity(
                        familyId = doc.id,
                        name = doc.name,
                        ownerId = doc.ownerId,
                        createdByUserId = doc.createdByUserId.ifBlank { doc.ownerId },
                        currencyCode = doc.currencyCode,
                        memberUidsCsv = doc.memberUids.joinToString(","),
                        adminUidsCsv = doc.adminUids.ifEmpty { listOf(doc.ownerId) }.joinToString(","),
                        suspendedUidsCsv = doc.suspendedUids.joinToString(","),
                        isPersonal = doc.isPersonal || FamilySecurityHelper.isPersonalWorkspaceId(doc.id),
                        description = doc.description,
                        requireAdminApproval = doc.requireAdminApproval,
                        memberChatEnabled = doc.memberChatEnabled,
                        membersCanAddExpenses = doc.membersCanAddExpenses,
                        budgetStartDay = doc.budgetStartDay.toInt().coerceIn(1, 28),
                        chatRetentionDays = doc.chatRetentionDays.toInt().let { if (it in setOf(0, 30, 90, 365)) it else 90 },
                        createdAtMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        syncStatus = "SYNCED",
                        updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
                val pendingIds = familyDao.getPendingFamilies(uid).map { it.familyId }
                val personalId = FamilySecurityHelper.personalWorkspaceId(uid)
                val allowedIds = (entities.map { it.familyId } + pendingIds + personalId).toSet()
                if (entities.isNotEmpty()) {
                    familyDao.upsertFamilies(entities)
                    familyDao.pruneSyncedFamiliesNotIn(uid, (entities.map { it.familyId } + personalId).distinct())
                } else {
                    familyDao.pruneSyncedFamiliesNotIn(uid, listOf(personalId))
                }
                localStore.purgeUnauthorizedFamilies(allowedIds)
            }
        )
    }

    fun observeLocalMemberCards(familyId: String): Flow<List<LocalMemberCardEntity>> =
        localStore.observeMemberCards(familyId)

    fun syncRemoteFamilyMembers(userId: String, familyId: String): Flow<List<FamilyMemberDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user member query." }
        require(familyId.isNotBlank()) { "Family ID must not be blank" }
        val path = "families/$familyId/members"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("families").document(familyId).collection("members")
                    .whereArrayContains("memberUids", uid),
                clazz = FamilyMemberDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { members ->
                val entities = members.map { member ->
                    LocalFamilyMemberEntity(
                        familyId = member.familyId.ifBlank { familyId },
                        userId = member.userId.ifBlank { member.id },
                        displayName = member.displayName,
                        memberId = member.memberId,
                        maskedPhone = member.maskedPhone,
                        role = FamilyRole.fromRaw(member.role).name,
                        memberStatus = member.memberStatus.ifBlank { "ACTIVE" },
                        relationLabel = member.relationLabel,
                        canCreateRecords = member.canCreateRecords,
                        canViewSharedFinances = member.canViewSharedFinances,
                        canParticipateInChat = member.canParticipateInChat,
                        joinedAtMillis = member.joinedAt?.toDate()?.time ?: System.currentTimeMillis(),
                        syncStatus = "SYNCED",
                        updatedAtMillis = member.updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
                        cardMemberId = member.cardMemberId
                    )
                }
                if (entities.isNotEmpty()) {
                    memberDao.upsertMembers(entities)
                    memberDao.pruneSyncedMembersNotIn(familyId, entities.map { it.userId })
                }
            }
        )
    }

    fun syncRemoteMemberCards(userId: String, familyId: String): Flow<List<MemberCardDoc>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user member card query." }
        require(familyId.isNotBlank()) { "Family ID must not be blank" }
        val path = "families/$familyId/member_cards"
        emitAll(
            firestoreService.observeQuery(
                query = db.collection("families").document(familyId).collection("member_cards"),
                clazz = MemberCardDoc::class.java,
                pathForLogs = path,
                activeUidProvider = { auth.currentUser?.uid }
            ).onEach { cards ->
                val entities = cards.mapNotNull { doc ->
                    val targetUid = doc.userId.ifBlank { doc.id }
                    if (targetUid.isBlank() || !FamilySecurityHelper.isValidMemberCardId16(doc.cardMemberId)) {
                        null
                    } else {
                        LocalMemberCardEntity(
                            familyId = doc.familyId.ifBlank { familyId },
                            userId = targetUid,
                            cardMemberId = doc.cardMemberId.trim().uppercase(),
                            familyName = doc.familyName,
                            displayName = doc.displayName,
                            photoUrl = doc.photoUrl,
                            role = FamilyRole.fromRaw(doc.role).name,
                            memberStatus = if (doc.memberStatus.equals("SUSPENDED", ignoreCase = true)) "SUSPENDED" else "ACTIVE",
                            joinedAtMillis = doc.joinedAt?.toDate()?.time ?: System.currentTimeMillis(),
                            showAvailableBalance = doc.showAvailableBalance,
                            showIncomeAndExpense = doc.showIncomeAndExpense,
                            showFamilyContributions = doc.showFamilyContributions,
                            showLoans = doc.showLoans,
                            showSavings = doc.showSavings,
                            showInvestments = doc.showInvestments,
                            showTransactionHistory = doc.showTransactionHistory,
                            allowAdminDetailedAudit = doc.allowAdminDetailedAudit,
                            syncStatus = SyncState.SYNCED.name,
                            updatedAtMillis = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                        )
                    }
                }
                if (entities.isNotEmpty()) {
                    localStore.reconcileRemoteMemberCards(familyId, entities)
                }
            }
        )
    }

    suspend fun ensureMemberCardsProvisioned(
        family: LocalFamilyEntity,
        members: List<LocalFamilyMemberEntity>,
        currentUserPhotoUrl: String? = null
    ) {
        val uid = auth.currentUser?.uid ?: return
        if (members.isEmpty()) return
        val existingCards = localStore.observeMemberCards(family.familyId).firstOrNull().orEmpty()
        val usedCardIds = (
            existingCards.map { it.cardMemberId } +
                members.map { it.cardMemberId }
            ).filter { FamilySecurityHelper.isValidMemberCardId16(it) }.toMutableSet()
        val viewerIsAdmin = family.isAdmin(uid)
        val now = System.currentTimeMillis()

        for (member in members) {
            val existingCard = localStore.getMemberCard(family.familyId, member.userId)
            val resolvedCardId = when {
                existingCard != null && FamilySecurityHelper.isValidMemberCardId16(existingCard.cardMemberId) ->
                    existingCard.cardMemberId.uppercase()
                FamilySecurityHelper.isValidMemberCardId16(member.cardMemberId) ->
                    member.cardMemberId.uppercase()
                else -> {
                    var candidate = FamilySecurityHelper.generateMemberCardId16()
                    while (!usedCardIds.add(candidate)) {
                        candidate = FamilySecurityHelper.generateMemberCardId16()
                    }
                    candidate
                }
            }
            val resolvedRole = if (member.userId == family.ownerId || FamilyRole.fromRaw(member.role) == FamilyRole.ADMIN) {
                FamilyRole.ADMIN.name
            } else {
                FamilyRole.MEMBER.name
            }
            val resolvedStatus = if (family.isSuspended(member.userId) || member.memberStatus.equals("SUSPENDED", ignoreCase = true)) {
                "SUSPENDED"
            } else {
                "ACTIVE"
            }
            val photoForMember = if (member.userId == uid && !currentUserPhotoUrl.isNullOrBlank()) {
                currentUserPhotoUrl.take(500)
            } else {
                existingCard?.photoUrl
            }
            val needsLocalWrite = existingCard == null ||
                existingCard.cardMemberId != resolvedCardId ||
                existingCard.familyName != family.name ||
                existingCard.displayName != member.displayName ||
                existingCard.role != resolvedRole ||
                existingCard.memberStatus != resolvedStatus ||
                (photoForMember != null && existingCard.photoUrl != photoForMember)

            val updatedCard = LocalMemberCardEntity(
                familyId = family.familyId,
                userId = member.userId,
                cardMemberId = resolvedCardId,
                familyName = family.name,
                displayName = member.displayName.ifBlank { "Family Member" },
                photoUrl = photoForMember,
                role = resolvedRole,
                memberStatus = resolvedStatus,
                joinedAtMillis = existingCard?.joinedAtMillis ?: member.joinedAtMillis,
                showAvailableBalance = existingCard?.showAvailableBalance ?: true,
                showIncomeAndExpense = existingCard?.showIncomeAndExpense ?: true,
                showFamilyContributions = existingCard?.showFamilyContributions ?: true,
                showLoans = existingCard?.showLoans ?: true,
                showSavings = existingCard?.showSavings ?: true,
                showInvestments = existingCard?.showInvestments ?: true,
                showTransactionHistory = existingCard?.showTransactionHistory ?: true,
                allowAdminDetailedAudit = existingCard?.allowAdminDetailedAudit ?: true,
                syncStatus = if (existingCard == null) SyncState.PENDING_CREATE.name else existingCard.syncStatus,
                updatedAtMillis = now
            )

            if (member.cardMemberId != resolvedCardId) {
                memberDao.upsertMember(member.copy(cardMemberId = resolvedCardId))
            }
            if (needsLocalWrite) {
                localStore.saveMemberCard(updatedCard)
            }

            if ((member.userId == uid || viewerIsAdmin) && !family.isPersonal) {
                val cardRef = db.collection("families").document(family.familyId)
                    .collection("member_cards").document(member.userId)
                if (existingCard == null) {
                    val payload = MemberCardDoc(
                        userId = member.userId,
                        familyId = family.familyId,
                        cardMemberId = resolvedCardId,
                        familyName = family.name,
                        displayName = updatedCard.displayName,
                        photoUrl = photoForMember,
                        role = resolvedRole,
                        memberStatus = resolvedStatus,
                        memberUids = family.memberUids,
                        adminUids = family.adminUids,
                        showAvailableBalance = updatedCard.showAvailableBalance,
                        showIncomeAndExpense = updatedCard.showIncomeAndExpense,
                        showFamilyContributions = updatedCard.showFamilyContributions,
                        showLoans = updatedCard.showLoans,
                        showSavings = updatedCard.showSavings,
                        showInvestments = updatedCard.showInvestments,
                        showTransactionHistory = updatedCard.showTransactionHistory,
                        allowAdminDetailedAudit = updatedCard.allowAdminDetailedAudit
                    ).toCreateMap()
                    val remoteRes = runCatching { cardRef.get().await() }.getOrNull()
                    if (remoteRes == null || !remoteRes.exists()) {
                        val setRes = firestoreService.setDocument(cardRef, payload, cardRef.path)
                        if (setRes.isSuccess) {
                            localStore.saveMemberCard(updatedCard.copy(syncStatus = SyncState.SYNCED.name))
                        }
                    }
                    if (!FamilySecurityHelper.isValidMemberCardId16(member.cardMemberId)) {
                        val memberRef = db.collection("families").document(family.familyId)
                            .collection("members").document(member.userId)
                        firestoreService.updateDocument(
                            docRef = memberRef,
                            updates = mapOf(
                                "cardMemberId" to resolvedCardId,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            pathForLogs = memberRef.path
                        )
                    }
                }
            }
        }
    }

    suspend fun updateMemberCardPrivacy(
        familyId: String,
        settings: MemberPrivacySettings
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val existing = localStore.getMemberCard(familyId, uid)
                ?: return Result.failure(IllegalStateException("Member Card not initialized yet."))
            val updated = existing.copy(
                showAvailableBalance = settings.showAvailableBalance,
                showIncomeAndExpense = settings.showIncomeAndExpense,
                showFamilyContributions = settings.showFamilyContributions,
                showLoans = settings.showLoans,
                showSavings = settings.showSavings,
                showInvestments = settings.showInvestments,
                showTransactionHistory = settings.showTransactionHistory,
                allowAdminDetailedAudit = settings.allowAdminDetailedAudit,
                syncStatus = SyncState.PENDING_UPDATE.name,
                updatedAtMillis = System.currentTimeMillis()
            )
            localStore.saveMemberCard(updated)

            val docRef = db.collection("families").document(familyId)
                .collection("member_cards").document(uid)
            val privacyDoc = MemberCardDoc(
                showAvailableBalance = settings.showAvailableBalance,
                showIncomeAndExpense = settings.showIncomeAndExpense,
                showFamilyContributions = settings.showFamilyContributions,
                showLoans = settings.showLoans,
                showSavings = settings.showSavings,
                showInvestments = settings.showInvestments,
                showTransactionHistory = settings.showTransactionHistory,
                allowAdminDetailedAudit = settings.allowAdminDetailedAudit
            )
            val remoteRes = firestoreService.updateDocument(
                docRef = docRef,
                updates = privacyDoc.toPrivacyUpdateMap(),
                pathForLogs = docRef.path
            )
            if (remoteRes.isSuccess) {
                localStore.saveMemberCard(updated.copy(syncStatus = SyncState.SYNCED.name))
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "families/$familyId/member_cards")
            Result.failure(e)
        }
    }

    suspend fun recordAdminMemberAuditLog(
        family: LocalFamilyEntity,
        targetCard: DigitalMemberCardData
    ) {
        val uid = auth.currentUser?.uid ?: return
        if (uid == targetCard.userId || !family.isAdmin(uid)) return
        val now = System.currentTimeMillis()
        val auditId = DatabaseIdentityHelper.generateAuditId()
        val deviceId = prefsStore.getOrCreateDeviceId()
        val auditEntry = LocalAuditLogEntity(
            auditId = auditId,
            familyId = family.familyId,
            actorUserId = uid,
            entityCollection = "member_cards",
            entityId = targetCard.userId,
            action = AuditAction.MEMBER_CHANGE.name,
            summary = "Admin audited Member Card ${targetCard.cardMemberId} (${targetCard.fullName})".take(240),
            afterHash = DatabaseIdentityHelper.computePayloadHash("${targetCard.familyId}|${targetCard.cardMemberId}|$now"),
            deviceId = deviceId,
            syncStatus = SyncState.PENDING_CREATE.name,
            createdAtMillis = now
        )
        localStore.appendAuditLog(auditEntry)
        val safeMembers = (family.memberUids + uid).distinct().take(20)
        val safeAdmins = family.adminUids.ifEmpty { listOf(family.ownerId, uid) }.distinct().take(20)
        val auditRef = db.collection("auditLogs").document(auditId)
        val res = firestoreService.setDocument(auditRef, auditEntry.toCreatePayload(safeMembers, safeAdmins), auditRef.path)
        if (res.isSuccess) {
            localStore.appendAuditLog(auditEntry.copy(syncStatus = SyncState.SYNCED.name))
        }
    }

    suspend fun ensurePersonalWorkspaceInitialized(
        uid: String,
        displayName: String,
        currencyCode: String = "BDT"
    ) {
        val personalId = FamilySecurityHelper.personalWorkspaceId(uid)
        val existingLocal = familyDao.getFamilyById(personalId)
        if (existingLocal == null) {
            val entity = LocalFamilyEntity(
                familyId = personalId,
                name = "Personal Finance",
                ownerId = uid,
                createdByUserId = uid,
                currencyCode = currencyCode,
                memberUidsCsv = uid,
                adminUidsCsv = uid,
                isPersonal = true,
                description = "Private personal finance workspace",
                memberChatEnabled = false,
                syncStatus = "PENDING_SYNC"
            )
            familyDao.upsertFamily(entity)
        }
        val famRef = db.collection("families").document(personalId)
        val snap = runCatching { famRef.get().await() }.getOrNull()
        if (snap == null || !snap.exists()) {
            val payload = FamilyDoc(
                name = "Personal Finance",
                ownerId = uid,
                createdByUserId = uid,
                currencyCode = currencyCode,
                memberUids = listOf(uid),
                adminUids = listOf(uid),
                isPersonal = true,
                description = "Private personal finance workspace",
                memberChatEnabled = false
            ).toCreateMap()
            val writeRes = firestoreService.commitBatch("families/$personalId") { batch ->
                batch.set(famRef, payload)
                batch.set(
                    famRef.collection("members").document(uid),
                    FamilyMemberDoc(
                        userId = uid,
                        familyId = personalId,
                        displayName = displayName.ifBlank { "Personal Owner" },
                        role = FamilyRole.ADMIN.name,
                        memberUids = listOf(uid),
                        adminUids = listOf(uid),
                        relationLabel = "Self"
                    ).toCreateMap()
                )
            }
            if (writeRes.isSuccess) {
                familyDao.getFamilyById(personalId)?.let {
                    familyDao.upsertFamily(it.copy(syncStatus = "SYNCED"))
                }
            }
        }
    }

    suspend fun ensureUserProfileInitialized(firebaseUser: FirebaseUser): Result<Unit> {
        val uid = firebaseUser.uid
        val docPath = "users/$uid"
        return try {
            val safeName = (firebaseUser.displayName ?: "Family Member").trim().take(80).ifBlank { "Family Member" }
            val safeEmail = (firebaseUser.email ?: "${uid.take(10)}@phone.familyfinance.app").trim().take(120)
            val safePhone = firebaseUser.phoneNumber?.takeIf { AuthValidation.isValidE164Phone(it) }
            val safePhoto = firebaseUser.photoUrl?.toString()?.take(500)

            val existingLocal = userProfileDao.getUserProfile(uid)?.let(profileMapper::decrypt)
            val resolvedPhone = safePhone ?: existingLocal?.phoneNumber
            val resolvedMemberId = AuthValidation.resolveMemberIdFromPhone(resolvedPhone.orEmpty())
                .ifBlank { existingLocal?.memberId.orEmpty() }
            val resolvedMasked = resolvedPhone?.let { AuthValidation.maskPhoneNumber(it) }
                ?: existingLocal?.maskedPhone

            val plainProfile = LocalUserProfileEntity(
                userId = uid,
                displayName = safeName,
                email = safeEmail,
                phoneNumber = resolvedPhone,
                memberId = resolvedMemberId,
                maskedPhone = resolvedMasked,
                dateOfBirth = existingLocal?.dateOfBirth,
                accountStatus = existingLocal?.accountStatus ?: AccountStatus.ACTIVE.name,
                passwordConfigured = existingLocal?.passwordConfigured ?: false,
                passkeyCount = existingLocal?.passkeyCount ?: 0,
                newDeviceCheckEnabled = existingLocal?.newDeviceCheckEnabled ?: true,
                photoUrl = safePhoto ?: existingLocal?.photoUrl,
                activeFamilyId = existingLocal?.activeFamilyId,
                themePreference = existingLocal?.themePreference ?: ThemeMode.SYSTEM.name,
                registeredAtMillis = existingLocal?.registeredAtMillis ?: System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis()
            )
            userProfileDao.upsertUserProfile(profileMapper.encrypt(plainProfile))
            ensurePersonalWorkspaceInitialized(uid, safeName)

            val docRef = db.collection("users").document(uid)
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val payload = UserProfileDoc(
                    userId = uid,
                    displayName = safeName,
                    email = safeEmail,
                    phoneNumber = resolvedPhone,
                    memberId = resolvedMemberId,
                    maskedPhone = resolvedMasked,
                    accountStatus = AccountStatus.ACTIVE.name,
                    passwordConfigured = false,
                    passkeyCount = 0L,
                    newDeviceCheckEnabled = true,
                    photoUrl = safePhoto,
                    activeFamilyId = existingLocal?.activeFamilyId,
                    themePreference = existingLocal?.themePreference ?: ThemeMode.SYSTEM.name
                ).toCreateMap()
                firestoreService.setDocument(docRef, payload, docPath)
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!resolvedPhone.isNullOrBlank()) {
                    updateMap["phoneNumber"] = resolvedPhone
                    updateMap["memberId"] = resolvedMemberId
                    if (!resolvedMasked.isNullOrBlank()) updateMap["maskedPhone"] = resolvedMasked
                }
                if (!safePhoto.isNullOrBlank()) updateMap["photoUrl"] = safePhoto
                firestoreService.updateDocument(docRef, updateMap, docPath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun trackCurrentSession(authService: AuthService, user: FirebaseUser): Result<String> {
        return try {
            val sessionId = authService.ensureSessionTrackedInFirestore(db, firestoreService, prefsStore, user)
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/${user.uid}/sessions")
            Result.failure(e)
        }
    }

    suspend fun deletePasskey(credentialId: String): Result<Unit> {
        val uid = requireUserId()
        val path = "users/$uid/passkeys/$credentialId"
        return try {
            db.collection("users").document(uid).collection("passkeys").document(credentialId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun revokeSession(sessionId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("sessions").document(sessionId)
        return firestoreService.updateDocument(
            docRef = docRef,
            updates = mapOf(
                "revoked" to true,
                "revokedAt" to FieldValue.serverTimestamp(),
                "lastActiveAt" to FieldValue.serverTimestamp()
            ),
            pathForLogs = docRef.path
        )
    }

    suspend fun revokeAllOtherSessions(authService: AuthService): Result<Int> {
        val keepSessionId = prefsStore.getCurrentSessionId()
        return authService.functionsClient.revokeAllSessions(keepSessionId).map { it.revokedCount }
    }

    suspend fun toggleNewDeviceCheck(enabled: Boolean): Result<Unit> {
        val uid = requireUserId()
        val existing = userProfileDao.getUserProfile(uid)
        if (existing != null) {
            userProfileDao.upsertUserProfile(
                existing.copy(newDeviceCheckEnabled = enabled, updatedAtMillis = System.currentTimeMillis())
            )
        }
        val docRef = db.collection("users").document(uid)
        return firestoreService.updateDocument(
            docRef = docRef,
            updates = mapOf("newDeviceCheckEnabled" to enabled, "updatedAt" to FieldValue.serverTimestamp()),
            pathForLogs = docRef.path
        )
    }

    suspend fun createFamily(
        name: String,
        currencyCode: String,
        description: String?,
        familyPassword: String = "",
        authService: AuthService? = null
    ): Result<LocalFamilyEntity> {
        val cleanName = name.trim()
        if (cleanName.length !in 2..60) {
            return Result.failure(IllegalArgumentException("Family name must be between 2 and 60 characters."))
        }
        val cleanCurrency = currencyCode.trim().uppercase().ifBlank { "BDT" }
        if (cleanCurrency.length !in 3..6) {
            return Result.failure(IllegalArgumentException("Currency code must be 3 to 6 characters."))
        }
        val cleanDesc = description?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        val requiresFamilyPassword = (authService != null && authService.functionsClient.isConfigured) || familyPassword.isNotBlank()
        if (requiresFamilyPassword && !AuthValidation.evaluateFamilyPasswordStrength(familyPassword).isAcceptable) {
            return Result.failure(
                IllegalArgumentException("Family Password must be 8+ chars with upper, lower, digit, and symbol.")
            )
        }

        return try {
            val uid = requireUserId()
            val userDisplay = auth.currentUser?.displayName?.trim()?.take(80)?.ifBlank { "Family Admin" }
                ?: auth.currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
                ?: "Family Admin"
            val myMemberId = AuthValidation.resolveMemberIdFromPhone(auth.currentUser?.phoneNumber.orEmpty())
            val myMasked = auth.currentUser?.phoneNumber?.let { AuthValidation.maskPhoneNumber(it) }

            if (authService != null && authService.functionsClient.isConfigured) {
                val remoteRes = authService.functionsClient.createFamily(
                    name = cleanName,
                    currencyCode = cleanCurrency,
                    description = cleanDesc,
                    displayName = userDisplay,
                    familyPassword = familyPassword
                )
                return remoteRes.map { created ->
                    val entity = LocalFamilyEntity(
                        familyId = created.familyId,
                        name = created.name,
                        ownerId = uid,
                        createdByUserId = uid,
                        currencyCode = cleanCurrency,
                        memberUidsCsv = uid,
                        adminUidsCsv = uid,
                        isPersonal = false,
                        description = cleanDesc,
                        syncStatus = "SYNCED"
                    )
                    familyDao.upsertFamily(entity)
                    memberDao.upsertMember(
                        LocalFamilyMemberEntity(
                            familyId = created.familyId,
                            userId = uid,
                            displayName = userDisplay,
                            memberId = myMemberId,
                            maskedPhone = myMasked,
                            role = FamilyRole.ADMIN.name,
                            memberStatus = "ACTIVE",
                            relationLabel = "Family Admin",
                            syncStatus = "SYNCED"
                        )
                    )
                    prefsStore.setActiveFamilyId(created.familyId)
                    prefsStore.setWorkspaceMode(WorkspaceMode.FAMILY)
                    entity
                }
            }

            val familyId = FamilySecurityHelper.generateRandomFamilyId()
            val docPath = "families/$familyId"

            val creatorCardMemberId = FamilySecurityHelper.generateMemberCardId16()
            val localFamily = LocalFamilyEntity(
                familyId = familyId,
                name = cleanName,
                ownerId = uid,
                createdByUserId = uid,
                currencyCode = cleanCurrency,
                memberUidsCsv = uid,
                adminUidsCsv = uid,
                description = cleanDesc,
                syncStatus = "PENDING_SYNC"
            )
            val localMember = LocalFamilyMemberEntity(
                familyId = familyId,
                userId = uid,
                displayName = userDisplay,
                role = FamilyRole.ADMIN.name,
                relationLabel = "Family Admin",
                syncStatus = "PENDING_SYNC",
                cardMemberId = creatorCardMemberId
            )

            familyDao.upsertFamily(localFamily)
            memberDao.upsertMember(localMember)
            localStore.saveMemberCard(
                LocalMemberCardEntity(
                    familyId = familyId,
                    userId = uid,
                    cardMemberId = creatorCardMemberId,
                    familyName = cleanName,
                    displayName = userDisplay,
                    photoUrl = auth.currentUser?.photoUrl?.toString()?.take(500),
                    role = FamilyRole.ADMIN.name,
                    memberStatus = "ACTIVE",
                    syncStatus = SyncState.PENDING_CREATE.name
                )
            )
            prefsStore.setActiveFamilyId(familyId)

            val familyRef = db.collection("families").document(familyId)
            val memberRef = familyRef.collection("members").document(uid)
            val cardRef = familyRef.collection("member_cards").document(uid)
            val topMemberRef = db.collection("familyMembers").document("${familyId}_$uid")
            val userRef = db.collection("users").document(uid)

            val familyPayload = FamilyDoc(
                name = cleanName,
                ownerId = uid,
                createdByUserId = uid,
                currencyCode = cleanCurrency,
                memberUids = listOf(uid),
                adminUids = listOf(uid),
                description = cleanDesc
            ).toCreateMap()

            val memberPayload = FamilyMemberDoc(
                userId = uid,
                familyId = familyId,
                displayName = userDisplay,
                role = FamilyRole.ADMIN.name,
                memberUids = listOf(uid),
                adminUids = listOf(uid),
                relationLabel = "Family Admin",
                cardMemberId = creatorCardMemberId
            ).toCreateMap()

            val cardPayload = MemberCardDoc(
                userId = uid,
                familyId = familyId,
                cardMemberId = creatorCardMemberId,
                familyName = cleanName,
                displayName = userDisplay,
                photoUrl = auth.currentUser?.photoUrl?.toString()?.take(500),
                role = FamilyRole.ADMIN.name,
                memberStatus = "ACTIVE",
                memberUids = listOf(uid),
                adminUids = listOf(uid)
            ).toCreateMap()

            val batchResult = firestoreService.commitBatch(docPath) { batch ->
                batch.set(familyRef, familyPayload)
                batch.set(memberRef, memberPayload)
                batch.set(cardRef, cardPayload)
                batch.set(topMemberRef, memberPayload)
                batch.update(
                    userRef,
                    mapOf("activeFamilyId" to familyId, "updatedAt" to FieldValue.serverTimestamp())
                )
            }

            if (batchResult.isSuccess) {
                val syncedFamily = localFamily.copy(syncStatus = "SYNCED")
                familyDao.upsertFamily(syncedFamily)
                memberDao.upsertMember(localMember.copy(syncStatus = "SYNCED"))
                Result.success(syncedFamily)
            } else {
                Result.success(localFamily)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "families")
            Result.failure(e)
        }
    }

    suspend fun switchActiveFamily(familyId: String): Result<Unit> {
        return try {
            require(familyId.isNotBlank()) { "Family ID cannot be blank" }
            val uid = requireUserId()
            val docPath = "users/$uid"
            prefsStore.setActiveFamilyId(familyId)

            val existing = userProfileDao.getUserProfile(uid)
            if (existing != null) {
                userProfileDao.upsertUserProfile(
                    existing.copy(activeFamilyId = familyId, updatedAtMillis = System.currentTimeMillis())
                )
            }

            firestoreService.updateDocument(
                docRef = db.collection("users").document(uid),
                updates = mapOf("activeFamilyId" to familyId, "updatedAt" to FieldValue.serverTimestamp()),
                pathForLogs = docPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users")
            Result.failure(e)
        }
    }

    suspend fun updateThemePreference(mode: ThemeMode): Result<Unit> {
        prefsStore.setThemeMode(mode)
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val existing = userProfileDao.getUserProfile(uid)
        if (existing != null) {
            userProfileDao.upsertUserProfile(
                existing.copy(themePreference = mode.name, updatedAtMillis = System.currentTimeMillis())
            )
        }
        return firestoreService.updateDocument(
            docRef = db.collection("users").document(uid),
            updates = mapOf("themePreference" to mode.name, "updatedAt" to FieldValue.serverTimestamp()),
            pathForLogs = "users/$uid"
        )
    }

    suspend fun syncPendingFamilies(userId: String): Result<Int> {
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user sync." }
            val pending = familyDao.getPendingFamilies(uid)
            var syncedCount = 0
            val userDisplay = auth.currentUser?.displayName?.trim()?.take(80)?.ifBlank { "Family Admin" } ?: "Family Admin"
            for (item in pending) {
                val docPath = "families/${item.familyId}"
                val familyRef = db.collection("families").document(item.familyId)
                val memberRef = familyRef.collection("members").document(uid)
                val cardRef = familyRef.collection("member_cards").document(uid)
                val existingLocalMember = memberDao.getMember(item.familyId, uid)
                val existingCard = localStore.getMemberCard(item.familyId, uid)
                val cardMemberId = existingCard?.cardMemberId?.takeIf { FamilySecurityHelper.isValidMemberCardId16(it) }
                    ?: existingLocalMember?.cardMemberId?.takeIf { FamilySecurityHelper.isValidMemberCardId16(it) }
                    ?: FamilySecurityHelper.generateMemberCardId16()
                val res = firestoreService.commitBatch(docPath) { batch ->
                    batch.set(
                        familyRef,
                        FamilyDoc(
                            name = item.name,
                            ownerId = item.ownerId,
                            createdByUserId = item.createdByUserId,
                            currencyCode = item.currencyCode,
                            memberUids = item.memberUids,
                            adminUids = item.adminUids,
                            description = item.description,
                            requireAdminApproval = item.requireAdminApproval,
                            memberChatEnabled = item.memberChatEnabled,
                            membersCanAddExpenses = item.membersCanAddExpenses,
                            budgetStartDay = item.budgetStartDay.toLong(),
                            chatRetentionDays = item.chatRetentionDays.toLong()
                        ).toCreateMap()
                    )
                    batch.set(
                        memberRef,
                        FamilyMemberDoc(
                            userId = uid,
                            familyId = item.familyId,
                            displayName = userDisplay,
                            role = FamilyRole.ADMIN.name,
                            memberUids = item.memberUids,
                            adminUids = item.adminUids,
                            relationLabel = "Family Admin",
                            cardMemberId = cardMemberId
                        ).toCreateMap()
                    )
                    batch.set(
                        cardRef,
                        MemberCardDoc(
                            userId = uid,
                            familyId = item.familyId,
                            cardMemberId = cardMemberId,
                            familyName = item.name,
                            displayName = userDisplay,
                            photoUrl = auth.currentUser?.photoUrl?.toString()?.take(500),
                            role = FamilyRole.ADMIN.name,
                            memberStatus = "ACTIVE",
                            memberUids = item.memberUids,
                            adminUids = item.adminUids
                        ).toCreateMap()
                    )
                }
                if (res.isSuccess) {
                    familyDao.upsertFamily(item.copy(syncStatus = "SYNCED"))
                    syncedCount++
                }
            }
            Result.success(syncedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "families")
            Result.failure(e)
        }
    }
}
