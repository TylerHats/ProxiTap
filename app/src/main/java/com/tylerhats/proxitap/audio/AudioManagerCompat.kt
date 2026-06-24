package com.tylerhats.proxitap.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

class AudioManagerCompat(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isBluetoothScoStarted = false

    /**
     * Configures the system audio to act like a phone call (MODE_IN_COMMUNICATION).
     * This ensures the physical volume buttons control the "Call Volume" slider
     * instead of the "Media Volume" slider, so users can listen to music independently.
     */
    fun startCallMode(useBluetoothMic: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        if (useBluetoothMic) {
            routeToBluetooth()
        } else {
            // Force standard behavior (could use phone mic, allows high-quality music on earbuds if supported)
            audioManager.stopBluetoothSco()
            isBluetoothScoStarted = false
            Log.d("AudioManagerCompat", "Using standard/phone mic. Music quality preserved.")
        }
    }

    fun stopCallMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        if (isBluetoothScoStarted) {
            audioManager.stopBluetoothSco()
            isBluetoothScoStarted = false
        }
        audioManager.isSpeakerphoneOn = false
    }

    private fun routeToBluetooth() {
        // Modern approach: Check for LE Audio or standard Bluetooth devices
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = devices.any { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

        if (hasBluetooth) {
            Log.d("AudioManagerCompat", "Bluetooth device detected. Starting SCO/HFP profile for microphone access.")
            // This forces the headset into HFP (Hands-Free Profile) to use its microphone.
            // Note: This reduces audio quality for music on standard Bluetooth Classic devices.
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            isBluetoothScoStarted = true
        } else {
            Log.d("AudioManagerCompat", "No Bluetooth device found. Defaulting to earpiece/speaker.")
        }
    }
}
