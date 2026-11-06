package com.tylerhats.proxitap.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tylerhats.proxitap.signaling.ConnectionState
import com.tylerhats.proxitap.signaling.SignalingClient
import com.tylerhats.proxitap.signaling.SignalingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RawParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean
)

data class PeerQuality(
    val peerId: String,
    val deviceName: String,
    val pingMs: Long?,
    val packetLossRate: Float?,
    val isHost: Boolean,
    val bitrateBps: Int? = null
)

class CallService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var myPeerId = UUID.randomUUID().toString()
    private var isEndingCall = false
    private val initDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var callStartTime = 0L
    private var currentDynamicBitrate = 64000

    // Core Dependencies
    lateinit var webRtcClient: WebRtcClient
        private set
    lateinit var audioManagerCompat: AudioManagerCompat
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

    private val _rawParticipants = MutableStateFlow<List<RawParticipant>>(emptyList())
    val rawParticipants: StateFlow<List<RawParticipant>> = _rawParticipants

    private val _peerQualities = MutableStateFlow<Map<String, PeerQuality>>(emptyMap())
    val peerQualities: StateFlow<Map<String, PeerQuality>> = _peerQualities

    private val peerPings = ConcurrentHashMap<String, Long>()
    private val smoothedPings = ConcurrentHashMap<String, Double>()
    private val smoothedLosses = ConcurrentHashMap<String, Float>()
    private var successiveExcellentReadings = 0

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _connectionStats = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionStats: StateFlow<Map<String, String>> = _connectionStats

    private val _hasJoined = MutableStateFlow(false)
    val hasJoined: StateFlow<Boolean> = _hasJoined

    private val _isConnecting = MutableStateFlow(true)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    val audioRoute: StateFlow<AudioRoute>
        get() = audioManagerCompat.currentRoute

    val isHeadsetAvailable: StateFlow<Boolean>
        get() = audioManagerCompat.isHeadsetAvailable

    fun cycleAudioRoute() {
        if (::audioManagerCompat.isInitialized) {
            audioManagerCompat.cycleAudioRoute()
        }
    }

    fun setHasJoined(joined: Boolean) {
        _hasJoined.value = joined
    }

    private var hardwarePttManager: HardwarePttManager? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    var isMediaLobby = false
        private set
    var isBidirectional = false
        private set
    var isGroupVoice = false
        private set
    private var mediaStreamer: MediaStreamer? = null
    private val mediaStreamerMutex = kotlinx.coroutines.sync.Mutex()
    private val outgoingAudioChannel = kotlinx.coroutines.channels.Channel<ByteArray>(
        capacity = 24,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var myIcePort: Int? = null
    @Volatile
    private var remoteIcePort: Int? = null
    private var udpProxy: UdpProxy? = null
    private var directUdpStreamer: DirectUdpAudioStreamer? = null

    @Volatile
    var isHostSignaling = false
        private set

    // Network Lifecycle decoupled from UI
    lateinit var nanImpl: com.tylerhats.proxitap.network.NanImpl
    lateinit var hotspotImpl: com.tylerhats.proxitap.network.HotspotImpl

    private var lastToastMessage: String = ""
    private var lastToastTime: Long = 0L

    private fun showToast(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && now - lastToastTime < 2500L) return
        lastToastMessage = message
        lastToastTime = now
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("CallService", "Failed to show toast: ${e.message}")
            }
        }
    }

    fun getNetworkManager(mode: String?): com.tylerhats.proxitap.network.LocalNetworkManager {
        return if (mode == "hotspot") hotspotImpl else nanImpl
    }

    @Synchronized
    private fun checkAndStartProxy() {
        val rPort = remoteIcePort
        val mPort = myIcePort
        if (rPort != null) {
            val currentProxy = udpProxy
            if (currentProxy != null) {
                if (currentProxy.localListenPort != rPort) {
                    Log.d("CallService", "Recreating UDP proxy for new remote ICE port $rPort (was ${currentProxy.localListenPort})")
                    currentProxy.stop()
                    udpProxy = null
                } else {
                    if (mPort != null && currentProxy.localTargetPort != mPort) {
                        Log.d("CallService", "Updating UDP proxy target port to $mPort")
                        currentProxy.updateTargetPort(mPort)
                    }
                    return
                }
            }

            val newProxy = UdpProxy(
                localListenPort = rPort,
                targetPort = mPort
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
            newProxy.start()
            udpProxy = newProxy
            Log.d("CallService", "UDP Proxy started! Listen: $rPort, Target: $mPort")
        }
    }

    @Synchronized
    private fun startDirectUdpStreamer(remoteIp: String) {
        // Prefer full scoped IPv6 address (with %aware_data0) if available from NanImpl
        val scopedNanIp = nanImpl.lastPeerIpv6
        val resolvedIp = if (
            remoteIp.contains(":") &&
            !remoteIp.contains("%") &&
            scopedNanIp != null &&
            scopedNanIp.startsWith(remoteIp.removePrefix("[").removeSuffix("]"))
        ) {
            scopedNanIp
        } else {
            remoteIp
        }

        val existing = directUdpStreamer
        if (existing != null) {
            Log.d("CallService", "Updating existing Direct UDP Streamer remote IP to: $resolvedIp")
            existing.updateRemoteIp(resolvedIp)
            return
        }
        Log.d("CallService", "Starting Direct UDP Streamer targeting remote IP: $resolvedIp")
        directUdpStreamer = DirectUdpAudioStreamer(
            localPort = 8085,
            initialRemoteIp = resolvedIp,
            remotePort = 8085
        ) { audioData ->
            mediaStreamer?.playAudioData(audioData)
        }
        directUdpStreamer?.start()
    }

    inner class LocalBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()

        nanImpl = com.tylerhats.proxitap.network.NanImpl(this)
        hotspotImpl = com.tylerhats.proxitap.network.HotspotImpl(this)
        audioManagerCompat = AudioManagerCompat(
            context = this,
            onDevicesSelected = { _, inputDev, outputDev ->
                if (::webRtcClient.isInitialized) {
                    webRtcClient.setPreferredInputDevice(inputDev)
                }
                mediaStreamer?.setPreferredDevices(inputDev, outputDev)
            },
            onStatusToast = { msg -> showToast(msg) }
        )

        createNotificationChannel()

        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val nsEnabled = prefs.getBoolean("ns_enabled", true)
        val aecEnabled = prefs.getBoolean("aec_enabled", true)
        val pttEnabled = prefs.getBoolean("hardware_ptt", false)
        val opusDtx = prefs.getBoolean("opus_dtx", true)
        val opusBitrate = prefs.getInt("opus_bitrate", 64000)

        webRtcClient = WebRtcClient(this).apply {
            isNoiseSuppressionEnabled = nsEnabled
            isAcousticEchoCancellationEnabled = aecEnabled
            opusDtxEnabled = opusDtx
            opusBitrateBps = opusBitrate
        }

        hardwarePttManager = HardwarePttManager(this) {
            toggleMute()
        }
        hardwarePttManager?.isHardwarePttEnabled = pttEnabled
        hardwarePttManager?.start()

        // Acquire Wi-Fi Lock to prevent sleep during foreground call
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            },
            "ProxiTap::CallServiceWifiLock"
        )
        wifiLock?.acquire()

        // Acquire CPU WakeLock to prevent audio stuttering when screen is off
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxiTap::CallServiceCpuWakeLock")
        wakeLock?.acquire()

        // Reactive combined participants tracking
        scope.launch {
            _rawParticipants.collect { plist ->
                val formattedList = plist.map { p ->
                    val isMe = (p.id == myPeerId) || (isHostSignaling && p.id == "HOST")
                    val suffix = when {
                        isMe && p.isHost -> " (You, Host)"
                        isMe -> " (You)"
                        p.isHost -> " (Host)"
                        else -> ""
                    }
                    "${p.name}$suffix"
                }
                _participants.value = formattedList
            }
        }
    }

    companion object {
        const val ACTION_END_CALL = "com.tylerhats.proxitap.action.END_CALL"
        const val ACTION_SET_MUTE = "com.tylerhats.proxitap.action.SET_MUTE"
    }

    fun initializeCall(
        mediaLobby: Boolean,
        bidirectional: Boolean,
        groupVoice: Boolean,
        mediaResult: Int = 0,
        mediaData: Intent? = null
    ) {
        isMediaLobby = mediaLobby
        isBidirectional = bidirectional
        isGroupVoice = groupVoice
        if (callStartTime == 0L) {
            callStartTime = System.currentTimeMillis()
        }
        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        currentDynamicBitrate = prefs.getInt("opus_bitrate", 64000)
        val useBluetoothMic = prefs.getBoolean("bluetooth_mic", true)

        val isVoiceMode = !isMediaLobby || isGroupVoice
        audioManagerCompat.startCallMode(voiceMode = isVoiceMode, useBtMic = useBluetoothMic)

        if (!isMediaLobby) {
            try {
                if (!webRtcClient.hasWebRtcInitialized()) {
                    webRtcClient.initWebRtcAndTracks()
                }
            } catch (e: Exception) {
                Log.e("CallService", "Error initializing WebRTC", e)
            }
        }

        // Launch host broadcast loop
        scope.launch {
            for (audioData in outgoingAudioChannel) {
                if (isHostSignaling) {
                    try {
                        signalingServer?.broadcastAudioData(audioData)
                    } catch (e: Exception) {
                        Log.e("CallService", "Error broadcasting audio data", e)
                    }
                }
            }
        }

        if (mediaStreamer == null) {
            mediaStreamer = MediaStreamer(this)
            val useMono = !isMediaLobby || isGroupVoice
            val rate = prefs.getInt("group_sample_rate", 16000)
            val sampleRate = if (isGroupVoice) rate else 48000
            mediaStreamer?.startPlayback(isMono = useMono, sampleRate = sampleRate)

            if (isMediaLobby && !isGroupVoice && mediaResult != 0 && mediaData != null) {
                val mediaProjectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = mediaProjectionManager.getMediaProjection(mediaResult, mediaData)
                if (projection != null) {
                    mediaStreamer?.startCapture(projection) { audioData ->
                        val isDirectUdpMedia = isMediaLobby && isBidirectional && !isGroupVoice
                        if (isDirectUdpMedia) {
                            directUdpStreamer?.sendAudio(audioData)
                        } else {
                            if (isHostSignaling) {
                                outgoingAudioChannel.trySend(audioData)
                            } else {
                                signalingClient?.sendAudioData(audioData)
                            }
                        }
                    }
                }
            } else if (isMediaLobby && isGroupVoice) {
                mediaStreamer?.startMicrophoneCapture(sampleRate = sampleRate) { audioData ->
                    if (!_isMuted.value) {
                        if (isHostSignaling) {
                            outgoingAudioChannel.trySend(audioData)
                        } else {
                            signalingClient?.sendAudioData(audioData)
                        }
                    }
                }
            }

            // Apply initial preferred input/output devices (e.g. connected earbuds) to MediaStreamer & WebRTC
            audioManagerCompat.applyRoute(audioManagerCompat.currentRoute.value)

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
            sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED").setPackage(packageName))
            endCall()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_MUTE) {
            toggleMute()
            return START_STICKY
        }

        val isMedia = intent?.getBooleanExtra("isMediaLobby", false) ?: false
        val requiresProjection = intent?.getBooleanExtra("requiresProjection", false) ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isMedia && requiresProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, createNotification(), type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isMedia && requiresProjection) {
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
                val mData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelableExtra("media_projection_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra("media_projection_data")
                }
                initializeCall(
                    it.getBooleanExtra("isMediaLobby", false),
                    it.getBooleanExtra("isBidirectional", false),
                    it.getBooleanExtra("isGroupVoice", false),
                    mResult,
                    mData
                )
            }
        }

        // Start Stats Poller
        scope.launch {
            while (isActive) {
                val stats = mutableMapOf<String, String>()
                stats["Audio Route"] = if (::audioManagerCompat.isInitialized) {
                    audioManagerCompat.currentRoute.value.label
                } else {
                    "Default"
                }
                stats["Media Streamer"] = if (mediaStreamer != null) "Active" else "Inactive"
                stats["Hardware PTT"] = if (hardwarePttManager?.isHardwarePttEnabled == true) "Enabled" else "Disabled"
                stats["Network Transport"] = if (isHostSignaling) "Server Mode" else "Client Mode"
                stats["Lobby Type"] = if (isMediaLobby) "Media" else "Talk"
                stats["WebRTC Mode"] = if (::webRtcClient.isInitialized) "Initialized" else "Not Initialized"

                _connectionStats.value = stats

                // --- Connection Quality Updates ---
                if (_hasJoined.value) {
                    val pingMsg = JSONObject().apply {
                        put("type", "PING")
                        put("timestamp", System.currentTimeMillis())
                    }
                    if (isHostSignaling) {
                        _rawParticipants.value.forEach { p ->
                            if (p.id != "HOST") {
                                scope.launch {
                                    signalingServer?.sendMessageToPeer(p.id, pingMsg)
                                }
                            }
                        }
                    } else {
                        scope.launch {
                            signalingClient?.sendMessage(pingMsg)
                        }
                    }

                    val currentRawParts = _rawParticipants.value
                    val newQualities = mutableMapOf<String, PeerQuality>()

                    currentRawParts.forEach { p ->
                        val isMe = (p.id == myPeerId) || (isHostSignaling && p.id == "HOST")
                        if (!isMe) {
                            val ping = peerPings[p.id]

                            if (isMediaLobby && isBidirectional && !isGroupVoice) {
                                // Direct UDP 48kHz Stereo 16-bit PCM (1536 kbps)
                                val lossRate = directUdpStreamer?.lossTracker?.getLossRate()
                                newQualities[p.id] = PeerQuality(
                                    peerId = p.id,
                                    deviceName = p.name,
                                    pingMs = ping,
                                    packetLossRate = lossRate,
                                    isHost = p.isHost,
                                    bitrateBps = 1_536_000
                                )
                            } else if (!isMediaLobby) {
                                // WebRTC Call
                                webRtcClient.getPeerConnectionQuality(p.id) { rtt, loss, hasTraffic ->
                                    val rttVal = rtt?.toLong() ?: ping
                                    val servicePrefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                                    val currentBitrate = if (isHostSignaling && servicePrefs.getBoolean("dynamic_bitrate_enabled", true)) {
                                        currentDynamicBitrate
                                    } else {
                                        webRtcClient.opusBitrateBps
                                    }
                                    val q = PeerQuality(
                                        peerId = p.id,
                                        deviceName = p.name,
                                        pingMs = rttVal,
                                        packetLossRate = loss,
                                        isHost = p.isHost,
                                        bitrateBps = currentBitrate
                                    )
                                    synchronized(_peerQualities) {
                                        val currentMap = _peerQualities.value.toMutableMap()
                                        currentMap[p.id] = q
                                        _peerQualities.value = currentMap
                                    }

                                    if (isHostSignaling && !isGroupVoice && !isMediaLobby) {
                                        if (servicePrefs.getBoolean("dynamic_bitrate_enabled", true) && hasTraffic) {
                                            val alpha = 0.35f
                                            val prevPing = smoothedPings[p.id]
                                            val nextPing = if (rttVal != null) {
                                                if (prevPing != null) (alpha.toDouble() * rttVal + (1.0 - alpha.toDouble()) * prevPing) else rttVal.toDouble()
                                            } else {
                                                prevPing
                                            }
                                            if (nextPing != null) smoothedPings[p.id] = nextPing

                                            val prevLoss = smoothedLosses[p.id]
                                            val nextLoss = if (loss != null) {
                                                if (prevLoss != null) (alpha * loss + (1f - alpha) * prevLoss) else loss
                                            } else {
                                                prevLoss
                                            }
                                            if (nextLoss != null) smoothedLosses[p.id] = nextLoss

                                            val maxBitrate = servicePrefs.getInt("opus_bitrate", 64000)
                                            adjustDynamicBitrate(nextPing?.toLong(), nextLoss, maxBitrate)
                                        }
                                    }
                                }
                            } else {
                                // Media Broadcast or Group Voice
                                val appPrefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                                val streamBitrate = if (isGroupVoice) {
                                    appPrefs.getInt("group_sample_rate", 16000) * 16
                                } else {
                                    1_536_000
                                }
                                newQualities[p.id] = PeerQuality(
                                    peerId = p.id,
                                    deviceName = p.name,
                                    pingMs = ping,
                                    packetLossRate = 0f,
                                    isHost = p.isHost,
                                    bitrateBps = streamBitrate
                                )
                            }
                        }
                    }

                    val validIds = currentRawParts.map { it.id }.toSet()
                    synchronized(_peerQualities) {
                        val currentMap = _peerQualities.value.toMutableMap()
                        currentMap.keys.retainAll(validIds)
                        newQualities.forEach { (id, q) ->
                            currentMap[id] = q
                        }
                        _peerQualities.value = currentMap
                    }

                    if (isHostSignaling) {
                        val updateMsg = JSONObject().apply {
                            put("type", "QUALITIES_UPDATE")
                            val array = JSONArray()
                            _peerQualities.value.forEach { (peerId, q) ->
                                val item = JSONObject().apply {
                                    put("peerId", peerId)
                                    put("pingMs", q.pingMs ?: -1L)
                                    put("packetLossRate", q.packetLossRate?.toDouble() ?: -1.0)
                                    put("bitrateBps", q.bitrateBps ?: -1)
                                }
                                array.put(item)
                            }
                            put("qualities", array)
                        }
                        scope.launch {
                            signalingServer?.broadcastMessage(updateMsg)
                        }
                    }
                } else {
                    if (_peerQualities.value.isNotEmpty()) {
                        _peerQualities.value = emptyMap()
                    }
                    peerPings.clear()
                }

                delay(2000)
            }
        }

        return START_NOT_STICKY
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted

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

        when (key) {
            "ns_enabled" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                if (::webRtcClient.isInitialized) {
                    webRtcClient.isNoiseSuppressionEnabled = enabled
                    webRtcClient.updateAudioConstraints(enabled, webRtcClient.isAcousticEchoCancellationEnabled)
                }
                if (isHostSignaling) broadcastSettings()
                if (isGroupVoice) restartMediaStreamerForGroupVoice()
            }
            "aec_enabled" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                if (::webRtcClient.isInitialized) {
                    webRtcClient.isAcousticEchoCancellationEnabled = enabled
                    webRtcClient.updateAudioConstraints(webRtcClient.isNoiseSuppressionEnabled, enabled)
                }
                if (isHostSignaling) broadcastSettings()
                if (isGroupVoice) restartMediaStreamerForGroupVoice()
            }
            "hardware_ptt" -> {
                hardwarePttManager?.isHardwarePttEnabled = sharedPreferences.getBoolean(key, false)
            }
            "bluetooth_mic" -> {
                val useBluetooth = sharedPreferences.getBoolean(key, true)
                Log.d("CallService", "Bluetooth mic setting changed: $useBluetooth")
                if (::audioManagerCompat.isInitialized) {
                    audioManagerCompat.updateBluetoothMicPreference(useBluetooth)
                }
            }
            "opus_dtx" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                if (::webRtcClient.isInitialized) {
                    webRtcClient.opusDtxEnabled = enabled
                }
                if (isHostSignaling) broadcastSettings()
            }
            "opus_bitrate" -> {
                val bitrate = sharedPreferences.getInt(key, 64000)
                if (::webRtcClient.isInitialized) {
                    webRtcClient.opusBitrateBps = bitrate
                    webRtcClient.applyAudioSettingsToActiveConnections()
                }
                if (isHostSignaling) broadcastSettings()
            }
            "dtx_threshold" -> {
                if (isHostSignaling) broadcastSettings()
            }
            "group_sample_rate" -> {
                val rate = sharedPreferences.getInt(key, 16000)
                if (isHostSignaling) broadcastSettings()
                if (isGroupVoice) restartMediaStreamerForGroupVoice(rate)
            }
            "display_name" -> {
                val newName = sharedPreferences.getString(key, Build.MODEL) ?: Build.MODEL
                if (isHostSignaling) {
                    scope.launch {
                        signalingServer?.updateHostName(newName)
                    }
                } else {
                    val updateMsg = JSONObject().apply {
                        put("type", "UPDATE_NAME")
                        put("name", newName)
                    }
                    signalingClient?.sendMessage(updateMsg)
                }
            }
            "max_participants" -> {
                val maxParts = if (!isMediaLobby || (isBidirectional && !isGroupVoice)) 2 else sharedPreferences.getInt(key, 4)
                signalingServer?.maxParticipants = maxParts
            }
        }
    }

    private fun restartMediaStreamerForGroupVoice(customRate: Int? = null) {
        scope.launch {
            mediaStreamerMutex.withLock {
                val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                val rate = customRate ?: prefs.getInt("group_sample_rate", 16000)
                Log.d("CallService", "Restarting MediaStreamer for Group Voice at sample rate: $rate")
                try {
                    mediaStreamer?.stop()
                } catch (e: Exception) {
                    Log.e("CallService", "Error stopping old mediaStreamer", e)
                }

                mediaStreamer = MediaStreamer(this@CallService)
                val useMono = !isMediaLobby || isGroupVoice
                try {
                    mediaStreamer?.startPlayback(isMono = useMono, sampleRate = rate)
                } catch (e: Exception) {
                    Log.e("CallService", "Error starting playback", e)
                }

                try {
                    mediaStreamer?.startMicrophoneCapture(sampleRate = rate) { audioData ->
                        if (!_isMuted.value) {
                            if (isHostSignaling) {
                                outgoingAudioChannel.trySend(audioData)
                            } else {
                                signalingClient?.sendAudioData(audioData)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CallService", "Error starting microphone capture", e)
                }

                if (::audioManagerCompat.isInitialized) {
                    audioManagerCompat.applyRoute(audioManagerCompat.currentRoute.value)
                }
            }
        }
    }

    private fun broadcastSettings() {
        if (!isHostSignaling) return
        val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
        val dynamicEnabled = prefs.getBoolean("dynamic_bitrate_enabled", true)
        val finalBitrate = if (!isGroupVoice && !isMediaLobby && dynamicEnabled) {
            currentDynamicBitrate
        } else {
            prefs.getInt("opus_bitrate", 64000)
        }

        val settingsMsg = JSONObject().apply {
            put("type", "SETTINGS")
            put("opusBitrate", finalBitrate)
            put("opusDtx", prefs.getBoolean("opus_dtx", true))
            put("dtxThreshold", prefs.getFloat("dtx_threshold", 50f).toDouble())
            put("nsEnabled", prefs.getBoolean("ns_enabled", true))
            put("aecEnabled", prefs.getBoolean("aec_enabled", true))
            put("groupSampleRate", prefs.getInt("group_sample_rate", 16000))
        }
        scope.launch {
            signalingServer?.broadcastMessage(settingsMsg)
        }
    }

    private fun adjustDynamicBitrate(ping: Long?, loss: Float?, targetMaxBitrate: Int) {
        val steps = listOf(2000, 4000, 8000, 16000, 32000, 64000, 128000, 256000)
        var currentIndex = steps.indexOf(currentDynamicBitrate)
        if (currentIndex == -1) {
            currentIndex = steps.indexOf(64000)
        }

        var newIndex = currentIndex

        if (ping != null && loss != null) {
            if (ping > 200 || loss > 8f) {
                newIndex = 0
                successiveExcellentReadings = 0
            } else if (ping > 120 || loss > 4f) {
                newIndex = (currentIndex - 2).coerceAtLeast(0)
                successiveExcellentReadings = 0
            } else if (ping > 70 || loss > 1.5f) {
                newIndex = (currentIndex - 1).coerceAtLeast(0)
                successiveExcellentReadings = 0
            } else if (ping <= 40 && loss <= 0.5f) {
                successiveExcellentReadings++
                if (successiveExcellentReadings >= 3) {
                    val targetIndex = steps.indexOf(targetMaxBitrate).coerceAtLeast(0)
                    newIndex = (currentIndex + 1).coerceAtMost(targetIndex)
                    successiveExcellentReadings = 0
                }
            } else {
                successiveExcellentReadings = 0
            }
        }

        val newBitrate = steps[newIndex]
        if (newBitrate != currentDynamicBitrate) {
            currentDynamicBitrate = newBitrate
            webRtcClient.opusBitrateBps = newBitrate
            webRtcClient.applyAudioSettingsToActiveConnections()
            Log.d("CallService", "Dynamic Quality Adjustment: Bitrate adjusted to ${newBitrate / 1000.0} kbps (Ping: ${ping}ms, Loss: ${loss}%)")
            broadcastSettings()
        }
    }

    fun startHostSignaling(port: Int = 8080) {
        _isConnecting.value = false
        _isReconnecting.value = false
        myPeerId = "HOST"
        scope.launch {
            initDeferred.await()
            if (signalingServer != null) {
                Log.d("CallService", "Signaling Server already running")
                return@launch
            }

            isHostSignaling = true

            val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
            val hostDisplayName = prefs.getString("display_name", Build.MODEL) ?: Build.MODEL
            val maxParts = if (!isMediaLobby || (isBidirectional && !isGroupVoice)) 2 else prefs.getInt("max_participants", 4)
            signalingServer = SignalingServer().apply {
                hostName = hostDisplayName
                maxParticipants = maxParts
            }
            signalingServer?.startServer(port)

            val hostSignalingCallback = object : WebRtcClient.SignalingCallback {
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

                override fun onIceConnectionStateChanged(
                    peerId: String,
                    state: PeerConnection.IceConnectionState
                ) {
                    val cb = this
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        showToast("Voice link unstable, renegotiating with client...")
                        scope.launch {
                            delay(300)
                            if (_rawParticipants.value.any { it.id == peerId }) {
                                webRtcClient.createPeerConnection(peerId, isHost = true, cb)
                                webRtcClient.createOffer(peerId, cb)
                            }
                        }
                    }
                }
            }

            fun setupPeerSession(senderId: String) {
                if (isMediaLobby && isBidirectional && !isGroupVoice) {
                    val peerIp = signalingServer?.peerIpAddresses?.get(senderId) ?: nanImpl.lastPeerIpv6
                    if (peerIp != null) {
                        startDirectUdpStreamer(peerIp)
                    } else {
                        Log.w("CallService", "Peer IP not yet in signaling map for $senderId; starting UDP listener for auto-discovery")
                        startDirectUdpStreamer("0.0.0.0")
                    }
                }

                if (!isMediaLobby) {
                    webRtcClient.createPeerConnection(senderId, isHost = true, hostSignalingCallback)
                    webRtcClient.createOffer(senderId, hostSignalingCallback)

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

            scope.launch {
                signalingServer?.incomingMessages?.collect { (senderId, json) ->
                    val type = json.optString("type")
                    when (type) {
                        "PONG" -> {
                            val timestamp = json.optLong("timestamp")
                            if (timestamp > 0L) {
                                val rtt = System.currentTimeMillis() - timestamp
                                peerPings[senderId] = rtt
                            }
                        }
                        "JOIN" -> {
                            Log.d("CallService", "Peer $senderId joined.")
                            setupPeerSession(senderId)
                        }
                        "REQUEST_OFFER" -> {
                            Log.d("CallService", "Peer $senderId requested fresh WebRTC OFFER")
                            showToast("Re-syncing audio channel with client...")
                            setupPeerSession(senderId)
                        }
                        "LEAVE" -> {
                            Log.d("CallService", "Peer $senderId left.")
                            webRtcClient.removePeerConnection(senderId)
                        }
                        "PARTICIPANTS_UPDATE" -> {
                            val array = json.getJSONArray("participants")
                            val list = mutableListOf<RawParticipant>()
                            for (i in 0 until array.length()) {
                                val p = array.getJSONObject(i)
                                list.add(
                                    RawParticipant(
                                        id = p.getString("id"),
                                        name = p.getString("name"),
                                        isHost = p.optBoolean("isHost", false)
                                    )
                                )
                            }
                            _rawParticipants.value = list
                        }
                        "ANSWER" -> {
                            if (!isMediaLobby) {
                                webRtcClient.handleRemoteAnswer(senderId, json.getString("sdp"))
                            }
                        }
                        "ICE" -> {
                            if (!isMediaLobby) {
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
                }
            }

            scope.launch {
                signalingServer?.incomingAudio?.collect { (_, audioData) ->
                    if (isMediaLobby) {
                        mediaStreamer?.playAudioData(audioData)
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
            if (isMediaLobby && isBidirectional && !isGroupVoice) {
                startDirectUdpStreamer(hostIp)
            }

            val prefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
            val deviceName = prefs.getString("display_name", Build.MODEL) ?: Build.MODEL
            signalingClient = SignalingClient(
                hostIp = hostIp,
                port = port,
                onStatusMessage = { msg -> showToast(msg) }
            )
            signalingClient?.connect(myPeerId, deviceName)

            var hasConnectedOnce = false
            var connectedTimestamp = 0L
            var handshakeWatchdogJob: Job? = null

            scope.launch {
                signalingClient?.connectionState?.collect { state ->
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            connectedTimestamp = System.currentTimeMillis()
                            _isConnecting.value = false
                            _isReconnecting.value = false

                            // Watchdog: ensure JOIN and WebRTC OFFER/ICE complete promptly after WS connect
                            handshakeWatchdogJob?.cancel()
                            handshakeWatchdogJob = scope.launch {
                                delay(2200L)
                                if (signalingClient?.connectionState?.value == ConnectionState.CONNECTED &&
                                    _rawParticipants.value.isEmpty()
                                ) {
                                    Log.w("CallService", "No PARTICIPANTS_UPDATE after 2.2s; re-sending JOIN")
                                    showToast("Syncing lobby state with host...")
                                    signalingClient?.sendJoinMessage(myPeerId, deviceName)
                                }

                                if (!isMediaLobby) {
                                    delay(2300L)
                                    val iceState = webRtcClient.getIceConnectionState("HOST")
                                    if (signalingClient?.connectionState?.value == ConnectionState.CONNECTED &&
                                        iceState != PeerConnection.IceConnectionState.CONNECTED &&
                                        iceState != PeerConnection.IceConnectionState.COMPLETED
                                    ) {
                                        Log.w("CallService", "WebRTC ICE not connected after 4.5s (state=$iceState); requesting fresh OFFER")
                                        showToast("Voice link slow to start, retrying audio handshake...")
                                        val reqOffer = JSONObject().apply {
                                            put("type", "REQUEST_OFFER")
                                            put("peerId", myPeerId)
                                        }
                                        signalingClient?.sendMessage(reqOffer)
                                    }
                                }
                            }
                        }
                        ConnectionState.CONNECTING,
                        ConnectionState.DISCONNECTED -> {
                            handshakeWatchdogJob?.cancel()
                            if (connectedTimestamp > 0L && (System.currentTimeMillis() - connectedTimestamp) > 3000L) {
                                hasConnectedOnce = true
                            }
                            if (hasConnectedOnce) {
                                _isConnecting.value = false
                                _isReconnecting.value = true
                            } else {
                                _isConnecting.value = true
                                _isReconnecting.value = false
                            }
                        }
                    }
                }
            }

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

                override fun onIceConnectionStateChanged(
                    peerId: String,
                    state: PeerConnection.IceConnectionState
                ) {
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        showToast("Voice stream interrupted, re-establishing audio...")
                        val reqOffer = JSONObject().apply {
                            put("type", "REQUEST_OFFER")
                            put("peerId", myPeerId)
                        }
                        signalingClient?.sendMessage(reqOffer)
                    }
                }
            }

            scope.launch {
                signalingClient?.incomingMessages?.collect { json ->
                    val type = json.optString("type")
                    if (type == "PONG") {
                        val timestamp = json.optLong("timestamp")
                        if (timestamp > 0L) {
                            val rtt = System.currentTimeMillis() - timestamp
                            peerPings["HOST"] = rtt
                        }
                        return@collect
                    }
                    if (type == "QUALITIES_UPDATE") {
                        val array = json.getJSONArray("qualities")
                        val currentParts = _rawParticipants.value
                        val newQualities = _peerQualities.value.toMutableMap()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            val peerId = obj.getString("peerId")
                            if (peerId == myPeerId) continue

                            val part = currentParts.find { it.id == peerId }
                            val peerDeviceName = part?.name ?: "Unknown Device"
                            val isHost = part?.isHost ?: false

                            val ping = obj.getLong("pingMs")
                            val loss = obj.getDouble("packetLossRate")
                            val bRate = obj.optInt("bitrateBps", -1)

                            newQualities[peerId] = PeerQuality(
                                peerId = peerId,
                                deviceName = peerDeviceName,
                                pingMs = if (ping >= 0) ping else null,
                                packetLossRate = if (loss >= 0) loss.toFloat() else null,
                                isHost = isHost,
                                bitrateBps = if (bRate >= 0) bRate else null
                            )
                        }
                        _peerQualities.value = newQualities
                        return@collect
                    }
                    if (type == "REJECT") {
                        val reason = json.optString("reason", "Lobby is full")
                        Log.w("CallService", "Rejected by Host: $reason")
                        showToast("Connection failed: $reason")
                        sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED").setPackage(packageName))
                        endCall()
                        return@collect
                    }

                    if (type == "SESSION_CLOSED") {
                        Log.d("CallService", "Host closed the session. Ending call.")
                        showToast("Host ended the lobby")
                        sendBroadcast(Intent("com.tylerhats.proxitap.CALL_ENDED").setPackage(packageName))
                        endCall()
                        return@collect
                    }

                    if (json.has("target") && json.getString("target") != myPeerId) return@collect

                    when (type) {
                        "PARTICIPANTS_UPDATE" -> {
                            val array = json.getJSONArray("participants")
                            val list = mutableListOf<RawParticipant>()
                            for (i in 0 until array.length()) {
                                val p = array.getJSONObject(i)
                                list.add(
                                    RawParticipant(
                                        id = p.getString("id"),
                                        name = p.getString("name"),
                                        isHost = p.optBoolean("isHost", false)
                                    )
                                )
                            }
                            _rawParticipants.value = list
                        }
                        "SETTINGS" -> {
                            val bitrate = json.optInt("opusBitrate", 64000)
                            val dtx = json.optBoolean("opusDtx", true)
                            val dtxThresh = json.optDouble("dtxThreshold", 50.0).toFloat()
                            val ns = json.optBoolean("nsEnabled", true)
                            val aec = json.optBoolean("aecEnabled", true)
                            val groupSampleRate = json.optInt("groupSampleRate", 16000)
                            Log.d("CallService", "Received synced settings from Host: Bitrate=$bitrate DTX=$dtx Threshold=$dtxThresh SampleRate=$groupSampleRate")

                            val localPrefs = getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)
                            val oldSampleRate = localPrefs.getInt("group_sample_rate", 16000)
                            val oldNs = localPrefs.getBoolean("ns_enabled", true)
                            val oldAec = localPrefs.getBoolean("aec_enabled", true)

                            val sampleRateChanged = oldSampleRate != groupSampleRate
                            val nsChanged = oldNs != ns
                            val aecChanged = oldAec != aec

                            localPrefs.unregisterOnSharedPreferenceChangeListener(this@CallService)
                            localPrefs.edit()
                                .putInt("opus_bitrate", bitrate)
                                .putBoolean("opus_dtx", dtx)
                                .putFloat("dtx_threshold", dtxThresh)
                                .putBoolean("ns_enabled", ns)
                                .putBoolean("aec_enabled", aec)
                                .putInt("group_sample_rate", groupSampleRate)
                                .apply()
                            localPrefs.registerOnSharedPreferenceChangeListener(this@CallService)

                            if (!isMediaLobby) {
                                webRtcClient.opusBitrateBps = bitrate
                                webRtcClient.opusDtxEnabled = dtx
                                webRtcClient.isNoiseSuppressionEnabled = ns
                                webRtcClient.isAcousticEchoCancellationEnabled = aec

                                webRtcClient.applyAudioSettingsToActiveConnections()
                                webRtcClient.updateAudioConstraints(ns, aec)
                            } else if (isGroupVoice) {
                                if (sampleRateChanged || nsChanged || aecChanged) {
                                    restartMediaStreamerForGroupVoice(groupSampleRate)
                                }
                            }
                        }
                        "OFFER" -> {
                            if (!isMediaLobby) {
                                val senderId = "HOST"
                                webRtcClient.createPeerConnection(senderId, isHost = false, signalingCallback)
                                webRtcClient.handleRemoteOffer(senderId, json.getString("sdp"), signalingCallback)
                            }
                        }
                        "ICE" -> {
                            if (!isMediaLobby) {
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
            try {
                kotlinx.coroutines.runBlocking {
                    signalingClient?.sendMessageSuspend(msg)
                }
            } catch (e: Exception) {
                Log.e("CallService", "Failed to send LEAVE message", e)
            }
        }

        _isSpeaking.value = false
        _rawParticipants.value = emptyList()
        _participants.value = emptyList()
        _connectionStats.value = emptyMap()
        _hasJoined.value = false
        _isConnecting.value = true
        _isReconnecting.value = false
        while (outgoingAudioChannel.tryReceive().isSuccess) { /* drain */ }
        callStartTime = 0L
        smoothedPings.clear()
        smoothedLosses.clear()
        successiveExcellentReadings = 0
        myPeerId = UUID.randomUUID().toString()

        if (::audioManagerCompat.isInitialized) {
            try {
                audioManagerCompat.stopCallMode()
            } catch (e: Exception) {
                Log.e("CallService", "Error stopping audioManagerCompat", e)
            }
        }

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

        try {
            scope.cancel()
        } catch (e: Exception) {
            Log.e("CallService", "Error cancelling scope", e)
        }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        if (::webRtcClient.isInitialized) {
            try {
                webRtcClient.dispose()
            } catch (e: Exception) {
                Log.e("CallService", "Error disposing webRtcClient", e)
            }
        }

        try {
            udpProxy?.stop()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping udpProxy", e)
        }
        udpProxy = null

        try {
            directUdpStreamer?.stop()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping directUdpStreamer", e)
        }
        directUdpStreamer = null

        try {
            mediaStreamer?.stop()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping mediaStreamer", e)
        }
        mediaStreamer = null

        try {
            signalingServer?.stopServer()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping signalingServer", e)
        }
        signalingServer = null

        try {
            signalingClient?.disconnect()
        } catch (e: Exception) {
            Log.e("CallService", "Error disconnecting signalingClient", e)
        }
        signalingClient = null

        try {
            hardwarePttManager?.stop()
            hardwarePttManager?.release()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping hardwarePttManager", e)
        }
        hardwarePttManager = null

        try {
            nanImpl.stop()
            hotspotImpl.stop()
        } catch (e: Exception) {
            Log.e("CallService", "Error stopping network managers", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Thread {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CallService", "Service Destroyed")
        endCall()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "proxitap_call_channel",
                "ProxiTap Active Call",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps the microphone active for Walkie-Talkie features"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingAppIntent = android.app.PendingIntent.getActivity(
            this, 0, appIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

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
            .setWhen(callStartTime)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }
}
