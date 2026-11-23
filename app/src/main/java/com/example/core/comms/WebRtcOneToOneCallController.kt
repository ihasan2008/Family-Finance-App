package com.example.core.comms

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.core.model.AudioOutputRoute
import com.example.core.model.CallConnectionQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct WebRTC peer-to-peer controller for 1-to-1 family audio & video calling.
 * Uses Unified Plan SDP via Cloud Firestore signaling, STUN/TURN from [BuildConfig],
 * Camera2 front/rear switching, [RTCStatsReport] network quality scoring, and ICE restart recovery.
 */
class WebRtcOneToOneCallController(
    context: Context,
    private val audioRouter: NativeAudioRouter = NativeAudioRouter(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(ActiveCallMediaUiState())
    val uiState: StateFlow<ActiveCallMediaUiState> = _uiState.asStateFlow()

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var statsJob: Job? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false
    private val nativeInitialized = AtomicBoolean(false)

    private fun ensureNativeEngine(): Boolean {
        if (factory != null && eglBase != null) return true
        return runCatching {
            if (nativeInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
            val egl = eglBase ?: EglBase.create().also { eglBase = it }
            val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            _uiState.update { it.copy(eglBaseContext = egl.eglBaseContext, mediaError = null) }
            true
        }.getOrElse { err ->
            Log.w(TAG, "WebRTC native initialization failed: ${err.message}")
            _uiState.update {
                it.copy(mediaError = "WebRTC media engine unavailable on this device/runtime (${err.javaClass.simpleName}).")
            }
            false
        }
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        BuildConfig.WEBRTC_STUN_URLS
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stunUrl ->
                servers.add(PeerConnection.IceServer.builder(stunUrl).createIceServer())
            }
        if (servers.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val turnUrls = BuildConfig.WEBRTC_TURN_URLS
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        turnUrls.forEach { turnUrl ->
            val builder = PeerConnection.IceServer.builder(turnUrl)
            if (BuildConfig.WEBRTC_TURN_USERNAME.isNotBlank()) {
                builder.setUsername(BuildConfig.WEBRTC_TURN_USERNAME)
            }
            if (BuildConfig.WEBRTC_TURN_CREDENTIAL.isNotBlank()) {
                builder.setPassword(BuildConfig.WEBRTC_TURN_CREDENTIAL)
            }
            servers.add(builder.createIceServer())
        }
        return servers
    }

    fun startOutgoingCall(
        callId: String,
        isVideo: Boolean,
        hasRecordAudioPerm: Boolean,
        hasCameraPerm: Boolean,
        onLocalOfferCreated: (sdp: String, type: String) -> Unit,
        onLocalIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit
    ) {
        prepareCallSession(
            callId = callId,
            isVideo = isVideo,
            hasRecordAudioPerm = hasRecordAudioPerm,
            hasCameraPerm = hasCameraPerm,
            onLocalIceCandidate = onLocalIceCandidate
        )
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        onLocalOfferCreated(desc.description, desc.type.canonicalForm())
                    }
                    override fun onSetFailure(err: String?) {
                        _uiState.update { it.copy(mediaError = err ?: "Failed setting local offer SDP.") }
                    }
                }, desc)
            }
            override fun onCreateFailure(err: String?) {
                _uiState.update { it.copy(mediaError = err ?: "Failed creating WebRTC offer.") }
            }
        }, constraints)
    }

    fun acceptIncomingCall(
        callId: String,
        isVideo: Boolean,
        remoteOfferSdp: String,
        remoteOfferType: String,
        hasRecordAudioPerm: Boolean,
        hasCameraPerm: Boolean,
        onLocalAnswerCreated: (sdp: String, type: String) -> Unit,
        onLocalIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit
    ) {
        prepareCallSession(
            callId = callId,
            isVideo = isVideo,
            hasRecordAudioPerm = hasRecordAudioPerm,
            hasCameraPerm = hasCameraPerm,
            onLocalIceCandidate = onLocalIceCandidate
        )
        val pc = peerConnection ?: return
        val type = SessionDescription.Type.fromCanonicalForm(remoteOfferType.ifBlank { "offer" })
        val remoteDesc = SessionDescription(type, remoteOfferSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIceCandidates()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onLocalAnswerCreated(answerDesc.description, answerDesc.type.canonicalForm())
                            }
                            override fun onSetFailure(err: String?) {
                                _uiState.update { it.copy(mediaError = err ?: "Failed setting local answer SDP.") }
                            }
                        }, answerDesc)
                    }
                    override fun onCreateFailure(err: String?) {
                        _uiState.update { it.copy(mediaError = err ?: "Failed creating WebRTC answer.") }
                    }
                }, constraints)
            }
            override fun onSetFailure(err: String?) {
                _uiState.update { it.copy(mediaError = err ?: "Failed applying remote offer.") }
            }
        }, remoteDesc)
    }

    fun applyRemoteAnswer(answerSdp: String, answerType: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answerType.ifBlank { "answer" }),
            answerSdp
        )
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIceCandidates()
            }
            override fun onSetFailure(err: String?) {
                _uiState.update { it.copy(mediaError = err ?: "Failed applying remote answer.") }
            }
        }, remoteDesc)
    }

    fun applyIncomingRenegotiationOffer(
        offerSdp: String,
        offerType: String,
        onAnswerCreated: (sdp: String, type: String) -> Unit
    ) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(offerType.ifBlank { "offer" }),
            offerSdp
        )
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIceCandidates()
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onAnswerCreated(answer.description, answer.type.canonicalForm())
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, remoteDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val pc = peerConnection
        if (pc != null && hasRemoteDescription) {
            runCatching { pc.addIceCandidate(ice) }
        } else {
            pendingRemoteIce.add(ice)
        }
    }

    fun triggerIceRestart(onRestartOfferCreated: (sdp: String, type: String) -> Unit) {
        val pc = peerConnection ?: return
        _uiState.update { it.copy(isReconnecting = true) }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        onRestartOfferCreated(desc.description, desc.type.canonicalForm())
                    }
                }, desc)
            }
        }, constraints)
    }

    fun setMicMuted(muted: Boolean) {
        runCatching { localAudioTrack?.setEnabled(!muted) }
        audioRouter.setMicrophoneHardwareMute(muted)
        _uiState.update { it.copy(micMuted = muted) }
    }

    fun toggleMic(): Boolean {
        val next = !_uiState.value.micMuted
        setMicMuted(next)
        return next
    }

    fun setCameraEnabled(enabled: Boolean) {
        runCatching { localVideoTrack?.setEnabled(enabled) }
        _uiState.update { it.copy(cameraEnabled = enabled) }
    }

    fun toggleCamera(): Boolean {
        val next = !_uiState.value.cameraEnabled
        setCameraEnabled(next)
        return next
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _uiState.update { it.copy(isFrontCamera = isFrontCamera) }
            }
            override fun onCameraSwitchError(err: String?) {
                _uiState.update { it.copy(mediaError = err ?: "Unable to switch camera.") }
            }
        })
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

    fun endAndRelease() {
        statsJob?.cancel()
        statsJob = null
        pendingRemoteIce.clear()
        hasRemoteDescription = false

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        audioRouter.stopCommunicationAudio()
        _uiState.value = ActiveCallMediaUiState(eglBaseContext = eglBase?.eglBaseContext)
    }

    fun disposeEngine() {
        endAndRelease()
        runCatching { factory?.dispose() }
        factory = null
        runCatching { eglBase?.release() }
        eglBase = null
        _uiState.value = ActiveCallMediaUiState()
    }

    private fun prepareCallSession(
        callId: String,
        isVideo: Boolean,
        hasRecordAudioPerm: Boolean,
        hasCameraPerm: Boolean,
        onLocalIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit
    ) {
        endAndRelease()
        audioRouter.startCommunicationAudio(defaultSpeaker = isVideo)

        _uiState.update {
            ActiveCallMediaUiState(
                activeCallId = callId,
                micMuted = !hasRecordAudioPerm,
                cameraEnabled = isVideo && hasCameraPerm,
                isFrontCamera = true,
                audioRoute = audioRouter.selectedRoute.value,
                availableAudioRoutes = audioRouter.availableRoutes.value,
                connectionQuality = CallConnectionQuality.UNKNOWN
            )
        }

        if (!ensureNativeEngine()) return
        val pcFactory = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    onLocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _uiState.update { it.copy(isPeerConnected = true, isReconnecting = false) }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        _uiState.update { it.copy(isReconnecting = true) }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        _uiState.update { it.copy(isPeerConnected = false, isReconnecting = false) }
                    }
                    else -> Unit
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    _uiState.update { it.copy(remoteVideoTrack = track) }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        })

        if (pc == null) {
            _uiState.update { it.copy(mediaError = "Unable to allocate WebRTC PeerConnection.") }
            return
        }
        peerConnection = pc

        val streamIds = listOf("family_finance_call")
        val aSource = pcFactory.createAudioSource(MediaConstraints())
        audioSource = aSource
        val aTrack = pcFactory.createAudioTrack("FF_AUDIO_$callId", aSource).apply {
            setEnabled(hasRecordAudioPerm)
        }
        localAudioTrack = aTrack
        pc.addTrack(aTrack, streamIds)

        if (isVideo && hasCameraPerm) {
            setupLocalVideoTrack(pcFactory, pc, callId, streamIds)
        }

        startStatsPolling()
    }

    private fun setupLocalVideoTrack(
        pcFactory: PeerConnectionFactory,
        pc: PeerConnection,
        callId: String,
        streamIds: List<String>
    ) {
        val eglContext = eglBase?.eglBaseContext ?: return
        val enumerator = Camera2Enumerator(appContext)
        val devices = enumerator.deviceNames
        val frontCamera = devices.firstOrNull { enumerator.isFrontFacing(it) }
        val chosenCamera = frontCamera ?: devices.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(chosenCamera, null) ?: return

        val helper = SurfaceTextureHelper.create("FFCaptureThread", eglContext)
        val vSource = pcFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, appContext, vSource.capturerObserver)
        runCatching { capturer.startCapture(720, 1280, 24) }

        val vTrack = pcFactory.createVideoTrack("FF_VIDEO_$callId", vSource).apply {
            setEnabled(true)
        }
        videoCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = vSource
        localVideoTrack = vTrack
        pc.addTrack(vTrack, streamIds)

        _uiState.update {
            it.copy(
                localVideoTrack = vTrack,
                isFrontCamera = frontCamera != null,
                cameraEnabled = true
            )
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        val queued = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        queued.forEach { candidate ->
            runCatching { pc.addIceCandidate(candidate) }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && peerConnection != null) {
                delay(2500L)
                peerConnection?.getStats { report ->
                    val quality = evaluateQualityFromStats(report)
                    _uiState.update { it.copy(connectionQuality = quality) }
                }
            }
        }
    }

    private fun evaluateQualityFromStats(report: RTCStatsReport): CallConnectionQuality {
        var roundTripSeconds = -1.0
        var packetsLost = 0L
        var packetsReceived = 0L

        report.statsMap.values.forEach { stat ->
            when (stat.type) {
                "candidate-pair" -> {
                    val state = stat.members["state"] as? String
                    val nominated = stat.members["nominated"] as? Boolean ?: false
                    if (state == "succeeded" || nominated) {
                        val rtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                        if (rtt != null) roundTripSeconds = rtt
                    }
                }
                "inbound-rtp" -> {
                    packetsLost += (stat.members["packetsLost"] as? Number)?.toLong() ?: 0L
                    packetsReceived += (stat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                }
            }
        }

        if (roundTripSeconds < 0.0 && packetsReceived == 0L) {
            return CallConnectionQuality.UNKNOWN
        }
        val lossRatio = if (packetsReceived + packetsLost > 0L) {
            packetsLost.toDouble() / (packetsReceived + packetsLost).toDouble()
        } else {
            0.0
        }
        return when {
            roundTripSeconds in 0.0..0.12 && lossRatio < 0.02 -> CallConnectionQuality.EXCELLENT
            roundTripSeconds <= 0.25 && lossRatio < 0.05 -> CallConnectionQuality.GOOD
            roundTripSeconds <= 0.50 && lossRatio < 0.12 -> CallConnectionQuality.FAIR
            else -> CallConnectionQuality.POOR
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(err: String?) = Unit
        override fun onSetFailure(err: String?) = Unit
    }

    private companion object {
        const val TAG = "WebRtcOneToOneCtrl"
    }
}
