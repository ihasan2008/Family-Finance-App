package com.example.core.comms

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.core.model.AudioOutputRoute
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase

/**
 * LiveKit SFU controller managing scalable Family Meet group rooms.
 * Requires server-minted LiveKit JWT + URL from Cloud Functions (`MeetBackendClient`).
 */
class LiveKitGroupMeetController(
    context: Context,
    private val audioRouter: NativeAudioRouter = NativeAudioRouter(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(ActiveMeetingMediaUiState())
    val uiState: StateFlow<ActiveMeetingMediaUiState> = _uiState.asStateFlow()

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var eglBase: EglBase? = null

    fun currentRoom(): Room? = room

    suspend fun joinRoom(
        meetingId: String,
        roomName: String,
        livekitUrl: String,
        token: String,
        joinAudio: Boolean,
        joinVideo: Boolean,
        onRoomDisconnectedUnexpectedly: () -> Unit = {}
    ): Result<Unit> {
        if (livekitUrl.isBlank() || token.isBlank()) {
            val msg = "LiveKit SFU URL or token is missing. Configure LIVEKIT_URL, LIVEKIT_API_KEY, and LIVEKIT_API_SECRET on Cloud Functions."
            _uiState.update { it.copy(mediaError = msg, isConnecting = false) }
            return Result.failure(IllegalStateException(msg))
        }

        leaveRoom()
        audioRouter.startCommunicationAudio(defaultSpeaker = true)

        return try {
            val egl = eglBase ?: EglBase.create().also { eglBase = it }
            _uiState.value = ActiveMeetingMediaUiState(
                activeMeetingId = meetingId,
                roomName = roomName,
                isConnecting = true,
                localMicEnabled = joinAudio,
                localCameraEnabled = joinVideo,
                isFrontCamera = true,
                audioRoute = audioRouter.selectedRoute.value,
                availableAudioRoutes = audioRouter.availableRoutes.value,
                eglBaseContext = egl.eglBaseContext
            )

            val createdRoom = LiveKit.create(
                appContext = appContext,
                options = RoomOptions(adaptiveStream = true, dynacast = true)
            )
            room = createdRoom

            eventsJob = scope.launch {
                createdRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.Connected -> {
                            _uiState.update { it.copy(isConnecting = false, isConnected = true, isReconnecting = false) }
                            syncParticipantsFromRoom(createdRoom)
                        }
                        is RoomEvent.Reconnecting -> {
                            _uiState.update { it.copy(isReconnecting = true) }
                        }
                        is RoomEvent.Reconnected -> {
                            _uiState.update { it.copy(isReconnecting = false, isConnected = true) }
                            syncParticipantsFromRoom(createdRoom)
                        }
                        is RoomEvent.Disconnected -> {
                            _uiState.update { it.copy(isConnected = false, isConnecting = false, isReconnecting = false) }
                            onRoomDisconnectedUnexpectedly()
                        }
                        is RoomEvent.ActiveSpeakersChanged -> {
                            val speakerIds = event.speakers.mapNotNull { it.identity?.value }.toSet()
                            _uiState.update { it.copy(activeSpeakerIdentities = speakerIds) }
                            syncParticipantsFromRoom(createdRoom)
                        }
                        is RoomEvent.ParticipantConnected,
                        is RoomEvent.ParticipantDisconnected,
                        is RoomEvent.TrackSubscribed,
                        is RoomEvent.TrackUnsubscribed,
                        is RoomEvent.TrackPublished,
                        is RoomEvent.TrackUnpublished,
                        is RoomEvent.TrackMuted,
                        is RoomEvent.TrackUnmuted -> {
                            syncParticipantsFromRoom(createdRoom)
                        }
                        else -> Unit
                    }
                }
            }

            createdRoom.connect(
                url = livekitUrl,
                token = token,
                options = ConnectOptions(
                    audio = joinAudio,
                    video = joinVideo,
                    autoSubscribe = true
                )
            )

            runCatching { createdRoom.localParticipant.setMicrophoneEnabled(joinAudio) }
            runCatching { createdRoom.localParticipant.setCameraEnabled(joinVideo) }

            _uiState.update {
                it.copy(
                    isConnecting = false,
                    isConnected = true,
                    localMicEnabled = createdRoom.localParticipant.isMicrophoneEnabled,
                    localCameraEnabled = createdRoom.localParticipant.isCameraEnabled
                )
            }
            syncParticipantsFromRoom(createdRoom)
            Result.success(Unit)
        } catch (err: Throwable) {
            Log.w(TAG, "LiveKit joinRoom failed: ${err.message}")
            val errMsg = err.message ?: "Unable to connect to LiveKit meeting room."
            leaveRoom()
            _uiState.update { it.copy(mediaError = errMsg, isConnecting = false) }
            Result.failure(err)
        }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val currentRoom = room
        runCatching { currentRoom?.localParticipant?.setMicrophoneEnabled(enabled) }
        audioRouter.setMicrophoneHardwareMute(!enabled)
        _uiState.update { it.copy(localMicEnabled = enabled) }
        currentRoom?.let { syncParticipantsFromRoom(it) }
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        val currentRoom = room
        runCatching { currentRoom?.localParticipant?.setCameraEnabled(enabled) }
        _uiState.update { it.copy(localCameraEnabled = enabled) }
        currentRoom?.let { syncParticipantsFromRoom(it) }
    }

    fun switchCamera() {
        val currentRoom = room ?: return
        val localTrack = currentRoom.localParticipant
            .getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
        if (localTrack != null) {
            runCatching { localTrack.switchCamera() }
            _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
        }
    }

    suspend fun setScreenShareEnabled(enabled: Boolean, mediaProjectionPermissionData: Intent? = null): Result<Unit> {
        val currentRoom = room ?: return Result.failure(IllegalStateException("Not connected to a meeting room."))
        return try {
            if (enabled) {
                val data = mediaProjectionPermissionData
                    ?: return Result.failure(IllegalStateException("Screen capture permission is required."))
                currentRoom.localParticipant.setScreenShareEnabled(
                    enabled = true,
                    screenCaptureParams = ScreenCaptureParams(mediaProjectionPermissionResultData = data)
                )
            } else {
                currentRoom.localParticipant.setScreenShareEnabled(enabled = false)
            }
            _uiState.update { it.copy(isScreenSharing = enabled) }
            syncParticipantsFromRoom(currentRoom)
            Result.success(Unit)
        } catch (err: Throwable) {
            Result.failure(err)
        }
    }

    fun selectAudioRoute(route: AudioOutputRoute) {
        val actual = audioRouter.selectRoute(route)
        _uiState.update {
            it.copy(
                audioRoute = actual,
                availableAudioRoutes = audioRouter.availableRoutes.value
            )
        }
    }

    fun cycleAudioRoute(): AudioOutputRoute {
        val next = audioRouter.cycleNextRoute()
        _uiState.update {
            it.copy(
                audioRoute = next,
                availableAudioRoutes = audioRouter.availableRoutes.value
            )
        }
        return next
    }

    fun leaveRoom() {
        eventsJob?.cancel()
        eventsJob = null
        val currentRoom = room
        room = null
        runCatching { currentRoom?.disconnect() }
        runCatching { currentRoom?.release() }
        audioRouter.stopCommunicationAudio()
        _uiState.value = ActiveMeetingMediaUiState(eglBaseContext = eglBase?.eglBaseContext)
    }

    fun disposeEngine() {
        leaveRoom()
        runCatching { eglBase?.release() }
        eglBase = null
        _uiState.value = ActiveMeetingMediaUiState()
    }

    private fun syncParticipantsFromRoom(currentRoom: Room) {
        val speakerSet = currentRoom.activeSpeakers.mapNotNull { it.identity?.value }.toSet()
        val mapped = buildList {
            add(mapParticipant(currentRoom.localParticipant, isLocal = true, speakerSet = speakerSet))
            currentRoom.remoteParticipants.values.forEach { remote ->
                add(mapParticipant(remote, isLocal = false, speakerSet = speakerSet))
            }
        }
        _uiState.update { state ->
            state.copy(
                participants = mapped,
                activeSpeakerIdentities = speakerSet,
                localMicEnabled = currentRoom.localParticipant.isMicrophoneEnabled,
                localCameraEnabled = currentRoom.localParticipant.isCameraEnabled,
                isScreenSharing = currentRoom.localParticipant.isScreenShareEnabled
            )
        }
    }

    private fun mapParticipant(
        participant: Participant,
        isLocal: Boolean,
        speakerSet: Set<String>
    ): LiveKitParticipantMedia {
        val identity = participant.identity?.value.orEmpty()
        val micPub = participant.getTrackPublication(Track.Source.MICROPHONE)
        val camPub = participant.getTrackPublication(Track.Source.CAMERA)
        val screenPub = participant.getTrackPublication(Track.Source.SCREEN_SHARE)

        val micEnabled = if (participant is LocalParticipant) {
            participant.isMicrophoneEnabled
        } else {
            micPub != null && !micPub.muted
        }
        val camEnabled = if (participant is LocalParticipant) {
            participant.isCameraEnabled
        } else {
            camPub != null && !camPub.muted
        }
        val screenSharing = if (participant is LocalParticipant) {
            participant.isScreenShareEnabled
        } else {
            screenPub != null && !screenPub.muted
        }

        return LiveKitParticipantMedia(
            identity = identity,
            displayName = participant.name?.takeIf { it.isNotBlank() } ?: if (isLocal) "You" else "Family Member",
            isLocal = isLocal,
            isSpeaking = participant.isSpeaking || identity in speakerSet,
            micEnabled = micEnabled,
            cameraEnabled = camEnabled,
            screenSharing = screenSharing,
            cameraTrack = (camPub?.track as? VideoTrack)?.takeIf { camEnabled },
            screenShareTrack = (screenPub?.track as? VideoTrack)?.takeIf { screenSharing },
            audioTrackSid = micPub?.sid
        )
    }

    private companion object {
        const val TAG = "LiveKitGroupMeetCtrl"
    }
}
