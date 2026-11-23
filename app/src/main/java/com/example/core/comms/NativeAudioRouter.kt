package com.example.core.comms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.model.AudioOutputRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Native Android [AudioManager] router for 1-to-1 WebRTC calls and LiveKit meetings.
 * Supports API 31+ CommunicationDevice switching and legacy speaker/SCO routing.
 */
class NativeAudioRouter(private val context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var hasSessionFocus: Boolean = false

    private val _selectedRoute = MutableStateFlow(AudioOutputRoute.EARPIECE)
    val selectedRoute: StateFlow<AudioOutputRoute> = _selectedRoute.asStateFlow()

    private val _availableRoutes = MutableStateFlow(
        listOf(AudioOutputRoute.EARPIECE, AudioOutputRoute.SPEAKER)
    )
    val availableRoutes: StateFlow<List<AudioOutputRoute>> = _availableRoutes.asStateFlow()

    fun startCommunicationAudio(defaultSpeaker: Boolean) {
        val am = audioManager ?: return
        runCatching {
            if (!hasSessionFocus) {
                previousAudioMode = am.mode
                requestVoiceAudioFocus(am)
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                hasSessionFocus = true
            }
            refreshAvailableRoutes()
            val routes = _availableRoutes.value
            val initialRoute = when {
                AudioOutputRoute.BLUETOOTH in routes -> AudioOutputRoute.BLUETOOTH
                AudioOutputRoute.WIRED_HEADSET in routes -> AudioOutputRoute.WIRED_HEADSET
                defaultSpeaker -> AudioOutputRoute.SPEAKER
                else -> AudioOutputRoute.EARPIECE
            }
            selectRoute(initialRoute)
        }.onFailure { err ->
            Log.w(TAG, "Unable to initialize communication audio routing: ${err.message}")
        }
    }

    fun refreshAvailableRoutes(): List<AudioOutputRoute> {
        val am = audioManager ?: return listOf(AudioOutputRoute.EARPIECE, AudioOutputRoute.SPEAKER)
        val routes = linkedSetOf<AudioOutputRoute>()
        val canInspectBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
            devices.forEach { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> routes.add(AudioOutputRoute.SPEAKER)
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> routes.add(AudioOutputRoute.EARPIECE)
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> routes.add(AudioOutputRoute.WIRED_HEADSET)
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> if (canInspectBluetooth) routes.add(AudioOutputRoute.BLUETOOTH)
                }
            }
        } else {
            routes.add(AudioOutputRoute.EARPIECE)
            routes.add(AudioOutputRoute.SPEAKER)
            @Suppress("DEPRECATION")
            if (am.isWiredHeadsetOn) {
                routes.add(AudioOutputRoute.WIRED_HEADSET)
            }
            if (am.isBluetoothScoAvailableOffCall && canInspectBluetooth) {
                routes.add(AudioOutputRoute.BLUETOOTH)
            }
        }

        if (routes.isEmpty()) {
            routes.add(AudioOutputRoute.EARPIECE)
            routes.add(AudioOutputRoute.SPEAKER)
        }
        val list = routes.toList()
        _availableRoutes.value = list
        return list
    }

    fun selectRoute(route: AudioOutputRoute): AudioOutputRoute {
        val am = audioManager ?: return route
        refreshAvailableRoutes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
            val target = when (route) {
                AudioOutputRoute.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                AudioOutputRoute.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                AudioOutputRoute.WIRED_HEADSET -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                AudioOutputRoute.BLUETOOTH -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
            }
            if (target != null) {
                runCatching { am.setCommunicationDevice(target) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = route == AudioOutputRoute.SPEAKER
            }
        } else {
            @Suppress("DEPRECATION")
            when (route) {
                AudioOutputRoute.SPEAKER -> {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                }
                AudioOutputRoute.EARPIECE,
                AudioOutputRoute.WIRED_HEADSET -> {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = false
                }
                AudioOutputRoute.BLUETOOTH -> {
                    am.isSpeakerphoneOn = false
                    runCatching {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                }
            }
        }
        _selectedRoute.value = route
        return route
    }

    fun cycleNextRoute(): AudioOutputRoute {
        val available = refreshAvailableRoutes()
        val currentIdx = available.indexOf(_selectedRoute.value)
        val next = available[(currentIdx + 1) % available.size]
        return selectRoute(next)
    }

    fun setMicrophoneHardwareMute(muted: Boolean) {
        runCatching { audioManager?.isMicrophoneMute = muted }
    }

    fun stopCommunicationAudio() {
        val am = audioManager ?: return
        runCatching {
            am.isMicrophoneMute = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            if (hasSessionFocus) {
                am.mode = previousAudioMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { am.abandonAudioFocusRequest(it) }
                    focusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                hasSessionFocus = false
            }
        }.onFailure { err ->
            Log.w(TAG, "Unable to release communication audio focus: ${err.message}")
        }
    }

    private fun requestVoiceAudioFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private companion object {
        const val TAG = "NativeAudioRouter"
    }
}
