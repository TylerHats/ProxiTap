package com.tylerhats.proxitap.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap

class WebRtcClient(
    private val context: Context,
    private val onAudioError: (String) -> Unit = {}
) {

    interface SignalingCallback {
        fun onOfferCreated(peerId: String, sdp: String)
        fun onAnswerCreated(peerId: String, sdp: String)
        fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String)
        fun onIceConnectionStateChanged(peerId: String, state: PeerConnection.IceConnectionState) {}
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var preferredInputDevice: AudioDeviceInfo? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var rootEglBase: EglBase? = null
    
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val remoteDescriptionSet = ConcurrentHashMap.newKeySet<String>()
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    var isNoiseSuppressionEnabled: Boolean = true
    var isAcousticEchoCancellationEnabled: Boolean = true
    var opusDtxEnabled: Boolean = true
    var opusBitrateBps: Int = 64000

    fun hasWebRtcInitialized(): Boolean {
        return peerConnectionFactory != null
    }

    fun setPreferredInputDevice(device: AudioDeviceInfo?) {
        preferredInputDevice = device
        try {
            audioDeviceModule?.setPreferredInputDevice(device)
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error setting preferred input device", e)
        }
    }

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

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(isAcousticEchoCancellationEnabled)
            .setUseHardwareNoiseSuppressor(isNoiseSuppressionEnabled)
            .setUseLowLatency(true)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e("WebRtcClient", "AudioRecord init error: $errorMessage")
                    onAudioError("Microphone init issue, retrying audio route...")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e("WebRtcClient", "AudioRecord start error ($errorCode): $errorMessage")
                    onAudioError("Microphone start issue, checking audio device...")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e("WebRtcClient", "AudioRecord error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e("WebRtcClient", "AudioTrack init error: $errorMessage")
                    onAudioError("Audio output init issue, checking audio device...")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e("WebRtcClient", "AudioTrack start error ($errorCode): $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e("WebRtcClient", "AudioTrack error: $errorMessage")
                }
            })
            .createAudioDeviceModule()

        audioDeviceModule = adm
        preferredInputDevice?.let {
            try { adm.setPreferredInputDevice(it) } catch (_: Exception) {}
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
            
        adm.release()
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

    fun getIceConnectionState(peerId: String): PeerConnection.IceConnectionState? {
        return peerConnections[peerId]?.iceConnectionState()
    }

    fun createPeerConnection(peerId: String, isHost: Boolean, signalingCallback: SignalingCallback) {
        Log.d("WebRtcClient", "Creating PeerConnection for $peerId (isHost=$isHost)")
        if (peerConnectionFactory == null) return

        removePeerConnection(peerId)
        remoteDescriptionSet.remove(peerId)
        pendingIceCandidates[peerId] = java.util.Collections.synchronizedList(mutableListOf())

        // Empty ICE servers because we are on a local network (NAN/Hotspot)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("WebRtcClient", "ICE Connection State for $peerId: $state")
                if (state != null) {
                    signalingCallback.onIceConnectionStateChanged(peerId, state)
                }
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
            if (isHost) {
                localAudioTrack?.let {
                    pc.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
                }
            }
        }
    }

    private fun mungeSdpForOpus(sdp: String): String {
        val dtxValue = if (opusDtxEnabled) 1 else 0
        return sdp.replace(Regex("a=fmtp:111(.*)"), "a=fmtp:111$1;usedtx=$dtxValue;maxaveragebitrate=$opusBitrateBps;stereo=0")
    }

    private fun flushPendingIceCandidates(peerId: String) {
        val pc = peerConnections[peerId] ?: return
        val list = pendingIceCandidates[peerId] ?: return
        synchronized(list) {
            if (list.isNotEmpty()) {
                Log.d("WebRtcClient", "Flushing ${list.size} queued ICE candidates for $peerId")
                for (c in list) {
                    try {
                        pc.addIceCandidate(c)
                    } catch (e: Exception) {
                        Log.e("WebRtcClient", "Error adding queued ICE candidate", e)
                    }
                }
                list.clear()
            }
        }
    }

    fun triggerIceRestart(peerId: String, signalingCallback: SignalingCallback) {
        val pc = peerConnections[peerId] ?: return
        Log.d("WebRtcClient", "Triggering ICE Restart for $peerId")
        remoteDescriptionSet.remove(peerId)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    val munged = SessionDescription(sdp.type, mungeSdpForOpus(sdp.description))
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            signalingCallback.onOfferCreated(peerId, munged.description)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e("WebRtcClient", "SetLocalDescription failed on ICE restart: $p0")
                            signalingCallback.onOfferCreated(peerId, munged.description)
                        }
                    }, munged)
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
        remoteDescriptionSet.remove(peerId)
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    val munged = SessionDescription(sdp.type, mungeSdpForOpus(sdp.description))
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            signalingCallback.onOfferCreated(peerId, munged.description)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e("WebRtcClient", "SetLocalDescription failed on offer: $p0")
                            signalingCallback.onOfferCreated(peerId, munged.description)
                        }
                    }, munged)
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
        remoteDescriptionSet.remove(peerId)
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteDescriptionSet.add(peerId)
                flushPendingIceCandidates(peerId)

                // Configure local audio track on the transceiver to enable outgoing audio
                for (transceiver in pc.transceivers) {
                    if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                        transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        localAudioTrack?.let { track ->
                            transceiver.sender.setTrack(track, false)
                        }
                    }
                }

                // Once remote description is set, create the answer
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        if (answerSdp != null) {
                            val munged = SessionDescription(answerSdp.type, mungeSdpForOpus(answerSdp.description))
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    signalingCallback.onAnswerCreated(peerId, munged.description)
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {
                                    Log.e("WebRtcClient", "SetLocalDescription failed on answer: $p0")
                                    signalingCallback.onAnswerCreated(peerId, munged.description)
                                }
                            }, munged)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) { Log.e("WebRtcClient", "Create Answer failed: $p0") }
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
            override fun onSetSuccess() {
                remoteDescriptionSet.add(peerId)
                flushPendingIceCandidates(peerId)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e("WebRtcClient", "Set Remote Answer failed: $p0")
            }
        }, sessionDesc)
    }

    fun handleRemoteIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        if (!remoteDescriptionSet.contains(peerId)) {
            Log.d("WebRtcClient", "Queueing ICE candidate for $peerId until remote description is set")
            val list = pendingIceCandidates.getOrPut(peerId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }
            list.add(candidate)
            return
        }
        val pc = peerConnections[peerId] ?: return
        pc.addIceCandidate(candidate)
    }

    private var audioLevelTimer: java.util.Timer? = null

    fun startAudioLevelMonitoring(onSpeakingChanged: (Boolean) -> Unit) {
        audioLevelTimer?.cancel()
        audioLevelTimer = java.util.Timer()
        audioLevelTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                var isAnySpeaking = false
                
                // If there are no peers connected, nobody is speaking
                if (peerConnections.isEmpty()) {
                    onSpeakingChanged(false)
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
        Log.d("WebRtcClient", "Disposing WebRTC Client")
        audioLevelTimer?.cancel()
        audioLevelTimer = null
        for (pc in peerConnections.values) {
            try {
                pc.close()
                pc.dispose()
            } catch (e: Exception) { Log.e("WebRtcClient", "Failed to dispose PC", e) }
        }
        peerConnections.clear()
        remoteDescriptionSet.clear()
        pendingIceCandidates.clear()
        
        try { localAudioTrack?.dispose() } catch (e: Exception) { Log.e("WebRtcClient", "Failed to dispose localAudioTrack", e) }
        localAudioTrack = null
        
        try { localAudioSource?.dispose() } catch (e: Exception) { Log.e("WebRtcClient", "Failed to dispose localAudioSource", e) }
        localAudioSource = null
        
        try { peerConnectionFactory?.dispose() } catch (e: Exception) { Log.e("WebRtcClient", "Failed to dispose factory", e) }
        peerConnectionFactory = null
        audioDeviceModule = null
        
        try { rootEglBase?.release() } catch (e: Exception) { Log.e("WebRtcClient", "Failed to release rootEglBase", e) }
        rootEglBase = null
    }

    fun removePeerConnection(peerId: String) {
        remoteDescriptionSet.remove(peerId)
        pendingIceCandidates.remove(peerId)
        val pc = peerConnections.remove(peerId)
        try {
            pc?.close()
            pc?.dispose()
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error removing PeerConnection for $peerId", e)
        }
        Log.d("WebRtcClient", "Peer connection removed for $peerId")
        
        if (peerConnections.isEmpty()) {
            Log.d("WebRtcClient", "All peers disconnected. Local audio track remains alive for future connections.")
        }
    }

    fun applyAudioSettingsToActiveConnections() {
        Log.d("WebRtcClient", "Applying settings: Bitrate=$opusBitrateBps DTX=$opusDtxEnabled")
        for (pc in peerConnections.values) {
            val senders = pc.senders
            for (sender in senders) {
                if (sender.track()?.kind() == "audio") {
                    val parameters = sender.parameters
                    if (parameters != null) {
                        for (encoding in parameters.encodings) {
                            encoding.maxBitrateBps = opusBitrateBps
                        }
                        sender.parameters = parameters
                    }
                }
            }
        }
    }

    fun updateAudioConstraints(nsEnabled: Boolean, aecEnabled: Boolean) {
        val factory = peerConnectionFactory ?: return
        Log.d("WebRtcClient", "Re-creating local audio track with NS=$nsEnabled, AEC=$aecEnabled")
        
        val oldTrack = localAudioTrack
        val oldSource = localAudioSource
        
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", aecEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", nsEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        
        val newSource = factory.createAudioSource(audioConstraints)
        val newTrack = factory.createAudioTrack("LOCAL_AUDIO_TRACK", newSource)
        val wasEnabled = oldTrack?.enabled() ?: true
        newTrack.setEnabled(wasEnabled)
        
        localAudioSource = newSource
        localAudioTrack = newTrack
        
        // Update track on all peer connection senders
        for (pc in peerConnections.values) {
            for (sender in pc.senders) {
                if (sender.track()?.kind() == "audio") {
                    sender.setTrack(newTrack, false)
                }
            }
        }
        
        // Clean up old track and source safely
        try {
            oldTrack?.setEnabled(false)
            oldTrack?.dispose()
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error disposing old audio track", e)
        }
        try {
            oldSource?.dispose()
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error disposing old audio source", e)
        }
    }

    private val lastPacketsLost = ConcurrentHashMap<String, Long>()
    private val lastPacketsReceived = ConcurrentHashMap<String, Long>()

    fun getPeerConnectionQuality(peerId: String, callback: (rttMs: Double?, packetLossRate: Float?, hasTraffic: Boolean) -> Unit) {
        val pc = peerConnections[peerId]
        if (pc == null) {
            callback(null, null, false)
            return
        }
        pc.getStats { report ->
            var rtt: Double? = null
            var packetsReceived: Long? = null
            var packetsLost: Long? = null
            
            for (stat in report.statsMap.values) {
                if (stat.type == "candidate-pair") {
                    val currentRtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                    if (currentRtt != null) {
                        rtt = currentRtt * 1000.0
                    }
                }
                if (stat.type == "inbound-rtp" && stat.members["kind"] == "audio") {
                    val lost = (stat.members["packetsLost"] as? Number)?.toLong()
                    val received = (stat.members["packetsReceived"] as? Number)?.toLong()
                    if (lost != null) packetsLost = lost
                    if (received != null) packetsReceived = received
                }
            }
            
            var hasTraffic = false
            val lossRate = if (packetsLost != null && packetsReceived != null) {
                val lastLost = lastPacketsLost[peerId] ?: 0L
                val lastReceived = lastPacketsReceived[peerId] ?: 0L
                
                val deltaLost = (packetsLost - lastLost).coerceAtLeast(0L)
                val deltaReceived = (packetsReceived - lastReceived).coerceAtLeast(0L)
                
                lastPacketsLost[peerId] = packetsLost
                lastPacketsReceived[peerId] = packetsReceived
                
                val deltaTotal = deltaLost + deltaReceived
                if (deltaTotal > 0) {
                    hasTraffic = true
                    (deltaLost.toFloat() / deltaTotal.toFloat()) * 100f
                } else {
                    0f
                }
            } else {
                null
            }
            callback(rtt, lossRate, hasTraffic)
        }
    }
}
