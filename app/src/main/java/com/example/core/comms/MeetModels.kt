package com.example.core.comms

import com.example.core.model.AudioOutputRoute
import com.example.core.model.CallConnectionQuality
import kotlinx.serialization.Serializable
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.VideoTrack as WebRtcVideoTrack

@Serializable
data class MeetTokenResponse(
    val ok: Boolean = false,
    val token: String = "",
    val livekitUrl: String = "",
    val roomName: String = "",
    val ttlSeconds: Int = 1800,
    val isHost: Boolean = false
)

@Serializable
data class MeetModerationResponse(
    val ok: Boolean = false,
    val meetingId: String = "",
    val targetUid: String? = null,
    val muted: Boolean? = null,
    val status: String? = null,
    val warning: String? = null
)

@Serializable
data class DispatchPushResponse(
    val ok: Boolean = false,
    val sentCount: Int = 0,
    val targetedDevices: Int = 0
)

data class ActiveCallMediaUiState(
    val activeCallId: String? = null,
    val micMuted: Boolean = false,
    val cameraEnabled: Boolean = false,
    val isFrontCamera: Boolean = true,
    val audioRoute: AudioOutputRoute = AudioOutputRoute.EARPIECE,
    val availableAudioRoutes: List<AudioOutputRoute> = listOf(AudioOutputRoute.EARPIECE, AudioOutputRoute.SPEAKER),
    val connectionQuality: CallConnectionQuality = CallConnectionQuality.UNKNOWN,
    val isReconnecting: Boolean = false,
    val isPeerConnected: Boolean = false,
    val localVideoTrack: WebRtcVideoTrack? = null,
    val remoteVideoTrack: WebRtcVideoTrack? = null,
    val eglBaseContext: EglBase.Context? = null,
    val mediaError: String? = null
)

data class LiveKitParticipantMedia(
    val identity: String,
    val displayName: String,
    val isLocal: Boolean,
    val isSpeaking: Boolean,
    val micEnabled: Boolean,
    val cameraEnabled: Boolean,
    val screenSharing: Boolean,
    val cameraTrack: io.livekit.android.room.track.VideoTrack? = null,
    val screenShareTrack: io.livekit.android.room.track.VideoTrack? = null,
    val audioTrackSid: String? = null
)

data class ActiveMeetingMediaUiState(
    val activeMeetingId: String? = null,
    val roomName: String? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val localMicEnabled: Boolean = true,
    val localCameraEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val audioRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val availableAudioRoutes: List<AudioOutputRoute> = listOf(AudioOutputRoute.SPEAKER, AudioOutputRoute.EARPIECE),
    val participants: List<LiveKitParticipantMedia> = emptyList(),
    val activeSpeakerIdentities: Set<String> = emptySet(),
    val eglBaseContext: EglBase.Context? = null,
    val mediaError: String? = null
)
