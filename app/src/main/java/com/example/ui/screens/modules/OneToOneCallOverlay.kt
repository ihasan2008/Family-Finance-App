package com.example.ui.screens.modules

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SpeakerPhone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.comms.ActiveCallMediaUiState
import com.example.core.comms.WebRtcVideoSurfaceView
import com.example.core.model.AudioOutputRoute
import com.example.core.model.CallConnectionQuality
import com.example.core.model.CallMediaType
import com.example.core.model.CallSessionDoc
import com.example.core.model.CallStatus

@Composable
fun IncomingCallBannerOverlay(
    incomingCall: CallSessionDoc,
    onAccept: (CallSessionDoc, hasAudioPerm: Boolean, hasCameraPerm: Boolean) -> Unit,
    onReject: (CallSessionDoc) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVideo = incomingCall.mediaType == CallMediaType.VIDEO.name
    var awaitingCameraAfterAudio by remember { mutableStateOf(false) }

    fun hasAudio() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun hasCam() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk ->
        awaitingCameraAfterAudio = false
        onAccept(incomingCall, hasAudio(), camOk)
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioOk ->
        if (isVideo && !hasCam()) {
            awaitingCameraAfterAudio = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            onAccept(incomingCall, audioOk, hasCam())
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("incoming_call_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.Call,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = incomingCall.callerName.ifBlank { "Family Member" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isVideo) "Incoming Family Video Call..." else "Incoming Family Audio Call...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onReject(incomingCall) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("reject_call_button")
                    ) {
                        Icon(Icons.Outlined.CallEnd, contentDescription = stringResource(R.string.cd_reject_call), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Decline")
                    }
                    Button(
                        onClick = {
                            if (!hasAudio()) {
                                audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (isVideo && !hasCam()) {
                                cameraLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                onAccept(incomingCall, true, hasCam())
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("accept_call_button")
                    ) {
                        Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.cd_accept_call), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
fun OneToOneCallOverlay(
    currentUserId: String,
    callSession: CallSessionDoc,
    mediaState: ActiveCallMediaUiState,
    durationSeconds: Int,
    isInPipMode: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCycleAudioRoute: () -> Unit,
    onTriggerIceRestart: () -> Unit,
    onEnterPipMode: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = callSession.mediaType == CallMediaType.VIDEO.name
    val peerName = if (callSession.callerUid == currentUserId) {
        callSession.calleeName.ifBlank { "Family Member" }
    } else {
        callSession.callerName.ifBlank { "Family Member" }
    }
    val status = CallStatus.fromRaw(callSession.status)
    val subtitleText = when {
        mediaState.isReconnecting -> "Reconnecting peer link..."
        status == CallStatus.RINGING -> "Ringing..."
        status == CallStatus.CONNECTING -> "Establishing media..."
        else -> formatDurationMmSs(durationSeconds)
    }

    if (isInPipMode) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0E1726))
                .testTag("pip_call_surface")
        ) {
            if (isVideo && mediaState.remoteVideoTrack != null) {
                WebRtcVideoSurfaceView(
                    videoTrack = mediaState.remoteVideoTrack,
                    eglBaseContext = mediaState.eglBaseContext,
                    mirror = false,
                    isOverlay = false,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
        return
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("active_call_overlay"),
        color = Color(0xFF0E1726)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isVideo && mediaState.remoteVideoTrack != null) {
                WebRtcVideoSurfaceView(
                    videoTrack = mediaState.remoteVideoTrack,
                    eglBaseContext = mediaState.eglBaseContext,
                    mirror = false,
                    isOverlay = false,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(96.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = peerName.take(2).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                    if (!mediaState.mediaError.isNullOrBlank()) {
                        Text(
                            text = mediaState.mediaError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                }
            }

            // Self-view floating preview for 1-to-1 video calls
            if (isVideo && mediaState.cameraEnabled && mediaState.localVideoTrack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 68.dp, end = 16.dp)
                        .width(108.dp)
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    WebRtcVideoSurfaceView(
                        videoTrack = mediaState.localVideoTrack,
                        eglBaseContext = mediaState.eglBaseContext,
                        mirror = mediaState.isFrontCamera,
                        isOverlay = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Top Call Header + Quality Telemetry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (isVideo) "Video Call" else "Audio Call"} • $subtitleText",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NetworkQualityBadge(quality = mediaState.connectionQuality)
                    if (mediaState.isReconnecting || mediaState.connectionQuality == CallConnectionQuality.POOR) {
                        FilledTonalIconButton(
                            onClick = onTriggerIceRestart,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reconnect_call_button")
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect call")
                        }
                    }
                    if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        FilledTonalIconButton(
                            onClick = onEnterPipMode,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("call_enter_pip_button")
                        ) {
                            Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = stringResource(R.string.cd_enter_pip))
                        }
                    }
                }
            }

            // Bottom Call Control Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1A2438).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onToggleMic,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (mediaState.micMuted) MaterialTheme.colorScheme.errorContainer else Color.White.copy(alpha = 0.14f),
                            contentColor = if (mediaState.micMuted) MaterialTheme.colorScheme.onErrorContainer else Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("call_toggle_mic_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                            contentDescription = stringResource(R.string.cd_toggle_mic)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onCycleAudioRoute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("call_audio_route_button")
                    ) {
                        Icon(
                            imageVector = audioRouteIcon(mediaState.audioRoute),
                            contentDescription = stringResource(R.string.cd_toggle_speaker)
                        )
                    }

                    if (isVideo) {
                        FilledTonalIconButton(
                            onClick = onToggleCamera,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (!mediaState.cameraEnabled) MaterialTheme.colorScheme.errorContainer else Color.White.copy(alpha = 0.14f),
                                contentColor = if (!mediaState.cameraEnabled) MaterialTheme.colorScheme.onErrorContainer else Color.White
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("call_toggle_camera_button")
                        ) {
                            Icon(
                                imageVector = if (mediaState.cameraEnabled) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
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
                                .testTag("call_switch_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Cameraswitch,
                                contentDescription = stringResource(R.string.cd_switch_camera)
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = onEndCall,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFD92D20),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("end_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CallEnd,
                            contentDescription = stringResource(R.string.cd_end_call)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkQualityBadge(quality: CallConnectionQuality) {
    val bgColor = when (quality) {
        CallConnectionQuality.EXCELLENT, CallConnectionQuality.GOOD -> Color(0xFF15803D)
        CallConnectionQuality.FAIR -> Color(0xFFB45309)
        CallConnectionQuality.POOR -> Color(0xFFB91C1C)
        CallConnectionQuality.UNKNOWN -> Color(0xFF334155)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.SignalCellularAlt,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = quality.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

internal fun audioRouteIcon(route: AudioOutputRoute) = when (route) {
    AudioOutputRoute.SPEAKER -> Icons.Outlined.VolumeUp
    AudioOutputRoute.BLUETOOTH -> Icons.Outlined.BluetoothAudio
    AudioOutputRoute.WIRED_HEADSET -> Icons.Outlined.Headset
    AudioOutputRoute.EARPIECE -> Icons.Outlined.SpeakerPhone
}
