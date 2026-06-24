package com.tylerhats.proxitap.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tylerhats.proxitap.signaling.SignalingClient
import com.tylerhats.proxitap.signaling.SignalingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import android.content.SharedPreferences

class CallService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val myPeerId = UUID.randomUUID().toString()
    private var isEndingCall = false
    private val initDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    
    // Core Dependencies
    lateinit var webRtcClient: WebRtcClient
        private set

    var signalingServer: SignalingServer? = null
        private set
    var signalingClient: SignalingClient? = null
        private set

    // UI State Observables
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    
    private val _distanceMeters = MutableStateFlow<Float?>(null)
    val distanceMeters: StateFlow<Float?> = _distanceMeters

    private val _participants = MutableStateFlow<List<String>>(emptyList())
    val participants: StateFlow<List<String>> = _participants

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted
    
    private val _connectionStats = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionStats: StateFlow<Map<String, String>> = _connectionStats
    
    private val _hasJoined = MutableStateFlow(false)
    val hasJoined: StateFlow<Boolean> = _hasJoined
    
    fun setHasJoined(joined: Boolean) {
        _hasJoined.value = joined
    }
    
    private var hardwarePttManager: HardwarePttManager? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pttReceiver: BroadcastReceiver? = null

    var isMediaLobby = false
        private set
    var isBidirectional = false
        private set
    private var mediaStreamer: MediaStreamer? = null
    
    private var myIcePort: Int? = null
    private var remoteIcePort: Int? = null
    private var udpProxy: UdpProxy? = null
    private var isHostSignaling = false
    
    // Network Lifecycle decoupled from UI
    lateinit var nanImpl: com.tylerhats.proxitap.network.NanImpl
    lateinit var hotspotImpl: com.tylerhats.proxitap.network.HotspotImpl
    lateinit var distanceTracker: com.tylerhats.proxitap.network.DistanceTracker
    
    fun getNetworkManager(mode: String?): com.tylerhats.proxitap.network.LocalNetworkManager {
        return if (mode == "hotspot") hotspotImpl else nanImpl
    }

    private fun checkAndStartProxy() {
        if (remoteIcePort != null) {
            if (udpProxy != null) {
                if (udpProxy?.localListenPort != remoteIcePort) {
                    Log.d("CallService", "Recreating UDP proxy for new remote ICE port")
                    udpProxy?.stop()
                    udpProxy = null
                } else {
                    // Proxy already exists for this remote port. Update target port if we now have myIcePort
                    if (myIcePort != null && udpProxy?.localTargetPort != myIcePort) {
                        Log.d("CallService", "Updating UDP proxy target port to $myIcePort")
                        udpProxy?.localTargetPort = myIcePort
                    }
                    return
                }
            }
            
            udpProxy = UdpProxy(
                localListenPort = remoteIcePort!!,
                localTargetPort = myIcePort
            ) { data ->
                scope.launch {
                    initDeferred.await()
                    if (isHostSignaling) {
                        signalingServer?.broadcastAudioData(data)
                    } else {
                        signalingClient?.sendAudioData(data)
                    }
                }
            }
            udpProxy?.start()
            Log.d("CallService", "UDP Proxy started! Listen: $remoteIcePort, Target: $myIcePort")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
        
        nanImpl = com.tylerhats.proxitap.network.NanImpl(this)
        hotspotImpl = com.tylerhats.proxitap.network.HotspotImpl(this)
        distanceTracker = com.tylerhats.proxitap.network.DistanceTracker(this)
        
        createNotificationChannel()
        
        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        
        val nsEnabled = prefs.getBoolean("ns_enabled", true)
        val aecEnabled = prefs.getBoolean("aec_enabled", true)
        val pttEnabled = prefs.getBoolean("hardware_ptt", false) // Default OFF
        val useBluetoothMic = prefs.getBoolean("bluetooth_mic", true)
        val opusDtx = prefs.getBoolean("opus_dtx", true)
        val opusBitrate = prefs.getInt("opus_bitrate", 64000)

        if (useBluetoothMic) {
            // WebRTC's JavaAudioDeviceModule naturally handles Bluetooth SCO natively.
            // Manually enforcing it here breaks the audio routing on some devices.
            Log.d("CallService", "Bluetooth Mic enabled in settings. WebRTC will handle routing.")
        }

        webRtcClient = WebRtcClient(this).apply {
            isNoiseSuppressionEnabled = nsEnabled
            isAcousticEchoCancellationEnabled = aecEnabled
            opusDtxEnabled = opusDtx
            opusBitrateBps = opusBitrate
        }
        
        hardwarePttManager = HardwarePttManager(this) { muted ->
            _isMuted.value = muted
            webRtcClient.setMute(muted)
        }
        hardwarePttManager?.isHardwarePttEnabled = pttEnabled
        hardwarePttManager?.start()

        // Acquire Wi-Fi Lock to prevent sleep during foreground call
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "ProxiTap::CallServiceWifiLock"
        )
        wifiLock?.acquire()

        // Acquire CPU WakeLock to prevent audio stuttering when screen is off
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxiTap::CallServiceCpuWakeLock")
        wakeLock?.acquire()

        // Register receiver for Volume PTT Accessibility Service
        pttReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SET_MUTE) {
                    val mute = intent.getBooleanExtra("isMuted", false)
                    _isMuted.value = mute
                    webRtcClient.setMute(mute)
                }
            }
        }
        
        val filter = IntentFilter(ACTION_SET_MUTE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pttReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pttReceiver, filter)
        }
    }

    companion object {
        const val ACTION_END_CALL = "com.tylerhats.proxitap.action.END_CALL"
        const val ACTION_SET_MUTE = "com.tylerhats.proxitap.action.SET_MUTE"
    }

    fun initializeCall(mediaLobby: Boolean, bidirectional: Boolean, mediaResult: Int = 0, mediaData: Intent? = null) {
        isMediaLobby = mediaLobby
        isBidirectional = bidirectional

        if (!isMediaLobby) {
            try {
                if (!webRtcClient.hasWebRtcInitialized()) {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    webRtcClient.initWebRtcAndTracks()
                }
            } catch (e: Exception) {
                Log.e("CallService", "Error initializing WebRTC", e)
            }
        }
        
        if (mediaStreamer == null) {
            mediaStreamer = MediaStreamer(this)
            mediaStreamer?.startPlayback(isMono = !isMediaLobby)
            
            if (isMediaLobby && mediaResult != 0 && mediaData != null) {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = mediaProjectionManager.getMediaProjection(mediaResult, mediaData)
                if (projection != null) {
                    mediaStreamer?.startCapture(projection) { audioData ->
                        scope.launch {
                            if (signalingServer != null) {
                                signalingServer?.broadcastAudioData(audioData)
                            } else if (signalingClient != null) {
                                signalingClient?.sendAudioData(audioData)
                            }
                        }
                    }
                }
            } else if (isMediaLobby && isBidirectional) {
                // In Media Lobbies with bidirectional audio, we can still use MediaStreamer mic
                mediaStreamer?.startMicrophoneCapture { audioData ->
                    if (!_isMuted.value) {
                        scope.launch {
                            if (signalingServer != null) {
                                signalingServer?.broadcastAudioData(audioData)
                            } else if (signalingClient != null) {
                                signalingClient?.sendAudioData(audioData)
                            }
                        }
                    }
                }
            }
            
            // Voice Activity Detection
            if (!isMediaLobby && ::webRtcClient.isInitialized) {
                webRtcClient.startAudioLevelMonitoring { speaking ->
                    _isSpeaking.value = speaking
                }
            }
        }
        
        initDeferred.complete(Unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_CALL) {
            Log.d("CallService", "Received ACTION_END_CALL from Notification")
            // Send broadcast to let MainActivity know the call ended
            sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED").setPackage(packageName))
            endCall()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_MUTE) {
            toggleMute()
            return START_STICKY
        }

        // Determine service type early to call startForeground before getting projection
        val isMedia = intent?.getBooleanExtra("isMediaLobby", false) ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isMedia) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, createNotification(), type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isMedia) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, createNotification(), type)
        } else {
            startForeground(1, createNotification())
        }

        intent?.let {
            if (it.hasExtra("isMediaLobby")) {
                val mResult = it.getIntExtra("media_projection_result", 0)
                val mData = it.getParcelableExtra<Intent>("media_projection_data")
                initializeCall(
                    it.getBooleanExtra("isMediaLobby", false),
                    it.getBooleanExtra("isBidirectional", false),
                    mResult,
                    mData
                )
            }
        }
        
        // Start Stats Poller
        scope.launch {
            while (isActive) {
                val stats = mutableMapOf<String, String>()
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                stats["Audio Route"] = if (audioManager.isBluetoothScoOn) "Bluetooth SCO" 
                                       else if (audioManager.isSpeakerphoneOn) "Speakerphone"
                                       else "Earpiece/Default"
                stats["Media Streamer"] = if (mediaStreamer != null) "Active" else "Inactive"
                stats["Hardware PTT"] = if (hardwarePttManager?.isHardwarePttEnabled == true) "Enabled" else "Disabled"
                stats["Network Transport"] = if (isHostSignaling) "Server Mode" else "Client Mode"
                stats["Lobby Type"] = if (isMediaLobby) "Media" else "Talk"
                stats["WebRTC Mode"] = if (::webRtcClient.isInitialized) "Initialized" else "Not Initialized"
                
                _connectionStats.value = stats
                kotlinx.coroutines.delay(2000)
            }
        }

        return START_NOT_STICKY
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        
        // Update notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
        
        if (isMediaLobby && mediaStreamer != null) {
            return
        }
        
        if (::webRtcClient.isInitialized) {
            webRtcClient.setMute(newMuted)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (key) {
            "ns_enabled" -> {
                if (::webRtcClient.isInitialized) {
                    webRtcClient.isNoiseSuppressionEnabled = sharedPreferences.getBoolean(key, true)
                }
            }
            "aec_enabled" -> {
                if (::webRtcClient.isInitialized) {
                    webRtcClient.isAcousticEchoCancellationEnabled = sharedPreferences.getBoolean(key, true)
                }
            }
            "hardware_ptt" -> {
                hardwarePttManager?.isHardwarePttEnabled = sharedPreferences.getBoolean(key, false)
            }
            "bluetooth_mic" -> {
                val useBluetooth = sharedPreferences.getBoolean(key, true)
                Log.d("CallService", "Bluetooth mic setting changed: $useBluetooth")
                // Routing will update automatically via WebRTC context
            }
            "opus_dtx" -> {
                if (::webRtcClient.isInitialized) {
                    webRtcClient.opusDtxEnabled = sharedPreferences.getBoolean(key, true)
                }
            }
            "opus_bitrate" -> {
                if (::webRtcClient.isInitialized) {
                    webRtcClient.opusBitrateBps = sharedPreferences.getInt(key, 64000)
                }
            }
        }
    }

    fun startHostSignaling(port: Int = 8080) {
        scope.launch {
            initDeferred.await()
            if (signalingServer != null) {
                Log.d("CallService", "Signaling Server already running")
                return@launch
            }
            
            isHostSignaling = true

        signalingServer = SignalingServer()
        signalingServer?.startServer(port)

        scope.launch {
            signalingServer?.incomingMessages?.collect { (senderId, json) ->
                val type = json.getString("type")
                if (type == "JOIN") {
                    Log.d("CallService", "Peer $senderId joined.")
                    
                    if (!isMediaLobby) {
                        val signalingCallback = object : WebRtcClient.SignalingCallback {
                            override fun onOfferCreated(peerId: String, sdp: String) {
                                val offerMsg = JSONObject().apply {
                                    put("type", "OFFER")
                                    put("target", peerId)
                                    put("sdp", sdp)
                                }
                                scope.launch { signalingServer?.sendMessageToPeer(peerId, offerMsg) }
                            }

                            override fun onAnswerCreated(peerId: String, sdp: String) {}

                            override fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
                                if (sdp.contains("127.0.0.1") && sdp.contains("udp")) {
                                    val parts = sdp.split(" ")
                                    if (parts.size >= 6) {
                                        myIcePort = parts[5].toIntOrNull()
                                        checkAndStartProxy()
                                    }
                                }
                                // Only send 127.0.0.1 loopback candidates to trick peer WebRTC to hit the proxy
                                if (sdp.contains("127.0.0.1")) {
                                    val iceMsg = JSONObject().apply {
                                        put("type", "ICE")
                                        put("target", peerId)
                                        put("sdpMid", sdpMid)
                                        put("sdpMLineIndex", sdpMLineIndex)
                                        put("sdp", sdp)
                                    }
                                    scope.launch { signalingServer?.sendMessageToPeer(peerId, iceMsg) }
                                }
                            }
                        }

                        if (webRtcClient.hasPeerConnection(senderId)) {
                            Log.d("CallService", "Peer $senderId already exists. Triggering ICE Restart.")
                            webRtcClient.triggerIceRestart(senderId, signalingCallback)
                        } else {
                            webRtcClient.createPeerConnection(senderId, isHost = true, signalingCallback)
                            webRtcClient.createOffer(senderId, signalingCallback)
                        }
                        
                        // Sync settings to the new peer
                        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                        val settingsMsg = JSONObject().apply {
                            put("type", "SETTINGS")
                            put("opusBitrate", prefs.getInt("opus_bitrate", 64000))
                            put("opusDtx", prefs.getBoolean("opus_dtx", true))
                            put("nsEnabled", prefs.getBoolean("ns_enabled", true))
                            put("aecEnabled", prefs.getBoolean("aec_enabled", true))
                        }
                        scope.launch { signalingServer?.sendMessageToPeer(senderId, settingsMsg) }
                    }
                }
                else if (type == "LEAVE") {
                    Log.d("CallService", "Peer $senderId left.")
                    webRtcClient.removePeerConnection(senderId)
                    // The server will handle updating the participant list and broadcasting it automatically
                }
                else if (type == "PARTICIPANTS_UPDATE") {
                    val array = json.getJSONArray("participants")
                    val list = mutableListOf<String>()
                    val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                    val hostName = prefs.getString("display_name", Build.MODEL) ?: Build.MODEL
                    list.add("$hostName (Host)")
                    
                    for (i in 0 until array.length()) {
                        val p = array.getJSONObject(i)
                        list.add(p.getString("name"))
                    }
                    _participants.value = list
                }
                else if (type == "ANSWER" && !isMediaLobby) {
                    webRtcClient.handleRemoteAnswer(senderId, json.getString("sdp"))
                }
                else if (type == "ICE" && !isMediaLobby) {
                    val sdp = json.getString("sdp")
                    if (sdp.contains("127.0.0.1") && sdp.contains("udp")) {
                        val parts = sdp.split(" ")
                        if (parts.size >= 6) {
                            remoteIcePort = parts[5].toIntOrNull()
                            checkAndStartProxy()
                        }
                    }
                    webRtcClient.handleRemoteIceCandidate(
                        senderId,
                        json.getString("sdpMid"),
                        json.getInt("sdpMLineIndex"),
                        sdp
                    )
                }
            }
        }
        
        scope.launch {
            signalingServer?.incomingAudio?.collect { (_, audioData) ->
                if (isMediaLobby) {
                    mediaStreamer?.playAudioData(audioData)
                    signalingServer?.broadcastAudioData(audioData)
                } else {
                    udpProxy?.sendUdpData(audioData)
                }
            }
            }
        }
    }

    fun startPeerSignaling(hostIp: String, port: Int = 8080) {
        scope.launch {
            initDeferred.await()
            if (signalingClient != null) {
                Log.d("CallService", "Signaling Client already running")
                return@launch
            }
            
            isHostSignaling = false

        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        val deviceName = prefs.getString("display_name", Build.MODEL) ?: Build.MODEL
        signalingClient = SignalingClient(hostIp, port)
        signalingClient?.connect(myPeerId, deviceName)
        
        val signalingCallback = object : WebRtcClient.SignalingCallback {
            override fun onOfferCreated(peerId: String, sdp: String) {}

            override fun onAnswerCreated(peerId: String, sdp: String) {
                val answerMsg = JSONObject().apply {
                    put("type", "ANSWER")
                    put("target", peerId)
                    put("sdp", sdp)
                }
                signalingClient?.sendMessage(answerMsg)
            }

            override fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
                if (sdp.contains("127.0.0.1") && sdp.contains("udp")) {
                    val parts = sdp.split(" ")
                    if (parts.size >= 6) {
                        myIcePort = parts[5].toIntOrNull()
                        checkAndStartProxy()
                    }
                }
                if (sdp.contains("127.0.0.1")) {
                    val iceMsg = JSONObject().apply {
                        put("type", "ICE")
                        put("target", peerId)
                        put("sdpMid", sdpMid)
                        put("sdpMLineIndex", sdpMLineIndex)
                        put("sdp", sdp)
                    }
                    signalingClient?.sendMessage(iceMsg)
                }
            }
        }

        scope.launch {
            signalingClient?.incomingMessages?.collect { json ->
                val type = json.getString("type")
                if (type == "SESSION_CLOSED") {
                    Log.d("CallService", "Host closed the session. Ending call.")
                    sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED").setPackage(packageName))
                    endCall()
                    return@collect
                }
                
                if (json.has("target") && json.getString("target") != myPeerId) return@collect

                if (type == "PARTICIPANTS_UPDATE") {
                    val array = json.getJSONArray("participants")
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        val p = array.getJSONObject(i)
                        val name = p.getString("name")
                        val id = p.getString("id")
                        if (id == myPeerId) list.add("$name (You)") else list.add(name)
                    }
                    val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                    val hostName = prefs.getString("host_display_name", "Host")
                    // Wait, we don't have host display name reliably on peer unless host sends it
                    list.add(0, "Host")
                    _participants.value = list
                }
                else if (type == "SETTINGS" && !isMediaLobby) {
                    val bitrate = json.optInt("opusBitrate", 64000)
                    val dtx = json.optBoolean("opusDtx", true)
                    val ns = json.optBoolean("nsEnabled", true)
                    val aec = json.optBoolean("aecEnabled", true)
                    Log.d("CallService", "Received synced settings from Host: Bitrate=$bitrate DTX=$dtx")
                    
                    webRtcClient.opusBitrateBps = bitrate
                    webRtcClient.opusDtxEnabled = dtx
                    webRtcClient.isNoiseSuppressionEnabled = ns
                    webRtcClient.isAcousticEchoCancellationEnabled = aec
                    
                    // The PeerConnection will use these updated parameters when modifying local tracks/sender
                    webRtcClient.applyAudioSettingsToActiveConnections()
                }
                else if (type == "OFFER" && !isMediaLobby) {
                    val senderId = "HOST"
                    if (!webRtcClient.hasPeerConnection(senderId)) {
                        webRtcClient.createPeerConnection(senderId, isHost = false, signalingCallback)
                    }
                    webRtcClient.handleRemoteOffer(senderId, json.getString("sdp"), signalingCallback)
                }
                else if (type == "ICE" && !isMediaLobby) {
                    val sdp = json.getString("sdp")
                    if (sdp.contains("127.0.0.1") && sdp.contains("udp")) {
                        val parts = sdp.split(" ")
                        if (parts.size >= 6) {
                            remoteIcePort = parts[5].toIntOrNull()
                            checkAndStartProxy()
                        }
                    }
                    webRtcClient.handleRemoteIceCandidate(
                        "HOST",
                        json.getString("sdpMid"),
                        json.getInt("sdpMLineIndex"),
                        sdp
                    )
                }
            }
        }

        scope.launch {
            signalingClient?.incomingAudio?.collect { audioData ->
                if (isMediaLobby) {
                    mediaStreamer?.playAudioData(audioData)
                } else {
                    udpProxy?.sendUdpData(audioData)
                }
            }
        }
    }
}

    fun endCall() {
        if (isEndingCall) return
        isEndingCall = true
        Log.d("CallService", "Ending call")
        if (!isHostSignaling) {
            val msg = JSONObject().apply { put("type", "LEAVE") }
            signalingClient?.sendMessage(msg)
        }

        _isSpeaking.value = false
        _participants.value = emptyList()
        _connectionStats.value = emptyMap()
        _hasJoined.value = false
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        pttReceiver?.let { 
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore if not registered
            }
        }
        pttReceiver = null

        try { scope.cancel() } catch (e: Exception) { Log.e("CallService", "Error cancelling scope", e) }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        if (::webRtcClient.isInitialized) {
            try { webRtcClient.dispose() } catch (e: Exception) { Log.e("CallService", "Error disposing webRtcClient", e) }
        }
        
        try { udpProxy?.stop() } catch (e: Exception) { Log.e("CallService", "Error stopping udpProxy", e) }
        udpProxy = null
        
        try { mediaStreamer?.stop() } catch (e: Exception) { Log.e("CallService", "Error stopping mediaStreamer", e) }
        mediaStreamer = null
        
        try { signalingServer?.stopServer() } catch (e: Exception) { Log.e("CallService", "Error stopping signalingServer", e) }
        signalingServer = null
        
        try { signalingClient?.disconnect() } catch (e: Exception) { Log.e("CallService", "Error disconnecting signalingClient", e) }
        signalingClient = null
        
        try { 
            hardwarePttManager?.stop()
            hardwarePttManager?.release()
        } catch (e: Exception) { Log.e("CallService", "Error stopping hardwarePttManager", e) }
        hardwarePttManager = null
        
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) { Log.e("CallService", "Error stopping Bluetooth SCO", e) }
        
        try {
            nanImpl.stop()
            hotspotImpl.stop()
            distanceTracker.stopTracking()
        } catch (e: Exception) { Log.e("CallService", "Error stopping network managers", e) }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CallService", "Service Destroyed")
        // Just in case endCall wasn't called
        endCall()
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "proxitap_call_channel",
                "ProxiTap Active Call",
                NotificationManager.IMPORTANCE_HIGH // High importance required for CallStyle
            ).apply {
                description = "Keeps the microphone active for Walkie-Talkie features"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Main Intent to open the app
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingAppIntent = android.app.PendingIntent.getActivity(
            this, 0, appIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // End Call Intent
        val endCallIntent = Intent(this, CallService::class.java).apply { action = ACTION_END_CALL }
        val pendingEndCallIntent = android.app.PendingIntent.getService(
            this, 1, endCallIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, CallService::class.java).apply { action = ACTION_SET_MUTE }
        val pendingMuteIntent = android.app.PendingIntent.getService(
            this, 2, muteIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val muteActionTitle = if (isMuted.value) "Unmute" else "Mute"
        val muteIcon = if (isMuted.value) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        val muteAction = NotificationCompat.Action.Builder(
            muteIcon,
            muteActionTitle,
            pendingMuteIntent
        ).build()

        val person = androidx.core.app.Person.Builder()
            .setName("ProxiTap Lobby")
            .setImportant(true)
            .build()

        val callStyle = NotificationCompat.CallStyle.forOngoingCall(person, pendingEndCallIntent)

        return NotificationCompat.Builder(this, "proxitap_call_channel")
            .setContentTitle("ProxiTap Call Active")
            .setContentText("Walkie-Talkie stream is running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setStyle(callStyle)
            .addAction(muteAction)
            .setUsesChronometer(true) // Native animated timer for the call duration!
            .setContentIntent(pendingAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }
}
