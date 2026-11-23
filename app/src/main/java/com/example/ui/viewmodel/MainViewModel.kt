package com.example.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.comms.ActiveCallForegroundService
import com.example.core.comms.ActiveCallMediaUiState
import com.example.core.comms.ActiveMeetingMediaUiState
import com.example.core.comms.LiveKitGroupMeetController
import com.example.core.comms.WebRtcOneToOneCallController
import com.example.core.firebase.AppCheckStatus
import com.example.core.firebase.AuthService
import com.example.core.firebase.ConnectionMonitor
import com.example.core.firebase.DatabaseInitReport
import com.example.core.firebase.DatabaseInitializer
import com.example.core.firebase.FirebaseConfigSnapshot
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.NetworkAvailability
import com.example.core.firebase.SyncConnectionState
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalMyJoinRequestEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalProposalVoteHistoryEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.LocalUserProfileEntity
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.AudioOutputRoute
import com.example.core.model.BudgetScope
import com.example.core.model.CallMediaType
import com.example.core.model.CallSessionDoc
import com.example.core.model.CallStatus
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.model.DecisionCategory
import com.example.core.model.DeviceSessionDoc
import com.example.core.model.DigitalMemberCardData
import com.example.core.model.EligibleVoterRule
import com.example.core.model.FamilyInvitationDoc
import com.example.core.model.FamilyJoinRequestDoc
import com.example.core.model.FamilyMeetingDoc
import com.example.core.model.FamilyMembershipState
import com.example.core.model.FamilyRole
import com.example.core.model.InvestmentAssetType
import com.example.core.model.JoinRequestStatus
import com.example.core.model.LoanDirection
import com.example.core.model.MeetInviteHelper
import com.example.core.model.MeetingParticipantDoc
import com.example.core.model.MeetingStatus
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MembershipNoticeDoc
import com.example.core.model.NotificationType
import com.example.core.model.PasskeyCredentialDoc
import com.example.core.model.PhoneOtpState
import com.example.core.model.RecordStatus
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecoveredFamilySummary
import com.example.core.model.ThemeMode
import com.example.core.model.TransactionType
import com.example.core.model.UiState
import com.example.core.model.VoteChoice
import com.example.core.model.WorkspaceMode
import com.example.core.notifications.AndroidNotificationHelper
import com.example.core.sync.CachedReportBuilder
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.SyncRunSummary
import com.example.data.repository.FamilyFinanceRepository
import com.example.ui.screens.modules.BudgetAlertLevel
import com.example.ui.screens.modules.BudgetSummaryCalculator
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val repository: FamilyFinanceRepository,
    private val connectionMonitor: ConnectionMonitor,
    private val databaseInitializer: DatabaseInitializer,
    val configSnapshot: FirebaseConfigSnapshot,
    private val currentUserId: String,
    private val firebaseUser: FirebaseUser?,
    private val authService: AuthService = AuthService(),
    private val notificationHelper: AndroidNotificationHelper? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val familyManagementRepo = repository.familyManagement
    private val dataArchitectureRepo = repository.dataArchitecture
    private val commsRepo = repository.commsCoordinator
    private val decisionsRepo = repository.decisions

    private val webRtcCallController: WebRtcOneToOneCallController? = appContext?.let {
        WebRtcOneToOneCallController(it.applicationContext)
    }
    private val liveKitMeetController: LiveKitGroupMeetController? = appContext?.let {
        LiveKitGroupMeetController(it.applicationContext)
    }

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _initReport = MutableStateFlow<DatabaseInitReport?>(null)
    val initReport: StateFlow<DatabaseInitReport?> = _initReport.asStateFlow()

    private val _latestCreatedInvitation = MutableStateFlow<CreateInvitationResponse?>(null)
    val latestCreatedInvitation: StateFlow<CreateInvitationResponse?> = _latestCreatedInvitation.asStateFlow()

    private val _recoveredFamilies = MutableStateFlow<List<RecoveredFamilySummary>>(emptyList())
    val recoveredFamilies: StateFlow<List<RecoveredFamilySummary>> = _recoveredFamilies.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow<SyncRunSummary?>(null)
    val lastSyncSummary: StateFlow<SyncRunSummary?> = _lastSyncSummary.asStateFlow()

    private val _sessionRevokedWarning = MutableStateFlow<String?>(null)
    val sessionRevokedWarning: StateFlow<String?> = _sessionRevokedWarning.asStateFlow()

    private val _chatPageLimit = MutableStateFlow(30)
    private val _isLoadingOlderChats = MutableStateFlow(false)
    val isLoadingOlderChats: StateFlow<Boolean> = _isLoadingOlderChats.asStateFlow()

    private val _hasMoreOlderChats = MutableStateFlow(true)
    val hasMoreOlderChats: StateFlow<Boolean> = _hasMoreOlderChats.asStateFlow()

    private val sessionStartedMillis = System.currentTimeMillis()
    private val notifiedAlertIds = mutableSetOf<String>()
    private val notifiedRejectedRequestKeys = mutableSetOf<String>()

    private val _activeCallId = MutableStateFlow<String?>(null)
    private val _remoteCalls = MutableStateFlow<List<CallSessionDoc>>(emptyList())
    private val _callDurationSeconds = MutableStateFlow(0)
    val callDurationSeconds: StateFlow<Int> = _callDurationSeconds.asStateFlow()

    private val _activeMeetingId = MutableStateFlow<String?>(null)
    private val _meetingDurationSeconds = MutableStateFlow(0)
    val meetingDurationSeconds: StateFlow<Int> = _meetingDurationSeconds.asStateFlow()

    private val _selectedProposalId = MutableStateFlow<String?>(null)
    val selectedProposalId: StateFlow<String?> = _selectedProposalId.asStateFlow()

    private val appliedAnswersByCall = mutableSetOf<String>()
    private val appliedRenegotiationVersion = mutableMapOf<String, Long>()
    private val processedIceCandidateIds = mutableSetOf<String>()
    private var ringTimeoutJob: Job? = null
    private var callTimerJob: Job? = null
    private var meetingTimerJob: Job? = null

    private val syncMutex = Mutex()

    val appCheckStatus: StateFlow<AppCheckStatus> = FirebaseServiceRegistry.appCheckStatus

    val connectionState: StateFlow<SyncConnectionState> = connectionMonitor.syncConnectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SyncConnectionState.LOCAL_CACHE_ONLY
        )

    val syncStatusLabel: StateFlow<String> = connectionState
        .map { it.label }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SyncConnectionState.LOCAL_CACHE_ONLY.label
        )

    val themeMode: StateFlow<ThemeMode> = repository.localThemeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThemeMode.SYSTEM
        )

    val conflictPolicy: StateFlow<ConflictResolutionPolicy> = repository.conflictPolicyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ConflictResolutionPolicy.MANUAL_REVIEW
        )

    val currentSessionId: StateFlow<String?> = repository.currentSessionIdFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val userProfile: StateFlow<LocalUserProfileEntity?> =
        repository.observeLocalUserProfile(currentUserId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = null
            )

    val enrolledPasskeys: StateFlow<List<PasskeyCredentialDoc>> =
        repository.syncRemotePasskeys(currentUserId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Passkeys snapshot warning", firestoreError ?: error)
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    val deviceSessions: StateFlow<List<DeviceSessionDoc>> =
        repository.syncRemoteSessions(currentUserId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Sessions snapshot warning", firestoreError ?: error)
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    val familiesState: StateFlow<UiState<List<LocalFamilyEntity>>> =
        repository.observeLocalFamilies(currentUserId)
            .map<List<LocalFamilyEntity>, UiState<List<LocalFamilyEntity>>> { list ->
                UiState.Success(list.filter { !it.isPersonal })
            }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(UiState.Error(error.message ?: "Unable to read local families"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val workspaceMode: StateFlow<WorkspaceMode> = repository.localWorkspaceMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = WorkspaceMode.FAMILY
        )

    val membershipNotices: StateFlow<List<MembershipNoticeDoc>> =
        repository.syncRemoteMembershipNotices(currentUserId)
            .catch { error ->
                handleStreamError("MembershipNotices", error)
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    val personalFamily: StateFlow<LocalFamilyEntity?> =
        repository.observeLocalFamilies(currentUserId)
            .map { list -> list.firstOrNull { it.isPersonal } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = null
            )

    val sharedFamilies: StateFlow<List<LocalFamilyEntity>> =
        repository.observeLocalFamilies(currentUserId)
            .map { list -> list.filter { !it.isPersonal && !it.isSuspended(currentUserId) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    val activeFamily: StateFlow<LocalFamilyEntity?> = combine(
        repository.observeLocalFamilies(currentUserId),
        repository.observeLocalUserProfile(currentUserId),
        repository.localActiveFamilyId,
        workspaceMode
    ) { families, profile, prefFamilyId, mode ->
        val personal = families.firstOrNull { it.isPersonal }
        val shared = families.filter { !it.isPersonal && !it.isSuspended(currentUserId) }
        if (mode == WorkspaceMode.PERSONAL || shared.isEmpty()) {
            personal ?: shared.firstOrNull()
        } else {
            val targetId = profile?.activeFamilyId ?: prefFamilyId
            shared.firstOrNull { it.familyId == targetId } ?: shared.firstOrNull() ?: personal
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )

    val otpState: StateFlow<PhoneOtpState> = authService.otpState

    private val _adminUnlockedFamilies = MutableStateFlow<Set<String>>(emptySet())
    val adminUnlockedFamilies: StateFlow<Set<String>> = _adminUnlockedFamilies.asStateFlow()

    val activeMembers: StateFlow<List<LocalFamilyMemberEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeLocalFamilyMembers(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val memberCardsState: StateFlow<List<LocalMemberCardEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeLocalMemberCards(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val approvalSettingsState: StateFlow<LocalApprovalSettingsEntity?> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(null)
            else decisionsRepo.observeApprovalSettings(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val proposalsState: StateFlow<List<LocalProposalEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else decisionsRepo.observeProposals(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val proposalVotesState: StateFlow<List<LocalProposalVoteEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else decisionsRepo.observeVotesForFamily(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val proposalHistoryState: StateFlow<List<LocalProposalVoteHistoryEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else decisionsRepo.observeAllHistoryForFamily(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val activeFamilyInvitations: StateFlow<List<FamilyInvitationDoc>> = activeFamily
        .map { fam ->
            if (fam != null && fam.isAdmin(currentUserId)) fam.familyId else null
        }
        .distinctUntilChanged()
        .flatMapLatest { adminFamilyId ->
            if (adminFamilyId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                familyManagementRepo.observeFamilyInvitations(currentUserId, adminFamilyId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Invitations snapshot warning", firestoreError ?: error)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val activeFamilyJoinRequests: StateFlow<List<FamilyJoinRequestDoc>> = activeFamily
        .map { fam ->
            if (fam != null && fam.isAdmin(currentUserId)) fam.familyId else null
        }
        .distinctUntilChanged()
        .flatMapLatest { adminFamilyId ->
            if (adminFamilyId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                familyManagementRepo.observePendingJoinRequests(currentUserId, adminFamilyId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Join requests snapshot warning", firestoreError ?: error)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val myJoinRequests: StateFlow<List<LocalMyJoinRequestEntity>> =
        familyManagementRepo.observeMyJoinRequests(currentUserId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    val membershipState: StateFlow<FamilyMembershipState> = combine(
        sharedFamilies,
        myJoinRequests,
        membershipNotices
    ) { shared, myReqs, notices ->
        when {
            shared.isNotEmpty() -> FamilyMembershipState.ACTIVE_MEMBER
            notices.any { it.status == "SUSPENDED" } -> FamilyMembershipState.SUSPENDED
            notices.any { it.status == "REMOVED" } -> FamilyMembershipState.REMOVED
            myReqs.any { it.status == JoinRequestStatus.PENDING_APPROVAL.name } -> FamilyMembershipState.PENDING_REQUEST
            else -> FamilyMembershipState.NO_FAMILY
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = FamilyMembershipState.NO_FAMILY
    )

    val databaseHealth: StateFlow<DatabaseHealthSnapshot> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) {
                flowOf(DatabaseHealthSnapshot())
            } else {
                dataArchitectureRepo.observeDatabaseHealth(familyId, currentUserId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DatabaseHealthSnapshot()
        )

    val recentAuditLogs: StateFlow<List<LocalAuditLogEntity>> = activeFamily
        .map { fam -> fam?.let { it.familyId to it.isAdmin(currentUserId) } }
        .distinctUntilChanged()
        .flatMapLatest { info ->
            if (info == null || info.first.isBlank()) {
                flowOf(emptyList())
            } else {
                val (familyId, isAdmin) = info
                dataArchitectureRepo.observeLocalAuditLogs(familyId, limit = 25)
                    .map { logs ->
                        if (isAdmin) logs else logs.filter { it.actorUserId == currentUserId }
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val allTransactions: StateFlow<List<LocalTransactionEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalTransactions(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val incomeTransactions: StateFlow<List<LocalTransactionEntity>> = allTransactions
        .map { list -> list.filter { it.transactionType == TransactionType.INCOME.name } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val expenseTransactions: StateFlow<List<LocalTransactionEntity>> = allTransactions
        .map { list -> list.filter { it.transactionType == TransactionType.EXPENSE.name } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val loansState: StateFlow<List<LocalLoanEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalLoans(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val savingsState: StateFlow<List<LocalSavingsEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalSavings(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val investmentsState: StateFlow<List<LocalInvestmentEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalInvestments(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val budgetsState: StateFlow<List<LocalBudgetEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalBudgets(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val financialEvents: StateFlow<List<LocalFinancialEventEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalFinancialEvents(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val notificationsState: StateFlow<List<LocalNotificationEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalNotifications(familyId, currentUserId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val outboxItems: StateFlow<List<LocalSyncOutboxEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeOutbox(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val syncConflicts: StateFlow<List<LocalSyncConflictEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeConflicts(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val cachedReports: StateFlow<List<LocalCachedReportEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeCachedReports(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val chatMessages: StateFlow<List<LocalChatMessageEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else dataArchitectureRepo.observeLocalChats(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val unreadChatCount: StateFlow<Int> = combine(activeFamily, chatMessages) { fam, msgs ->
        if (fam == null) {
            0
        } else {
            msgs.count { msg ->
                msg.recordStatus == RecordStatus.ACTIVE.name &&
                    msg.senderUserId != currentUserId &&
                    currentUserId !in msg.readByUids
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = 0
    )

    val unreadNotificationCount: StateFlow<Int> = notificationsState
        .map { list -> list.count { it.isUnreadFor(currentUserId) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = 0
        )

    val notificationsEnabled: StateFlow<Boolean> = repository.notificationsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = true
        )

    val callHistory: StateFlow<List<LocalCallRecordEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else commsRepo.observeLocalCallHistory(familyId, currentUserId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val meetingsState: StateFlow<List<LocalMeetingEntity>> = activeFamily
        .map { it?.familyId }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isNullOrBlank()) flowOf(emptyList())
            else commsRepo.observeLocalMeetings(familyId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val activeCallSession: StateFlow<CallSessionDoc?> = combine(_activeCallId, _remoteCalls) { activeId, calls ->
        if (activeId.isNullOrBlank()) null
        else calls.firstOrNull { (it.callId.ifBlank { it.id }) == activeId && !CallStatus.fromRaw(it.status).isTerminal }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )

    val incomingCallSession: StateFlow<CallSessionDoc?> = combine(_activeCallId, _remoteCalls) { activeId, calls ->
        calls.firstOrNull { call ->
            val cid = call.callId.ifBlank { call.id }
            cid != activeId &&
                call.calleeUid == currentUserId &&
                call.status == CallStatus.RINGING.name
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )

    val activeCallMediaState: StateFlow<ActiveCallMediaUiState> =
        webRtcCallController?.uiState ?: MutableStateFlow(ActiveCallMediaUiState())

    val activeMeetingDoc: StateFlow<LocalMeetingEntity?> = combine(_activeMeetingId, meetingsState) { id, list ->
        if (id.isNullOrBlank()) null else list.firstOrNull { it.meetingId == id }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )

    val meetingParticipants: StateFlow<List<MeetingParticipantDoc>> = _activeMeetingId
        .flatMapLatest { meetingId ->
            if (meetingId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                commsRepo.syncMeetingParticipants(currentUserId, meetingId)
                    .catch { err ->
                        handleStreamError("MeetingParticipants", err)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val activeMeetingMediaState: StateFlow<ActiveMeetingMediaUiState> =
        liveKitMeetController?.uiState ?: MutableStateFlow(ActiveMeetingMediaUiState())

    init {
        initializeUserAndSync()
    }

    private fun initializeUserAndSync() {
        viewModelScope.launch {
            repository.ensurePersonalWorkspaceInitialized(
                uid = currentUserId,
                displayName = firebaseUser?.displayName ?: "Personal Owner"
            )
            firebaseUser?.let { user ->
                _initReport.value = databaseInitializer.initializeSession(user)
                repository.trackCurrentSession(authService, user)
            }
        }

        repository.syncRemoteUserProfile(currentUserId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Profile snapshot stream warning", firestoreError ?: error)
            }
            .launchIn(viewModelScope)

        repository.syncRemoteFamilies(currentUserId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Families snapshot stream warning", firestoreError ?: error)
            }
            .launchIn(viewModelScope)

        activeFamily
            .map { it?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    repository.syncRemoteFamilyMembers(currentUserId, familyId)
                        .catch { error ->
                            val firestoreError = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (firestoreError == null && error is CancellationException) throw error
                            Log.w(TAG, "Members snapshot stream warning", firestoreError ?: error)
                            emit(emptyList())
                        }
                }
            }
            .launchIn(viewModelScope)

        activeFamily
            .map { it?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    repository.syncRemoteMemberCards(currentUserId, familyId)
                        .catch { error ->
                            handleStreamError("MemberCards", error)
                            emit(emptyList())
                        }
                }
            }
            .launchIn(viewModelScope)

        combine(
            activeFamily,
            activeMembers,
            userProfile.map { it?.photoUrl }.distinctUntilChanged()
        ) { fam, members, profilePhoto ->
            Triple(fam, members, profilePhoto)
        }
            .onEach { (fam, members, profilePhoto) ->
                if (fam != null && members.isNotEmpty()) {
                    repository.ensureMemberCardsProvisioned(
                        family = fam,
                        members = members,
                        currentUserPhotoUrl = profilePhoto ?: firebaseUser?.photoUrl?.toString()
                    )
                }
            }
            .catch { err -> handleStreamError("MemberCardProvisioning", err) }
            .launchIn(viewModelScope)

        // Family Approval Rules & Proposals real-time Firestore sync
        activeFamily
            .map { it?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId.isNullOrBlank()) {
                    flowOf(Unit)
                } else {
                    merge(
                        decisionsRepo.syncRemoteApprovalSettings(familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("ApprovalSettings", err) },
                        decisionsRepo.syncRemoteProposals(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Proposals", err) }
                    )
                }
            }
            .launchIn(viewModelScope)

        _selectedProposalId
            .flatMapLatest { propId ->
                if (propId.isNullOrBlank()) flowOf(Unit)
                else decisionsRepo.syncRemoteVotesAndHistoryForProposal(propId)
                    .catch { err -> handleStreamError("ProposalDetailSync", err) }
            }
            .launchIn(viewModelScope)

        combine(activeFamily, proposalsState, proposalVotesState) { fam, props, votes ->
            Triple(fam, props, votes)
        }
            .onEach { (fam, props, votes) ->
                if (fam != null && props.isNotEmpty()) {
                    decisionsRepo.evaluateDeadlinesAndAlerts(
                        family = fam,
                        proposals = props,
                        votes = votes
                    )
                }
            }
            .catch { err -> handleStreamError("ProposalDeadlines", err) }
            .launchIn(viewModelScope)

        activeFamily
            .map { it?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId.isNullOrBlank()) {
                    flowOf(Unit)
                } else {
                    merge(
                        dataArchitectureRepo.syncRemoteTransactions(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Transactions", err) },
                        dataArchitectureRepo.syncRemoteLoans(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Loans", err) },
                        dataArchitectureRepo.syncRemoteSavings(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Savings", err) },
                        dataArchitectureRepo.syncRemoteInvestments(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Investments", err) },
                        dataArchitectureRepo.syncRemoteBudgets(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Budgets", err) },
                        dataArchitectureRepo.syncRemoteFinancialEvents(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("FinancialEvents", err) },
                        dataArchitectureRepo.syncRemoteNotifications(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Notifications", err) }
                    )
                }
            }
            .launchIn(viewModelScope)

        activeFamily
            .map { fam -> fam?.let { it.familyId to it.isAdmin(currentUserId) } }
            .distinctUntilChanged()
            .flatMapLatest { info ->
                if (info == null || info.first.isBlank()) {
                    flowOf(Unit)
                } else {
                    dataArchitectureRepo.syncRemoteAuditLogs(
                        userId = currentUserId,
                        familyId = info.first,
                        isAdmin = info.second
                    )
                        .map { Unit }
                        .catch { err -> handleStreamError("AuditLogs", err) }
                }
            }
            .launchIn(viewModelScope)

        combine(
            activeFamily.map { it?.familyId }.distinctUntilChanged(),
            _chatPageLimit
        ) { familyId, limit -> familyId to limit }
            .flatMapLatest { (familyId, limit) ->
                if (familyId.isNullOrBlank()) {
                    flowOf(Unit)
                } else {
                    dataArchitectureRepo.syncRemoteChats(currentUserId, familyId, pageLimit = limit)
                        .map { Unit }
                        .catch { err -> handleStreamError("Chats", err) }
                }
            }
            .launchIn(viewModelScope)

        var wasOffline = false
        combine(
            connectionMonitor.networkAvailability,
            activeFamily.map { it?.familyId }.distinctUntilChanged(),
            outboxItems.map { list -> list.count { it.status == "PENDING" } }.distinctUntilChanged()
        ) { availability, familyId, pendingCount ->
            Triple(availability, familyId, pendingCount)
        }
            .onEach { (availability, familyId, pendingCount) ->
                when (availability) {
                    NetworkAvailability.OFFLINE -> wasOffline = true
                    NetworkAvailability.ONLINE -> {
                        val shouldFlushOnReconnect = wasOffline && familyId != null
                        val shouldFlushPendingStartup = !wasOffline && familyId != null && pendingCount > 0
                        if (shouldFlushOnReconnect || shouldFlushPendingStartup) {
                            wasOffline = false
                            syncNow(autoTriggered = true)
                        }
                    }
                }
            }
            .launchIn(viewModelScope)

        combine(deviceSessions, currentSessionId) { sessions, sessionId ->
            sessionId != null && sessions.any { it.sessionId == sessionId && it.revoked }
        }.distinctUntilChanged()
            .onEach { revoked ->
                if (revoked) {
                    repository.purgeAllLocalData()
                    _sessionRevokedWarning.value =
                        "This device session was revoked. Local financial cache has been wiped for security."
                }
            }
            .catch { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "Session revocation monitor warning", err)
            }
            .launchIn(viewModelScope)

        combine(
            combine(activeFamily, allTransactions, loansState) { fam, tx, loans -> Triple(fam, tx, loans) },
            combine(savingsState, investmentsState, budgetsState) { sav, inv, bud -> Triple(sav, inv, bud) },
            connectionState
        ) { left, right, conn ->
            val fam = left.first ?: return@combine
            val synced = conn != SyncConnectionState.OFFLINE_QUEUED
            dataArchitectureRepo.refreshCachedReport(
                family = fam,
                periodKey = CachedReportBuilder.PERIOD_ALL,
                transactions = left.second,
                loans = left.third,
                savings = right.first,
                investments = right.second,
                budgets = right.third,
                isOnlineSynced = synced
            )
            dataArchitectureRepo.refreshCachedReport(
                family = fam,
                periodKey = BudgetSummaryCalculator.currentPeriodKey(),
                transactions = left.second,
                loans = left.third,
                savings = right.first,
                investments = right.second,
                budgets = right.third,
                isOnlineSynced = synced
            )
        }.catch { err ->
            if (err is CancellationException) throw err
            Log.w(TAG, "Cached report refresh warning", err)
        }.launchIn(viewModelScope)

        combine(activeFamily, budgetsState, expenseTransactions) { family, budgets, expenses ->
            Triple(family, budgets, expenses)
        }.onEach { (family, budgets, expenses) ->
            if (family == null || budgets.isEmpty()) return@onEach
            val periods = budgets.map { it.periodKey }.distinct()
            for (periodKey in periods) {
                val summary = BudgetSummaryCalculator.summarizeForPeriod(budgets, expenses, periodKey)
                val allEvaluated = listOfNotNull(summary.overallBudget) + summary.categoryBudgets
                for (evaluated in allEvaluated) {
                    if (evaluated.alertLevel != BudgetAlertLevel.OK) {
                        dataArchitectureRepo.recordBudgetAlertIfNeeded(
                            budget = evaluated.entity,
                            spentMinor = evaluated.spentMinor,
                            alertLevel = evaluated.alertLevel.name,
                            memberUids = family.memberUids
                        )
                    }
                }
            }
        }.catch { err ->
            if (err is CancellationException) throw err
            Log.w(TAG, "Budget alert monitor warning", err)
        }.launchIn(viewModelScope)

        activeFamily
            .distinctUntilChanged { old, new -> old?.familyId == new?.familyId }
            .flatMapLatest { family ->
                if (family == null) {
                    flowOf(0)
                } else {
                    kotlinx.coroutines.flow.flow {
                        val generated = dataArchitectureRepo.materializeDueRecurringTransactions(
                            familyId = family.familyId,
                            memberUids = family.memberUids,
                            adminUids = family.adminUids
                        ).getOrDefault(0)
                        emit(generated)
                    }
                }
            }
            .catch { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "Recurring materializer warning", err)
            }
            .launchIn(viewModelScope)

        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.registerFcmToken(tokenOverride = token)
                    }
                }
            }
        }

        combine(activeFamily, loansState) { fam, loans -> fam to loans }
            .onEach { (fam, loans) ->
                if (fam != null && loans.isNotEmpty()) {
                    dataArchitectureRepo.evaluateLoanDueDateNotifications(
                        familyId = fam.familyId,
                        loans = loans,
                        memberUids = fam.memberUids
                    )
                }
            }
            .catch { err -> handleStreamError("LoanDueDates", err) }
            .launchIn(viewModelScope)

        activeFamily
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                old.familyId == new.familyId && old.chatRetentionDays == new.chatRetentionDays
            }
            .onEach { family ->
                if (family.chatRetentionDays > 0) {
                    dataArchitectureRepo.purgeExpiredChatMessages(family)
                }
            }
            .catch { err -> handleStreamError("ChatRetention", err) }
            .launchIn(viewModelScope)

        combine(activeFamily, activeFamilyJoinRequests) { fam, reqs -> fam to reqs }
            .onEach { (fam, reqs) ->
                if (fam != null && fam.isAdmin(currentUserId)) {
                    reqs.forEach { req ->
                        dataArchitectureRepo.recordPendingJoinRequestAlert(
                            familyId = fam.familyId,
                            requesterUid = req.userId.ifBlank { req.id },
                            requesterName = req.displayName,
                            relationLabel = req.relationLabel ?: "Member",
                            memberUids = fam.memberUids
                        )
                    }
                }
            }
            .catch { err -> handleStreamError("JoinRequestAlerts", err) }
            .launchIn(viewModelScope)

        myJoinRequests
            .onEach { requests ->
                if (!notificationsEnabled.value) return@onEach
                requests.filter {
                    it.status == JoinRequestStatus.REJECTED.name &&
                        it.updatedAtMillis >= sessionStartedMillis - 5_000L
                }.forEach { req ->
                    val key = "rejected_${req.familyId}_${req.updatedAtMillis}"
                    if (notifiedRejectedRequestKeys.add(key)) {
                        notificationHelper?.postNotification(
                            notificationId = key,
                            title = "Join Request Declined",
                            body = "Your request to join ${req.familyName} was declined by a Family Admin.",
                            notificationType = NotificationType.INVITATION_APPROVAL.name,
                            deepLinkModule = "FAMILY_MANAGEMENT",
                            relatedRecordId = req.familyId
                        )
                    }
                }
            }
            .catch { err -> handleStreamError("MyJoinRequestAlerts", err) }
            .launchIn(viewModelScope)

        combine(notificationsState, notificationsEnabled) { list, enabled -> list to enabled }
            .onEach { (list, enabled) ->
                if (!enabled || notificationHelper == null) return@onEach
                list.forEach { ntf ->
                    val shouldAlert = ntf.isUnreadFor(currentUserId) &&
                        ntf.createdByUserId != currentUserId &&
                        ntf.createdAtMillis >= sessionStartedMillis - 5_000L &&
                        notifiedAlertIds.add(ntf.notificationId)
                    if (shouldAlert) {
                        notificationHelper.postNotification(
                            notificationId = ntf.notificationId,
                            title = ntf.title,
                            body = ntf.body,
                            notificationType = ntf.notificationType,
                            deepLinkModule = ntf.deepLinkModule
                                ?: NotificationType.fromRaw(ntf.notificationType).defaultModuleName,
                            relatedRecordId = ntf.relatedRecordId
                        )
                    }
                }
            }
            .catch { err -> handleStreamError("NotificationDispatch", err) }
            .launchIn(viewModelScope)

        // Real-time 1-to-1 call signaling & Group Family Meet metadata streams
        activeFamily
            .map { it?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId.isNullOrBlank()) {
                    _remoteCalls.value = emptyList()
                    flowOf(Unit)
                } else {
                    merge(
                        commsRepo.syncRemoteCalls(currentUserId, familyId)
                            .onEach { docs -> _remoteCalls.value = docs }
                            .map { Unit }
                            .catch { err -> handleStreamError("Calls", err) },
                        commsRepo.syncRemoteMeetings(currentUserId, familyId)
                            .map { Unit }
                            .catch { err -> handleStreamError("Meetings", err) }
                    )
                }
            }
            .launchIn(viewModelScope)

        // WebRTC answer SDP, renegotiation SDP, and terminal call cleanup observer
        combine(_activeCallId, _remoteCalls) { activeId, calls ->
            activeId to calls.firstOrNull { (it.callId.ifBlank { it.id }) == activeId }
        }.onEach { (activeId, doc) ->
            if (activeId == null) return@onEach
            if (doc == null) return@onEach
            val status = CallStatus.fromRaw(doc.status)
            if (status.isTerminal) {
                ringTimeoutJob?.cancel()
                callTimerJob?.cancel()
                webRtcCallController?.endAndRelease()
                appContext?.let { ActiveCallForegroundService.stop(it) }
                _activeCallId.value = null
                _callDurationSeconds.value = 0
                if (status == CallStatus.REJECTED && doc.callerUid == currentUserId) {
                    _statusMessage.value = "${doc.calleeName} declined the call."
                }
                return@onEach
            }
            if (doc.callerUid == currentUserId && !doc.answerSdp.isNullOrBlank()) {
                ringTimeoutJob?.cancel()
                val answerKey = "$activeId:${doc.answerSdp.hashCode()}"
                if (appliedAnswersByCall.add(answerKey)) {
                    webRtcCallController?.applyRemoteAnswer(
                        answerSdp = doc.answerSdp,
                        answerType = doc.answerType ?: "answer"
                    )
                }
            }
            if (doc.calleeUid == currentUserId && doc.iceRestartCount > 0L && !doc.offerSdp.isNullOrBlank()) {
                val prevVer = appliedRenegotiationVersion[activeId] ?: 0L
                if (doc.iceRestartCount > prevVer) {
                    appliedRenegotiationVersion[activeId] = doc.iceRestartCount
                    webRtcCallController?.applyIncomingRenegotiationOffer(
                        offerSdp = doc.offerSdp,
                        offerType = doc.offerType ?: "offer"
                    ) { answerSdp, answerType ->
                        viewModelScope.launch {
                            commsRepo.publishCallAnswer(activeId, answerSdp, answerType)
                        }
                    }
                }
            }
            if (status == CallStatus.ACTIVE && callTimerJob?.isActive != true) {
                callTimerJob = viewModelScope.launch {
                    while (isActive) {
                        delay(1000L)
                        _callDurationSeconds.value += 1
                    }
                }
            }
        }.catch { err -> handleStreamError("CallSignalingState", err) }
            .launchIn(viewModelScope)

        // Trickle ICE candidate stream for active 1-to-1 call
        _activeCallId
            .flatMapLatest { callId ->
                if (callId.isNullOrBlank()) flowOf(emptyList())
                else commsRepo.syncRemoteIceCandidates(currentUserId, callId)
                    .catch { err ->
                        handleStreamError("IceCandidates", err)
                        emit(emptyList())
                    }
            }
            .onEach { candidates ->
                candidates.forEach { cand ->
                    val cid = cand.candidateId.ifBlank { cand.id }
                    if (cand.senderUid != currentUserId && processedIceCandidateIds.add(cid)) {
                        webRtcCallController?.addRemoteIceCandidate(
                            sdpMid = cand.sdpMid,
                            sdpMLineIndex = cand.sdpMLineIndex.toInt(),
                            candidate = cand.candidate
                        )
                    }
                }
            }
            .launchIn(viewModelScope)

        // Enforce host moderation (remote mute / removal) and meeting-ended close in real time
        combine(activeMeetingDoc, meetingParticipants) { meeting, parts -> meeting to parts }
            .onEach { (meeting, parts) ->
                val activeId = _activeMeetingId.value ?: return@onEach
                if (meeting != null && MeetingStatus.fromRaw(meeting.status).isTerminal) {
                    _statusMessage.value = "Meeting \"${meeting.title}\" ended by host."
                    leaveActiveMeeting(localOnlyCleanup = true)
                    return@onEach
                }
                val myPart = parts.firstOrNull { (it.participantUid.ifBlank { it.id }) == currentUserId }
                if (myPart != null) {
                    if (myPart.removedByHost) {
                        _statusMessage.value = "You were removed from the meeting by the host."
                        leaveActiveMeeting(localOnlyCleanup = true)
                        return@onEach
                    }
                    if (myPart.mutedByHost && activeMeetingMediaState.value.localMicEnabled) {
                        liveKitMeetController?.setMicrophoneEnabled(false)
                        _statusMessage.value = "The meeting host muted your microphone."
                    }
                }
                if (_activeMeetingId.value == activeId && meetingTimerJob?.isActive != true) {
                    meetingTimerJob = viewModelScope.launch {
                        while (isActive) {
                            delay(1000L)
                            _meetingDurationSeconds.value += 1
                        }
                    }
                }
            }
            .catch { err -> handleStreamError("MeetingModeration", err) }
            .launchIn(viewModelScope)
    }

    private fun handleStreamError(label: String, error: Throwable) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        Log.w(TAG, "$label snapshot stream warning", firestoreError ?: error)
    }

    fun enrollPasskey(activity: Activity, credentialManager: CredentialManager, label: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = authService.enrollPasskey(activity, credentialManager, label)
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Passkey enrolled on this device (private key stored in hardware authenticator)."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Passkey enrollment failed."
            }
        }
    }

    fun deletePasskey(credentialId: String) {
        viewModelScope.launch {
            repository.deletePasskey(credentialId)
                .onSuccess { _statusMessage.value = "Passkey removed from your account." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to remove passkey." }
        }
    }

    fun revokeSession(sessionId: String) {
        viewModelScope.launch {
            repository.revokeSession(sessionId)
                .onSuccess { _statusMessage.value = "Device session revoked." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Failed to revoke session." }
        }
    }

    fun revokeAllOtherSessions() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.revokeAllOtherSessions(authService)
            _isBusy.value = false
            result.onSuccess { count ->
                _statusMessage.value = "Revoked refresh tokens and $count session(s)."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Server-side session revocation unavailable."
            }
        }
    }

    fun toggleNewDeviceCheck(enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleNewDeviceCheck(enabled)
                .onSuccess {
                    _statusMessage.value = if (enabled) {
                        "New-device SMS OTP verification enabled."
                    } else {
                        "New-device verification paused."
                    }
                }
                .onFailure { err -> _statusMessage.value = err.message }
        }
    }

    fun switchWorkspaceMode(mode: WorkspaceMode) {
        viewModelScope.launch {
            repository.setWorkspaceMode(mode)
            _statusMessage.value = when (mode) {
                WorkspaceMode.PERSONAL -> "Switched to private Personal Finance workspace."
                WorkspaceMode.FAMILY -> if (sharedFamilies.value.isNotEmpty()) {
                    "Switched to shared Family Finance workspace."
                } else {
                    "No active shared family yet — staying in Personal Finance until approved."
                }
            }
        }
    }

    fun dismissMembershipNotice(familyId: String) {
        viewModelScope.launch {
            repository.dismissMembershipNotice(familyId)
                .onSuccess { _statusMessage.value = "Notice dismissed." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to dismiss notice." }
        }
    }

    fun verifyFamilyAdminLogin(familyId: String, familyPassword: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = authService.verifyFamilyAdminLogin(familyId, familyPassword)
            _isBusy.value = false
            res.onSuccess { verified ->
                _adminUnlockedFamilies.value = _adminUnlockedFamilies.value + verified.familyId
                repository.setWorkspaceMode(WorkspaceMode.FAMILY)
                repository.switchActiveFamily(verified.familyId)
                _statusMessage.value = "Family Admin access unlocked for ${verified.familyId}."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Family Admin verification failed."
            }
        }
    }

    fun contributeFromPersonalToFamily(
        targetFamilyId: String,
        amountMinor: Long,
        category: String,
        description: String
    ) {
        val personal = personalFamily.value ?: run {
            _statusMessage.value = "Personal Finance workspace is still initializing."
            return
        }
        val target = sharedFamilies.value.firstOrNull { it.familyId == targetFamilyId } ?: run {
            _statusMessage.value = "Target shared family is not active."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.contributeFromPersonalToFamily(
                personalFamily = personal,
                targetFamily = target,
                amountMinor = amountMinor,
                category = category,
                description = description
            )
            _isBusy.value = false
            res.onSuccess {
                _statusMessage.value = "Recorded personal transfer and added contribution to \"${target.name}\"."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to contribute from Personal Finance."
            }
        }
    }

    fun updateMemberCardPrivacy(settings: MemberPrivacySettings) {
        val fam = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = repository.updateMemberCardPrivacy(fam.familyId, settings)
            _isBusy.value = false
            res.onSuccess {
                _statusMessage.value = "Member Card privacy preferences updated."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save Member Card privacy preferences."
            }
        }
    }

    fun recordMemberProfileViewAudit(targetCard: DigitalMemberCardData) {
        val fam = activeFamily.value ?: return
        if (fam.familyId != targetCard.familyId) return
        viewModelScope.launch {
            repository.recordAdminMemberAuditLog(fam, targetCard)
        }
    }

    fun sendOtpForMobileChange(activity: Activity, newPhoneNumber: String, isResend: Boolean = false) {
        viewModelScope.launch {
            authService.sendSmsOtp(activity, repository.prefsStore, newPhoneNumber, isResend)
        }
    }

    fun verifyAndChangeMobileNumber(
        verificationId: String,
        smsCode: String,
        newPhoneNumber: String,
        revokeOtherSessions: Boolean
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val otpRes = authService.verifySmsOtpCode(verificationId, smsCode, newPhoneNumber, repository.prefsStore)
            val verified = otpRes.getOrElse { err ->
                _isBusy.value = false
                _statusMessage.value = err.message ?: "SMS OTP verification failed."
                return@launch
            }
            val updateRes = authService.changeVerifiedMobileNumber(
                newPhoneNumber = verified.phoneNumber,
                newPhoneIdToken = verified.idToken,
                revokeOtherSessions = revokeOtherSessions,
                prefsStore = repository.prefsStore
            )
            _isBusy.value = false
            updateRes.onSuccess { resp ->
                authService.phoneOtpService.resetState()
                _statusMessage.value = "Verified mobile & Member ID updated to ${resp.maskedPhone}."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update verified mobile number."
            }
        }
    }

    fun resetMobileOtpState() {
        authService.phoneOtpService.resetState()
    }

    fun createFamily(
        name: String,
        currencyCode: String,
        description: String?,
        familyPassword: String
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.createFamily(
                name = name,
                currencyCode = currencyCode,
                description = description,
                familyPassword = familyPassword,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess { created ->
                _statusMessage.value = if (created.syncStatus == "SYNCED") {
                    "Family \"${created.name}\" (${created.familyId}) created as Primary Admin."
                } else {
                    "Family \"${created.name}\" saved locally (sync pending)."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to create family workspace."
            }
        }
    }

    fun requestJoinByFamilyPassword(
        familyId: String,
        familyPassword: String,
        relationLabel: String
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.requestJoinByFamilyPassword(
                rawFamilyId = familyId,
                familyPassword = familyPassword,
                relationLabel = relationLabel,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusMessage.value = "Join request sent to \"${resp.familyName}\". Awaiting Primary Admin approval."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to submit join request."
            }
        }
    }

    fun setMemberSuspension(familyId: String, targetUid: String, suspend: Boolean) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.setMemberSuspension(familyId, targetUid, suspend, authService)
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = if (suspend) "Member suspended from family access." else "Member access reinstated."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update member suspension status."
            }
        }
    }

    fun changeFamilyPassword(familyId: String, currentPassword: String, newPassword: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.changeFamilyPassword(
                familyId = familyId,
                currentFamilyPassword = currentPassword,
                newFamilyPassword = newPassword,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Family Password updated securely."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update Family Password."
            }
        }
    }

    fun createFamilyInvitation(
        familyId: String,
        familyName: String,
        targetRole: FamilyRole,
        inviteeHint: String?,
        expiresInHours: Int,
        maxUses: Int
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.createFamilyInvitation(
                familyId = familyId,
                familyName = familyName,
                targetRole = targetRole,
                inviteeHint = inviteeHint,
                expiresInHours = expiresInHours,
                maxUses = maxUses,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess { created ->
                _latestCreatedInvitation.value = created
                _statusMessage.value = "Secure invitation token generated (${created.tokenPreview})."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Failed to generate invitation token."
            }
        }
    }

    fun clearLatestCreatedInvitation() {
        _latestCreatedInvitation.value = null
    }

    fun revokeInvitation(familyId: String, invitationId: String, tokenHash: String) {
        viewModelScope.launch {
            familyManagementRepo.revokeInvitation(familyId, invitationId, tokenHash, authService)
                .onSuccess { _statusMessage.value = "Invitation token revoked." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to revoke invitation." }
        }
    }

    fun requestJoinWithToken(invitationToken: String, relationLabel: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.requestJoinWithInvitation(
                rawInvitationToken = invitationToken,
                relationLabel = relationLabel,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusMessage.value = "Join request sent to \"${resp.familyName}\". Awaiting Family Admin approval."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to submit join request."
            }
        }
    }

    fun refreshMyJoinRequests() {
        viewModelScope.launch {
            familyManagementRepo.refreshJoinRequestStatuses(currentUserId, myJoinRequests.value)
        }
    }

    fun reviewJoinRequest(familyId: String, requesterUid: String, approve: Boolean) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.reviewJoinRequest(familyId, requesterUid, approve, authService)
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = if (approve) {
                    "Member approved and granted authorized family access."
                } else {
                    "Join request rejected."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to review join request."
            }
        }
    }

    fun updateMemberRoleAndPermissions(
        familyId: String,
        targetUid: String,
        newRole: FamilyRole,
        canCreateRecords: Boolean,
        canViewSharedFinances: Boolean,
        canParticipateInChat: Boolean
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.updateMemberRoleAndPermissions(
                familyId = familyId,
                targetUid = targetUid,
                newRole = newRole,
                canCreateRecords = canCreateRecords,
                canViewSharedFinances = canViewSharedFinances,
                canParticipateInChat = canParticipateInChat,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Updated member role to ${newRole.label}."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Failed to update member role."
            }
        }
    }

    fun removeMember(familyId: String, targetUid: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.removeMember(familyId, targetUid, authService)
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = if (targetUid == currentUserId) {
                    "You left the family workspace."
                } else {
                    "Member removed from family workspace."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to remove member."
            }
        }
    }

    fun transferOwnership(familyId: String, newOwnerUid: String, familyPassword: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.transferOwnership(
                familyId = familyId,
                newOwnerUid = newOwnerUid,
                familyPassword = familyPassword,
                authService = authService
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Primary Admin transferred. Original creator Family ID link preserved."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Ownership transfer failed."
            }
        }
    }

    fun updateFamilySettings(
        familyId: String,
        name: String,
        currencyCode: String,
        description: String?,
        memberChatEnabled: Boolean,
        membersCanAddExpenses: Boolean,
        budgetStartDay: Int,
        chatRetentionDays: Int = activeFamily.value?.chatRetentionDays ?: 90
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.updateFamilySettings(
                familyId = familyId,
                name = name,
                currencyCode = currencyCode,
                description = description,
                memberChatEnabled = memberChatEnabled,
                membersCanAddExpenses = membersCanAddExpenses,
                budgetStartDay = budgetStartDay,
                authService = authService,
                chatRetentionDays = chatRetentionDays
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Family profile & settings saved."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save family settings."
            }
        }
    }

    fun recoverAdminFamilyIds() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = familyManagementRepo.recoverAdminFamilies(authService)
            _isBusy.value = false
            result.onSuccess { list ->
                _recoveredFamilies.value = list
                _statusMessage.value = if (list.isEmpty()) {
                    "No Admin-linked Family IDs found for UID $currentUserId."
                } else {
                    "Recovered ${list.size} Family ID(s) linked to your authenticated account."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to recover Family IDs right now."
            }
        }
    }

    fun createTransaction(
        transactionType: TransactionType,
        amountMinor: Long,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency
    ) {
        val family = activeFamily.value
        if (family == null) {
            _statusMessage.value = "Create or join a family workspace before recording transactions."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dataArchitectureRepo.createTransactionMinor(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                amountMinor = amountMinor,
                currency = family.currencyCode,
                category = category,
                description = description,
                transactionType = transactionType,
                memberId = memberId.ifBlank { currentUserId },
                paymentMethod = paymentMethod,
                occurredAtMillis = occurredAtMillis,
                recurrenceFrequency = recurrenceFrequency
            )
            _isBusy.value = false
            result.onSuccess { write ->
                _statusMessage.value = when {
                    write.deduplicatedExisting -> "Duplicate transaction prevented cleanly."
                    write.record.syncStatus == "SYNCED" -> "${transactionType.label} recorded and synced."
                    else -> "${transactionType.label} saved offline in SQLite (sync queued)."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save ${transactionType.label.lowercase()}."
            }
        }
    }

    fun updateTransaction(
        recordId: String,
        amountMinor: Long,
        category: String,
        description: String,
        memberId: String,
        paymentMethod: String?,
        occurredAtMillis: Long,
        recurrenceFrequency: RecurrenceFrequency,
        keepRecurringActive: Boolean
    ) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dataArchitectureRepo.updateTransactionMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                amountMinor = amountMinor,
                currency = family.currencyCode,
                category = category,
                description = description,
                memberId = memberId,
                paymentMethod = paymentMethod,
                occurredAtMillis = occurredAtMillis,
                recurrenceFrequency = recurrenceFrequency,
                keepRecurringActive = keepRecurringActive
            )
            _isBusy.value = false
            result.onSuccess { updated ->
                _statusMessage.value = if (updated.syncStatus == "SYNCED") {
                    "Transaction updated and synced."
                } else {
                    "Transaction updated locally (sync queued)."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update transaction."
            }
        }
    }

    fun deleteTransaction(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dataArchitectureRepo.deleteTransaction(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = "Transaction deleted and recorded in family audit log."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to delete transaction."
            }
        }
    }

    fun postNextRecurringNow(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dataArchitectureRepo.materializeDueRecurringTransactions(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                forceTemplateId = recordId
            )
            _isBusy.value = false
            result.onSuccess { count ->
                _statusMessage.value = if (count > 0) {
                    "Posted next recurring transaction occurrence."
                } else {
                    "This recurring cycle was already posted (duplicate prevented)."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to post recurring transaction."
            }
        }
    }

    fun toggleRecurringSchedule(recordId: String, active: Boolean) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dataArchitectureRepo.toggleRecurringTemplate(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                active = active
            )
            _isBusy.value = false
            result.onSuccess {
                _statusMessage.value = if (active) {
                    "Recurring schedule resumed."
                } else {
                    "Recurring schedule paused."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update recurring schedule."
            }
        }
    }

    // --- Prompt 08: Loans, Savings, Investments & Budgets ---

    fun createLoan(
        loanDirection: LoanDirection,
        principalMinor: Long,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long,
        dueDateMillis: Long?,
        description: String
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select or create a family workspace first."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.createLoanMinor(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                loanDirection = loanDirection,
                principalMinor = principalMinor,
                currency = family.currencyCode,
                category = category,
                borrowerName = borrowerName,
                borrowerContact = borrowerContact,
                lenderName = lenderName,
                lenderContact = lenderContact,
                interestRateApr = interestRateApr,
                totalInstallments = totalInstallments,
                installmentAmountMinor = installmentAmountMinor,
                startDateMillis = startDateMillis,
                dueDateMillis = dueDateMillis,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { write ->
                _statusMessage.value = when {
                    write.deduplicatedExisting -> "Duplicate loan entry prevented."
                    write.record.syncStatus == "SYNCED" -> "${loanDirection.label} loan recorded and synced."
                    else -> "${loanDirection.label} loan saved locally (sync queued)."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save loan."
            }
        }
    }

    fun updateLoan(
        recordId: String,
        loanDirection: LoanDirection,
        principalMinor: Long,
        remainingBalanceMinor: Long,
        category: String,
        borrowerName: String,
        borrowerContact: String,
        lenderName: String,
        lenderContact: String,
        interestRateApr: Double?,
        totalInstallments: Int,
        paidInstallments: Int,
        installmentAmountMinor: Long,
        startDateMillis: Long?,
        dueDateMillis: Long?,
        description: String
    ) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.updateLoanMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                loanDirection = loanDirection,
                principalMinor = principalMinor,
                remainingBalanceMinor = remainingBalanceMinor,
                currency = family.currencyCode,
                category = category,
                borrowerName = borrowerName,
                borrowerContact = borrowerContact,
                lenderName = lenderName,
                lenderContact = lenderContact,
                interestRateApr = interestRateApr,
                totalInstallments = totalInstallments,
                paidInstallments = paidInstallments,
                installmentAmountMinor = installmentAmountMinor,
                startDateMillis = startDateMillis,
                dueDateMillis = dueDateMillis,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Loan updated." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to update loan." }
        }
    }

    fun recordLoanRepayment(recordId: String, repaymentMinor: Long, paymentDateMillis: Long, note: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.recordLoanRepaymentMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                repaymentMinor = repaymentMinor,
                paymentDateMillis = paymentDateMillis,
                note = note
            )
            _isBusy.value = false
            res.onSuccess { updated ->
                _statusMessage.value = if (updated.effectiveRemainingMinor() == 0L) {
                    "Repayment recorded. Loan is now settled!"
                } else {
                    "Loan repayment recorded in repayment history."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to record repayment."
            }
        }
    }

    fun deleteLoan(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.deleteLoan(recordId, family.familyId, family.memberUids, family.adminUids)
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Loan deleted and audited." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to delete loan." }
        }
    }

    fun createSavingsGoal(
        goalName: String,
        category: String,
        initialAmountMinor: Long,
        targetAmountMinor: Long,
        targetDateMillis: Long?,
        description: String
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select or create a family workspace first."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.createSavingsGoalMinor(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                goalName = goalName,
                category = category,
                initialAmountMinor = initialAmountMinor,
                targetAmountMinor = targetAmountMinor,
                currency = family.currencyCode,
                targetDateMillis = targetDateMillis,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { write ->
                _statusMessage.value = if (write.deduplicatedExisting) {
                    "Duplicate savings goal prevented."
                } else {
                    "Savings goal \"${write.record.goalName}\" created."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to create savings goal."
            }
        }
    }

    fun updateSavingsGoal(
        recordId: String,
        goalName: String,
        category: String,
        targetAmountMinor: Long,
        targetDateMillis: Long?,
        description: String
    ) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.updateSavingsGoalMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                goalName = goalName,
                category = category,
                targetAmountMinor = targetAmountMinor,
                currency = family.currencyCode,
                targetDateMillis = targetDateMillis,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Savings goal updated." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to update savings goal." }
        }
    }

    fun recordSavingsMove(recordId: String, moveAmountMinor: Long, isDeposit: Boolean, moveDateMillis: Long, note: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.recordSavingsMoveMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                moveAmountMinor = moveAmountMinor,
                isDeposit = isDeposit,
                moveDateMillis = moveDateMillis,
                note = note
            )
            _isBusy.value = false
            res.onSuccess {
                _statusMessage.value = if (isDeposit) "Savings deposit recorded." else "Savings withdrawal recorded."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update savings balance."
            }
        }
    }

    fun deleteSavingsGoal(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.deleteSavingsGoal(recordId, family.familyId, family.memberUids, family.adminUids)
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Savings goal deleted and audited." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to delete savings goal." }
        }
    }

    fun createInvestment(
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        investedAtMillis: Long,
        description: String
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select or create a family workspace first."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.createInvestmentMinor(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                assetName = assetName,
                assetType = assetType,
                symbol = symbol,
                principalMinor = principalMinor,
                currentValueMinor = currentValueMinor,
                units = units,
                currency = family.currencyCode,
                investedAtMillis = investedAtMillis,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { write ->
                _statusMessage.value = if (write.deduplicatedExisting) {
                    "Duplicate investment entry prevented."
                } else {
                    "Investment \"${write.record.assetName}\" added to portfolio."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to add investment."
            }
        }
    }

    fun updateInvestment(
        recordId: String,
        assetName: String,
        assetType: InvestmentAssetType,
        symbol: String?,
        principalMinor: Long,
        currentValueMinor: Long,
        units: Double?,
        investedAtMillis: Long,
        description: String,
        note: String = ""
    ) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.updateInvestmentMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                assetName = assetName,
                assetType = assetType,
                symbol = symbol,
                principalMinor = principalMinor,
                currentValueMinor = currentValueMinor,
                units = units,
                currency = family.currencyCode,
                investedAtMillis = investedAtMillis,
                description = description,
                note = note
            )
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Investment valuation updated." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to update investment." }
        }
    }

    fun deleteInvestment(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.deleteInvestment(recordId, family.familyId, family.memberUids, family.adminUids)
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Investment removed and audited." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to delete investment." }
        }
    }

    fun createBudget(
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        description: String
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select or create a family workspace first."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.createBudgetMinor(
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                budgetScope = budgetScope,
                category = category,
                periodKey = periodKey,
                limitMinor = limitMinor,
                alertThresholdPercent = alertThresholdPercent,
                currency = family.currencyCode,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { write ->
                _statusMessage.value = if (write.deduplicatedExisting) {
                    "A budget for $periodKey (${write.record.category}) already exists."
                } else {
                    "Budget limit saved for $periodKey."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save budget."
            }
        }
    }

    fun updateBudget(
        recordId: String,
        budgetScope: BudgetScope,
        category: String,
        periodKey: String,
        limitMinor: Long,
        alertThresholdPercent: Int,
        description: String
    ) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.updateBudgetMinor(
                recordId = recordId,
                familyId = family.familyId,
                memberUids = family.memberUids,
                adminUids = family.adminUids,
                budgetScope = budgetScope,
                category = category,
                periodKey = periodKey,
                limitMinor = limitMinor,
                alertThresholdPercent = alertThresholdPercent,
                currency = family.currencyCode,
                description = description
            )
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Budget updated." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to update budget." }
        }
    }

    fun deleteBudget(recordId: String) {
        val family = activeFamily.value ?: return
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = dataArchitectureRepo.deleteBudget(recordId, family.familyId, family.memberUids, family.adminUids)
            _isBusy.value = false
            res.onSuccess { _statusMessage.value = "Budget deleted and audited." }
                .onFailure { err -> _statusMessage.value = err.message ?: "Unable to delete budget." }
        }
    }

    fun notifyMessage(message: String) {
        _statusMessage.value = message
    }

    fun switchFamily(familyId: String, familyName: String) {
        viewModelScope.launch {
            val targetMode = if (com.example.core.model.FamilySecurityHelper.isPersonalWorkspaceId(familyId)) {
                WorkspaceMode.PERSONAL
            } else {
                WorkspaceMode.FAMILY
            }
            repository.setWorkspaceMode(targetMode)
            repository.switchActiveFamily(familyId)
                .onSuccess {
                    _statusMessage.value = "Switched workspace to $familyName"
                }
                .onFailure {
                    _statusMessage.value = "Switched workspace locally to $familyName"
                }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updateThemePreference(mode)
        }
    }

    fun setConflictPolicy(policy: ConflictResolutionPolicy) {
        viewModelScope.launch {
            repository.setConflictPolicy(policy)
            _statusMessage.value = "Conflict resolution policy set to ${policy.label}."
        }
    }

    fun resolveConflict(conflictId: String, chosenPolicy: ConflictResolutionPolicy) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val isOnline = connectionMonitor.networkAvailability.first() == NetworkAvailability.ONLINE
            val result = dataArchitectureRepo.resolveConflict(
                conflictId = conflictId,
                chosenPolicy = chosenPolicy,
                family = activeFamily.value,
                isOnline = isOnline
            )
            _isBusy.value = false
            result.onSuccess { ok ->
                _statusMessage.value = if (ok) {
                    if (chosenPolicy == ConflictResolutionPolicy.CLIENT_WINS) {
                        "Kept this device's changes and ${if (isOnline) "synchronized to Cloud Firestore" else "queued for next sync"}."
                    } else {
                        "Accepted Cloud Firestore version and updated local cache."
                    }
                } else {
                    "Conflict record is already resolved."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to resolve sync conflict."
            }
        }
    }

    fun sendChatMessage(messageText: String, relatedRecordId: String? = null) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Create or join a family workspace before using Family Chat."
            return
        }
        if (family.isPersonal || family.isSuspended(currentUserId)) {
            _statusMessage.value = "Family Chat is only available inside an active shared Family workspace."
            return
        }
        val member = activeMembers.value.firstOrNull { it.userId == currentUserId }
        val senderName = userProfile.value?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: firebaseUser?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: "Family Member"
        val senderPhoto = userProfile.value?.photoUrl
            ?.takeIf { it.isNotBlank() }
            ?: firebaseUser?.photoUrl?.toString()
        val roleLabel = member?.relationLabel?.takeIf { it.isNotBlank() }
            ?: if (family.isAdmin(currentUserId)) "Admin" else "Member"
        viewModelScope.launch {
            val result = dataArchitectureRepo.sendChatMessageOfflineFirst(
                family = family,
                senderDisplayName = senderName,
                messageText = messageText,
                canParticipateInChat = member?.canParticipateInChat ?: true,
                relatedRecordId = relatedRecordId,
                senderPhotoUrl = senderPhoto,
                senderRoleLabel = roleLabel
            )
            result.onSuccess { msg ->
                if (msg.syncStatus != "SYNCED") {
                    _statusMessage.value = "Message saved offline and queued for sync."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to send message."
            }
        }
    }

    fun loadOlderChatMessages() {
        val family = activeFamily.value ?: return
        if (_isLoadingOlderChats.value) return
        viewModelScope.launch {
            _isLoadingOlderChats.value = true
            val oldestMillis = chatMessages.value.minOfOrNull { it.createdAtMillis }
            val loadedRes = dataArchitectureRepo.loadOlderChatMessages(
                userId = currentUserId,
                familyId = family.familyId,
                beforeMillis = oldestMillis,
                pageSize = 30
            )
            _chatPageLimit.value = (_chatPageLimit.value + 30).coerceAtMost(200)
            val loadedCount = loadedRes.getOrDefault(0)
            if (loadedCount == 0 && _chatPageLimit.value >= 200) {
                _hasMoreOlderChats.value = false
            }
            _isLoadingOlderChats.value = false
        }
    }

    fun editChatMessage(messageId: String, newText: String) {
        val family = activeFamily.value ?: return
        val member = activeMembers.value.firstOrNull { it.userId == currentUserId }
        viewModelScope.launch {
            dataArchitectureRepo.editChatMessage(
                family = family,
                messageId = messageId,
                newText = newText,
                canParticipateInChat = member?.canParticipateInChat ?: true
            ).onSuccess {
                _statusMessage.value = "Message updated."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to edit message."
            }
        }
    }

    fun deleteChatMessage(messageId: String) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            dataArchitectureRepo.deleteChatMessage(family, messageId)
                .onSuccess {
                    _statusMessage.value = "Message deleted."
                }
                .onFailure { err ->
                    _statusMessage.value = err.message ?: "Unable to delete message."
                }
        }
    }

    fun markVisibleChatsRead(messageIds: List<String>) {
        val family = activeFamily.value ?: return
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            dataArchitectureRepo.markChatMessagesRead(family.familyId, messageIds)
        }
    }

    fun updateChatRetentionDays(retentionDays: Int) {
        val family = activeFamily.value ?: return
        if (!family.isAdmin(currentUserId)) {
            _statusMessage.value = "Only a Family Admin can change chat retention policy."
            return
        }
        updateFamilySettings(
            familyId = family.familyId,
            name = family.name,
            currencyCode = family.currencyCode,
            description = family.description,
            memberChatEnabled = family.memberChatEnabled,
            membersCanAddExpenses = family.membersCanAddExpenses,
            budgetStartDay = family.budgetStartDay,
            chatRetentionDays = retentionDays
        )
    }

    fun purgeExpiredChatsNow() {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            val count = dataArchitectureRepo.purgeExpiredChatMessages(family).getOrDefault(0)
            _statusMessage.value = if (count > 0) {
                "Removed $count expired chat message(s) per retention policy."
            } else {
                "No expired chat messages to remove."
            }
        }
    }

    fun markNotificationRead(notificationId: String) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            dataArchitectureRepo.markNotificationRead(family.familyId, notificationId)
        }
    }

    fun markAllNotificationsRead() {
        val family = activeFamily.value ?: return
        val unreadIds = notificationsState.value
            .filter { it.isUnreadFor(currentUserId) }
            .map { it.notificationId }
        viewModelScope.launch {
            val count = dataArchitectureRepo.markAllNotificationsRead(family.familyId, unreadIds).getOrDefault(0)
            _statusMessage.value = if (count > 0) {
                "Marked $count notification(s) as read."
            } else {
                "All notifications are already read."
            }
        }
    }

    fun archiveNotification(notificationId: String) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            dataArchitectureRepo.archiveNotification(family.familyId, notificationId)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setNotificationsEnabled(enabled)
            _statusMessage.value = if (enabled) {
                "Family notifications enabled."
            } else {
                "Push alerts muted on this device."
            }
        }
    }

    fun refreshCachedReport(periodKey: String = CachedReportBuilder.PERIOD_ALL) {
        val fam = activeFamily.value ?: return
        viewModelScope.launch {
            val synced = connectionState.value != SyncConnectionState.OFFLINE_QUEUED
            dataArchitectureRepo.refreshCachedReport(
                family = fam,
                periodKey = periodKey,
                transactions = allTransactions.value,
                loans = loansState.value,
                savings = savingsState.value,
                investments = investmentsState.value,
                budgets = budgetsState.value,
                isOnlineSynced = synced
            )
            _statusMessage.value = "Updated AES-256-GCM encrypted offline report ($periodKey)."
        }
    }

    suspend fun purgeLocalCacheOnSignOut() {
        repository.purgeAllLocalData()
    }

    fun syncNow(autoTriggered: Boolean = false) {
        viewModelScope.launch {
            if (syncMutex.isLocked) return@launch
            syncMutex.withLock {
                if (!autoTriggered) _isBusy.value = true
                val isOnline = connectionMonitor.networkAvailability.first() == NetworkAvailability.ONLINE
                val familyResult = if (isOnline) repository.syncPendingFamilies(currentUserId) else Result.success(0)
                val fam = activeFamily.value
                val engineResult = if (fam != null) {
                    dataArchitectureRepo.synchronizeWithEngine(
                        userId = currentUserId,
                        family = fam,
                        policy = conflictPolicy.value,
                        isConnectivityOnline = isOnline
                    )
                } else {
                    null
                }
                if (isOnline) {
                    familyManagementRepo.refreshJoinRequestStatuses(currentUserId, myJoinRequests.value)
                }
                if (!autoTriggered) _isBusy.value = false

                val famCount = familyResult.getOrDefault(0)
                val summary = engineResult?.getOrNull()
                if (summary != null) {
                    _lastSyncSummary.value = summary
                }

                when {
                    !isOnline -> if (!autoTriggered) {
                        val queued = summary?.pendingReadCount ?: outboxItems.value.size
                        _statusMessage.value = "Offline mode: $queued pending operation(s) queued safely in encrypted SQLite."
                    }
                    summary != null && summary.conflictsDetected > 0 -> {
                        _statusMessage.value = "Sync completed with ${summary.conflictsDetected} conflict(s) flagged for review."
                    }
                    summary != null && summary.rejectedCount > 0 -> {
                        _statusMessage.value = "Blocked ${summary.rejectedCount} unauthorized operation(s); ${summary.syncedCount} synced."
                    }
                    else -> {
                        val totalSynced = famCount + (summary?.syncedCount ?: 0)
                        if (totalSynced > 0) {
                            _statusMessage.value = "Synced $totalSynced offline operation(s) to Cloud Firestore."
                        } else if (!autoTriggered) {
                            _statusMessage.value = "All local records are synchronized (${connectionState.value.label})."
                        }
                    }
                }
            }
        }
    }

    fun startOutgoingCall(
        callee: LocalFamilyMemberEntity,
        mediaType: CallMediaType,
        hasAudioPerm: Boolean,
        hasCameraPerm: Boolean
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select or create a family workspace before placing calls."
            return
        }
        if (family.isPersonal || family.isSuspended(currentUserId)) {
            _statusMessage.value = "Calls are only available inside an active shared Family workspace."
            return
        }
        if (_activeCallId.value != null || _activeMeetingId.value != null) {
            _statusMessage.value = "Finish your current call or meeting before starting another."
            return
        }
        if (callee.userId == currentUserId) {
            _statusMessage.value = "Select another family member to call."
            return
        }
        val ctrl = webRtcCallController ?: run {
            _statusMessage.value = "Call audio/video hardware is unavailable."
            return
        }
        val callId = MeetInviteHelper.generateCallId()
        val callerName = currentUserDisplayName()
        val isVideo = mediaType == CallMediaType.VIDEO
        _activeCallId.value = callId
        _callDurationSeconds.value = 0

        appContext?.let {
            ActiveCallForegroundService.startOrUpdate(
                context = it,
                title = "${mediaType.label}: ${callee.displayName}",
                subtitle = "Connecting secure WebRTC call...",
                includeCamera = isVideo && hasCameraPerm,
                includeMediaProjection = false
            )
        }

        ctrl.startOutgoingCall(
            callId = callId,
            isVideo = isVideo,
            hasRecordAudioPerm = hasAudioPerm,
            hasCameraPerm = hasCameraPerm,
            onLocalOfferCreated = { sdp, type ->
                viewModelScope.launch {
                    val res = commsRepo.createOutgoingCallSession(
                        callId = callId,
                        family = family,
                        callerName = callerName,
                        callee = callee,
                        mediaType = mediaType,
                        offerSdp = sdp,
                        offerType = type
                    )
                    if (res.isFailure) {
                        _statusMessage.value = res.exceptionOrNull()?.message ?: "Unable to initiate call."
                        cleanupActiveCallLocal()
                    } else {
                        scheduleOutgoingRingTimeout(res.getOrThrow(), family)
                    }
                }
            },
            onLocalIceCandidate = { sdpMid, sdpMLineIndex, candidate ->
                viewModelScope.launch {
                    commsRepo.addIceCandidate(
                        callId = callId,
                        familyId = family.familyId,
                        role = "CALLER",
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex,
                        candidate = candidate,
                        participantUids = listOf(currentUserId, callee.userId)
                    )
                }
            }
        )
    }

    fun acceptIncomingCall(
        call: CallSessionDoc,
        hasAudioPerm: Boolean,
        hasCameraPerm: Boolean
    ) {
        val ctrl = webRtcCallController ?: return
        val callId = call.callId.ifBlank { call.id }
        val offerSdp = call.offerSdp
        if (offerSdp.isNullOrBlank()) {
            _statusMessage.value = "Caller WebRTC offer is missing or expired."
            return
        }
        val isVideo = call.mediaType == CallMediaType.VIDEO.name
        _activeCallId.value = callId
        _callDurationSeconds.value = 0

        appContext?.let {
            ActiveCallForegroundService.startOrUpdate(
                context = it,
                title = "${CallMediaType.fromRaw(call.mediaType).label}: ${call.callerName}",
                subtitle = "In call • Tap to return",
                includeCamera = isVideo && hasCameraPerm,
                includeMediaProjection = false
            )
        }

        ctrl.acceptIncomingCall(
            callId = callId,
            isVideo = isVideo,
            remoteOfferSdp = offerSdp,
            remoteOfferType = call.offerType ?: "offer",
            hasRecordAudioPerm = hasAudioPerm,
            hasCameraPerm = hasCameraPerm,
            onLocalAnswerCreated = { answerSdp, answerType ->
                viewModelScope.launch {
                    commsRepo.publishCallAnswer(callId, answerSdp, answerType)
                        .onFailure { err ->
                            _statusMessage.value = err.message ?: "Unable to answer call."
                        }
                }
            },
            onLocalIceCandidate = { sdpMid, sdpMLineIndex, candidate ->
                viewModelScope.launch {
                    commsRepo.addIceCandidate(
                        callId = callId,
                        familyId = call.familyId,
                        role = "CALLEE",
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex,
                        candidate = candidate,
                        participantUids = call.participantUids.ifEmpty { listOf(call.callerUid, call.calleeUid) }
                    )
                }
            }
        )
    }

    fun rejectIncomingCall(call: CallSessionDoc) {
        viewModelScope.launch {
            commsRepo.updateCallStatus(
                call = call,
                family = activeFamily.value,
                newStatus = CallStatus.REJECTED,
                durationSeconds = 0,
                quality = activeCallMediaState.value.connectionQuality
            )
        }
    }

    fun endActiveCall() {
        val current = activeCallSession.value
        val duration = _callDurationSeconds.value
        val quality = activeCallMediaState.value.connectionQuality
        val nextStatus = when {
            current == null -> CallStatus.ENDED
            current.status == CallStatus.RINGING.name && current.callerUid == currentUserId -> CallStatus.CANCELLED
            else -> CallStatus.ENDED
        }
        cleanupActiveCallLocal()
        if (current != null) {
            viewModelScope.launch {
                commsRepo.updateCallStatus(
                    call = current,
                    family = activeFamily.value,
                    newStatus = nextStatus,
                    durationSeconds = duration,
                    quality = quality
                )
            }
        }
    }

    fun toggleCallMic() {
        webRtcCallController?.toggleMic()
    }

    fun toggleCallCamera() {
        val enabled = webRtcCallController?.toggleCamera() ?: return
        appContext?.let {
            val active = activeCallSession.value
            if (active != null) {
                ActiveCallForegroundService.startOrUpdate(
                    context = it,
                    title = "Active Video Call",
                    subtitle = "In call • Tap to return",
                    includeCamera = enabled,
                    includeMediaProjection = false
                )
            }
        }
    }

    fun switchCallCamera() {
        webRtcCallController?.switchCamera()
    }

    fun selectCallAudioRoute(route: AudioOutputRoute) {
        webRtcCallController?.selectAudioRoute(route)
    }

    fun cycleCallAudioRoute() {
        webRtcCallController?.cycleAudioRoute()
    }

    fun triggerCallIceRestart() {
        val call = activeCallSession.value ?: return
        if (call.callerUid != currentUserId) {
            _statusMessage.value = "Reconnecting ICE peer connection..."
            return
        }
        val callId = call.callId.ifBlank { call.id }
        webRtcCallController?.triggerIceRestart { sdp, type ->
            viewModelScope.launch {
                commsRepo.publishOfferRenegotiation(
                    callId = callId,
                    offerSdp = sdp,
                    offerType = type,
                    iceRestartCount = call.iceRestartCount + 1L
                )
            }
        }
    }

    fun createMeeting(
        title: String,
        isScheduled: Boolean,
        scheduledForMillis: Long?,
        invitedUids: List<String>,
        audioEnabledDefault: Boolean,
        videoEnabledDefault: Boolean,
        autoJoinIfLive: Boolean = true,
        hasAudioPerm: Boolean = true,
        hasCameraPerm: Boolean = true
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Create or join a family workspace before creating meetings."
            return
        }
        if (family.isPersonal || family.isSuspended(currentUserId)) {
            _statusMessage.value = "Family Meet is only available inside an active shared Family workspace."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = commsRepo.createMeeting(
                family = family,
                members = activeMembers.value,
                hostName = currentUserDisplayName(),
                title = title,
                isScheduled = isScheduled,
                scheduledForMillis = scheduledForMillis,
                invitedUids = invitedUids,
                audioEnabledDefault = audioEnabledDefault,
                videoEnabledDefault = videoEnabledDefault
            )
            _isBusy.value = false
            res.onSuccess { created ->
                if (isScheduled) {
                    _statusMessage.value = "Scheduled \"${created.title}\" (Code: ${created.inviteCode})."
                } else if (autoJoinIfLive) {
                    _statusMessage.value = "Meeting created (${created.inviteCode}). Connecting..."
                    joinMeetingDoc(
                        family = family,
                        meeting = created,
                        joinAudio = audioEnabledDefault && hasAudioPerm,
                        joinVideo = videoEnabledDefault && hasCameraPerm
                    )
                } else {
                    _statusMessage.value = "Live meeting created (Code: ${created.inviteCode})."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to create family meeting."
            }
        }
    }

    fun joinMeetingByInput(
        rawInput: String,
        hasAudioPerm: Boolean,
        hasCameraPerm: Boolean,
        joinAudio: Boolean = true,
        joinVideo: Boolean = true
    ) {
        val family = activeFamily.value ?: run {
            _statusMessage.value = "Select an active family workspace first."
            return
        }
        if (family.isPersonal || family.isSuspended(currentUserId)) {
            _statusMessage.value = "Family Meet is only available inside an active shared Family workspace."
            return
        }
        val clean = rawInput.trim()
        if (clean.isEmpty()) {
            _statusMessage.value = "Enter a Meeting ID, invite code, or Family Meet link."
            return
        }
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val resolved = commsRepo.resolveMeetingForJoin(family, clean)
            _isBusy.value = false
            resolved.onSuccess { meeting ->
                joinMeetingDoc(
                    family = family,
                    meeting = meeting,
                    joinAudio = joinAudio && meeting.audioEnabledDefault && hasAudioPerm,
                    joinVideo = joinVideo && meeting.videoEnabledDefault && hasCameraPerm
                )
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to find that family meeting."
            }
        }
    }

    fun joinExistingMeeting(
        meeting: LocalMeetingEntity,
        hasAudioPerm: Boolean,
        hasCameraPerm: Boolean
    ) {
        val family = activeFamily.value ?: return
        if (family.isPersonal || family.isSuspended(currentUserId)) {
            _statusMessage.value = "Family Meet is only available inside an active shared Family workspace."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val resolved = commsRepo.resolveMeetingForJoin(family, meeting.meetingId)
            _isBusy.value = false
            resolved.onSuccess { doc ->
                joinMeetingDoc(
                    family = family,
                    meeting = doc,
                    joinAudio = doc.audioEnabledDefault && hasAudioPerm,
                    joinVideo = doc.videoEnabledDefault && hasCameraPerm
                )
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to join meeting."
            }
        }
    }

    private suspend fun joinMeetingDoc(
        family: LocalFamilyEntity,
        meeting: FamilyMeetingDoc,
        joinAudio: Boolean,
        joinVideo: Boolean
    ) {
        val ctrl = liveKitMeetController ?: run {
            _statusMessage.value = "LiveKit media engine unavailable."
            return
        }
        val meetingId = meeting.meetingId.ifBlank { meeting.id }
        val member = activeMembers.value.firstOrNull { it.userId == currentUserId }
        val roleLabel = member?.relationLabel ?: member?.role ?: "Member"
        _isBusy.value = true
        val mintRes = commsRepo.mintTokenAndJoinMetadata(
            family = family,
            meeting = meeting,
            displayName = currentUserDisplayName(),
            roleLabel = roleLabel,
            audioEnabled = joinAudio,
            videoEnabled = joinVideo
        )
        if (mintRes.isFailure) {
            _isBusy.value = false
            _statusMessage.value = mintRes.exceptionOrNull()?.message
                ?: "Unable to obtain verified LiveKit meeting token."
            return
        }
        val tokenPayload = mintRes.getOrThrow()
        _activeMeetingId.value = meetingId
        _meetingDurationSeconds.value = 0

        appContext?.let {
            ActiveCallForegroundService.startOrUpdate(
                context = it,
                title = "Family Meet: ${meeting.title}",
                subtitle = "Live group room (${meeting.inviteCode})",
                includeCamera = joinVideo,
                includeMediaProjection = false
            )
        }

        val joinRes = ctrl.joinRoom(
            meetingId = meetingId,
            roomName = tokenPayload.roomName.ifBlank { meeting.roomName },
            livekitUrl = tokenPayload.livekitUrl,
            token = tokenPayload.token,
            joinAudio = joinAudio,
            joinVideo = joinVideo,
            onRoomDisconnectedUnexpectedly = {
                viewModelScope.launch {
                    if (_activeMeetingId.value == meetingId) {
                        leaveActiveMeeting(localOnlyCleanup = true)
                    }
                }
            }
        )
        _isBusy.value = false
        if (joinRes.isFailure) {
            commsRepo.markLeftMeeting(meetingId)
            _activeMeetingId.value = null
            appContext?.let { ActiveCallForegroundService.stop(it) }
            _statusMessage.value = joinRes.exceptionOrNull()?.message ?: "Failed connecting to LiveKit room."
        }
    }

    fun leaveActiveMeeting(localOnlyCleanup: Boolean = false) {
        val meetingId = _activeMeetingId.value
        meetingTimerJob?.cancel()
        meetingTimerJob = null
        _activeMeetingId.value = null
        _meetingDurationSeconds.value = 0
        liveKitMeetController?.leaveRoom()
        appContext?.let { ActiveCallForegroundService.stop(it) }
        if (!localOnlyCleanup && !meetingId.isNullOrBlank()) {
            viewModelScope.launch {
                commsRepo.markLeftMeeting(meetingId)
            }
        }
    }

    fun endMeetingForEveryone(meetingId: String) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val res = commsRepo.endMeetingForEveryone(family.familyId, meetingId)
            _isBusy.value = false
            res.onSuccess { resp ->
                if (_activeMeetingId.value == meetingId) {
                    leaveActiveMeeting(localOnlyCleanup = true)
                }
                _statusMessage.value = resp.warning ?: "Family meeting ended for all participants."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to end meeting."
            }
        }
    }

    fun muteParticipantAsHost(meetingId: String, targetUid: String, muted: Boolean, trackSid: String?) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            commsRepo.muteParticipantAsHost(
                familyId = family.familyId,
                meetingId = meetingId,
                targetUid = targetUid,
                muted = muted,
                trackSid = trackSid
            ).onSuccess {
                _statusMessage.value = if (muted) "Participant muted." else "Participant unmuted."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to moderate participant audio."
            }
        }
    }

    fun removeParticipantAsHost(meetingId: String, targetUid: String) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            commsRepo.removeParticipantAsHost(
                familyId = family.familyId,
                meetingId = meetingId,
                targetUid = targetUid
            ).onSuccess {
                _statusMessage.value = "Participant removed from meeting."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to remove participant."
            }
        }
    }

    fun toggleMeetingMic() {
        val meetingId = _activeMeetingId.value ?: return
        val partState = meetingParticipants.value.firstOrNull { (it.participantUid.ifBlank { it.id }) == currentUserId }
        val next = !activeMeetingMediaState.value.localMicEnabled
        if (next && partState?.mutedByHost == true) {
            _statusMessage.value = "You were muted by the meeting host."
            return
        }
        viewModelScope.launch {
            liveKitMeetController?.setMicrophoneEnabled(next)
            commsRepo.updateMyMeetingMediaState(
                meetingId = meetingId,
                audioEnabled = next,
                videoEnabled = activeMeetingMediaState.value.localCameraEnabled,
                screenSharing = activeMeetingMediaState.value.isScreenSharing
            )
        }
    }

    fun toggleMeetingCamera() {
        val meetingId = _activeMeetingId.value ?: return
        val next = !activeMeetingMediaState.value.localCameraEnabled
        viewModelScope.launch {
            liveKitMeetController?.setCameraEnabled(next)
            appContext?.let {
                ActiveCallForegroundService.startOrUpdate(
                    context = it,
                    title = "Family Meet: ${activeMeetingDoc.value?.title ?: "Live Room"}",
                    subtitle = "Tap to return",
                    includeCamera = next,
                    includeMediaProjection = activeMeetingMediaState.value.isScreenSharing
                )
            }
            commsRepo.updateMyMeetingMediaState(
                meetingId = meetingId,
                audioEnabled = activeMeetingMediaState.value.localMicEnabled,
                videoEnabled = next,
                screenSharing = activeMeetingMediaState.value.isScreenSharing
            )
        }
    }

    fun switchMeetingCamera() {
        liveKitMeetController?.switchCamera()
    }

    fun selectMeetingAudioRoute(route: AudioOutputRoute) {
        liveKitMeetController?.selectAudioRoute(route)
    }

    fun cycleMeetingAudioRoute() {
        liveKitMeetController?.cycleAudioRoute()
    }

    fun toggleMeetingScreenShare(enable: Boolean, projectionIntent: Intent?) {
        val meetingId = _activeMeetingId.value ?: return
        val ctrl = liveKitMeetController ?: return
        viewModelScope.launch {
            if (enable) {
                appContext?.let {
                    ActiveCallForegroundService.startOrUpdate(
                        context = it,
                        title = "Family Meet Screen Share",
                        subtitle = "Sharing screen with family",
                        includeCamera = activeMeetingMediaState.value.localCameraEnabled,
                        includeMediaProjection = true
                    )
                }
            }
            ctrl.setScreenShareEnabled(enable, projectionIntent)
                .onSuccess {
                    if (!enable) {
                        appContext?.let {
                            ActiveCallForegroundService.startOrUpdate(
                                context = it,
                                title = "Family Meet: ${activeMeetingDoc.value?.title ?: "Live Room"}",
                                subtitle = "Tap to return",
                                includeCamera = activeMeetingMediaState.value.localCameraEnabled,
                                includeMediaProjection = false
                            )
                        }
                    }
                    commsRepo.updateMyMeetingMediaState(
                        meetingId = meetingId,
                        audioEnabled = activeMeetingMediaState.value.localMicEnabled,
                        videoEnabled = activeMeetingMediaState.value.localCameraEnabled,
                        screenSharing = enable
                    )
                }
                .onFailure { err ->
                    _statusMessage.value = err.message ?: "Unable to update screen sharing."
                }
        }
    }

    private fun scheduleOutgoingRingTimeout(call: CallSessionDoc, family: LocalFamilyEntity) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = viewModelScope.launch {
            delay(45_000L)
            val latest = _remoteCalls.value.firstOrNull {
                (it.callId.ifBlank { it.id }) == (call.callId.ifBlank { call.id })
            } ?: call
            if (latest.status == CallStatus.RINGING.name) {
                commsRepo.updateCallStatus(
                    call = latest,
                    family = family,
                    newStatus = CallStatus.MISSED,
                    durationSeconds = 0,
                    quality = activeCallMediaState.value.connectionQuality
                )
                cleanupActiveCallLocal()
                _statusMessage.value = "No answer from ${latest.calleeName}."
            }
        }
    }

    private fun cleanupActiveCallLocal() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        callTimerJob?.cancel()
        callTimerJob = null
        webRtcCallController?.endAndRelease()
        appContext?.let { ActiveCallForegroundService.stop(it) }
        _activeCallId.value = null
        _callDurationSeconds.value = 0
    }

    private fun currentUserDisplayName(): String {
        return userProfile.value?.displayName?.takeIf { it.isNotBlank() }
            ?: firebaseUser?.displayName?.takeIf { it.isNotBlank() }
            ?: "Family Member"
    }

    override fun onCleared() {
        ringTimeoutJob?.cancel()
        callTimerJob?.cancel()
        meetingTimerJob?.cancel()
        webRtcCallController?.disposeEngine()
        liveKitMeetController?.disposeEngine()
        appContext?.let { ActiveCallForegroundService.stop(it) }
        super.onCleared()
    }

    fun selectProposal(proposalId: String?) {
        _selectedProposalId.value = proposalId
    }

    fun saveApprovalSettings(
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
    ) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.saveApprovalSettings(
                family = family,
                ruleMode = ruleMode,
                minApprovalPercent = minApprovalPercent,
                minQuorumCount = minQuorumCount,
                defaultDeadlineHours = defaultDeadlineHours,
                allowCreatorVote = allowCreatorVote,
                allowVoteChangeBeforeDeadline = allowVoteChangeBeforeDeadline,
                eligibleVoterRule = eligibleVoterRule,
                specificEligibleUids = specificEligibleUids,
                requiredCategories = requiredCategories,
                largeExpenseThresholdMinor = largeExpenseThresholdMinor
            ).onSuccess {
                _statusMessage.value = "Family approval rules saved (${ruleMode.label})."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to update approval settings."
            }
            _isBusy.value = false
        }
    }

    fun createOrSubmitProposal(
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
        onSaved: (LocalProposalEntity) -> Unit = {}
    ) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.saveOrSubmitProposal(
                family = family,
                members = activeMembers.value,
                settings = approvalSettingsState.value,
                existingDraft = existingDraft,
                title = title,
                category = category,
                description = description,
                reason = reason,
                amountMinor = amountMinor,
                currency = currency,
                relatedParty = relatedParty,
                supportingDocName = supportingDocName,
                supportingDocUri = supportingDocUri,
                targetSavingsId = targetSavingsId,
                proposedDateMillis = proposedDateMillis,
                votingDeadlineMillis = votingDeadlineMillis,
                submitNow = submitNow,
                proposerName = currentUserDisplayName()
            ).onSuccess { saved ->
                _statusMessage.value = if (submitNow) {
                    "Proposal submitted for family voting."
                } else {
                    "Draft proposal saved."
                }
                onSaved(saved)
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to save proposal."
            }
            _isBusy.value = false
        }
    }

    fun deleteDraftProposal(proposalId: String, onDeleted: () -> Unit = {}) {
        val family = activeFamily.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.deleteDraftProposal(family, proposalId)
                .onSuccess {
                    if (_selectedProposalId.value == proposalId) {
                        _selectedProposalId.value = null
                    }
                    _statusMessage.value = "Draft proposal deleted."
                    onDeleted()
                }
                .onFailure { err ->
                    _statusMessage.value = err.message ?: "Unable to delete draft."
                }
            _isBusy.value = false
        }
    }

    fun castOrChangeProposalVote(
        proposalId: String,
        choice: VoteChoice,
        comment: String? = null
    ) {
        val family = activeFamily.value ?: return
        val proposal = proposalsState.value.firstOrNull { it.proposalId == proposalId } ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.castOrChangeVote(
                family = family,
                proposal = proposal,
                choice = choice,
                comment = comment,
                voterName = currentUserDisplayName()
            ).onSuccess { tally ->
                _statusMessage.value = when (tally.nextStatus) {
                    com.example.core.model.ProposalStatus.APPROVED -> "Vote recorded — Proposal Approved!"
                    com.example.core.model.ProposalStatus.REJECTED -> "Vote recorded — Proposal Rejected."
                    else -> "Vote recorded (${choice.label})."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to cast vote."
            }
            _isBusy.value = false
        }
    }

    fun cancelProposal(proposalId: String, reason: String) {
        val family = activeFamily.value ?: return
        val proposal = proposalsState.value.firstOrNull { it.proposalId == proposalId } ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.cancelProposal(
                family = family,
                proposal = proposal,
                reason = reason,
                actorName = currentUserDisplayName()
            ).onSuccess {
                _statusMessage.value = "Proposal cancelled."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to cancel proposal."
            }
            _isBusy.value = false
        }
    }

    fun executeApprovedProposal(proposalId: String) {
        val family = activeFamily.value ?: return
        val proposal = proposalsState.value.firstOrNull { it.proposalId == proposalId } ?: return
        viewModelScope.launch {
            _isBusy.value = true
            decisionsRepo.executeApprovedProposal(
                family = family,
                proposal = proposal,
                actorName = currentUserDisplayName()
            ).onSuccess { outcome ->
                _statusMessage.value =
                    "Approved decision executed in ${outcome.executedCollection} (${outcome.executedRecordId})."
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Unable to execute proposal."
            }
            _isBusy.value = false
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
