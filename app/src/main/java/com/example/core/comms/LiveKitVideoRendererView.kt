package com.example.core.comms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack as LiveKitVideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack as WebRtcVideoTrack

/**
 * Renders a 1-to-1 WebRTC [WebRtcVideoTrack] using WebRTC's hardware-accelerated [SurfaceViewRenderer].
 */
@Composable
fun WebRtcVideoSurfaceView(
    videoTrack: WebRtcVideoTrack?,
    eglBaseContext: EglBase.Context?,
    mirror: Boolean,
    isOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    if (videoTrack == null || eglBaseContext == null) return

    val rendererHolder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }

    DisposableEffect(videoTrack, eglBaseContext) {
        onDispose {
            val view = rendererHolder[0]
            if (view != null) {
                runCatching { videoTrack.removeSink(view) }
                runCatching { view.release() }
                rendererHolder[0] = null
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                setZOrderMediaOverlay(isOverlay)
                setEnableHardwareScaler(true)
                rendererHolder[0] = this
                runCatching { videoTrack.addSink(this) }
            }
        },
        update = { view ->
            view.setMirror(mirror)
        }
    )
}

/**
 * Renders a LiveKit SFU [LiveKitVideoTrack] inside Compose grids/cards using [TextureViewRenderer]
 * so tiles clip cleanly inside rounded cards without Z-order bleed.
 */
@Composable
fun LiveKitParticipantVideoView(
    videoTrack: LiveKitVideoTrack?,
    eglBaseContext: EglBase.Context?,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    if (videoTrack == null || eglBaseContext == null) return

    val rendererHolder = remember { arrayOfNulls<TextureViewRenderer>(1) }

    DisposableEffect(videoTrack, eglBaseContext) {
        onDispose {
            val view = rendererHolder[0]
            if (view != null) {
                runCatching { videoTrack.removeRenderer(view) }
                runCatching { view.release() }
                rendererHolder[0] = null
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                rendererHolder[0] = this
                runCatching { videoTrack.addRenderer(this) }
            }
        },
        update = { view ->
            view.setMirror(mirror)
        }
    )
}
