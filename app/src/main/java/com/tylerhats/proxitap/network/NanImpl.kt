package com.tylerhats.proxitap.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NanImpl(private val context: Context) : LocalNetworkManager {

    private val wifiAwareManager: WifiAwareManager? =
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peerNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val hostPeerCallbacks = ConcurrentHashMap<PeerHandle, ConnectivityManager.NetworkCallback>()
    private val hostPeerRequestTimestamps = ConcurrentHashMap<PeerHandle, Long>()
    private val establishedPeers = ConcurrentHashMap.newKeySet<PeerHandle>()

    @Volatile
    private var localHostIpv6: String? = null

    @Volatile
    var lastPeerIpv6: String? = null
        private set

    private val DISCOVERY_SERVICE_NAME = "ProxiTap_Discovery"
    private var currentSessionId: String = ""
    private var currentPin: String = ""
    private var connectionRetryHandler: Handler? = null
    private var connectionRetryRunnable: Runnable? = null
    private var discoveryWatchdogRunnable: Runnable? = null
    private var ndpWatchdogRunnable: Runnable? = null

    private val _connectedPeers = MutableStateFlow<List<PeerHandle>>(emptyList())
    val connectedPeers: StateFlow<List<PeerHandle>> = _connectedPeers.asStateFlow()
    val peerIpToHandleMap = ConcurrentHashMap<String, PeerHandle>()

    private var lastToastMessage: String = ""
    private var lastToastTime: Long = 0L

    private fun showToast(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && now - lastToastTime < 2500L) return
        lastToastMessage = message
        lastToastTime = now
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("NanImpl", "Failed to show toast: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startHosting(
        lobbyName: String,
        pin: String,
        isMediaLobby: Boolean,
        isBidirectional: Boolean,
        isGroupVoice: Boolean
    ): String = suspendCancellableCoroutine { continuation ->
        stop()
        if (wifiAwareManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            showToast("Wi-Fi Aware is not supported on this device")
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported on this device"))
            return@suspendCancellableCoroutine
        }

        if (!wifiAwareManager.isAvailable) {
            showToast("Wi-Fi Aware is disabled. Please enable Wi-Fi and Location.")
            continuation.resumeWithException(Exception("Wi-Fi Aware is currently disabled by the system"))
            return@suspendCancellableCoroutine
        }

        currentSessionId = UUID.randomUUID().toString().take(8)
        currentPin = pin
        val serviceInfo =
            "$lobbyName|${if (pin.isNotBlank()) 1 else 0}|$currentSessionId|${if (isMediaLobby) 1 else 0}|${if (isBidirectional) 1 else 0}|${if (isGroupVoice) 1 else 0}"

        var attempt = 0
        val maxAttempts = 4

        fun attemptHostSetup() {
            if (!continuation.isActive) return
            attempt++
            Log.d("NanImpl", "Starting NAN Host setup (attempt $attempt/$maxAttempts)")

            try {
                publishSession?.close()
            } catch (e: Exception) {}
            publishSession = null
            try {
                awareSession?.close()
            } catch (e: Exception) {}
            awareSession = null

            var attemptCompleted = false
            val timeoutRunnable = Runnable {
                if (!attemptCompleted && continuation.isActive) {
                    attemptCompleted = true
                    Log.w("NanImpl", "NAN Host setup timed out on attempt $attempt")
                    if (attempt < maxAttempts) {
                        showToast("Wi-Fi Aware busy, retrying lobby setup ($attempt/$maxAttempts)...")
                        mainHandler.postDelayed({ attemptHostSetup() }, 600L)
                    } else {
                        showToast("Could not start Wi-Fi Aware lobby after $maxAttempts attempts")
                        continuation.resumeWithException(Exception("Timed out starting Wi-Fi Aware lobby"))
                    }
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 5000L)

            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (attemptCompleted || !continuation.isActive) {
                        try { session.close() } catch (e: Exception) {}
                        return
                    }
                    awareSession = session

                    val config = PublishConfig.Builder()
                        .setServiceName(DISCOVERY_SERVICE_NAME)
                        .setServiceSpecificInfo(serviceInfo.toByteArray())
                        .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                        .build()

                    session.publish(config, object : DiscoverySessionCallback() {
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                            if (attemptCompleted) return
                            attemptCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            publishSession = session
                            Log.d("NanImpl", "NAN Publish Started: $DISCOVERY_SERVICE_NAME")
                            val qrPayload = "PROXI:NAN:S:$serviceInfo"
                            if (continuation.isActive) {
                                continuation.resume(qrPayload)
                            }
                        }

                        override fun onSessionConfigFailed() {
                            if (attemptCompleted) return
                            attemptCompleted = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            Log.e("NanImpl", "NAN Publish config failed on attempt $attempt")
                            if (attempt < maxAttempts && continuation.isActive) {
                                showToast("Lobby publish busy, retrying ($attempt/$maxAttempts)...")
                                mainHandler.postDelayed({ attemptHostSetup() }, 600L)
                            } else if (continuation.isActive) {
                                showToast("Failed to publish Wi-Fi Aware lobby")
                                continuation.resumeWithException(Exception("Failed to publish Wi-Fi Aware session"))
                            }
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            val msgStr = String(message)
                            Log.d("NanImpl", "Host received message from peer: $msgStr")
                            when (msgStr) {
                                "CONNECT" -> acceptPeerConnection(peerHandle, forceReset = false)
                                "RESET_NDP" -> {
                                    showToast("Peer retrying data link, resetting host channel...")
                                    acceptPeerConnection(peerHandle, forceReset = true)
                                }
                            }
                        }

                        override fun onMessageSendSucceeded(messageId: Int) {
                            Log.d("NanImpl", "Host message send succeeded: id=$messageId")
                        }

                        override fun onMessageSendFailed(messageId: Int) {
                            Log.e("NanImpl", "Host message send failed: id=$messageId")
                        }
                    }, mainHandler)
                }

                override fun onAttachFailed() {
                    if (attemptCompleted) return
                    attemptCompleted = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e("NanImpl", "NAN Attach failed on attempt $attempt")
                    if (attempt < maxAttempts && continuation.isActive) {
                        showToast("Wi-Fi Aware attach failed, retrying ($attempt/$maxAttempts)...")
                        mainHandler.postDelayed({ attemptHostSetup() }, 600L)
                    } else if (continuation.isActive) {
                        showToast("Failed to attach to Wi-Fi Aware")
                        continuation.resumeWithException(Exception("Failed to attach to Wi-Fi Aware session"))
                    }
                }
            }, mainHandler)
        }

        attemptHostSetup()
    }

    fun stopDiscovery() {
        Log.d("NanImpl", "Stopping passive NAN discovery session")
        try {
            subscribeSession?.close()
        } catch (e: Exception) {}
        subscribeSession = null
    }

    override fun stop() {
        Log.d("NanImpl", "Stopping NAN Session...")
        stopConnectionRetryLoop()
        discoveryWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryWatchdogRunnable = null
        ndpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        ndpWatchdogRunnable = null

        try { publishSession?.close() } catch (e: Exception) {}
        try { subscribeSession?.close() } catch (e: Exception) {}
        try { awareSession?.close() } catch (e: Exception) {}
        publishSession = null
        subscribeSession = null
        awareSession = null

        peerNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        peerNetworkCallback = null

        hostPeerCallbacks.values.forEach { cb ->
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {}
        }
        hostPeerCallbacks.clear()
        hostPeerRequestTimestamps.clear()
        establishedPeers.clear()

        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (e: Exception) {}
        localHostIpv6 = null
        lastPeerIpv6 = null
        _connectedPeers.value = emptyList()
        peerIpToHandleMap.clear()
    }

    @SuppressLint("MissingPermission")
    override fun discoverLobbies(onLobbyFound: (String, Boolean, Boolean, Boolean, String) -> Unit) {
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable) return
        // Do not interrupt an active host publish session
        if (publishSession != null) return

        val startSubscribe: (WifiAwareSession) -> Unit = { session ->
            try {
                subscribeSession?.close()
            } catch (e: Exception) {}
            subscribeSession = null

            val config = SubscribeConfig.Builder()
                .setServiceName(DISCOVERY_SERVICE_NAME)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .build()

            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    Log.d("NanImpl", "Passive NAN Subscribe Started for Discovery")
                }

                override fun onSessionConfigFailed() {
                    Log.w("NanImpl", "Passive NAN Subscribe config failed, resetting session")
                    try { awareSession?.close() } catch (e: Exception) {}
                    awareSession = null
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    val infoString = String(serviceSpecificInfo)
                    val parts = infoString.split("|")
                    if (parts.size >= 5) {
                        val lobbyName = parts[0]
                        val isProtected = parts[1] == "1"
                        val isMedia = parts[3] == "1"
                        val isBidi = parts[4] == "1"
                        Log.d("NanImpl", "Lobby Discovered: $lobbyName (Protected: $isProtected, Media: $isMedia)")
                        onLobbyFound(lobbyName, isProtected, isMedia, isBidi, infoString)
                    } else if (parts.size >= 3) {
                        val lobbyName = parts[0]
                        val isProtected = parts[1] == "1"
                        Log.d("NanImpl", "Lobby Discovered: $lobbyName (Protected: $isProtected)")
                        onLobbyFound(lobbyName, isProtected, false, false, infoString)
                    }
                }
            }, mainHandler)
        }

        val existingSession = awareSession
        if (existingSession != null) {
            startSubscribe(existingSession)
        } else {
            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    startSubscribe(session)
                }

                override fun onAttachFailed() {
                    Log.w("NanImpl", "Passive discovery attach failed")
                }
            }, mainHandler)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun joinLobby(payload: String, pin: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            stop()
            if (wifiAwareManager == null) {
                showToast("Wi-Fi Aware is not supported on this device")
                continuation.resumeWithException(Exception("Wi-Fi Aware not supported"))
                return@suspendCancellableCoroutine
            }

            if (!payload.startsWith("PROXI:NAN:S:")) {
                continuation.resumeWithException(Exception("Invalid QR Code payload for NAN"))
                return@suspendCancellableCoroutine
            }

            val targetServiceInfo = payload.removePrefix("PROXI:NAN:S:")
            val targetParts = targetServiceInfo.split("|")
            val targetLobbyName = targetParts.getOrNull(0) ?: ""
            val targetSessionId = targetParts.getOrNull(2) ?: ""
            currentPin = pin

            var joinCompleted = false
            var ndpRetryCount = 0
            val maxNdpRetries = 5
            var discoveredPeerHandle: PeerHandle? = null

            fun startSubscribeAndHandshake() {
                try {
                    subscribeSession?.close()
                } catch (e: Exception) {}
                subscribeSession = null

                val runSubscribe = { session: WifiAwareSession ->
                    val config = SubscribeConfig.Builder()
                        .setServiceName(DISCOVERY_SERVICE_NAME)
                        .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                        .build()

                    discoveryWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                    val watchdog = object : Runnable {
                        override fun run() {
                            if (!joinCompleted && discoveredPeerHandle == null && continuation.isActive) {
                                showToast("Searching for host lobby, refreshing Wi-Fi Aware discovery...")
                                Log.w("NanImpl", "Target lobby not discovered yet, refreshing subscribe session")
                                startSubscribeAndHandshake()
                            }
                        }
                    }
                    discoveryWatchdogRunnable = watchdog
                    mainHandler.postDelayed(watchdog, 4500L)

                    session.subscribe(config, object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(subSession: SubscribeDiscoverySession) {
                            subscribeSession = subSession
                            Log.d("NanImpl", "Targeted NAN Subscribe Started for: $targetServiceInfo")
                        }

                        override fun onSessionConfigFailed() {
                            Log.e("NanImpl", "Targeted NAN Subscribe failed, retrying...")
                            if (!joinCompleted && continuation.isActive) {
                                showToast("Discovery busy, retrying lobby search...")
                                try { awareSession?.close() } catch (e: Exception) {}
                                awareSession = null
                                mainHandler.postDelayed({ startSubscribeAndHandshake() }, 500L)
                            }
                        }

                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val infoString = String(serviceSpecificInfo)
                            val parts = infoString.split("|")
                            val sessionIdMatch = targetSessionId.isNotEmpty() && parts.getOrNull(2) == targetSessionId
                            val lobbyNameMatch = infoString == targetServiceInfo || (sessionIdMatch && parts.getOrNull(0) == targetLobbyName)

                            if (lobbyNameMatch) {
                                discoveryWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                                val isNewHandle = discoveredPeerHandle != peerHandle
                                discoveredPeerHandle = peerHandle
                                if (peerNetworkCallback == null && !joinCompleted) {
                                    Log.d("NanImpl", "Target Lobby Discovered! Sending connection request to Host")
                                    startConnectionRetryLoop(peerHandle)
                                } else if (joinCompleted && peerNetworkCallback == null && isNewHandle) {
                                    Log.d("NanImpl", "Re-discovered Host after mid-call drop, re-requesting connection")
                                    startConnectionRetryLoop(peerHandle)
                                }
                            }
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            val msgStr = String(message)
                            Log.d("NanImpl", "Peer received message from host: $msgStr")
                            if (msgStr == "ACCEPT") {
                                discoveryWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                                stopConnectionRetryLoop()
                                discoveredPeerHandle = peerHandle
                                Log.d("NanImpl", "Host accepted connection! Requesting Data Path...")
                                requestDataPathAsPeer(
                                    peerHandle = peerHandle,
                                    continuation = continuation,
                                    isInitialJoin = !joinCompleted,
                                    onSuccess = {
                                        joinCompleted = true
                                        ndpRetryCount = 0
                                    },
                                    onFailure = { reason ->
                                        if (!joinCompleted) {
                                            ndpRetryCount++
                                            if (ndpRetryCount <= maxNdpRetries && continuation.isActive) {
                                                showToast("Connection attempt didn't complete ($reason), retrying ($ndpRetryCount/$maxNdpRetries)...")
                                                try {
                                                    subscribeSession?.sendMessage(peerHandle, 99, "RESET_NDP".toByteArray())
                                                } catch (e: Exception) {}
                                                mainHandler.postDelayed({
                                                    if (!joinCompleted && continuation.isActive) {
                                                        startConnectionRetryLoop(peerHandle)
                                                    }
                                                }, 450L)
                                            } else if (continuation.isActive) {
                                                showToast("Unable to establish Wi-Fi Aware link after $maxNdpRetries retries")
                                                continuation.resume(false)
                                            }
                                        } else {
                                            // Mid-call link loss recovery
                                            showToast("Wi-Fi Aware link interrupted, reconnecting to host...")
                                            mainHandler.postDelayed({
                                                val h = discoveredPeerHandle
                                                if (h != null && subscribeSession != null) {
                                                    try {
                                                        subscribeSession?.sendMessage(h, 99, "RESET_NDP".toByteArray())
                                                    } catch (e: Exception) {}
                                                    startConnectionRetryLoop(h)
                                                } else {
                                                    startSubscribeAndHandshake()
                                                }
                                            }, 500L)
                                        }
                                    }
                                )
                            }
                        }

                        override fun onMessageSendSucceeded(messageId: Int) {
                            Log.d("NanImpl", "Active subscribe message send succeeded: id=$messageId")
                        }

                        override fun onMessageSendFailed(messageId: Int) {
                            Log.w("NanImpl", "Active subscribe message send failed: id=$messageId")
                        }
                    }, mainHandler)
                }

                val currentAware = awareSession
                if (currentAware == null) {
                    wifiAwareManager.attach(object : AttachCallback() {
                        override fun onAttached(session: WifiAwareSession) {
                            awareSession = session
                            runSubscribe(session)
                        }

                        override fun onAttachFailed() {
                            Log.e("NanImpl", "Peer attach failed, retrying...")
                            if (!joinCompleted && continuation.isActive) {
                                showToast("Wi-Fi Aware busy, retrying connection...")
                                mainHandler.postDelayed({ startSubscribeAndHandshake() }, 600L)
                            }
                        }
                    }, mainHandler)
                } else {
                    runSubscribe(currentAware)
                }
            }

            startSubscribeAndHandshake()
        }

    @SuppressLint("MissingPermission")
    private fun acceptPeerConnection(peerHandle: PeerHandle, forceReset: Boolean) {
        val session = publishSession ?: run {
            Log.e("NanImpl", "acceptPeerConnection failed: publishSession is null")
            return
        }

        // Clean up any stale un-established callbacks for other handles or if forceReset is requested
        val existingCb = hostPeerCallbacks[peerHandle]
        val requestAge = System.currentTimeMillis() - (hostPeerRequestTimestamps[peerHandle] ?: 0L)
        if (existingCb != null) {
            if (forceReset || (!establishedPeers.contains(peerHandle) && requestAge > 3500L)) {
                Log.d("NanImpl", "Resetting existing responder NetworkCallback for peerHandle=$peerHandle (force=$forceReset, age=${requestAge}ms)")
                try {
                    connectivityManager.unregisterNetworkCallback(existingCb)
                } catch (e: Exception) {}
                hostPeerCallbacks.remove(peerHandle)
                establishedPeers.remove(peerHandle)
            } else {
                Log.d("NanImpl", "Responder NetworkRequest already active for peerHandle=$peerHandle. Resending ACCEPT.")
                try {
                    session.sendMessage(peerHandle, 2, "ACCEPT".toByteArray())
                } catch (e: Exception) {
                    Log.e("NanImpl", "Failed to resend ACCEPT message", e)
                }
                return
            }
        }

        // Also clean up any other un-established responder callbacks that might block the NAN interface
        hostPeerCallbacks.entries.toList().forEach { (handle, cb) ->
            if (handle != peerHandle && !establishedPeers.contains(handle)) {
                try {
                    connectivityManager.unregisterNetworkCallback(cb)
                } catch (e: Exception) {}
                hostPeerCallbacks.remove(handle)
                hostPeerRequestTimestamps.remove(handle)
            }
        }

        val builder = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
        if (currentPin.isNotBlank()) {
            builder.setPmk(currentPin.padEnd(32, '0').take(32).toByteArray())
        }
        val networkSpecifier = builder.build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                awareInfo?.peerIpv6Addr?.let { ipv6 ->
                    val ip = ipv6.hostAddress ?: return@let
                    lastPeerIpv6 = ip
                    val cleanIp = ip.split('%').first()
                    Log.d("NanImpl", "Host mapped peer IP: $ip (clean: $cleanIp) to PeerHandle: $peerHandle")
                    peerIpToHandleMap[cleanIp] = peerHandle
                }
            }

            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Host Data Path Established with Peer!")
                establishedPeers.add(peerHandle)
                connectivityManager.bindProcessToNetwork(network)
                if (!_connectedPeers.value.contains(peerHandle)) {
                    _connectedPeers.value = _connectedPeers.value + peerHandle
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w("NanImpl", "Host Data Path LOST for peer $peerHandle.")
                val wasEstablished = establishedPeers.remove(peerHandle)
                if (wasEstablished) {
                    showToast("Peer Wi-Fi Aware link dropped, waiting for automatic reconnect...")
                }
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                hostPeerCallbacks.remove(peerHandle, this)
                hostPeerRequestTimestamps.remove(peerHandle)
                _connectedPeers.value = _connectedPeers.value - peerHandle
                if (establishedPeers.isEmpty()) {
                    try {
                        connectivityManager.bindProcessToNetwork(null)
                    } catch (e: Exception) {}
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("NanImpl", "Host Data Path UNAVAILABLE for peer $peerHandle.")
                showToast("Client connection interrupted, ready for automatic retry...")
                establishedPeers.remove(peerHandle)
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                hostPeerCallbacks.remove(peerHandle, this)
                hostPeerRequestTimestamps.remove(peerHandle)
            }
        }

        hostPeerCallbacks[peerHandle] = callback
        hostPeerRequestTimestamps[peerHandle] = System.currentTimeMillis()
        connectivityManager.requestNetwork(networkRequest, callback)

        // Watchdog on Host: if NDP does not complete within 8s, clean up responder callback so retries work cleanly
        mainHandler.postDelayed({
            if (!establishedPeers.contains(peerHandle) && hostPeerCallbacks[peerHandle] === callback) {
                Log.w("NanImpl", "Host NDP responder timed out waiting for peer $peerHandle; resetting callback")
                showToast("Peer connection timed out, resetting channel for retry...")
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {}
                hostPeerCallbacks.remove(peerHandle, callback)
                hostPeerRequestTimestamps.remove(peerHandle)
            }
        }, 8000L)

        // Delay ACCEPT slightly so Host's responder NetworkRequest is registered in firmware before Peer initiates NDP
        mainHandler.postDelayed({
            try {
                session.sendMessage(peerHandle, 2, "ACCEPT".toByteArray())
            } catch (e: Exception) {
                Log.e("NanImpl", "Failed to send ACCEPT message", e)
            }
        }, 100L)

        // Backup ACCEPT in case the first NAN action frame is dropped over the air
        mainHandler.postDelayed({
            if (!establishedPeers.contains(peerHandle) && hostPeerCallbacks.containsKey(peerHandle)) {
                try {
                    session.sendMessage(peerHandle, 3, "ACCEPT".toByteArray())
                } catch (e: Exception) {}
            }
        }, 450L)
    }

    @SuppressLint("MissingPermission")
    private fun requestDataPathAsPeer(
        peerHandle: PeerHandle,
        continuation: CancellableContinuation<Boolean>,
        isInitialJoin: Boolean,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (peerNetworkCallback != null) {
            Log.d("NanImpl", "requestDataPathAsPeer called, but network request is already active.")
            return
        }
        val session = subscribeSession ?: run {
            Log.e("NanImpl", "requestDataPathAsPeer failed: subscribeSession is null")
            onFailure("session reset")
            return
        }

        val builder = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
        if (currentPin.isNotBlank()) {
            builder.setPmk(currentPin.padEnd(32, '0').take(32).toByteArray())
        }
        val networkSpecifier = builder.build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        var callbackHandled = false

        val callback = object : ConnectivityManager.NetworkCallback() {
            var isAvailable = false
            var hasIpv6 = false

            private fun checkAndResume() {
                if (isAvailable && hasIpv6 && !callbackHandled) {
                    callbackHandled = true
                    ndpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                    ndpWatchdogRunnable = null
                    onSuccess()
                    if (isInitialJoin && continuation.isActive) {
                        continuation.resume(true)
                    } else if (!isInitialJoin) {
                        showToast("Wi-Fi Aware link restored")
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                awareInfo?.peerIpv6Addr?.let { ipv6 ->
                    localHostIpv6 = ipv6.hostAddress
                    lastPeerIpv6 = ipv6.hostAddress
                    Log.d("NanImpl", "Host IPv6 Discovered: $localHostIpv6")
                    hasIpv6 = true
                    checkAndResume()
                }
            }

            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Peer Data Path Established!")
                stopConnectionRetryLoop()
                connectivityManager.bindProcessToNetwork(network)
                if (!_connectedPeers.value.contains(peerHandle)) {
                    _connectedPeers.value = _connectedPeers.value + peerHandle
                }
                isAvailable = true
                checkAndResume()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w("NanImpl", "Peer Data Path LOST.")
                stopConnectionRetryLoop()
                ndpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                ndpWatchdogRunnable = null
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback === this) {
                    peerNetworkCallback = null
                }
                _connectedPeers.value = _connectedPeers.value - peerHandle
                if (!callbackHandled) {
                    callbackHandled = true
                    onFailure("link dropped")
                } else {
                    // Link dropped after it was already established
                    onFailure("connection lost")
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("NanImpl", "Peer Data Path UNAVAILABLE.")
                stopConnectionRetryLoop()
                ndpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                ndpWatchdogRunnable = null
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback === this) {
                    peerNetworkCallback = null
                }
                if (!callbackHandled) {
                    callbackHandled = true
                    onFailure("data path busy")
                }
            }
        }

        peerNetworkCallback = callback
        connectivityManager.requestNetwork(networkRequest, callback)

        // Watchdog in case requestNetwork hangs without calling onAvailable or onUnavailable
        ndpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            if (!callbackHandled && peerNetworkCallback === callback) {
                Log.w("NanImpl", "Peer NDP request timed out after 6.5s; resetting and retrying")
                callbackHandled = true
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {}
                peerNetworkCallback = null
                onFailure("timeout")
            }
        }
        ndpWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, 6500L)
    }

    private fun startConnectionRetryLoop(peerHandle: PeerHandle) {
        stopConnectionRetryLoop()
        val handler = Handler(Looper.getMainLooper())
        connectionRetryHandler = handler
        var sendCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (subscribeSession != null && peerNetworkCallback == null) {
                    sendCount++
                    if (sendCount == 4 || sendCount == 8) {
                        showToast("Waiting for host response, retrying handshake...")
                    }
                    Log.d("NanImpl", "Sending/retrying connection request to Host (attempt $sendCount)...")
                    try {
                        subscribeSession?.sendMessage(peerHandle, 1, "CONNECT".toByteArray())
                    } catch (e: Exception) {
                        Log.w("NanImpl", "Failed to send CONNECT message: ${e.message}")
                    }
                    handler.postDelayed(this, 850L)
                }
            }
        }
        connectionRetryRunnable = runnable
        handler.post(runnable)
    }

    private fun stopConnectionRetryLoop() {
        connectionRetryRunnable?.let { connectionRetryHandler?.removeCallbacks(it) }
        connectionRetryRunnable = null
        connectionRetryHandler = null
    }

    override fun getLocalIpAddress(): String? {
        return localHostIpv6
    }
}
