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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NanImpl(private val context: Context) : LocalNetworkManager {

    private val wifiAwareManager: WifiAwareManager? = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peerNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var localHostIpv6: String? = null

    private val DISCOVERY_SERVICE_NAME = "ProxiTap_Discovery"
    private var currentSessionId: String = ""
    private var currentPin: String = ""
    private var connectionRetryHandler: Handler? = null
    private var connectionRetryRunnable: Runnable? = null

    private val _connectedPeers = MutableStateFlow<List<PeerHandle>>(emptyList())
    val connectedPeers: StateFlow<List<PeerHandle>> = _connectedPeers.asStateFlow()
    val peerIpToHandleMap = java.util.concurrent.ConcurrentHashMap<String, PeerHandle>()

    @SuppressLint("MissingPermission")
    override suspend fun startHosting(lobbyName: String, pin: String, isMediaLobby: Boolean, isBidirectional: Boolean, isGroupVoice: Boolean): String = suspendCancellableCoroutine { continuation ->
        stop()
        if (wifiAwareManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported on this device"))
            return@suspendCancellableCoroutine
        }

        if (!wifiAwareManager.isAvailable) {
            continuation.resumeWithException(Exception("Wi-Fi Aware is currently disabled by the system"))
            return@suspendCancellableCoroutine
        }

        currentSessionId = UUID.randomUUID().toString().take(8)
        currentPin = pin
        val serviceInfo = "$lobbyName|${if(pin.isNotBlank()) 1 else 0}|$currentSessionId|${if(isMediaLobby) 1 else 0}|${if(isBidirectional) 1 else 0}|${if(isGroupVoice) 1 else 0}"

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session
                
                val config = PublishConfig.Builder()
                    .setServiceName(DISCOVERY_SERVICE_NAME)
                    .setServiceSpecificInfo(serviceInfo.toByteArray())
                    .build()

                session.publish(config, object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession = session
                        Log.d("NanImpl", "NAN Publish Started: $DISCOVERY_SERVICE_NAME")
                        val qrPayload = "PROXI:NAN:S:$serviceInfo"
                        continuation.resume(qrPayload)
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        val msgStr = String(message)
                        Log.d("NanImpl", "Host received message from peer: $msgStr")
                        if (msgStr == "CONNECT") {
                            acceptPeerConnection(peerHandle)
                        }
                    }

                    override fun onMessageSendSucceeded(messageId: Int) {
                        Log.d("NanImpl", "Host message send succeeded: id=$messageId")
                    }

                    override fun onMessageSendFailed(messageId: Int) {
                        Log.e("NanImpl", "Host message send failed: id=$messageId")
                    }
                }, Handler(Looper.getMainLooper()))
            }

            override fun onAttachFailed() {
                continuation.resumeWithException(Exception("Failed to attach to Wi-Fi Aware session"))
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun stop() {
        Log.d("NanImpl", "Stopping NAN Session...")
        stopConnectionRetryLoop()
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null

        peerNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        peerNetworkCallback = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (e: Exception) {}
        localHostIpv6 = null
        _connectedPeers.value = emptyList()
        peerIpToHandleMap.clear()
    }

    @SuppressLint("MissingPermission")
    override fun discoverLobbies(onLobbyFound: (String, Boolean, Boolean, Boolean, String) -> Unit) {
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable) return

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session

                val config = SubscribeConfig.Builder()
                    .setServiceName(DISCOVERY_SERVICE_NAME)
                    .build()

                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribeSession = session
                        Log.d("NanImpl", "Passive NAN Subscribe Started for Discovery")
                    }

                    override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
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
                            // Backwards compatibility
                            val lobbyName = parts[0]
                            val isProtected = parts[1] == "1"
                            Log.d("NanImpl", "Lobby Discovered: $lobbyName (Protected: $isProtected)")
                            onLobbyFound(lobbyName, isProtected, false, false, infoString)
                        }
                    }

                    override fun onMessageSendSucceeded(messageId: Int) {
                        Log.d("NanImpl", "Passive subscribe message send succeeded: id=$messageId")
                    }

                    override fun onMessageSendFailed(messageId: Int) {
                        Log.e("NanImpl", "Passive subscribe message send failed: id=$messageId")
                    }
                }, Handler(Looper.getMainLooper()))
            }
            override fun onAttachFailed() {}
        }, Handler(Looper.getMainLooper()))
    }

    @SuppressLint("MissingPermission")
    override suspend fun joinLobby(payload: String, pin: String): Boolean = suspendCancellableCoroutine { continuation ->
        stop()
        if (wifiAwareManager == null) {
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported"))
            return@suspendCancellableCoroutine
        }

        if (!payload.startsWith("PROXI:NAN:S:")) {
            continuation.resumeWithException(Exception("Invalid QR Code payload for NAN"))
            return@suspendCancellableCoroutine
        }

        val targetServiceInfo = payload.removePrefix("PROXI:NAN:S:")
        currentPin = pin

        subscribeSession?.close()
        
        val doSubscribe = { session: WifiAwareSession ->
            val config = SubscribeConfig.Builder()
                .setServiceName(DISCOVERY_SERVICE_NAME)
                .build()

            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(subSession: SubscribeDiscoverySession) {
                    subscribeSession = subSession
                    Log.d("NanImpl", "Targeted NAN Subscribe Started for: $targetServiceInfo")
                }

                override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                    val infoString = String(serviceSpecificInfo)
                    if (infoString == targetServiceInfo) {
                        Log.d("NanImpl", "Target Lobby Discovered! Sending connection request to Host")
                        startConnectionRetryLoop(peerHandle)
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val msgStr = String(message)
                    Log.d("NanImpl", "Peer received message from host: $msgStr")
                    if (msgStr == "ACCEPT") {
                        Log.d("NanImpl", "Host accepted connection! Requesting Data Path...")
                        requestDataPathAsPeer(peerHandle, continuation)
                    }
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    Log.d("NanImpl", "Active subscribe message send succeeded: id=$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    Log.e("NanImpl", "Active subscribe message send failed: id=$messageId")
                }
            }, Handler(Looper.getMainLooper()))
        }

        if (awareSession == null) {
            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    doSubscribe(session)
                }
                override fun onAttachFailed() {
                    continuation.resumeWithException(Exception("Failed to attach to Wi-Fi Aware session"))
                }
            }, Handler(Looper.getMainLooper()))
        } else {
            doSubscribe(awareSession!!)
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptPeerConnection(peerHandle: PeerHandle) {
        val session = publishSession ?: run {
            Log.e("NanImpl", "acceptPeerConnection failed: publishSession is null")
            return
        }
        if (peerNetworkCallback != null) {
            Log.d("NanImpl", "Connection request received, but requestNetwork is already active. Resending ACCEPT.")
            try {
                session.sendMessage(peerHandle, 2, "ACCEPT".toByteArray())
            } catch (e: Exception) {
                Log.e("NanImpl", "Failed to resend ACCEPT message", e)
            }
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

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                awareInfo?.peerIpv6Addr?.let { ipv6 ->
                    val ip = ipv6.hostAddress ?: return@let
                    val cleanIp = ip.split('%').first()
                    Log.d("NanImpl", "Host mapped peer IP: $cleanIp to PeerHandle: $peerHandle")
                    peerIpToHandleMap[cleanIp] = peerHandle
                }
            }

            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Host Data Path Established with Peer!")
                connectivityManager.bindProcessToNetwork(network)
                _connectedPeers.value = _connectedPeers.value + peerHandle
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w("NanImpl", "Host Data Path LOST.")
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback == this) {
                    peerNetworkCallback = null
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("NanImpl", "Host Data Path UNAVAILABLE.")
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback == this) {
                    peerNetworkCallback = null
                }
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        peerNetworkCallback = callback

        try {
            session.sendMessage(peerHandle, 2, "ACCEPT".toByteArray())
        } catch (e: Exception) {
            Log.e("NanImpl", "Failed to send ACCEPT message", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDataPathAsPeer(peerHandle: PeerHandle, continuation: kotlinx.coroutines.CancellableContinuation<Boolean>) {
        if (peerNetworkCallback != null) {
            Log.d("NanImpl", "requestDataPathAsPeer called, but network request is already active.")
            return
        }
        val session = subscribeSession ?: run {
            Log.e("NanImpl", "requestDataPathAsPeer failed: subscribeSession is null")
            if (continuation.isActive) {
                continuation.resume(false)
            }
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

        val callback = object : ConnectivityManager.NetworkCallback() {
            var isAvailable = false
            var hasIpv6 = false

            private fun checkAndResume() {
                if (isAvailable && hasIpv6) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                awareInfo?.peerIpv6Addr?.let { ipv6 ->
                    localHostIpv6 = ipv6.hostAddress
                    Log.d("NanImpl", "Host IPv6 Discovered: $localHostIpv6")
                    hasIpv6 = true
                    checkAndResume()
                }
            }

            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Peer Data Path Established!")
                stopConnectionRetryLoop()
                connectivityManager.bindProcessToNetwork(network)
                _connectedPeers.value = _connectedPeers.value + peerHandle
                isAvailable = true
                checkAndResume()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w("NanImpl", "Peer Data Path LOST.")
                stopConnectionRetryLoop()
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback == this) {
                    peerNetworkCallback = null
                }
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("NanImpl", "Peer Data Path UNAVAILABLE.")
                stopConnectionRetryLoop()
                try {
                    connectivityManager.bindProcessToNetwork(null)
                } catch (e: Exception) {}
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) {}
                if (peerNetworkCallback == this) {
                    peerNetworkCallback = null
                }
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        peerNetworkCallback = callback
    }

    private fun startConnectionRetryLoop(peerHandle: PeerHandle) {
        stopConnectionRetryLoop()
        val handler = Handler(Looper.getMainLooper())
        connectionRetryHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (subscribeSession != null) {
                    Log.d("NanImpl", "Sending/retrying connection request to Host...")
                    subscribeSession?.sendMessage(peerHandle, 1, "CONNECT".toByteArray())
                    handler.postDelayed(this, 1000)
                }
            }
        }
        connectionRetryRunnable = runnable
        handler.post(runnable)
    }

    private fun stopConnectionRetryLoop() {
        Log.d("NanImpl", "Stopping connection retry loop")
        connectionRetryRunnable?.let { connectionRetryHandler?.removeCallbacks(it) }
        connectionRetryRunnable = null
        connectionRetryHandler = null
    }

    override fun getLocalIpAddress(): String? {
        return localHostIpv6
    }
}
