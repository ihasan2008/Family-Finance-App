package com.example.ui.screens.modules

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.model.CallMediaType
import com.example.core.model.CallStatus
import com.example.core.model.MeetingStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MeetHubTab(val label: String) {
    MEETINGS("Group Meet"),
    CALLS("1-to-1 Calls"),
    HISTORY("History")
}

private val meetDateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilyMeetScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    meetings: List<LocalMeetingEntity>,
    callHistory: List<LocalCallRecordEntity>,
    prefilledInviteInput: String? = null,
    onPrefilledInviteConsumed: () -> Unit = {},
    isBusy: Boolean = false,
    onStartCall: (callee: LocalFamilyMemberEntity, mediaType: CallMediaType, hasAudioPerm: Boolean, hasCameraPerm: Boolean) -> Unit,
    onCreateMeeting: (
        title: String,
        isScheduled: Boolean,
        scheduledForMillis: Long?,
        invitedUids: List<String>,
        audioDefault: Boolean,
        videoDefault: Boolean,
        hasAudioPerm: Boolean,
        hasCameraPerm: Boolean
    ) -> Unit,
    onJoinMeetingByInput: (input: String, hasAudioPerm: Boolean, hasCameraPerm: Boolean, joinAudio: Boolean, joinVideo: Boolean) -> Unit,
    onJoinExistingMeeting: (meeting: LocalMeetingEntity, hasAudioPerm: Boolean, hasCameraPerm: Boolean) -> Unit,
    onEndMeetingForEveryone: (meetingId: String) -> Unit,
    onNotifyMessage: (String) -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Family Meet & Calls",
            moduleSubtitle = "Create or join a family workspace to place 1-to-1 WebRTC calls and host encrypted LiveKit family video meetings.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember { mutableStateOf(MeetHubTab.MEETINGS) }
    var joinInput by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createDialogScheduledDefault by remember { mutableStateOf(false) }

    LaunchedEffect(prefilledInviteInput) {
        if (!prefilledInviteInput.isNullOrBlank()) {
            joinInput = prefilledInviteInput
            selectedTab = MeetHubTab.MEETINGS
            onPrefilledInviteConsumed()
        }
    }

    // Pending action gated on runtime mic/camera permission
    var pendingCallRequest by remember { mutableStateOf<Pair<LocalFamilyMemberEntity, CallMediaType>?>(null) }
    var pendingJoinExisting by remember { mutableStateOf<LocalMeetingEntity?>(null) }
    var pendingJoinCodeInput by remember { mutableStateOf<String?>(null) }
    var pendingCreateMeetingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun hasAudioPerm(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun hasCameraPerm(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { cameraGranted ->
        val callReq = pendingCallRequest
        val existingReq = pendingJoinExisting
        val codeReq = pendingJoinCodeInput
        val createReq = pendingCreateMeetingAction
        pendingCallRequest = null
        pendingJoinExisting = null
        pendingJoinCodeInput = null
        pendingCreateMeetingAction = null

        when {
            callReq != null -> onStartCall(callReq.first, callReq.second, hasAudioPerm(), cameraGranted)
            existingReq != null -> onJoinExistingMeeting(existingReq, hasAudioPerm(), cameraGranted)
            codeReq != null -> onJoinMeetingByInput(codeReq, hasAudioPerm(), cameraGranted, true, cameraGranted)
            createReq != null -> createReq()
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { audioGranted ->
        if (!audioGranted) {
            onNotifyMessage("Microphone permission is needed for live audio in calls and meetings.")
        }
        val callReq = pendingCallRequest
        val needsCamera = when {
            callReq != null -> callReq.second == CallMediaType.VIDEO && !hasCameraPerm()
            pendingJoinExisting != null || pendingJoinCodeInput != null || pendingCreateMeetingAction != null -> !hasCameraPerm()
            else -> false
        }
        if (needsCamera) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        } else {
            val existingReq = pendingJoinExisting
            val codeReq = pendingJoinCodeInput
            val createReq = pendingCreateMeetingAction
            pendingCallRequest = null
            pendingJoinExisting = null
            pendingJoinCodeInput = null
            pendingCreateMeetingAction = null
            when {
                callReq != null -> onStartCall(callReq.first, callReq.second, audioGranted, hasCameraPerm())
                existingReq != null -> onJoinExistingMeeting(existingReq, audioGranted, hasCameraPerm())
                codeReq != null -> onJoinMeetingByInput(codeReq, audioGranted, hasCameraPerm(), audioGranted, hasCameraPerm())
                createReq != null -> createReq()
            }
        }
    }

    fun requestPermissionsThenRun(
        needCamera: Boolean,
        forCall: Pair<LocalFamilyMemberEntity, CallMediaType>? = null,
        forExisting: LocalMeetingEntity? = null,
        forCodeInput: String? = null,
        forCreate: (() -> Unit)? = null
    ) {
        val audioOk = hasAudioPerm()
        val cameraOk = !needCamera || hasCameraPerm()
        if (audioOk && cameraOk) {
            when {
                forCall != null -> onStartCall(forCall.first, forCall.second, true, hasCameraPerm())
                forExisting != null -> onJoinExistingMeeting(forExisting, true, hasCameraPerm())
                forCodeInput != null -> onJoinMeetingByInput(forCodeInput, true, hasCameraPerm(), true, hasCameraPerm())
                forCreate != null -> forCreate()
            }
            return
        }
        pendingCallRequest = forCall
        pendingJoinExisting = forExisting
        pendingJoinCodeInput = forCodeInput
        pendingCreateMeetingAction = forCreate

        if (!audioOk) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (needCamera) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val otherMembers = remember(activeMembers, currentUserId) {
        activeMembers.filter { it.userId != currentUserId }
    }
    val activeOrUpcomingMeetings = remember(meetings) {
        meetings.filter { !MeetingStatus.fromRaw(it.status).isTerminal }
    }
    val endedMeetings = remember(meetings) {
        meetings.filter { MeetingStatus.fromRaw(it.status).isTerminal }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${activeFamily.name} Meet & Calls",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "1-to-1 WebRTC P2P calling & LiveKit group rooms isolated by family membership.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${activeOrUpcomingMeetings.count { it.status == MeetingStatus.LIVE.name }} Live",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    createDialogScheduledDefault = false
                                    showCreateDialog = true
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("create_meeting_button")
                            ) {
                                Icon(Icons.Outlined.VideoCall, contentDescription = stringResource(R.string.cd_create_meeting), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New Family Meet")
                            }
                            OutlinedButton(
                                onClick = {
                                    createDialogScheduledDefault = true
                                    showCreateDialog = true
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("schedule_meeting_button")
                            ) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Schedule")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = joinInput,
                                onValueChange = { joinInput = it },
                                label = { Text("Meeting ID, Code (FF-XXXX-XXXX), or Invite Link") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("meet_join_code_input")
                            )
                            Button(
                                onClick = {
                                    val code = joinInput.trim()
                                    requestPermissionsThenRun(
                                        needCamera = true,
                                        forCodeInput = code
                                    )
                                },
                                enabled = joinInput.isNotBlank() && !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("join_meet_button")
                            ) {
                                Text("Join")
                            }
                        }

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MeetHubTab.entries.forEach { tab ->
                                FilterChip(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("meet_tab_${tab.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }

            when (selectedTab) {
                MeetHubTab.MEETINGS -> {
                    if (activeOrUpcomingMeetings.isEmpty()) {
                        item {
                            ModuleEmptyRecordsCard(
                                icon = Icons.Outlined.Groups,
                                title = "No Active or Scheduled Meetings",
                                subtitle = "Start an instant LiveKit family meeting or schedule one with invite links and host moderation."
                            )
                        }
                    } else {
                        items(activeOrUpcomingMeetings, key = { it.meetingId }) { meeting ->
                            MeetingCard(
                                meeting = meeting,
                                canModerate = meeting.hostUid == currentUserId || activeFamily.isAdmin(currentUserId),
                                onJoin = {
                                    requestPermissionsThenRun(
                                        needCamera = meeting.videoEnabledDefault,
                                        forExisting = meeting
                                    )
                                },
                                onCopyInvite = {
                                    clipboard.setText(AnnotatedString(meeting.inviteLink))
                                    onNotifyMessage("Copied Family Meet invite link (${meeting.inviteCode}).")
                                },
                                onShareInvite = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Join \"${meeting.title}\" on Family Finance\nCode: ${meeting.inviteCode}\nLink: ${meeting.inviteLink}"
                                        )
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(shareIntent, "Invite Family Members"))
                                    }
                                },
                                onEndForAll = { onEndMeetingForEveryone(meeting.meetingId) }
                            )
                        }
                    }
                }

                MeetHubTab.CALLS -> {
                    if (otherMembers.isEmpty()) {
                        item {
                            ModuleEmptyRecordsCard(
                                icon = Icons.Outlined.Call,
                                title = "No Other Family Members Yet",
                                subtitle = "Invite approved family members in Family Management to start 1-to-1 encrypted audio & video calls."
                            )
                        }
                    } else {
                        items(otherMembers, key = { it.userId }) { member ->
                            FamilyMemberCallCard(
                                member = member,
                                onAudioCall = {
                                    requestPermissionsThenRun(
                                        needCamera = false,
                                        forCall = member to CallMediaType.AUDIO
                                    )
                                },
                                onVideoCall = {
                                    requestPermissionsThenRun(
                                        needCamera = true,
                                        forCall = member to CallMediaType.VIDEO
                                    )
                                }
                            )
                        }
                    }
                }

                MeetHubTab.HISTORY -> {
                    item {
                        Text(
                            text = "Recent 1-to-1 Calls (${callHistory.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (callHistory.isEmpty()) {
                        item {
                            ModuleEmptyRecordsCard(
                                icon = Icons.Outlined.History,
                                title = "No Call History Yet",
                                subtitle = "Completed, missed, and declined 1-to-1 calls are cached here per family."
                            )
                        }
                    } else {
                        items(callHistory.take(25), key = { it.callId }) { record ->
                            val peerUid = if (record.callerUid == currentUserId) record.calleeUid else record.callerUid
                            val peerMember = otherMembers.firstOrNull { it.userId == peerUid }
                            CallHistoryCard(
                                record = record,
                                currentUserId = currentUserId,
                                canCallBack = peerMember != null,
                                onCallBack = { mediaType ->
                                    peerMember?.let { target ->
                                        requestPermissionsThenRun(
                                            needCamera = mediaType == CallMediaType.VIDEO,
                                            forCall = target to mediaType
                                        )
                                    }
                                }
                            )
                        }
                    }
                    if (endedMeetings.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Past Group Meetings (${endedMeetings.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(endedMeetings.take(20), key = { it.meetingId }) { past ->
                            PastMeetingHistoryCard(meeting = past)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateMeetingDialog(
            familyName = activeFamily.name,
            members = otherMembers,
            initialScheduled = createDialogScheduledDefault,
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, isScheduled, scheduledForMillis, invitedUids, audioDefault, videoDefault ->
                showCreateDialog = false
                if (isScheduled) {
                    onCreateMeeting(
                        title, true, scheduledForMillis, invitedUids,
                        audioDefault, videoDefault, hasAudioPerm(), hasCameraPerm()
                    )
                } else {
                    requestPermissionsThenRun(
                        needCamera = videoDefault,
                        forCreate = {
                            onCreateMeeting(
                                title, false, null, invitedUids,
                                audioDefault, videoDefault, hasAudioPerm(), hasCameraPerm()
                            )
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun FamilyMemberCallCard(
    member: LocalFamilyMemberEntity,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val initials = remember(member.displayName) {
        member.displayName.trim().split(Regex("\\s+")).take(2)
            .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString().orEmpty() }
            .ifBlank { "FM" }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("call_member_card_${member.userId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.relationLabel?.takeIf { it.isNotBlank() } ?: member.role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onAudioCall,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("call_audio_${member.userId}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Call,
                    contentDescription = stringResource(R.string.cd_start_audio_call),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onVideoCall,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("call_video_${member.userId}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.VideoCall,
                    contentDescription = stringResource(R.string.cd_start_video_call),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingCard(
    meeting: LocalMeetingEntity,
    canModerate: Boolean,
    onJoin: () -> Unit,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
    onEndForAll: () -> Unit
) {
    val isLive = meeting.status == MeetingStatus.LIVE.name
    val scheduleLabel = remember(meeting.scheduledForMillis, meeting.createdAtMillis) {
        val ts = meeting.scheduledForMillis ?: meeting.createdAtMillis
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(meetDateFormatter)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isLive) 1.5.dp else 1.dp,
            color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.testTag("meeting_card_${meeting.meetingId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = meeting.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Host: ${meeting.hostName} • Code: ${meeting.inviteCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (meeting.isScheduled && meeting.scheduledForMillis != null) {
                            "Scheduled: $scheduleLabel"
                        } else {
                            "Started: $scheduleLabel"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isLive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (isLive) "LIVE (${meeting.activeParticipantUids.size})" else "SCHEDULED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isLive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onJoin,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("join_meeting_${meeting.meetingId}")
                ) {
                    Icon(Icons.Outlined.VideoCall, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isLive) "Join Room" else "Start / Join")
                }
                OutlinedButton(
                    onClick = onCopyInvite,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("copy_invite_${meeting.meetingId}")
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.cd_copy_invite_link), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Link")
                }
                IconButton(
                    onClick = onShareInvite,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("share_invite_${meeting.meetingId}")
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.cd_share_invite_link))
                }
                if (canModerate) {
                    TextButton(
                        onClick = onEndForAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("end_meeting_${meeting.meetingId}")
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("End")
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryCard(
    record: LocalCallRecordEntity,
    currentUserId: String,
    canCallBack: Boolean,
    onCallBack: (CallMediaType) -> Unit
) {
    val outgoing = record.callerUid == currentUserId
    val peerName = if (outgoing) record.calleeName else record.callerName
    val mediaType = CallMediaType.fromRaw(record.mediaType)
    val status = CallStatus.fromRaw(record.status)
    val timeLabel = remember(record.createdAtMillis) {
        Instant.ofEpochMilli(record.createdAtMillis).atZone(ZoneId.systemDefault()).format(meetDateFormatter)
    }
    val durationLabel = remember(record.durationSeconds) { formatDurationMmSs(record.durationSeconds) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    status == CallStatus.MISSED -> Icons.Outlined.CallMissed
                    outgoing -> Icons.Outlined.CallMade
                    else -> Icons.Outlined.CallReceived
                },
                contentDescription = null,
                tint = if (status == CallStatus.MISSED || status == CallStatus.REJECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${mediaType.label} • ${status.label} • $durationLabel • $timeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canCallBack) {
                IconButton(
                    onClick = { onCallBack(mediaType) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (mediaType == CallMediaType.VIDEO) Icons.Outlined.VideoCall else Icons.Outlined.Call,
                        contentDescription = "Call back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PastMeetingHistoryCard(meeting: LocalMeetingEntity) {
    val dateLabel = remember(meeting.endedAtMillis, meeting.createdAtMillis) {
        val ts = meeting.endedAtMillis ?: meeting.createdAtMillis
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(meetDateFormatter)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Event, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Host: ${meeting.hostName} • ${meeting.status} • $dateLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateMeetingDialog(
    familyName: String,
    members: List<LocalFamilyMemberEntity>,
    initialScheduled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        isScheduled: Boolean,
        scheduledForMillis: Long?,
        invitedUids: List<String>,
        audioDefault: Boolean,
        videoDefault: Boolean
    ) -> Unit
) {
    var title by remember { mutableStateOf("$familyName Sync") }
    var isScheduled by remember { mutableStateOf(initialScheduled) }
    var selectedOffsetHours by remember { mutableLongStateOf(1L) }
    var audioDefault by remember { mutableStateOf(true) }
    var videoDefault by remember { mutableStateOf(true) }
    val selectedUids = remember(members) {
        mutableStateListOf<String>().apply { addAll(members.map { it.userId }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isScheduled) "Schedule Family Meet" else "Start Family Meet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 100) title = it },
                    label = { Text("Meeting Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_meeting_title_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Schedule for later", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isScheduled, onCheckedChange = { isScheduled = it })
                }

                if (isScheduled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1L to "+1 hr", 3L to "+3 hrs", 24L to "Tomorrow").forEach { (hrs, lbl) ->
                            FilterChip(
                                selected = selectedOffsetHours == hrs,
                                onClick = { selectedOffsetHours = hrs },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mic enabled by default", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = audioDefault, onCheckedChange = { audioDefault = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Camera enabled by default", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = videoDefault, onCheckedChange = { videoDefault = it })
                }

                if (members.isNotEmpty()) {
                    Text("Invite Family Members", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        members.forEach { member ->
                            val included = member.userId in selectedUids
                            FilterChip(
                                selected = included,
                                onClick = {
                                    if (included) selectedUids.remove(member.userId) else selectedUids.add(member.userId)
                                },
                                label = { Text(member.displayName) },
                                modifier = Modifier.testTag("invite_member_${member.userId}")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val scheduledMillis = if (isScheduled) {
                        System.currentTimeMillis() + selectedOffsetHours * 3_600_000L
                    } else null
                    onConfirm(
                        title.trim().ifBlank { "$familyName Meet" },
                        isScheduled,
                        scheduledMillis,
                        selectedUids.toList(),
                        audioDefault,
                        videoDefault
                    )
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("confirm_create_meeting_button")
            ) {
                Text(if (isScheduled) "Schedule" else "Start Now")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Cancel")
            }
        }
    )
}

internal fun formatDurationMmSs(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hrs = safe / 3600
    val mins = (safe % 3600) / 60
    val secs = safe % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
