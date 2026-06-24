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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import android.content.SharedPreferences

class CallService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val myPeerId = UUID.randomUUID().toString()
    
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
    
    private var hardwarePttManager: HardwarePttManager? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pttReceiver: BroadcastReceiver? = null

    var isMediaLobby = false
        private set
    var isBidirectional = false
        private set
    private var mediaStreamer: MediaStreamer? = null

    inner class LocalBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
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
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_CALL) {
            Log.d("CallService", "Received ACTION_END_CALL from Notification")
            // Send broadcast to let MainActivity know the call ended
            sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED"))
            endCall()
            return START_NOT_STICKY
        }

        intent?.let {
            if (it.hasExtra("isMediaLobby")) {
                isMediaLobby = it.getBooleanExtra("isMediaLobby", false)
                isBidirectional = it.getBooleanExtra("isBidirectional", false)
                val mediaResult = it.getIntExtra("media_projection_result", 0)
                val mediaData = it.getParcelableExtra<Intent>("media_projection_data")
                
                if (isMediaLobby && mediaStreamer == null) {
                    mediaStreamer = MediaStreamer(this)
                    mediaStreamer?.startPlayback()
                    
                    if (mediaResult != 0 && mediaData != null) {
                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        val projection = mediaProjectionManager.getMediaProjection(mediaResult, mediaData)
                        if (projection != null) {
                            mediaStreamer?.startCapture(projection) { audioData ->
                                if (signalingServer != null) {
                                    scope.launch { signalingServer?.broadcastAudioData(audioData) }
                                } else if (signalingClient != null) {
                                    signalingClient?.sendAudioData(audioData)
                                }
                            }
                        }
                    }
                    webRtcClient.startAudioLevelMonitoring { speaking ->
                        _isSpeaking.value = speaking
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isMediaLobby) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, createNotification(), type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isMediaLobby) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, createNotification(), type)
        } else {
            startForeground(1, createNotification())
        }

        return START_NOT_STICKY
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        webRtcClient.setMute(newMuted)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (key) {
            "ns_enabled" -> {
                webRtcClient.isNoiseSuppressionEnabled = sharedPreferences.getBoolean(key, true)
                // Note: WebRTC hardware ADM constraints cannot be hot-swapped without rebuilding the AudioTrack.
                // However, software processing in WebRTC would adapt. For V1 we update the field.
            }
            "aec_enabled" -> {
                webRtcClient.isAcousticEchoCancellationEnabled = sharedPreferences.getBoolean(key, true)
            }
            "hardware_ptt" -> {
                hardwarePttManager?.isHardwarePttEnabled = sharedPreferences.getBoolean(key, false)
            }
            "bluetooth_mic" -> {
                val useBluetooth = sharedPreferences.getBoolean(key, true)
                if (useBluetooth) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
            }
            "opus_dtx" -> {
                webRtcClient.opusDtxEnabled = sharedPreferences.getBoolean(key, true)
            }
            "opus_bitrate" -> {
                webRtcClient.opusBitrateBps = sharedPreferences.getInt(key, 64000)
            }
        }
    }

    fun startHostSignaling(port: Int = 8080) {
        if (signalingServer != null) {
            Log.d("CallService", "Signaling Server already running")
            return
        }
        if (!isMediaLobby) {
            try {
                if (!webRtcClient.hasWebRtcInitialized()) {
                    webRtcClient.initWebRtcAndTracks()
                }
            } catch (e: Exception) {
                Log.e("CallService", "Error initializing WebRTC", e)
            }
        }

        signalingServer = SignalingServer()
        signalingServer?.startServer(port)

        scope.launch {
            signalingServer?.incomingMessages?.collect { (senderId, json) ->
                val type = json.getString("type")
                if (type == "JOIN") {
                    Log.d("CallService", "Peer $senderId joined. Creating PeerConnection.")
                    
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

                    if (!isMediaLobby) {
                        if (webRtcClient.hasPeerConnection(senderId)) {
                            Log.d("CallService", "Peer $senderId already exists. Triggering ICE Restart.")
                            webRtcClient.triggerIceRestart(senderId, signalingCallback)
                        } else {
                            webRtcClient.createPeerConnection(senderId, signalingCallback)
                            // In a star topology, Host acts as SFU. It sends an offer to the new peer.
                            webRtcClient.createOffer(senderId, signalingCallback)
                        }
                    } else {
                        Log.d("CallService", "Media Lobby: Skipping WebRTC for $senderId")
                    }
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
                else if (type == "ANSWER") {
                    webRtcClient.handleRemoteAnswer(senderId, json.getString("sdp"))
                }
                else if (type == "ICE") {
                    webRtcClient.handleRemoteIceCandidate(
                        senderId,
                        json.getString("sdpMid"),
                        json.getInt("sdpMLineIndex"),
                        json.getString("sdp")
                    )
                }
            }
        }
        
        scope.launch {
            signalingServer?.incomingAudio?.collect { (_, audioData) ->
                if (isMediaLobby && isBidirectional) {
                    mediaStreamer?.playAudioData(audioData)
                    // Optional: broadcast this peer's audio to other peers
                    signalingServer?.broadcastAudioData(audioData)
                }
            }
        }
    }

    fun startPeerSignaling(hostIp: String, port: Int = 8080) {
        if (signalingClient != null) {
            Log.d("CallService", "Signaling Client already running")
            return
        }
        if (!isMediaLobby) {
            try {
                if (!webRtcClient.hasWebRtcInitialized()) {
                    webRtcClient.initWebRtcAndTracks()
                }
            } catch (e: Exception) {
                Log.e("CallService", "Error initializing WebRTC", e)
            }
        }

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

        scope.launch {
            signalingClient?.incomingMessages?.collect { json ->
                val type = json.getString("type")
                if (type == "SESSION_CLOSED") {
                    Log.d("CallService", "Host closed the session. Ending call.")
                    sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED"))
                    endCall()
                    return@collect
                }
                
                if (json.has("target") && json.getString("target") != myPeerId) return@collect

                val senderId = "HOST" // Peers only connect directly to host in SFU

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
                else if (type == "OFFER") {
                    if (!webRtcClient.hasPeerConnection(senderId)) {
                        webRtcClient.createPeerConnection(senderId, signalingCallback)
                    } else {
                        Log.d("CallService", "Received new OFFER for existing peer. Processing as ICE Restart.")
                    }
                    webRtcClient.handleRemoteOffer(senderId, json.getString("sdp"), signalingCallback)
                }
                else if (type == "ICE") {
                    webRtcClient.handleRemoteIceCandidate(
                        senderId,
                        json.getString("sdpMid"),
                        json.getInt("sdpMLineIndex"),
                        json.getString("sdp")
                    )
                }
            }
        }

        scope.launch {
            signalingClient?.incomingAudio?.collect { audioData ->
                if (isMediaLobby) {
                    mediaStreamer?.playAudioData(audioData)
                }
            }
        }
    }

    fun endCall() {
        Log.d("CallService", "Ending call and cleaning up resources")
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

        scope.cancel()
        webRtcClient.dispose()
        mediaStreamer?.stop()
        signalingServer?.stopServer()
        signalingClient?.disconnect()
        hardwarePttManager?.stop()
        hardwarePttManager?.release()
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
        
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
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingAppIntent = android.app.PendingIntent.getActivity(
            this, 0, appIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // End Call Intent
        val endCallIntent = Intent(this, CallService::class.java).apply { action = ACTION_END_CALL }
        val pendingEndCallIntent = android.app.PendingIntent.getService(
            this, 1, endCallIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

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
            .setUsesChronometer(true) // Native animated timer for the call duration!
            .setContentIntent(pendingAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }
}
