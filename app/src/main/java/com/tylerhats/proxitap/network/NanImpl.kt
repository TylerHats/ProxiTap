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

    private val _connectedPeers = MutableStateFlow<List<PeerHandle>>(emptyList())
    val connectedPeers: StateFlow<List<PeerHandle>> = _connectedPeers.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startHosting(lobbyName: String, hasPin: Boolean): String = suspendCancellableCoroutine { continuation ->
        if (wifiAwareManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported on this device"))
            return@suspendCancellableCoroutine
        }

        if (!wifiAwareManager.isAvailable) {
            continuation.resumeWithException(Exception("Wi-Fi Aware is currently disabled by the system"))
            return@suspendCancellableCoroutine
        }

        currentSessionId = UUID.randomUUID().toString().take(8)
        val serviceInfo = "$lobbyName|${if(hasPin) 1 else 0}|$currentSessionId"

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
                        Log.d("NanImpl", "Host received connection request from peer")
                        acceptPeerConnection(peerHandle)
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
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null

        peerNetworkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        peerNetworkCallback = null
        localHostIpv6 = null
        _connectedPeers.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    override fun discoverLobbies(onLobbyFound: (String, Boolean, String) -> Unit) {
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
                        if (parts.size >= 2) {
                            val lobbyName = parts[0]
                            val isProtected = parts[1] == "1"
                            Log.d("NanImpl", "Lobby Discovered: $lobbyName (Protected: $isProtected)")
                            onLobbyFound(lobbyName, isProtected, infoString)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            }
            override fun onAttachFailed() {}
        }, Handler(Looper.getMainLooper()))
    }

    @SuppressLint("MissingPermission")
    override suspend fun joinLobby(payload: String): Boolean = suspendCancellableCoroutine { continuation ->
        if (wifiAwareManager == null) {
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported"))
            return@suspendCancellableCoroutine
        }

        if (!payload.startsWith("PROXI:NAN:S:")) {
            continuation.resumeWithException(Exception("Invalid QR Code payload for NAN"))
            return@suspendCancellableCoroutine
        }

        val targetServiceInfo = payload.removePrefix("PROXI:NAN:S:")

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
                        subscribeSession?.sendMessage(peerHandle, 1, "CONNECT".toByteArray())
                        requestDataPathAsPeer(peerHandle, continuation)
                    }
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
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(publishSession!!, peerHandle)
            .setPmk("ProxiTapSecureKey123".toByteArray())
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Host Data Path Established with Peer!")
                connectivityManager.bindProcessToNetwork(network)
                _connectedPeers.value = _connectedPeers.value + peerHandle
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        peerNetworkCallback = callback
    }

    @SuppressLint("MissingPermission")
    private fun requestDataPathAsPeer(peerHandle: PeerHandle, continuation: kotlinx.coroutines.CancellableContinuation<Boolean>) {
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(subscribeSession!!, peerHandle)
            .setPmk("ProxiTapSecureKey123".toByteArray())
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                awareInfo?.peerIpv6Addr?.let { ipv6 ->
                    localHostIpv6 = ipv6.hostAddress
                    Log.d("NanImpl", "Host IPv6 Discovered: $localHostIpv6")
                }
            }

            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Peer Data Path Established!")
                connectivityManager.bindProcessToNetwork(network)
                _connectedPeers.value = _connectedPeers.value + peerHandle
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w("NanImpl", "Peer Data Path LOST. Entering Reconnect State...")
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        peerNetworkCallback = callback
    }

    override fun getLocalIpAddress(): String? {
        return localHostIpv6
    }
}
