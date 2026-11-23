package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.LockPerson
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalMyJoinRequestEntity
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.FamilyInvitationDoc
import com.example.core.model.FamilyJoinRequestDoc
import com.example.core.model.FamilyMembershipState
import com.example.core.model.FamilyRole
import com.example.core.model.MembershipNoticeDoc
import com.example.core.model.RecoveredFamilySummary
import com.example.core.model.UiState
import com.example.core.model.WorkspaceMode
import com.example.ui.components.SectionHeader
import com.example.ui.screens.family.ChangeFamilyPasswordDialog
import com.example.ui.screens.family.ConfirmOwnershipTransferDialog
import com.example.ui.screens.family.ContributeFromPersonalDialog
import com.example.ui.screens.family.CreateFamilyDialog
import com.example.ui.screens.family.CreateInvitationDialog
import com.example.ui.screens.family.CreatedInvitationTokenDialog
import com.example.ui.screens.family.FamilyInvitationsAndApprovalsSection
import com.example.ui.screens.family.FamilyMembersSection
import com.example.ui.screens.family.FamilyProfileAndIdCard
import com.example.ui.screens.family.FamilyRecoverySection
import com.example.ui.screens.family.FamilySettingsDialog
import com.example.ui.screens.family.JoinViaTokenDialog
import com.example.ui.screens.family.ManageMemberDialog
import com.example.ui.theme.MoneySmallStyle

@Composable
fun FamilyManagementScreen(
    currentUserId: String,
    familiesState: UiState<List<LocalFamilyEntity>>,
    activeFamily: LocalFamilyEntity?,
    personalFamily: LocalFamilyEntity?,
    workspaceMode: WorkspaceMode,
    membershipState: FamilyMembershipState,
    membershipNotices: List<MembershipNoticeDoc>,
    activeMembers: List<LocalFamilyMemberEntity>,
    activeInvitations: List<FamilyInvitationDoc>,
    activeJoinRequests: List<FamilyJoinRequestDoc>,
    myJoinRequests: List<LocalMyJoinRequestEntity>,
    recoveredFamilies: List<RecoveredFamilySummary>,
    latestCreatedInvitation: CreateInvitationResponse?,
    isBusy: Boolean,
    onSwitchWorkspaceMode: (WorkspaceMode) -> Unit,
    onDismissMembershipNotice: (String) -> Unit,
    onCreateFamily: (name: String, currencyCode: String, description: String?, familyPassword: String) -> Unit,
    onRequestJoinByPassword: (familyId: String, familyPassword: String, relationLabel: String) -> Unit,
    onRequestJoinWithToken: (invitationToken: String, relationLabel: String) -> Unit,
    onSwitchFamily: (familyId: String, familyName: String) -> Unit,
    onCreateInvitation: (
        familyId: String,
        familyName: String,
        targetRole: FamilyRole,
        inviteeHint: String?,
        expiresInHours: Int,
        maxUses: Int
    ) -> Unit,
    onClearCreatedInvitation: () -> Unit,
    onRevokeInvitation: (familyId: String, invitationId: String, tokenHash: String) -> Unit,
    onReviewJoinRequest: (familyId: String, requesterUid: String, approve: Boolean) -> Unit,
    onUpdateMemberRoleAndPermissions: (
        familyId: String,
        targetUid: String,
        role: FamilyRole,
        canCreateRecords: Boolean,
        canViewSharedFinances: Boolean,
        canParticipateInChat: Boolean
    ) -> Unit,
    onSetMemberSuspension: (familyId: String, targetUid: String, suspend: Boolean) -> Unit,
    onChangeFamilyPassword: (familyId: String, currentPassword: String, newPassword: String) -> Unit,
    onContributeFromPersonal: (targetFamilyId: String, amountMinor: Long, category: String, description: String) -> Unit,
    onRemoveMember: (familyId: String, targetUid: String) -> Unit,
    onTransferOwnership: (familyId: String, newOwnerUid: String, familyPassword: String) -> Unit,
    onUpdateFamilySettings: (
        familyId: String,
        name: String,
        currencyCode: String,
        description: String?,
        memberChatEnabled: Boolean,
        membersCanAddExpenses: Boolean,
        budgetStartDay: Int,
        chatRetentionDays: Int
    ) -> Unit,
    onRecoverAdminFamilyIds: () -> Unit,
    onRefreshMyJoinRequests: () -> Unit,
    onOpenMemberCards: () -> Unit = {},
    onNotifyMessage: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showContributeDialog by remember { mutableStateOf(false) }
    var managedMember by remember { mutableStateOf<LocalFamilyMemberEntity?>(null) }
    var transferTargetMember by remember { mutableStateOf<LocalFamilyMemberEntity?>(null) }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        onNotifyMessage("$label copied to clipboard.")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 960.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Workspace Separation & Mode Switcher
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Personal & Family Workspaces",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Personal Finance is always isolated from shared families; records never merge automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = membershipState.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = workspaceMode == WorkspaceMode.PERSONAL || activeFamily?.isPersonal != false,
                            onClick = { onSwitchWorkspaceMode(WorkspaceMode.PERSONAL) },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Personal Finance") },
                            modifier = Modifier.testTag("workspace_mode_personal")
                        )
                        FilterChip(
                            selected = workspaceMode == WorkspaceMode.FAMILY && activeFamily?.isPersonal == false,
                            onClick = { onSwitchWorkspaceMode(WorkspaceMode.FAMILY) },
                            leadingIcon = { Icon(Icons.Outlined.Diversity3, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Family Finance") },
                            modifier = Modifier.testTag("workspace_mode_family")
                        )
                    }

                    if (personalFamily != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.LockPerson, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Private Workspace: ${personalFamily.name} (${personalFamily.familyId.take(16)}…) • Shared chat/meet are disabled here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showCreateDialog = true },
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("create_family_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Family")
                        }
                        OutlinedButton(
                            onClick = { showJoinDialog = true },
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("join_family_button")
                        ) {
                            Icon(Icons.Outlined.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Join Family")
                        }
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("sync_families_button")
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = "Sync families", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Membership Restriction Notices (Suspended / Removed)
            membershipNotices.forEach { notice ->
                RestrictedFamilyAccessCard(
                    notice = notice,
                    onContinuePersonal = { onSwitchWorkspaceMode(WorkspaceMode.PERSONAL) },
                    onDismiss = { onDismissMembershipNotice(notice.familyId) }
                )
            }

            // Pending Admin Approval Status Banner
            if (membershipState == FamilyMembershipState.PENDING_REQUEST) {
                PendingApprovalCard(onRefresh = onRefreshMyJoinRequests)
            }

            // Authorized Shared Family Workspaces
            SectionHeader(
                title = "Your Shared Families",
                subtitle = "Switch between authorized families or stay in Personal Finance"
            )

            when (familiesState) {
                UiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = familiesState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                is UiState.Success -> {
                    if (familiesState.data.isEmpty()) {
                        Card(
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Diversity3,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "No shared family workspace yet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Your private Personal Finance workspace is ready. You can also create a shared family as Primary Admin, join using a 12-char Family ID + Family Password or invite token, or recover an Admin-linked Family ID below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { showCreateDialog = true },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Create Family")
                                    }
                                    OutlinedButton(
                                        onClick = { showJoinDialog = true },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Join Family")
                                    }
                                }
                            }
                        }
                    } else {
                        familiesState.data.forEach { family ->
                            val isSelected = activeFamily?.familyId == family.familyId &&
                                workspaceMode == WorkspaceMode.FAMILY
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitchFamily(family.familyId, family.name) }
                                    .testTag("family_item_${family.familyId}"),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = family.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.CheckCircle,
                                                    contentDescription = "Active family",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (family.syncStatus == "SYNCED") {
                                                        Icons.Outlined.CloudDone
                                                    } else {
                                                        Icons.Outlined.CloudQueue
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = family.syncStatus,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Family ID: ${family.familyId}  •  ${family.currencyCode}  •  ${family.memberUids.size} member(s)",
                                        style = MoneySmallStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active Shared Family Profile, ID Copy, Approvals, Invitations & Member Directory
            if (activeFamily != null && !activeFamily.isPersonal) {
                val isAdmin = activeFamily.isAdmin(currentUserId)

                FamilyProfileAndIdCard(
                    family = activeFamily,
                    currentUserId = currentUserId,
                    isBusy = isBusy,
                    isPersonalWorkspace = false,
                    onCopyFamilyId = { fid -> copyToClipboard("Family ID", fid) },
                    onOpenInviteDialog = { showInviteDialog = true },
                    onOpenSettingsDialog = { showSettingsDialog = true },
                    onOpenChangeFamilyPassword = { showChangePasswordDialog = true },
                    onOpenContributeFromPersonal = { showContributeDialog = true },
                    onLeaveFamily = { onRemoveMember(activeFamily.familyId, currentUserId) }
                )

                FamilyInvitationsAndApprovalsSection(
                    isAdmin = isAdmin,
                    invitations = activeInvitations,
                    joinRequests = activeJoinRequests,
                    myJoinRequests = myJoinRequests,
                    isBusy = isBusy,
                    onApproveRequest = { uid -> onReviewJoinRequest(activeFamily.familyId, uid, true) },
                    onRejectRequest = { uid -> onReviewJoinRequest(activeFamily.familyId, uid, false) },
                    onCopyInvitationToken = { token -> copyToClipboard("Invitation Token", token) },
                    onRevokeInvitation = { invId, hash ->
                        onRevokeInvitation(activeFamily.familyId, invId, hash)
                    },
                    onRefreshMyJoinRequests = onRefreshMyJoinRequests
                )

                FamilyMembersSection(
                    family = activeFamily,
                    members = activeMembers,
                    currentUserId = currentUserId,
                    isAdmin = isAdmin,
                    onManageMember = { member -> managedMember = member },
                    onOpenMemberCards = onOpenMemberCards
                )
            } else if (myJoinRequests.isNotEmpty()) {
                FamilyInvitationsAndApprovalsSection(
                    isAdmin = false,
                    invitations = emptyList(),
                    joinRequests = emptyList(),
                    myJoinRequests = myJoinRequests,
                    isBusy = isBusy,
                    onApproveRequest = {},
                    onRejectRequest = {},
                    onCopyInvitationToken = {},
                    onRevokeInvitation = { _, _ -> },
                    onRefreshMyJoinRequests = onRefreshMyJoinRequests
                )
            }

            // Authenticated Admin Family ID Recovery
            FamilyRecoverySection(
                currentUserId = currentUserId,
                recoveredFamilies = recoveredFamilies,
                isBusy = isBusy,
                onRecoverFamilyIds = onRecoverAdminFamilyIds,
                onCopyFamilyId = { fid -> copyToClipboard("Recovered Family ID", fid) }
            )
        }
    }

    if (showCreateDialog) {
        CreateFamilyDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, currency, desc, familyPassword ->
                showCreateDialog = false
                onCreateFamily(name, currency, desc, familyPassword)
            }
        )
    }

    if (showJoinDialog) {
        JoinViaTokenDialog(
            onDismiss = { showJoinDialog = false },
            onConfirmByPassword = { familyId, familyPassword, relation ->
                showJoinDialog = false
                onRequestJoinByPassword(familyId, familyPassword, relation)
            },
            onConfirmByToken = { token, relation ->
                showJoinDialog = false
                onRequestJoinWithToken(token, relation)
            }
        )
    }

    if (showInviteDialog && activeFamily != null && !activeFamily.isPersonal) {
        CreateInvitationDialog(
            familyName = activeFamily.name,
            onDismiss = { showInviteDialog = false },
            onConfirm = { targetRole, hint, hours, uses ->
                showInviteDialog = false
                onCreateInvitation(activeFamily.familyId, activeFamily.name, targetRole, hint, hours, uses)
            }
        )
    }

    if (latestCreatedInvitation != null) {
        CreatedInvitationTokenDialog(
            invitation = latestCreatedInvitation,
            onCopyToken = { token -> copyToClipboard("Invitation Token", token) },
            onDismiss = onClearCreatedInvitation
        )
    }

    if (showSettingsDialog && activeFamily != null && !activeFamily.isPersonal) {
        FamilySettingsDialog(
            family = activeFamily,
            onDismiss = { showSettingsDialog = false },
            onSave = { name, currency, desc, chatEnabled, addExpenses, startDay, retentionDays ->
                showSettingsDialog = false
                onUpdateFamilySettings(
                    activeFamily.familyId,
                    name,
                    currency,
                    desc,
                    chatEnabled,
                    addExpenses,
                    startDay,
                    retentionDays
                )
            }
        )
    }

    if (showChangePasswordDialog && activeFamily != null && !activeFamily.isPersonal) {
        ChangeFamilyPasswordDialog(
            familyName = activeFamily.name,
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { currentPwd, newPwd ->
                showChangePasswordDialog = false
                onChangeFamilyPassword(activeFamily.familyId, currentPwd, newPwd)
            }
        )
    }

    if (showContributeDialog && activeFamily != null && !activeFamily.isPersonal) {
        ContributeFromPersonalDialog(
            targetFamilyName = activeFamily.name,
            currencyCode = activeFamily.currencyCode,
            onDismiss = { showContributeDialog = false },
            onConfirm = { amountMinor, category, description ->
                showContributeDialog = false
                onContributeFromPersonal(activeFamily.familyId, amountMinor, category, description)
            }
        )
    }

    val currentManaged = managedMember
    if (currentManaged != null && activeFamily != null && !activeFamily.isPersonal) {
        ManageMemberDialog(
            member = currentManaged,
            isTargetPrimaryOwner = currentManaged.userId == activeFamily.ownerId,
            isCallerPrimaryOwner = activeFamily.ownerId == currentUserId,
            onDismiss = { managedMember = null },
            onSaveRoleAndPermissions = { role, canCreate, canView, canChat ->
                managedMember = null
                onUpdateMemberRoleAndPermissions(
                    activeFamily.familyId,
                    currentManaged.userId,
                    role,
                    canCreate,
                    canView,
                    canChat
                )
            },
            onToggleSuspendMember = { suspend ->
                managedMember = null
                onSetMemberSuspension(activeFamily.familyId, currentManaged.userId, suspend)
            },
            onRequestRemoveMember = {
                managedMember = null
                onRemoveMember(activeFamily.familyId, currentManaged.userId)
            },
            onRequestTransferOwnership = {
                managedMember = null
                transferTargetMember = currentManaged
            }
        )
    }

    val transferTarget = transferTargetMember
    if (transferTarget != null && activeFamily != null && !activeFamily.isPersonal) {
        ConfirmOwnershipTransferDialog(
            familyName = activeFamily.name,
            targetMember = transferTarget,
            onDismiss = { transferTargetMember = null },
            onConfirm = { familyPassword ->
                transferTargetMember = null
                onTransferOwnership(activeFamily.familyId, transferTarget.userId, familyPassword)
            }
        )
    }
}

@Composable
private fun RestrictedFamilyAccessCard(
    notice: MembershipNoticeDoc,
    onContinuePersonal: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSuspended = notice.status.equals("SUSPENDED", ignoreCase = true)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("restricted_access_card_${notice.familyId}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (isSuspended) {
                        "Access Suspended: ${notice.familyName}"
                    } else {
                        "Removed from Family: ${notice.familyName}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = notice.reason?.takeIf { it.isNotBlank() }
                    ?: if (isSuspended) {
                        "The Primary Admin temporarily suspended your shared access. Your private Personal Finance data remains intact."
                    } else {
                        "You are no longer a member of this shared family. Your Personal Finance workspace remains active."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onContinuePersonal,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("restricted_continue_personal_button")
                ) {
                    Text("Continue in Personal Finance")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dismiss_notice_${notice.familyId}")
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun PendingApprovalCard(onRefresh: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column {
                    Text(
                        text = "Family Join Request Pending Approval",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Your Personal Finance workspace works normally while you wait for Primary Admin review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            TextButton(onClick = onRefresh) {
                Text("Refresh")
            }
        }
    }
}
