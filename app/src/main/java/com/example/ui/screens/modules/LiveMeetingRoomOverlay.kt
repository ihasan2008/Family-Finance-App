package com.example.ui.screens.modules

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.StopScreenShare
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.comms.ActiveMeetingMediaUiState
import com.example.core.comms.LiveKitParticipantMedia
import com.example.core.comms.LiveKitParticipantVideoView
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.model.MeetingParticipantDoc
import com.example.core.model.MeetingParticipantState

@Composable
fun LiveMeetingRoomOverlay(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    meeting: LocalMeetingEntity,
    mediaState: ActiveMeetingMediaUiState,
    participantsMetadata: List<MeetingParticipantDoc>,
    durationSeconds: Int,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCycleAudioRoute: () -> Unit,
    onToggleScreenShare: (enable: Boolean, projectionIntent: Intent?) -> Unit,
    onMuteParticipant: (targetUid: String, muted: Boolean, trackSid: String?) -> Unit,
    onRemoveParticipant: (targetUid: String) -> Unit,
    onLeaveMeeting: () -> Unit,
    onEndMeetingForEveryone: () -> Unit,
    onNotifyMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val canModerate = meeting.hostUid == currentUserId || (activeFamily?.isAdmin(currentUserId) == true)

    var showParticipantsSheet by remember { mutableStateOf(false) }
    var showEndConfirm by remember { mutableStateOf(false) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onToggleScreenShare(true, result.data)
        } else {
            onNotifyMessage("Screen sharing permission was cancelled.")
        }
    }

    // Merge LiveKit live tracks with Firestore roster metadata so participants display smoothly even during connect/rejoin
    val displayTiles = remember(mediaState.participants, participantsMetadata, currentUserId, mediaState.activeSpeakerIdentities) {
        if (mediaState.participants.isNotEmpty()) {
            mediaState.participants
        } else {
            participantsMetadata
                .filter { it.state == MeetingParticipantState.JOINED.name || it.participantUid == currentUserId }
                .map { meta ->
                    val uid = meta.participantUid.ifBlank { meta.id }
                    LiveKitParticipantMedia(
                        identity = uid,
                        displayName = meta.displayName.ifBlank { "Family Member" },
                        isLocal = uid == currentUserId,
                        isSpeaking = uid in mediaState.activeSpeakerIdentities,
                        micEnabled = meta.audioEnabled && !meta.mutedByHost,
                        cameraEnabled = meta.videoEnabled,
                        screenSharing = meta.screenSharing
                    )
                }
        }
    }

    val activeScreenSharer = remember(displayTiles) {
        displayTiles.firstOrNull { it.screenSharing && it.screenShareTrack != null }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("live_meeting_room_overlay"),
        color = Color(0xFF0C1424)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Meeting Header Bar
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF172236),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = meeting.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDurationMmSs(durationSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF86EFAC),
                                modifier = Modifier.testTag("meeting_duration_text")
                            )
                            Text("•", color = Color.White.copy(alpha = 0.4f))
                            Text(
                                text = "${displayTiles.size} in room",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.testTag("meeting_participant_count")
                            )
                            if (mediaState.isReconnecting) {
                                Text(
                                    text = "• Reconnecting",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFBBF24)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(meeting.inviteLink))
                                onNotifyMessage("Copied meeting link (${meeting.inviteCode}).")
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("meeting_copy_invite_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_invite_link),
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { showParticipantsSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("meeting_participants_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Group,
                                contentDescription = "Participant list and host controls",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Screen Share Stage (if active)
            if (activeScreenSharer != null && activeScreenSharer.screenShareTrack != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LiveKitParticipantVideoView(
                            videoTrack = activeScreenSharer.screenShareTrack,
                            eglBaseContext = mediaState.eglBaseContext,
                            mirror = false,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = 0.68f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "${activeScreenSharer.displayName} is sharing screen",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (!mediaState.mediaError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = mediaState.mediaError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }

            // Responsive Participant Video Grid
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 780.dp && displayTiles.size > 2 -> 3
                    displayTiles.size <= 1 -> 1
                    else -> 2
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("meeting_participant_grid")
                ) {
                    items(displayTiles, key = { it.identity }) { tile ->
                        val meta = participantsMetadata.firstOrNull { (it.participantUid.ifBlank { it.id }) == tile.identity }
                        ParticipantGridTile(
                            tile = tile,
                            isHost = tile.identity == meeting.hostUid,
                            mutedByHost = meta?.mutedByHost == true,
                            eglBaseContext = mediaState.eglBaseContext,
                            mirrorLocal = mediaState.isFrontCamera
                        )
                    }
                }
            }

            // Bottom Control Bar
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF172236),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onToggleMic,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (!mediaState.localMicEnabled) Color(0xFF7F1D1D) else Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("meeting_toggle_mic_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.localMicEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                            contentDescription = stringResource(R.string.cd_toggle_mic)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onToggleCamera,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (!mediaState.localCameraEnabled) Color(0xFF7F1D1D) else Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("meeting_toggle_camera_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.localCameraEnabled) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
                            contentDescription = stringResource(R.string.cd_toggle_camera)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onSwitchCamera,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("meeting_switch_camera_button")
                    ) {
                        Icon(Icons.Outlined.Cameraswitch, contentDescription = stringResource(R.string.cd_switch_camera))
                    }

                    FilledTonalIconButton(
                        onClick = onCycleAudioRoute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("meeting_audio_route_button")
                    ) {
                        Icon(audioRouteIcon(mediaState.audioRoute), contentDescription = stringResource(R.string.cd_toggle_speaker))
                    }

                    FilledTonalIconButton(
                        onClick = {
                            if (mediaState.isScreenSharing) {
                                onToggleScreenShare(false, null)
                            } else {
                                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                if (mpm != null) {
                                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                                } else {
                                    onNotifyMessage("Screen capture service unavailable.")
                                }
                            }
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (mediaState.isScreenSharing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("meeting_screen_share_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.isScreenSharing) Icons.Outlined.StopScreenShare else Icons.Outlined.PresentToAll,
                            contentDescription = stringResource(R.string.cd_toggle_screen_share)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onLeaveMeeting,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFD92D20),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("leave_meeting_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = stringResource(R.string.cd_leave_meeting))
                    }
                }
            }
        }
    }

    if (showParticipantsSheet) {
        AlertDialog(
            onDismissRequest = { showParticipantsSheet = false },
            title = {
                Text("Participants (${participantsMetadata.size.coerceAtLeast(displayTiles.size)})")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join \"${meeting.title}\" on Family Finance\nCode: ${meeting.inviteCode}\nLink: ${meeting.inviteLink}"
                                    )
                                }
                                runCatching { context.startActivity(Intent.createChooser(shareIntent, "Invite Family")) }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("share_meeting_link_button")
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Invite Link")
                        }
                        if (canModerate) {
                            Button(
                                onClick = {
                                    showParticipantsSheet = false
                                    showEndConfirm = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("end_meeting_for_all_button")
                            ) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("End All")
                            }
                        }
                    }

                    val listToRender = if (participantsMetadata.isNotEmpty()) participantsMetadata else {
                        displayTiles.map {
                            MeetingParticipantDoc(
                                participantUid = it.identity,
                                meetingId = meeting.meetingId,
                                familyId = meeting.familyId,
                                displayName = it.displayName,
                                isHost = it.identity == meeting.hostUid,
                                state = MeetingParticipantState.JOINED.name,
                                audioEnabled = it.micEnabled,
                                videoEnabled = it.cameraEnabled
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listToRender, key = { it.participantUid.ifBlank { it.id } }) { part ->
                            val uid = part.participantUid.ifBlank { part.id }
                            val trackSid = displayTiles.firstOrNull { it.identity == uid }?.audioTrackSid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (uid == currentUserId) "${part.displayName} (You)" else part.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            if (uid == meeting.hostUid || part.isHost) append("Host • ")
                                            append(part.state)
                                            if (part.mutedByHost) append(" • Host Muted")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (canModerate && uid != currentUserId) {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                onMuteParticipant(uid, !part.mutedByHost, trackSid)
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("host_mute_$uid")
                                        ) {
                                            Icon(
                                                imageVector = if (part.mutedByHost) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                                contentDescription = stringResource(R.string.cd_mute_participant),
                                                tint = if (part.mutedByHost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRemoveParticipant(uid) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("host_remove_$uid")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.PersonRemove,
                                                contentDescription = stringResource(R.string.cd_remove_participant),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showParticipantsSheet = false },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Close")
                }
            }
        )
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("End Meeting for Everyone?") },
            text = { Text("All connected family members will be disconnected from this LiveKit room and cannot rejoin.") },
            confirmButton = {
                Button(
                    onClick = {
                        showEndConfirm = false
                        onEndMeetingForEveryone()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_end_meeting_for_all")
                ) {
                    Text("End Meeting")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ParticipantGridTile(
    tile: LiveKitParticipantMedia,
    isHost: Boolean,
    mutedByHost: Boolean,
    eglBaseContext: livekit.org.webrtc.EglBase.Context?,
    mirrorLocal: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .testTag("participant_tile_${tile.identity}"),
        color = Color(0xFF1A253B),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (tile.isSpeaking) 2.5.dp else 1.dp,
            color = if (tile.isSpeaking) Color(0xFF22C55E) else Color.White.copy(alpha = 0.12f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (tile.cameraEnabled && tile.cameraTrack != null) {
                LiveKitParticipantVideoView(
                    videoTrack = tile.cameraTrack,
                    eglBaseContext = eglBaseContext,
                    mirror = tile.isLocal && mirrorLocal,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = tile.displayName.take(2).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (tile.isSpeaking) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF16A34A),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.GraphicEq,
                            contentDescription = "Active speaker",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text("Speaking", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (tile.micEnabled && !mutedByHost) Icons.Filled.Mic else Icons.Outlined.MicOff,
                        contentDescription = null,
                        tint = if (tile.micEnabled && !mutedByHost) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = buildString {
                            append(if (tile.isLocal) "You" else tile.displayName)
                            if (isHost) append(" • Host")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
