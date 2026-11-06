package com.tylerhats.proxitap.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    HEADSET("Earbuds")
}

class AudioManagerCompat(
    private val context: Context,
    private val onDevicesSelected: (route: AudioRoute, inputDevice: AudioDeviceInfo?, outputDevice: AudioDeviceInfo?) -> Unit = { _, _, _ -> },
    private val onStatusToast: (String) -> Unit = {}
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBluetoothScoStarted = false
    private var isStarted = false
    private var isVoiceMode = true
    private var useBluetoothMic = true

    private val _currentRoute = MutableStateFlow(AudioRoute.EARPIECE)
    val currentRoute: StateFlow<AudioRoute> = _currentRoute.asStateFlow()

    private val _isHeadsetAvailable = MutableStateFlow(false)
    val isHeadsetAvailable: StateFlow<Boolean> = _isHeadsetAvailable.asStateFlow()

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )

    private val commHeadsetPriority = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )

    private val inputHeadsetPriority = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    private val outputHeadsetPriority = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!isStarted || addedDevices == null) return
            val hadHeadset = _isHeadsetAvailable.value
            val nowHasHeadset = detectHeadsetAvailable()
            _isHeadsetAvailable.value = nowHasHeadset

            val addedHeadset = addedDevices.any { it.type in headsetTypes }
            if (addedHeadset && nowHasHeadset) {
                Log.d("AudioManagerCompat", "Headset/earbuds connected. Auto-routing to HEADSET.")
                applyRoute(AudioRoute.HEADSET)
                if (!hadHeadset) {
                    onStatusToast("Switched audio to earbuds")
                }
            } else if (_currentRoute.value == AudioRoute.HEADSET && nowHasHeadset) {
                // Re-bind preferred input/output devices in case SCO/BLE source just became ready
                applyRoute(AudioRoute.HEADSET)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!isStarted || removedDevices == null) return
            val nowHasHeadset = detectHeadsetAvailable()
            _isHeadsetAvailable.value = nowHasHeadset

            if (!nowHasHeadset && _currentRoute.value == AudioRoute.HEADSET) {
                val fallback = if (isVoiceMode) AudioRoute.EARPIECE else AudioRoute.SPEAKER
                Log.d("AudioManagerCompat", "Headset/earbuds removed. Falling back to $fallback")
                applyRoute(fallback)
                onStatusToast("Earbuds disconnected, using ${fallback.label}")
            }
        }
    }

    private val commDeviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (!isStarted) return@OnCommunicationDeviceChangedListener
        Log.d("AudioManagerCompat", "System communicationDevice changed to: ${device?.productName} (type=${device?.type})")
        if (_currentRoute.value == AudioRoute.HEADSET) {
            val inputDev = if (useBluetoothMic) findHeadsetInputDevice() else findBuiltinMicDevice()
            val outputDev = findHeadsetOutputDevice() ?: device
            onDevicesSelected(AudioRoute.HEADSET, inputDev, outputDev)
        }
    }

    fun startCallMode(voiceMode: Boolean, useBtMic: Boolean) {
        isVoiceMode = voiceMode
        useBluetoothMic = useBtMic
        isStarted = true

        audioManager.mode = if (isVoiceMode) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL

        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (e: Exception) {
            Log.e("AudioManagerCompat", "Error registering AudioDeviceCallback", e)
        }
        try {
            audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, commDeviceListener)
        } catch (e: Exception) {
            Log.e("AudioManagerCompat", "Error adding OnCommunicationDeviceChangedListener", e)
        }

        val hasHeadset = detectHeadsetAvailable()
        _isHeadsetAvailable.value = hasHeadset

        val initialRoute = when {
            hasHeadset -> AudioRoute.HEADSET
            isVoiceMode -> AudioRoute.EARPIECE
            else -> AudioRoute.SPEAKER
        }
        Log.d("AudioManagerCompat", "Starting audio routing (voiceMode=$voiceMode, useBtMic=$useBtMic, hasHeadset=$hasHeadset) -> $initialRoute")
        applyRoute(initialRoute)
    }

    fun updateBluetoothMicPreference(useBtMic: Boolean) {
        useBluetoothMic = useBtMic
        if (isStarted && _currentRoute.value == AudioRoute.HEADSET) {
            applyRoute(AudioRoute.HEADSET)
        }
    }

    fun cycleAudioRoute() {
        val hasHeadset = detectHeadsetAvailable()
        _isHeadsetAvailable.value = hasHeadset

        val nextRoute = if (isVoiceMode) {
            if (hasHeadset) {
                when (_currentRoute.value) {
                    AudioRoute.HEADSET -> AudioRoute.SPEAKER
                    AudioRoute.SPEAKER -> AudioRoute.EARPIECE
                    AudioRoute.EARPIECE -> AudioRoute.HEADSET
                }
            } else {
                when (_currentRoute.value) {
                    AudioRoute.SPEAKER -> AudioRoute.EARPIECE
                    else -> AudioRoute.SPEAKER
                }
            }
        } else {
            // Media Sharing mode
            if (hasHeadset) {
                when (_currentRoute.value) {
                    AudioRoute.HEADSET -> AudioRoute.SPEAKER
                    else -> AudioRoute.HEADSET
                }
            } else {
                onStatusToast("No earbuds or headphones detected")
                AudioRoute.SPEAKER
            }
        }

        applyRoute(nextRoute)
    }

    fun applyRoute(route: AudioRoute) {
        _currentRoute.value = route
        try {
            if (isVoiceMode) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                when (route) {
                    AudioRoute.HEADSET -> {
                        audioManager.isSpeakerphoneOn = false
                        val commDevice = findHeadsetCommunicationDevice()
                        var commSet = false
                        if (commDevice != null) {
                            try {
                                commSet = audioManager.setCommunicationDevice(commDevice)
                                Log.d("AudioManagerCompat", "setCommunicationDevice(${commDevice.productName}, type=${commDevice.type}) = $commSet")
                            } catch (e: Exception) {
                                Log.e("AudioManagerCompat", "Failed setCommunicationDevice for headset", e)
                            }
                        }
                        if (!commSet) {
                            try {
                                @Suppress("DEPRECATION")
                                audioManager.startBluetoothSco()
                                @Suppress("DEPRECATION")
                                audioManager.isBluetoothScoOn = true
                                isBluetoothScoStarted = true
                            } catch (e: Exception) {
                                Log.e("AudioManagerCompat", "Fallback startBluetoothSco failed", e)
                            }
                        }
                        val inputDev = if (useBluetoothMic) findHeadsetInputDevice() else findBuiltinMicDevice()
                        val outputDev = findHeadsetOutputDevice() ?: commDevice
                        onDevicesSelected(AudioRoute.HEADSET, inputDev, outputDev)

                        // Re-verify input/output device after 400ms once SCO/BLE channel finishes opening
                        mainHandler.postDelayed({
                            if (isStarted && _currentRoute.value == AudioRoute.HEADSET) {
                                val delayedInput = if (useBluetoothMic) findHeadsetInputDevice() else findBuiltinMicDevice()
                                val delayedOutput = findHeadsetOutputDevice() ?: findHeadsetCommunicationDevice()
                                onDevicesSelected(AudioRoute.HEADSET, delayedInput, delayedOutput)
                            }
                        }, 400L)
                    }
                    AudioRoute.SPEAKER -> {
                        stopScoIfNeeded()
                        val speakerComm = audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (speakerComm != null) {
                            try {
                                audioManager.setCommunicationDevice(speakerComm)
                            } catch (e: Exception) {
                                Log.e("AudioManagerCompat", "Failed setCommunicationDevice for speaker", e)
                            }
                        } else {
                            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
                        }
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        val builtinMic = findBuiltinMicDevice()
                        val speakerOut = findBuiltinSpeakerOutputDevice() ?: speakerComm
                        onDevicesSelected(AudioRoute.SPEAKER, builtinMic, speakerOut)
                    }
                    AudioRoute.EARPIECE -> {
                        stopScoIfNeeded()
                        val earpieceComm = audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        }
                        if (earpieceComm != null) {
                            try {
                                audioManager.setCommunicationDevice(earpieceComm)
                            } catch (e: Exception) {
                                Log.e("AudioManagerCompat", "Failed setCommunicationDevice for earpiece", e)
                            }
                        } else {
                            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
                        }
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        val builtinMic = findBuiltinMicDevice()
                        val earpieceOut = findBuiltinEarpieceOutputDevice() ?: earpieceComm
                        onDevicesSelected(AudioRoute.EARPIECE, builtinMic, earpieceOut)
                    }
                }
            } else {
                // Media Lobby Mode (48kHz Stereo Media)
                audioManager.mode = AudioManager.MODE_NORMAL
                stopScoIfNeeded()
                try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
                when (route) {
                    AudioRoute.HEADSET -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        val outDev = findHeadsetOutputDevice()
                        onDevicesSelected(AudioRoute.HEADSET, null, outDev)
                    }
                    AudioRoute.SPEAKER, AudioRoute.EARPIECE -> {
                        _currentRoute.value = AudioRoute.SPEAKER
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        val speakerOut = findBuiltinSpeakerOutputDevice()
                        onDevicesSelected(AudioRoute.SPEAKER, null, speakerOut)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioManagerCompat", "Error applying audio route $route", e)
        }
    }

    private fun stopScoIfNeeded() {
        if (isBluetoothScoStarted || audioManager.isBluetoothScoOn) {
            try {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            } catch (_: Exception) {}
            isBluetoothScoStarted = false
        }
    }

    fun stopCallMode() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {}
        try {
            audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
        } catch (_: Exception) {}
        stopScoIfNeeded()
        try {
            audioManager.clearCommunicationDevice()
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    fun detectHeadsetAvailable(): Boolean {
        val commHas = try {
            audioManager.availableCommunicationDevices.any { it.type in headsetTypes }
        } catch (_: Exception) { false }
        val outHas = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
        } catch (_: Exception) { false }
        return commHas || outHas
    }

    private fun findHeadsetCommunicationDevice(): AudioDeviceInfo? {
        val commDevices = try {
            audioManager.availableCommunicationDevices
        } catch (_: Exception) { emptyList() }

        for (type in commHeadsetPriority) {
            val match = commDevices.firstOrNull { it.type == type }
            if (match != null) return match
        }
        return null
    }

    private fun findHeadsetInputDevice(): AudioDeviceInfo? {
        val inputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: Exception) { emptyArray() }

        for (type in inputHeadsetPriority) {
            val match = inputs.firstOrNull { it.type == type }
            if (match != null) return match
        }
        return findBuiltinMicDevice()
    }

    private fun findBuiltinMicDevice(): AudioDeviceInfo? {
        val inputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: Exception) { emptyArray() }
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun findHeadsetOutputDevice(): AudioDeviceInfo? {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) { emptyArray() }

        for (type in outputHeadsetPriority) {
            val match = outputs.firstOrNull { it.type == type }
            if (match != null) return match
        }
        return null
    }

    private fun findBuiltinSpeakerOutputDevice(): AudioDeviceInfo? {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) { emptyArray() }
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun findBuiltinEarpieceOutputDevice(): AudioDeviceInfo? {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) { emptyArray() }
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
}
