package com.tylerhats.proxitap.audio

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcClient(private val context: Context) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var rootEglBase: EglBase? = null

    // Configuration Settings
    var isNoiseSuppressionEnabled: Boolean = true
    var isAcousticEchoCancellationEnabled: Boolean = true

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        Log.d("WebRtcClient", "Initializing WebRTC")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        rootEglBase = EglBase.create()

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            rootEglBase?.eglBaseContext, true, true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)

        // Setup Audio Device Module to handle the hardware recording and playback
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(isAcousticEchoCancellationEnabled)
            .setUseHardwareNoiseSuppressor(isNoiseSuppressionEnabled)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
            
        audioDeviceModule.release()
    }

    fun createLocalAudioTrack() {
        if (peerConnectionFactory == null) return

        // Create MediaConstraints to apply software NS/AEC if hardware isn't available or if specifically requested
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", isAcousticEchoCancellationEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", isNoiseSuppressionEnabled.toString()))
            // Disable auto gain control which sometimes pumps background noise on scooters
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        localAudioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("LOCAL_AUDIO_TRACK", localAudioSource)
        localAudioTrack?.setEnabled(true)
        Log.d("WebRtcClient", "Local Audio Track created. NS: $isNoiseSuppressionEnabled, AEC: $isAcousticEchoCancellationEnabled")
    }

    fun setMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun applyRemoteSettings(bitrate: Int?, enableNs: Boolean?, enableAec: Boolean?) {
        Log.d("WebRtcClient", "Applying remote settings: Bitrate=$bitrate, NS=$enableNs, AEC=$enableAec")
        enableNs?.let { isNoiseSuppressionEnabled = it }
        enableAec?.let { isAcousticEchoCancellationEnabled = it }
        
        // To apply new NS/AEC settings, we technically need to recreate the AudioSource.
        // For V1, we'll log it. In a full implementation, we tear down the track and rebuild it.
        // Bitrate is usually applied to the RtpSender of the PeerConnection.
    }

    private var audioLevelTimer: java.util.Timer? = null

    fun startAudioLevelMonitoring(onSpeakingChanged: (Boolean) -> Unit) {
        // Mock fallback if PeerConnection isn't fully established yet
        if (peerConnectionFactory == null) {
            Thread {
                while (true) {
                    Thread.sleep((500..2000).random().toLong())
                    onSpeakingChanged(true)
                    Thread.sleep((200..1000).random().toLong())
                    onSpeakingChanged(false)
                }
            }.start()
            return
        }

        audioLevelTimer?.cancel()
        audioLevelTimer = java.util.Timer()
        audioLevelTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                // In a full implementation, we'd call peerConnection?.getStats { report -> ... }
                // Since PeerConnection is managed by CallService/Signaling, we simulate the stats 
                // polling loop here for the POC, demonstrating where the RTCStatsCollector goes.
                
                // Example of real implementation:
                /*
                peerConnection?.getStats { report ->
                    var isSpeaking = false
                    for (stat in report.statsMap.values) {
                        if (stat.type == "inbound-rtp" && stat.members["kind"] == "audio") {
                            val audioLevel = stat.members["audioLevel"] as? Double
                            if (audioLevel != null && audioLevel > 0.05) {
                                isSpeaking = true
                                break
                            }
                        }
                    }
                    onSpeakingChanged(isSpeaking)
                }
                */
                
                // Simulated UI trigger
                onSpeakingChanged(Math.random() > 0.5)
            }
        }, 0, 500)
    }

    fun dispose() {
        audioLevelTimer?.cancel()
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        peerConnectionFactory?.dispose()
        rootEglBase?.release()
    }
}
