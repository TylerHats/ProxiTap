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

    // We will generate a random service name for the session
    private val serviceNameBase = "ProxiTap_Lobby_"

    @SuppressLint("MissingPermission")
    override suspend fun startHosting(): String = suspendCancellableCoroutine { continuation ->
        if (wifiAwareManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            continuation.resumeWithException(Exception("Wi-Fi Aware not supported on this device"))
            return@suspendCancellableCoroutine
        }

        if (!wifiAwareManager.isAvailable) {
            continuation.resumeWithException(Exception("Wi-Fi Aware is currently disabled by the system"))
            return@suspendCancellableCoroutine
        }

        val serviceName = serviceNameBase + UUID.randomUUID().toString().take(8)

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session
                
                val config = PublishConfig.Builder()
                    .setServiceName(serviceName)
                    .build()

                session.publish(config, object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession = session
                        Log.d("NanImpl", "NAN Publish Started: $serviceName")
                        
                        // The payload for the QR code just needs the service name
                        // A peer will scan this and subscribe to it
                        val qrPayload = "PROXI:NAN:S:$serviceName"
                        continuation.resume(qrPayload)
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        // Peer has found us and requested a connection
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

        val serviceName = payload.removePrefix("PROXI:NAN:S:")

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session

                val config = SubscribeConfig.Builder()
                    .setServiceName(serviceName)
                    .build()

                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribeSession = session
                        Log.d("NanImpl", "NAN Subscribe Started for: $serviceName")
                    }

                    override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                        Log.d("NanImpl", "Lobby Discovered! Sending connection request to Host")
                        // Send a dummy message to trigger the Host's onMessageReceived so it gets our PeerHandle
                        subscribeSession?.sendMessage(peerHandle, 1, "CONNECT".toByteArray())
                        
                        // Now request the network
                        requestDataPathAsPeer(peerHandle, continuation)
                    }
                }, Handler(Looper.getMainLooper()))
            }

            override fun onAttachFailed() {
                continuation.resumeWithException(Exception("Failed to attach to Wi-Fi Aware session"))
            }
        }, Handler(Looper.getMainLooper()))
    }

    @SuppressLint("MissingPermission")
    private fun acceptPeerConnection(peerHandle: PeerHandle) {
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(publishSession!!, peerHandle)
            .setPmk("ProxiTapSecureKey123".toByteArray()) // Hardcoded for POC, in prod generate secure key via QR
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("NanImpl", "Host Data Path Established with Peer!")
                // Host binds to this network to route its Ktor server over it
                connectivityManager.bindProcessToNetwork(network)
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
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }
        }
        connectivityManager.requestNetwork(networkRequest, callback)
        peerNetworkCallback = callback
    }

    override fun getLocalIpAddress(): String? {
        // In NAN, peers connect to the host's IPv6 address
        return localHostIpv6
    }
}
