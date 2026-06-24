package com.tylerhats.proxitap.audio

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcClient(private val context: Context) {

    interface SignalingCallback {
        fun onOfferCreated(peerId: String, sdp: String)
        fun onAnswerCreated(peerId: String, sdp: String)
        fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var rootEglBase: EglBase? = null
    
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    var isNoiseSuppressionEnabled: Boolean = true
    var isAcousticEchoCancellationEnabled: Boolean = true

    fun initWebRtcAndTracks() {
        initWebRtc()
        createLocalAudioTrack()
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
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)

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

    private fun createLocalAudioTrack() {
        if (peerConnectionFactory == null) return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", isAcousticEchoCancellationEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", isNoiseSuppressionEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        localAudioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("LOCAL_AUDIO_TRACK", localAudioSource)
        localAudioTrack?.setEnabled(true)
        Log.d("WebRtcClient", "Local Audio Track created.")
    }

    fun setMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun hasPeerConnection(peerId: String): Boolean {
        return peerConnections.containsKey(peerId)
    }

    fun createPeerConnection(peerId: String, signalingCallback: SignalingCallback) {
        Log.d("WebRtcClient", "Creating PeerConnection for $peerId")
        if (peerConnectionFactory == null) return

        // Empty ICE servers because we are on a local network (NAN/Hotspot)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                Log.d("WebRtcClient", "ICE Connection State for $peerId: $p0")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    signalingCallback.onIceCandidate(peerId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                Log.d("WebRtcClient", "Received remote track from $peerId")
                // Track is automatically played by the AudioDeviceModule
            }
        }

        val pc = peerConnectionFactory!!.createPeerConnection(rtcConfig, pcObserver)
        if (pc != null) {
            peerConnections[peerId] = pc
            localAudioTrack?.let {
                pc.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            }
        }
    }

    private fun mungeSdpForOpus(sdp: String): String {
        return sdp.replace(Regex("a=fmtp:111(.*)"), "a=fmtp:111$1;usedtx=1;maxaveragebitrate=64000;stereo=0")
    }

    fun triggerIceRestart(peerId: String, signalingCallback: SignalingCallback) {
        val pc = peerConnections[peerId] ?: return
        Log.d("WebRtcClient", "Triggering ICE Restart for $peerId")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    val munged = SessionDescription(sdp.type, mungeSdpForOpus(sdp.description))
                    pc.setLocalDescription(this, munged)
                    signalingCallback.onOfferCreated(peerId, munged.description)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Log.e("WebRtcClient", "ICE Restart Offer failed: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun createOffer(peerId: String, signalingCallback: SignalingCallback) {
        val pc = peerConnections[peerId] ?: return
        Log.d("WebRtcClient", "Creating offer for $peerId")
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    val munged = SessionDescription(sdp.type, mungeSdpForOpus(sdp.description))
                    pc.setLocalDescription(this, munged)
                    signalingCallback.onOfferCreated(peerId, munged.description)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Log.e("WebRtcClient", "Offer creation failed: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun handleRemoteOffer(peerId: String, sdp: String, signalingCallback: SignalingCallback) {
        Log.d("WebRtcClient", "Handling remote offer from $peerId")
        val pc = peerConnections[peerId] ?: return
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                // Once remote description is set, create the answer
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        if (answerSdp != null) {
                            val munged = SessionDescription(answerSdp.type, mungeSdpForOpus(answerSdp.description))
                            pc.setLocalDescription(this, munged)
                            signalingCallback.onAnswerCreated(peerId, munged.description)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Log.e("WebRtcClient", "Set Remote Offer failed: $p0") }
        }, sessionDesc)
    }

    fun handleRemoteAnswer(peerId: String, sdp: String) {
        Log.d("WebRtcClient", "Handling remote answer from $peerId")
        val pc = peerConnections[peerId] ?: return
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sessionDesc)
    }

    fun handleRemoteIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val pc = peerConnections[peerId] ?: return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        pc.addIceCandidate(candidate)
    }

    private var audioLevelTimer: java.util.Timer? = null

    fun startAudioLevelMonitoring(onSpeakingChanged: (Boolean) -> Unit) {
        audioLevelTimer?.cancel()
        audioLevelTimer = java.util.Timer()
        audioLevelTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                var isAnySpeaking = false
                
                // If there are no peers connected, we fallback to a mock for UI testing
                if (peerConnections.isEmpty()) {
                    onSpeakingChanged(Math.random() > 0.8)
                    return
                }

                // In full mesh, we check if ANY remote peer is speaking
                peerConnections.values.forEach { pc ->
                    pc.getStats { report ->
                        for (stat in report.statsMap.values) {
                            if (stat.type == "inbound-rtp" && stat.members["kind"] == "audio") {
                                val audioLevel = stat.members["audioLevel"] as? Double
                                if (audioLevel != null && audioLevel > 0.05) {
                                    isAnySpeaking = true
                                }
                            }
                        }
                    }
                }
                onSpeakingChanged(isAnySpeaking)
            }
        }, 0, 500)
    }

    fun dispose() {
        audioLevelTimer?.cancel()
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        peerConnectionFactory?.dispose()
        rootEglBase?.release()
    }
}
