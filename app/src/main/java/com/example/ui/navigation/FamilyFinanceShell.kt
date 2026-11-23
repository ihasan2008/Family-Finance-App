package com.example.ui.navigation

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.core.comms.ActiveCallMediaUiState
import com.example.core.comms.ActiveMeetingMediaUiState
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
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
import com.example.core.model.AppModule
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.BudgetScope
import com.example.core.model.CallMediaType
import com.example.core.model.CallSessionDoc
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.model.DecisionCategory
import com.example.core.model.DeviceSessionDoc
import com.example.core.model.DigitalMemberCardData
import com.example.core.model.EligibleVoterRule
import com.example.core.model.FamilyInvitationDoc
import com.example.core.model.FamilyJoinRequestDoc
import com.example.core.model.FamilyMembershipState
import com.example.core.model.FamilyRole
import com.example.core.model.InvestmentAssetType
import com.example.core.model.LoanDirection
import com.example.core.model.MeetingParticipantDoc
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MembershipNoticeDoc
import com.example.core.model.ModuleCategory
import com.example.core.model.PasskeyCredentialDoc
import com.example.core.model.PhoneOtpState
import com.example.core.model.ProposalStatus
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecoveredFamilySummary
import com.example.core.model.ThemeMode
import com.example.core.model.TransactionType
import com.example.core.model.VoteChoice
import com.example.core.model.WorkspaceMode
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictStatus
import com.example.core.firebase.SyncConnectionState
import com.example.ui.components.FamilyFinanceBrandLockup
import com.example.ui.components.FamilyWorkspacePill
import com.example.ui.screens.AllModulesHubScreen
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.AuthStatusScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.FamilyManagementScreen
import com.example.ui.screens.SecurityScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.modules.BudgetsScreen
import com.example.ui.screens.modules.FamilyChatScreen
import com.example.ui.screens.modules.FamilyDecisionsScreen
import com.example.ui.screens.modules.FamilyMeetScreen
import com.example.ui.screens.modules.IncomingCallBannerOverlay
import com.example.ui.screens.modules.InvestmentsScreen
import com.example.ui.screens.modules.LiveMeetingRoomOverlay
import com.example.ui.screens.modules.LoansScreen
import com.example.ui.screens.modules.MemberCardsScreen
import com.example.ui.screens.modules.NotificationsScreen
import com.example.ui.screens.modules.OneToOneCallOverlay
import com.example.ui.screens.modules.ReportsScreen
import com.example.ui.screens.modules.SavingsScreen
import com.example.ui.screens.transactions.IncomeExpenseScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

private data class PrimaryNavItem(
    val label: String,
    val icon: ImageVector,
    val route: AppRoute,
    val routeClass: KClass<out AppRoute>,
    val testTag: String
)

private val PrimaryNavItems = listOf(
    PrimaryNavItem("Home", Icons.Outlined.Dashboard, AppRoute.Dashboard, AppRoute.Dashboard::class, "nav_home"),
    PrimaryNavItem("Modules", Icons.Outlined.Apps, AppRoute.ModulesHub, AppRoute.ModulesHub::class, "nav_modules"),
    PrimaryNavItem("Family", Icons.Outlined.Diversity3, AppRoute.FamilyManagement, AppRoute.FamilyManagement::class, "nav_family"),
    PrimaryNavItem("Analytics", Icons.Outlined.Analytics, AppRoute.Analytics, AppRoute.Analytics::class, "nav_analytics"),
    PrimaryNavItem("Settings", Icons.Outlined.Settings, AppRoute.Settings, AppRoute.Settings::class, "nav_settings")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyFinanceShell(
    currentUserId: String,
    userDisplayName: String,
    userEmail: String,
    viewModel: MainViewModel,
    initialRoute: AppRoute = AppRoute.Dashboard,
    deepLinkRoute: AppRoute? = null,
    onDeepLinkConsumed: () -> Unit = {},
    prefilledMeetInviteInput: String? = null,
    onPrefilledMeetInviteConsumed: () -> Unit = {},
    isInPipMode: Boolean = false,
    onEnterPipMode: () -> Unit = {},
    onActiveVideoCallChanged: (Boolean) -> Unit = {},
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val familiesState by viewModel.familiesState.collectAsStateWithLifecycle()
    val activeFamily by viewModel.activeFamily.collectAsStateWithLifecycle()
    val personalFamily by viewModel.personalFamily.collectAsStateWithLifecycle()
    val workspaceMode by viewModel.workspaceMode.collectAsStateWithLifecycle()
    val membershipState by viewModel.membershipState.collectAsStateWithLifecycle()
    val membershipNotices by viewModel.membershipNotices.collectAsStateWithLifecycle()
    val otpState by viewModel.otpState.collectAsStateWithLifecycle()
    val activeMembers by viewModel.activeMembers.collectAsStateWithLifecycle()
    val memberCardsState by viewModel.memberCardsState.collectAsStateWithLifecycle()
    val activeInvitations by viewModel.activeFamilyInvitations.collectAsStateWithLifecycle()
    val activeJoinRequests by viewModel.activeFamilyJoinRequests.collectAsStateWithLifecycle()
    val myJoinRequests by viewModel.myJoinRequests.collectAsStateWithLifecycle()
    val recoveredFamilies by viewModel.recoveredFamilies.collectAsStateWithLifecycle()
    val latestCreatedInvitation by viewModel.latestCreatedInvitation.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val enrolledPasskeys by viewModel.enrolledPasskeys.collectAsStateWithLifecycle()
    val deviceSessions by viewModel.deviceSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val syncStatusLabel by viewModel.syncStatusLabel.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val appCheckStatus by viewModel.appCheckStatus.collectAsStateWithLifecycle()
    val databaseHealth by viewModel.databaseHealth.collectAsStateWithLifecycle()
    val recentAuditLogs by viewModel.recentAuditLogs.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val incomeTransactions by viewModel.incomeTransactions.collectAsStateWithLifecycle()
    val expenseTransactions by viewModel.expenseTransactions.collectAsStateWithLifecycle()
    val loansState by viewModel.loansState.collectAsStateWithLifecycle()
    val savingsState by viewModel.savingsState.collectAsStateWithLifecycle()
    val investmentsState by viewModel.investmentsState.collectAsStateWithLifecycle()
    val budgetsState by viewModel.budgetsState.collectAsStateWithLifecycle()
    val financialEvents by viewModel.financialEvents.collectAsStateWithLifecycle()
    val conflictPolicy by viewModel.conflictPolicy.collectAsStateWithLifecycle()
    val outboxItems by viewModel.outboxItems.collectAsStateWithLifecycle()
    val syncConflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val cachedReports by viewModel.cachedReports.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
    val unreadChatCount by viewModel.unreadChatCount.collectAsStateWithLifecycle()
    val unreadNotificationCount by viewModel.unreadNotificationCount.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val isLoadingOlderChats by viewModel.isLoadingOlderChats.collectAsStateWithLifecycle()
    val hasMoreOlderChats by viewModel.hasMoreOlderChats.collectAsStateWithLifecycle()
    val sessionRevokedWarning by viewModel.sessionRevokedWarning.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
    val meetingsState by viewModel.meetingsState.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCallSession.collectAsStateWithLifecycle()
    val incomingCall by viewModel.incomingCallSession.collectAsStateWithLifecycle()
    val activeCallMediaState by viewModel.activeCallMediaState.collectAsStateWithLifecycle()
    val callDurationSeconds by viewModel.callDurationSeconds.collectAsStateWithLifecycle()
    val activeMeeting by viewModel.activeMeetingDoc.collectAsStateWithLifecycle()
    val meetingParticipants by viewModel.meetingParticipants.collectAsStateWithLifecycle()
    val activeMeetingMediaState by viewModel.activeMeetingMediaState.collectAsStateWithLifecycle()
    val meetingDurationSeconds by viewModel.meetingDurationSeconds.collectAsStateWithLifecycle()
    val approvalSettingsState by viewModel.approvalSettingsState.collectAsStateWithLifecycle()
    val proposalsState by viewModel.proposalsState.collectAsStateWithLifecycle()
    val proposalVotesState by viewModel.proposalVotesState.collectAsStateWithLifecycle()
    val proposalHistoryState by viewModel.proposalHistoryState.collectAsStateWithLifecycle()
    val selectedProposalId by viewModel.selectedProposalId.collectAsStateWithLifecycle()

    val pendingDecisionVoteCount = remember(proposalsState, proposalVotesState, currentUserId) {
        val nowMillis = System.currentTimeMillis()
        proposalsState.count { proposal ->
            proposal.status == ProposalStatus.PENDING.name &&
                proposal.votingDeadlineMillis > nowMillis &&
                currentUserId in proposal.eligibleVoterUidList() &&
                proposalVotesState.none { it.proposalId == proposal.proposalId && it.voterUid == currentUserId }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeCall, activeCallMediaState.cameraEnabled) {
        onActiveVideoCallChanged(activeCall != null && activeCall?.mediaType == CallMediaType.VIDEO.name)
    }

    LaunchedEffect(sessionRevokedWarning) {
        if (!sessionRevokedWarning.isNullOrBlank()) {
            FirebaseServiceRegistry.auth.signOut()
            onSignedOut()
        }
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    fun navigateSingleTop(route: AppRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(deepLinkRoute) {
        if (deepLinkRoute != null) {
            navigateSingleTop(deepLinkRoute)
            onDeepLinkConsumed()
        }
    }

    val currentTitle = remember(currentDestination) {
        when {
            currentDestination == null -> "Dashboard"
            currentDestination.hasRoute(AppRoute.Dashboard::class) -> "Dashboard"
            currentDestination.hasRoute(AppRoute.ModulesHub::class) -> "All Modules"
            currentDestination.hasRoute(AppRoute.FamilyManagement::class) -> "Family Management"
            currentDestination.hasRoute(AppRoute.MemberCards::class) -> "Member Cards"
            currentDestination.hasRoute(AppRoute.Income::class) -> "Income"
            currentDestination.hasRoute(AppRoute.Expense::class) -> "Expense"
            currentDestination.hasRoute(AppRoute.Loans::class) -> "Loans"
            currentDestination.hasRoute(AppRoute.Savings::class) -> "Savings"
            currentDestination.hasRoute(AppRoute.Investments::class) -> "Investments"
            currentDestination.hasRoute(AppRoute.Budgets::class) -> "Budgets"
            currentDestination.hasRoute(AppRoute.Analytics::class) -> "Analytics"
            currentDestination.hasRoute(AppRoute.Reports::class) -> "Reports"
            currentDestination.hasRoute(AppRoute.FamilyChat::class) -> "Family Chat"
            currentDestination.hasRoute(AppRoute.FamilyMeet::class) -> "Family Meet"
            currentDestination.hasRoute(AppRoute.FamilyDecisions::class) -> "Approvals & Decisions"
            currentDestination.hasRoute(AppRoute.Notifications::class) -> "Notifications"
            currentDestination.hasRoute(AppRoute.Settings::class) -> "Settings"
            currentDestination.hasRoute(AppRoute.Security::class) -> "Security"
            currentDestination.hasRoute(AppRoute.AuthStatus::class) -> "Authentication & Passkeys"
            else -> "Family Finance"
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isDesktop = maxWidth >= 840.dp
        val isTablet = maxWidth in 600.dp..839.dp

        val mainScaffold: @Composable () -> Unit = {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            if (!isDesktop) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("open_modules_drawer_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Menu,
                                        contentDescription = stringResource(R.string.cd_open_drawer)
                                    )
                                }
                            }
                        },
                        actions = {
                            FamilyWorkspacePill(
                                familyName = if (activeFamily?.isPersonal == true) "Personal Finance" else activeFamily?.name,
                                familyId = if (activeFamily?.isPersonal == true) "PRIVATE" else activeFamily?.familyId,
                                syncStatus = if (activeFamily?.isPersonal == true) "PRIVATE" else (activeFamily?.syncStatus ?: "READY"),
                                onClick = { navigateSingleTop(AppRoute.FamilyManagement) }
                            )
                            IconButton(
                                onClick = { navigateSingleTop(AppRoute.Notifications) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("topbar_notifications_button")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotificationCount > 0) {
                                            Badge(modifier = Modifier.testTag("topbar_unread_notifications_badge")) {
                                                Text(if (unreadNotificationCount > 99) "99+" else "$unreadNotificationCount")
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Notifications,
                                        contentDescription = stringResource(R.string.cd_notifications)
                                    )
                                }
                            }
                            if (!isDesktop) {
                                IconButton(
                                    onClick = { navigateSingleTop(AppRoute.Settings) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("topbar_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.cd_settings)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                bottomBar = {
                    if (!isDesktop && !isTablet) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp
                        ) {
                            PrimaryNavItems.forEach { item ->
                                val selected = currentDestination?.hasRoute(item.routeClass) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateSingleTop(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isTablet) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            PrimaryNavItems.forEach { item ->
                                val selected = currentDestination?.hasRoute(item.routeClass) == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateSingleTop(item.route) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) },
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    val unresolvedConflictsCount = remember(syncConflicts) {
                        syncConflicts.count { it.status == ConflictStatus.UNRESOLVED.name }
                    }
                    val showSyncBanner = activeFamily != null && (
                        connectionState == SyncConnectionState.OFFLINE_QUEUED ||
                            unresolvedConflictsCount > 0 ||
                            outboxItems.isNotEmpty()
                        )

                    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                        if (showSyncBanner) {
                            val isConflictBanner = unresolvedConflictsCount > 0
                            val isOfflineBanner = connectionState == SyncConnectionState.OFFLINE_QUEUED
                            Surface(
                                color = when {
                                    isConflictBanner -> MaterialTheme.colorScheme.errorContainer
                                    isOfflineBanner -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("global_offline_sync_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isConflictBanner -> Icons.Outlined.SyncProblem
                                            isOfflineBanner -> Icons.Outlined.CloudOff
                                            else -> Icons.Outlined.CloudSync
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            isConflictBanner -> MaterialTheme.colorScheme.onErrorContainer
                                            isOfflineBanner -> MaterialTheme.colorScheme.onTertiaryContainer
                                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = when {
                                            isConflictBanner -> "$unresolvedConflictsCount sync conflict(s) flagged — resolve without data loss."
                                            isOfflineBanner -> "Offline mode • ${outboxItems.size} queued operation(s) stored in AES-256-GCM SQLite."
                                            else -> "${outboxItems.size} pending local operation(s) ready to sync."
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            isConflictBanner -> MaterialTheme.colorScheme.onErrorContainer
                                            isOfflineBanner -> MaterialTheme.colorScheme.onTertiaryContainer
                                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            if (isConflictBanner || isOfflineBanner) {
                                                navigateSingleTop(AppRoute.Settings)
                                            } else {
                                                viewModel.syncNow(autoTriggered = false)
                                            }
                                        },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text(
                                            when {
                                                isConflictBanner -> "Review"
                                                isOfflineBanner -> "Queue"
                                                else -> "Sync Now"
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        AppNavHost(
                            navController = navController,
                            initialRoute = initialRoute,
                            currentUserId = currentUserId,
                            userDisplayName = userDisplayName,
                            userEmail = userEmail,
                            userProfile = userProfile,
                            passkeys = enrolledPasskeys,
                            sessions = deviceSessions,
                            currentSessionId = currentSessionId,
                            otpState = otpState,
                            familiesState = familiesState,
                            activeFamily = activeFamily,
                            personalFamily = personalFamily,
                            workspaceMode = workspaceMode,
                            membershipState = membershipState,
                            membershipNotices = membershipNotices,
                            activeMembers = activeMembers,
                            memberCards = memberCardsState,
                            activeInvitations = activeInvitations,
                            activeJoinRequests = activeJoinRequests,
                            myJoinRequests = myJoinRequests,
                            recoveredFamilies = recoveredFamilies,
                            latestCreatedInvitation = latestCreatedInvitation,
                            themeMode = themeMode,
                            syncStatusLabel = syncStatusLabel,
                            connectionState = connectionState,
                            configSnapshot = viewModel.configSnapshot,
                            appCheckStatus = appCheckStatus,
                            databaseHealth = databaseHealth,
                            recentAuditLogs = recentAuditLogs,
                            allTransactions = allTransactions,
                            incomeTransactions = incomeTransactions,
                            expenseTransactions = expenseTransactions,
                            loansState = loansState,
                            savingsState = savingsState,
                            investmentsState = investmentsState,
                            budgetsState = budgetsState,
                            financialEvents = financialEvents,
                            conflictPolicy = conflictPolicy,
                            outboxItems = outboxItems,
                            syncConflicts = syncConflicts,
                            cachedReports = cachedReports,
                            chatMessages = chatMessages,
                            notifications = notificationsState,
                            unreadChatCount = unreadChatCount,
                            unreadNotificationCount = unreadNotificationCount,
                            notificationsEnabled = notificationsEnabled,
                            isLoadingOlderChats = isLoadingOlderChats,
                            hasMoreOlderChats = hasMoreOlderChats,
                            isBusy = isBusy,
                            onNavigate = ::navigateSingleTop,
                            onSwitchWorkspaceMode = viewModel::switchWorkspaceMode,
                            onDismissMembershipNotice = viewModel::dismissMembershipNotice,
                            onCreateFamily = viewModel::createFamily,
                            onRequestJoinByPassword = viewModel::requestJoinByFamilyPassword,
                            onRequestJoinWithToken = viewModel::requestJoinWithToken,
                            onSwitchFamily = viewModel::switchFamily,
                            onCreateInvitation = viewModel::createFamilyInvitation,
                            onClearCreatedInvitation = viewModel::clearLatestCreatedInvitation,
                            onRevokeInvitation = viewModel::revokeInvitation,
                            onReviewJoinRequest = viewModel::reviewJoinRequest,
                            onUpdateMemberRoleAndPermissions = viewModel::updateMemberRoleAndPermissions,
                            onSetMemberSuspension = viewModel::setMemberSuspension,
                            onChangeFamilyPassword = viewModel::changeFamilyPassword,
                            onContributeFromPersonal = viewModel::contributeFromPersonalToFamily,
                            onUpdateMemberCardPrivacy = viewModel::updateMemberCardPrivacy,
                            onRecordMemberProfileAudit = viewModel::recordMemberProfileViewAudit,
                            onRemoveMember = viewModel::removeMember,
                            onTransferOwnership = viewModel::transferOwnership,
                            onUpdateFamilySettings = viewModel::updateFamilySettings,
                            onRecoverAdminFamilyIds = viewModel::recoverAdminFamilyIds,
                            onRefreshMyJoinRequests = viewModel::refreshMyJoinRequests,
                            onSendMobileChangeOtp = viewModel::sendOtpForMobileChange,
                            onVerifyMobileChangeOtp = viewModel::verifyAndChangeMobileNumber,
                            onResetMobileOtpState = viewModel::resetMobileOtpState,
                        onCreateTransaction = viewModel::createTransaction,
                        onUpdateTransaction = viewModel::updateTransaction,
                        onDeleteTransaction = viewModel::deleteTransaction,
                        onPostNextRecurringNow = viewModel::postNextRecurringNow,
                        onToggleRecurringSchedule = viewModel::toggleRecurringSchedule,
                        onCreateLoan = viewModel::createLoan,
                        onUpdateLoan = viewModel::updateLoan,
                        onRecordLoanRepayment = viewModel::recordLoanRepayment,
                        onDeleteLoan = viewModel::deleteLoan,
                        onCreateSavingsGoal = viewModel::createSavingsGoal,
                        onUpdateSavingsGoal = viewModel::updateSavingsGoal,
                        onRecordSavingsMove = viewModel::recordSavingsMove,
                        onDeleteSavingsGoal = viewModel::deleteSavingsGoal,
                        onCreateInvestment = viewModel::createInvestment,
                        onUpdateInvestment = viewModel::updateInvestment,
                        onDeleteInvestment = viewModel::deleteInvestment,
                        onCreateBudget = viewModel::createBudget,
                        onUpdateBudget = viewModel::updateBudget,
                        onDeleteBudget = viewModel::deleteBudget,
                        onSendChatMessage = { msg -> viewModel.sendChatMessage(msg) },
                        onLoadOlderChatMessages = viewModel::loadOlderChatMessages,
                        onEditChatMessage = viewModel::editChatMessage,
                        onDeleteChatMessage = viewModel::deleteChatMessage,
                        onMarkVisibleChatsRead = viewModel::markVisibleChatsRead,
                        onUpdateChatRetentionDays = viewModel::updateChatRetentionDays,
                        onPurgeExpiredChatsNow = viewModel::purgeExpiredChatsNow,
                        onToggleNotificationsEnabled = viewModel::setNotificationsEnabled,
                        onMarkNotificationRead = viewModel::markNotificationRead,
                        onMarkAllNotificationsRead = viewModel::markAllNotificationsRead,
                        onArchiveNotification = viewModel::archiveNotification,
                        onRefreshCachedReport = viewModel::refreshCachedReport,
                        onSelectConflictPolicy = viewModel::setConflictPolicy,
                        onResolveConflict = viewModel::resolveConflict,
                        onNotifyMessage = viewModel::notifyMessage,
                        onSelectTheme = viewModel::setThemeMode,
                        onSyncNow = { viewModel.syncNow(autoTriggered = false) },
                        onEnrollPasskey = viewModel::enrollPasskey,
                        onDeletePasskey = viewModel::deletePasskey,
                        onRevokeSession = viewModel::revokeSession,
                        onRevokeAllOtherSessions = viewModel::revokeAllOtherSessions,
                        onToggleNewDeviceCheck = viewModel::toggleNewDeviceCheck,
                        onBeforeSignOutPurge = viewModel::purgeLocalCacheOnSignOut,
                        onSignedOut = onSignedOut,
                        callHistory = callHistory,
                        meetingsState = meetingsState,
                        prefilledMeetInviteInput = prefilledMeetInviteInput,
                        onPrefilledMeetInviteConsumed = onPrefilledMeetInviteConsumed,
                        onStartCall = viewModel::startOutgoingCall,
                        onCreateMeeting = { title, isScheduled, scheduledFor, invited, audioDef, videoDef, hasAudio, hasCam ->
                            viewModel.createMeeting(
                                title = title,
                                isScheduled = isScheduled,
                                scheduledForMillis = scheduledFor,
                                invitedUids = invited,
                                audioEnabledDefault = audioDef,
                                videoEnabledDefault = videoDef,
                                autoJoinIfLive = true,
                                hasAudioPerm = hasAudio,
                                hasCameraPerm = hasCam
                            )
                        },
                        onJoinMeetingByInput = viewModel::joinMeetingByInput,
                        onJoinExistingMeeting = viewModel::joinExistingMeeting,
                        onEndMeetingForEveryone = viewModel::endMeetingForEveryone,
                        approvalSettings = approvalSettingsState,
                        proposals = proposalsState,
                        proposalVotes = proposalVotesState,
                        proposalHistory = proposalHistoryState,
                        selectedProposalId = selectedProposalId,
                        onSelectProposal = viewModel::selectProposal,
                        onSaveApprovalSettings = viewModel::saveApprovalSettings,
                        onSaveOrSubmitProposal = viewModel::createOrSubmitProposal,
                        onDeleteDraftProposal = viewModel::deleteDraftProposal,
                        onCastOrChangeVote = viewModel::castOrChangeProposalVote,
                        onCancelProposal = viewModel::cancelProposal,
                        onExecuteApprovedProposal = viewModel::executeApprovedProposal,
                        modifier = Modifier.weight(1f)
                    )
                    }
                }
            }
        }

        val workspaceDisplayLabel = if (activeFamily?.isPersonal == true) {
            "Personal Finance (Private)"
        } else {
            activeFamily?.name
        }

        if (isDesktop) {
            Row(modifier = Modifier.fillMaxSize()) {
                DesktopSidebar(
                    currentDestination = currentDestination,
                    activeFamilyName = workspaceDisplayLabel,
                    userDisplayName = userDisplayName,
                    unreadChatCount = unreadChatCount,
                    unreadNotificationCount = unreadNotificationCount,
                    pendingDecisionVoteCount = pendingDecisionVoteCount,
                    onSelectModule = { module -> navigateSingleTop(module.toRoute()) },
                    onOpenHub = { navigateSingleTop(AppRoute.ModulesHub) }
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(modifier = Modifier.weight(1f)) {
                    mainScaffold()
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DrawerModulesContent(
                            currentDestination = currentDestination,
                            activeFamilyName = workspaceDisplayLabel,
                            userDisplayName = userDisplayName,
                            unreadChatCount = unreadChatCount,
                            unreadNotificationCount = unreadNotificationCount,
                            pendingDecisionVoteCount = pendingDecisionVoteCount,
                            onSelectModule = { module ->
                                scope.launch { drawerState.close() }
                                navigateSingleTop(module.toRoute())
                            },
                            onOpenHub = {
                                scope.launch { drawerState.close() }
                                navigateSingleTop(AppRoute.ModulesHub)
                            }
                        )
                    }
                }
            ) {
                mainScaffold()
            }
        }

        val currentMeeting = activeMeeting
        val currentCall = activeCall
        val currentIncoming = incomingCall
        if (currentMeeting != null) {
            LiveMeetingRoomOverlay(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                meeting = currentMeeting,
                mediaState = activeMeetingMediaState,
                participantsMetadata = meetingParticipants,
                durationSeconds = meetingDurationSeconds,
                onToggleMic = viewModel::toggleMeetingMic,
                onToggleCamera = viewModel::toggleMeetingCamera,
                onSwitchCamera = viewModel::switchMeetingCamera,
                onCycleAudioRoute = viewModel::cycleMeetingAudioRoute,
                onToggleScreenShare = viewModel::toggleMeetingScreenShare,
                onMuteParticipant = { targetUid, muted, trackSid ->
                    viewModel.muteParticipantAsHost(
                        meetingId = currentMeeting.meetingId,
                        targetUid = targetUid,
                        muted = muted,
                        trackSid = trackSid
                    )
                },
                onRemoveParticipant = { targetUid ->
                    viewModel.removeParticipantAsHost(
                        meetingId = currentMeeting.meetingId,
                        targetUid = targetUid
                    )
                },
                onLeaveMeeting = { viewModel.leaveActiveMeeting(localOnlyCleanup = false) },
                onEndMeetingForEveryone = { viewModel.endMeetingForEveryone(currentMeeting.meetingId) },
                onNotifyMessage = viewModel::notifyMessage
            )
        } else if (currentCall != null) {
            OneToOneCallOverlay(
                currentUserId = currentUserId,
                callSession = currentCall,
                mediaState = activeCallMediaState,
                durationSeconds = callDurationSeconds,
                isInPipMode = isInPipMode,
                onToggleMic = viewModel::toggleCallMic,
                onToggleCamera = viewModel::toggleCallCamera,
                onSwitchCamera = viewModel::switchCallCamera,
                onCycleAudioRoute = viewModel::cycleCallAudioRoute,
                onTriggerIceRestart = viewModel::triggerCallIceRestart,
                onEnterPipMode = onEnterPipMode,
                onEndCall = viewModel::endActiveCall
            )
        }

        if (currentIncoming != null && currentCall == null && currentMeeting == null) {
            IncomingCallBannerOverlay(
                incomingCall = currentIncoming,
                onAccept = viewModel::acceptIncomingCall,
                onReject = viewModel::rejectIncomingCall
            )
        }
    }
}

@Composable
private fun DesktopSidebar(
    currentDestination: androidx.navigation.NavDestination?,
    activeFamilyName: String?,
    userDisplayName: String,
    unreadChatCount: Int,
    unreadNotificationCount: Int,
    pendingDecisionVoteCount: Int,
    onSelectModule: (AppModule) -> Unit,
    onOpenHub: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(276.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DrawerModulesContent(
                currentDestination = currentDestination,
                activeFamilyName = activeFamilyName,
                userDisplayName = userDisplayName,
                unreadChatCount = unreadChatCount,
                unreadNotificationCount = unreadNotificationCount,
                pendingDecisionVoteCount = pendingDecisionVoteCount,
                onSelectModule = onSelectModule,
                onOpenHub = onOpenHub
            )
        }
    }
}

@Composable
private fun DrawerModulesContent(
    currentDestination: androidx.navigation.NavDestination?,
    activeFamilyName: String?,
    userDisplayName: String,
    unreadChatCount: Int,
    unreadNotificationCount: Int,
    pendingDecisionVoteCount: Int,
    onSelectModule: (AppModule) -> Unit,
    onOpenHub: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FamilyFinanceBrandLockup()
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = userDisplayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = activeFamilyName ?: "No active family workspace",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        NavigationDrawerItem(
            label = { Text("All ${AppModule.entries.size} Modules Hub") },
            selected = currentDestination?.hasRoute(AppRoute.ModulesHub::class) == true,
            onClick = onOpenHub,
            icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        ModuleCategory.entries.forEach { category ->
            val modules = AppModule.entries.filter { it.category == category }
            if (modules.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    text = category.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                modules.forEach { module ->
                    val routeClass = module.toRoute()::class
                    val selected = currentDestination?.hasRoute(routeClass) == true
                    val badgeCount = when (module) {
                        AppModule.FAMILY_CHAT -> unreadChatCount
                        AppModule.NOTIFICATIONS -> unreadNotificationCount
                        AppModule.FAMILY_DECISIONS -> pendingDecisionVoteCount
                        else -> 0
                    }
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = "${module.number}. ${module.title}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = selected,
                        onClick = { onSelectModule(module) },
                        icon = {
                            Icon(
                                imageVector = module.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        badge = if (badgeCount > 0) {
                            {
                                Badge {
                                    Text(if (badgeCount > 99) "99+" else "$badgeCount")
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_item_${module.name.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    initialRoute: AppRoute,
    currentUserId: String,
    userDisplayName: String,
    userEmail: String,
    userProfile: LocalUserProfileEntity?,
    passkeys: List<PasskeyCredentialDoc>,
    sessions: List<DeviceSessionDoc>,
    currentSessionId: String?,
    otpState: PhoneOtpState,
    familiesState: com.example.core.model.UiState<List<LocalFamilyEntity>>,
    activeFamily: LocalFamilyEntity?,
    personalFamily: LocalFamilyEntity?,
    workspaceMode: WorkspaceMode,
    membershipState: FamilyMembershipState,
    membershipNotices: List<MembershipNoticeDoc>,
    activeMembers: List<com.example.core.local.LocalFamilyMemberEntity>,
    memberCards: List<LocalMemberCardEntity>,
    activeInvitations: List<FamilyInvitationDoc>,
    activeJoinRequests: List<FamilyJoinRequestDoc>,
    myJoinRequests: List<LocalMyJoinRequestEntity>,
    recoveredFamilies: List<RecoveredFamilySummary>,
    latestCreatedInvitation: CreateInvitationResponse?,
    themeMode: ThemeMode,
    syncStatusLabel: String,
    connectionState: com.example.core.firebase.SyncConnectionState,
    configSnapshot: com.example.core.firebase.FirebaseConfigSnapshot,
    appCheckStatus: com.example.core.firebase.AppCheckStatus,
    databaseHealth: DatabaseHealthSnapshot,
    recentAuditLogs: List<LocalAuditLogEntity>,
    allTransactions: List<LocalTransactionEntity>,
    incomeTransactions: List<LocalTransactionEntity>,
    expenseTransactions: List<LocalTransactionEntity>,
    loansState: List<LocalLoanEntity>,
    savingsState: List<LocalSavingsEntity>,
    investmentsState: List<LocalInvestmentEntity>,
    budgetsState: List<LocalBudgetEntity>,
    financialEvents: List<LocalFinancialEventEntity>,
    conflictPolicy: ConflictResolutionPolicy,
    outboxItems: List<LocalSyncOutboxEntity>,
    syncConflicts: List<LocalSyncConflictEntity>,
    cachedReports: List<LocalCachedReportEntity>,
    chatMessages: List<LocalChatMessageEntity>,
    notifications: List<LocalNotificationEntity>,
    unreadChatCount: Int,
    unreadNotificationCount: Int,
    notificationsEnabled: Boolean,
    isLoadingOlderChats: Boolean,
    hasMoreOlderChats: Boolean,
    isBusy: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onSwitchWorkspaceMode: (WorkspaceMode) -> Unit,
    onDismissMembershipNotice: (String) -> Unit,
    onCreateFamily: (String, String, String?, String) -> Unit,
    onRequestJoinByPassword: (String, String, String) -> Unit,
    onRequestJoinWithToken: (String, String) -> Unit,
    onSwitchFamily: (String, String) -> Unit,
    onCreateInvitation: (String, String, FamilyRole, String?, Int, Int) -> Unit,
    onClearCreatedInvitation: () -> Unit,
    onRevokeInvitation: (String, String, String) -> Unit,
    onReviewJoinRequest: (String, String, Boolean) -> Unit,
    onUpdateMemberRoleAndPermissions: (String, String, FamilyRole, Boolean, Boolean, Boolean) -> Unit,
    onSetMemberSuspension: (String, String, Boolean) -> Unit,
    onChangeFamilyPassword: (String, String, String) -> Unit,
    onContributeFromPersonal: (String, Long, String, String) -> Unit,
    onUpdateMemberCardPrivacy: (MemberPrivacySettings) -> Unit,
    onRecordMemberProfileAudit: (DigitalMemberCardData) -> Unit,
    onRemoveMember: (String, String) -> Unit,
    onTransferOwnership: (String, String, String) -> Unit,
    onUpdateFamilySettings: (String, String, String, String?, Boolean, Boolean, Int, Int) -> Unit,
    onRecoverAdminFamilyIds: () -> Unit,
    onRefreshMyJoinRequests: () -> Unit,
    onSendMobileChangeOtp: (Activity, String, Boolean) -> Unit,
    onVerifyMobileChangeOtp: (String, String, String, Boolean) -> Unit,
    onResetMobileOtpState: () -> Unit,
    onCreateTransaction: (TransactionType, Long, String, String, String, String?, Long, RecurrenceFrequency) -> Unit,
    onUpdateTransaction: (String, Long, String, String, String, String?, Long, RecurrenceFrequency, Boolean) -> Unit,
    onDeleteTransaction: (String) -> Unit,
    onPostNextRecurringNow: (String) -> Unit,
    onToggleRecurringSchedule: (String, Boolean) -> Unit,
    onCreateLoan: (LoanDirection, Long, String, String, String, String, String, Double?, Int, Long, Long, Long?, String) -> Unit,
    onUpdateLoan: (String, LoanDirection, Long, Long, String, String, String, String, String, Double?, Int, Int, Long, Long?, Long?, String) -> Unit,
    onRecordLoanRepayment: (String, Long, Long, String) -> Unit,
    onDeleteLoan: (String) -> Unit,
    onCreateSavingsGoal: (String, String, Long, Long, Long?, String) -> Unit,
    onUpdateSavingsGoal: (String, String, String, Long, Long?, String) -> Unit,
    onRecordSavingsMove: (String, Long, Boolean, Long, String) -> Unit,
    onDeleteSavingsGoal: (String) -> Unit,
    onCreateInvestment: (String, InvestmentAssetType, String?, Long, Long, Double?, Long, String) -> Unit,
    onUpdateInvestment: (String, String, InvestmentAssetType, String?, Long, Long, Double?, Long, String, String) -> Unit,
    onDeleteInvestment: (String) -> Unit,
    onCreateBudget: (BudgetScope, String, String, Long, Int, String) -> Unit,
    onUpdateBudget: (String, BudgetScope, String, String, Long, Int, String) -> Unit,
    onDeleteBudget: (String) -> Unit,
    onSendChatMessage: (String) -> Unit,
    onLoadOlderChatMessages: () -> Unit,
    onEditChatMessage: (String, String) -> Unit,
    onDeleteChatMessage: (String) -> Unit,
    onMarkVisibleChatsRead: (List<String>) -> Unit,
    onUpdateChatRetentionDays: (Int) -> Unit,
    onPurgeExpiredChatsNow: () -> Unit,
    onToggleNotificationsEnabled: (Boolean) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllNotificationsRead: () -> Unit,
    onArchiveNotification: (String) -> Unit,
    onRefreshCachedReport: (String) -> Unit,
    onSelectConflictPolicy: (ConflictResolutionPolicy) -> Unit,
    onResolveConflict: (String, ConflictResolutionPolicy) -> Unit,
    onNotifyMessage: (String) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onSyncNow: () -> Unit,
    onEnrollPasskey: (Activity, CredentialManager, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onRevokeSession: (String) -> Unit,
    onRevokeAllOtherSessions: () -> Unit,
    onToggleNewDeviceCheck: (Boolean) -> Unit,
    onBeforeSignOutPurge: suspend () -> Unit,
    onSignedOut: () -> Unit,
    callHistory: List<LocalCallRecordEntity>,
    meetingsState: List<LocalMeetingEntity>,
    prefilledMeetInviteInput: String?,
    onPrefilledMeetInviteConsumed: () -> Unit,
    onStartCall: (com.example.core.local.LocalFamilyMemberEntity, CallMediaType, Boolean, Boolean) -> Unit,
    onCreateMeeting: (String, Boolean, Long?, List<String>, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onJoinMeetingByInput: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onJoinExistingMeeting: (LocalMeetingEntity, Boolean, Boolean) -> Unit,
    onEndMeetingForEveryone: (String) -> Unit,
    approvalSettings: LocalApprovalSettingsEntity?,
    proposals: List<LocalProposalEntity>,
    proposalVotes: List<LocalProposalVoteEntity>,
    proposalHistory: List<LocalProposalVoteHistoryEntity>,
    selectedProposalId: String?,
    onSelectProposal: (String?) -> Unit,
    onSaveApprovalSettings: (
        ApprovalRuleMode,
        Int,
        Int,
        Int,
        Boolean,
        Boolean,
        EligibleVoterRule,
        List<String>,
        Set<DecisionCategory>,
        Long
    ) -> Unit,
    onSaveOrSubmitProposal: (
        LocalProposalEntity?,
        String,
        DecisionCategory,
        String,
        String,
        Long,
        String,
        String,
        String?,
        String?,
        String?,
        Long,
        Long,
        Boolean,
        (LocalProposalEntity) -> Unit
    ) -> Unit,
    onDeleteDraftProposal: (String, () -> Unit) -> Unit,
    onCastOrChangeVote: (String, VoteChoice, String?) -> Unit,
    onCancelProposal: (String, String) -> Unit,
    onExecuteApprovedProposal: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = modifier.fillMaxSize()
    ) {
        composable<AppRoute.Dashboard> {
            DashboardScreen(
                currentUserId = currentUserId,
                userDisplayName = userDisplayName,
                familiesState = familiesState,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                transactions = allTransactions,
                loans = loansState,
                savings = savingsState,
                investments = investmentsState,
                budgets = budgetsState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                onSyncNow = onSyncNow,
                onNavigateToModule = { module -> onNavigate(module.toRoute()) },
                onOpenAllModules = { onNavigate(AppRoute.ModulesHub) }
            )
        }

        composable<AppRoute.ModulesHub> {
            AllModulesHubScreen(
                onNavigateToModule = { module -> onNavigate(module.toRoute()) }
            )
        }

        composable<AppRoute.FamilyManagement> {
            FamilyManagementScreen(
                currentUserId = currentUserId,
                familiesState = familiesState,
                activeFamily = activeFamily,
                personalFamily = personalFamily,
                workspaceMode = workspaceMode,
                membershipState = membershipState,
                membershipNotices = membershipNotices,
                activeMembers = activeMembers,
                activeInvitations = activeInvitations,
                activeJoinRequests = activeJoinRequests,
                myJoinRequests = myJoinRequests,
                recoveredFamilies = recoveredFamilies,
                latestCreatedInvitation = latestCreatedInvitation,
                isBusy = isBusy,
                onSwitchWorkspaceMode = onSwitchWorkspaceMode,
                onDismissMembershipNotice = onDismissMembershipNotice,
                onCreateFamily = onCreateFamily,
                onRequestJoinByPassword = onRequestJoinByPassword,
                onRequestJoinWithToken = onRequestJoinWithToken,
                onSwitchFamily = onSwitchFamily,
                onCreateInvitation = onCreateInvitation,
                onClearCreatedInvitation = onClearCreatedInvitation,
                onRevokeInvitation = onRevokeInvitation,
                onReviewJoinRequest = onReviewJoinRequest,
                onUpdateMemberRoleAndPermissions = onUpdateMemberRoleAndPermissions,
                onSetMemberSuspension = onSetMemberSuspension,
                onChangeFamilyPassword = onChangeFamilyPassword,
                onContributeFromPersonal = onContributeFromPersonal,
                onRemoveMember = onRemoveMember,
                onTransferOwnership = onTransferOwnership,
                onUpdateFamilySettings = onUpdateFamilySettings,
                onRecoverAdminFamilyIds = onRecoverAdminFamilyIds,
                onRefreshMyJoinRequests = onRefreshMyJoinRequests,
                onOpenMemberCards = { onNavigate(AppRoute.MemberCards) },
                onNotifyMessage = onNotifyMessage,
                onSyncNow = onSyncNow
            )
        }

        composable<AppRoute.MemberCards> {
            MemberCardsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                personalFamily = personalFamily,
                activeMembers = activeMembers,
                memberCards = memberCards,
                transactions = allTransactions,
                loans = loansState,
                savings = savingsState,
                investments = investmentsState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onUpdatePrivacy = onUpdateMemberCardPrivacy,
                onRecordAdminAudit = onRecordMemberProfileAudit,
                onCreateTransaction = onCreateTransaction,
                onContributeFromPersonal = onContributeFromPersonal,
                onSyncNow = onSyncNow,
                onNotifyMessage = onNotifyMessage,
                onNavigateToModule = { module -> onNavigate(module.toRoute()) },
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Settings> {
            SettingsScreen(
                currentUserId = currentUserId,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                activeFamily = activeFamily,
                themeMode = themeMode,
                connectionState = connectionState,
                configSnapshot = configSnapshot,
                appCheckStatus = appCheckStatus,
                databaseHealth = databaseHealth,
                recentAuditLogs = recentAuditLogs,
                conflictPolicy = conflictPolicy,
                outboxItems = outboxItems,
                syncConflicts = syncConflicts,
                onSelectTheme = onSelectTheme,
                onSelectConflictPolicy = onSelectConflictPolicy,
                onResolveConflict = onResolveConflict,
                onSyncNow = onSyncNow,
                onBeforeSignOutPurge = onBeforeSignOutPurge,
                onSignedOut = onSignedOut
            )
        }

        composable<AppRoute.Security> {
            SecurityScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                configSnapshot = configSnapshot,
                appCheckStatus = appCheckStatus,
                passkeyCount = passkeys.size,
                activeSessionCount = sessions.count { !it.revoked },
                newDeviceCheckEnabled = userProfile?.newDeviceCheckEnabled ?: true,
                databaseHealth = databaseHealth,
                onOpenAuthManagement = { onNavigate(AppRoute.AuthStatus) }
            )
        }

        composable<AppRoute.AuthStatus> {
            AuthStatusScreen(
                currentUserId = currentUserId,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                userProfile = userProfile,
                passkeys = passkeys,
                sessions = sessions,
                currentSessionId = currentSessionId,
                otpState = otpState,
                isBusy = isBusy,
                onEnrollPasskey = onEnrollPasskey,
                onDeletePasskey = onDeletePasskey,
                onRevokeSession = onRevokeSession,
                onRevokeAllOtherSessions = onRevokeAllOtherSessions,
                onToggleNewDeviceCheck = onToggleNewDeviceCheck,
                onSendMobileChangeOtp = onSendMobileChangeOtp,
                onVerifyMobileChangeOtp = onVerifyMobileChangeOtp,
                onResetMobileOtpState = onResetMobileOtpState
            )
        }

        composable<AppRoute.Income> {
            IncomeExpenseScreen(
                transactionType = TransactionType.INCOME,
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                transactions = incomeTransactions,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateTransaction = onCreateTransaction,
                onUpdateTransaction = onUpdateTransaction,
                onDeleteTransaction = onDeleteTransaction,
                onPostNextRecurringNow = onPostNextRecurringNow,
                onToggleRecurringSchedule = onToggleRecurringSchedule,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) },
                onNotifyMessage = onNotifyMessage
            )
        }

        composable<AppRoute.Expense> {
            IncomeExpenseScreen(
                transactionType = TransactionType.EXPENSE,
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                transactions = expenseTransactions,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateTransaction = onCreateTransaction,
                onUpdateTransaction = onUpdateTransaction,
                onDeleteTransaction = onDeleteTransaction,
                onPostNextRecurringNow = onPostNextRecurringNow,
                onToggleRecurringSchedule = onToggleRecurringSchedule,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) },
                onNotifyMessage = onNotifyMessage
            )
        }

        composable<AppRoute.Loans> {
            LoansScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                loans = loansState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateLoan = onCreateLoan,
                onUpdateLoan = onUpdateLoan,
                onRecordRepayment = onRecordLoanRepayment,
                onDeleteLoan = onDeleteLoan,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Savings> {
            SavingsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                savingsGoals = savingsState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateGoal = onCreateSavingsGoal,
                onUpdateGoal = onUpdateSavingsGoal,
                onRecordMove = onRecordSavingsMove,
                onDeleteGoal = onDeleteSavingsGoal,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Investments> {
            InvestmentsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                investments = investmentsState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateInvestment = onCreateInvestment,
                onUpdateInvestment = onUpdateInvestment,
                onDeleteInvestment = onDeleteInvestment,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Budgets> {
            BudgetsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                budgets = budgetsState,
                expenseTransactions = expenseTransactions,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                isBusy = isBusy,
                onCreateBudget = onCreateBudget,
                onUpdateBudget = onUpdateBudget,
                onDeleteBudget = onDeleteBudget,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Reports> {
            ReportsScreen(
                activeFamily = activeFamily,
                cachedReports = cachedReports,
                transactions = allTransactions,
                loans = loansState,
                savings = savingsState,
                investments = investmentsState,
                budgets = budgetsState,
                syncStatusLabel = syncStatusLabel,
                onRefreshReport = onRefreshCachedReport,
                onNotifyMessage = onNotifyMessage,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) },
                members = activeMembers,
                financialEvents = financialEvents
            )
        }

        composable<AppRoute.FamilyChat> {
            FamilyChatScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                messages = chatMessages,
                syncStatusLabel = syncStatusLabel,
                onSendMessage = onSendChatMessage,
                onSyncNow = onSyncNow,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) },
                unreadCount = unreadChatCount,
                isLoadingOlder = isLoadingOlderChats,
                hasMoreOlder = hasMoreOlderChats,
                onLoadOlderMessages = onLoadOlderChatMessages,
                onEditMessage = onEditChatMessage,
                onDeleteMessage = onDeleteChatMessage,
                onMarkMessagesRead = onMarkVisibleChatsRead,
                onUpdateRetentionDays = onUpdateChatRetentionDays,
                onPurgeExpiredChats = onPurgeExpiredChatsNow,
                onOpenMeet = { onNavigate(AppRoute.FamilyMeet) }
            )
        }

        composable<AppRoute.FamilyMeet> {
            FamilyMeetScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                meetings = meetingsState,
                callHistory = callHistory,
                prefilledInviteInput = prefilledMeetInviteInput,
                onPrefilledInviteConsumed = onPrefilledMeetInviteConsumed,
                isBusy = isBusy,
                onStartCall = onStartCall,
                onCreateMeeting = onCreateMeeting,
                onJoinMeetingByInput = onJoinMeetingByInput,
                onJoinExistingMeeting = onJoinExistingMeeting,
                onEndMeetingForEveryone = onEndMeetingForEveryone,
                onNotifyMessage = onNotifyMessage,
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.FamilyDecisions> {
            FamilyDecisionsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                approvalSettings = approvalSettings,
                proposals = proposals,
                allVotes = proposalVotes,
                allHistory = proposalHistory,
                savingsGoals = savingsState,
                selectedProposalId = selectedProposalId,
                isBusy = isBusy,
                onSelectProposal = onSelectProposal,
                onSaveApprovalSettings = onSaveApprovalSettings,
                onSaveOrSubmitProposal = onSaveOrSubmitProposal,
                onDeleteDraftProposal = onDeleteDraftProposal,
                onCastOrChangeVote = onCastOrChangeVote,
                onCancelProposal = onCancelProposal,
                onExecuteApprovedProposal = onExecuteApprovedProposal,
                onNavigateToModule = { module -> onNavigate(module.toRoute()) },
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }

        composable<AppRoute.Analytics> {
            AnalyticsScreen(
                familiesState = familiesState,
                activeFamily = activeFamily,
                activeMembers = activeMembers,
                transactions = allTransactions,
                loans = loansState,
                savings = savingsState,
                investments = investmentsState,
                financialEvents = financialEvents,
                syncStatusLabel = syncStatusLabel,
                onSyncNow = onSyncNow,
                onNavigateToModule = { module -> onNavigate(module.toRoute()) }
            )
        }

        composable<AppRoute.Notifications> {
            NotificationsScreen(
                currentUserId = currentUserId,
                activeFamily = activeFamily,
                notifications = notifications,
                unreadCount = unreadNotificationCount,
                notificationsEnabled = notificationsEnabled,
                onToggleNotificationsEnabled = onToggleNotificationsEnabled,
                onMarkRead = onMarkNotificationRead,
                onMarkAllRead = onMarkAllNotificationsRead,
                onArchive = onArchiveNotification,
                onOpenModule = { module -> onNavigate(module.toRoute()) },
                onNavigateToFamilyManagement = { onNavigate(AppRoute.FamilyManagement) }
            )
        }
    }
}
