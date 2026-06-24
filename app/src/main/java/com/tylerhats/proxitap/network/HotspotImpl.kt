package com.tylerhats.proxitap.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HotspotImpl(private val context: Context) : LocalNetworkManager {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var peerNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission") // Permissions are requested in the UI layer
    override suspend fun startHosting(): String = suspendCancellableCoroutine { continuation ->
        Log.d("HotspotImpl", "Starting LocalOnlyHotspot...")
        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                super.onStarted(reservation)
                hotspotReservation = reservation
                
                val config = reservation?.softApConfiguration
                val ssid = config?.ssid
                val password = config?.passphrase
                
                if (ssid != null && password != null) {
                    val qrPayload = "WIFI:T:WPA;S:$ssid;P:$password;;"
                    Log.d("HotspotImpl", "Hotspot started successfully. SSID: $ssid")
                    continuation.resume(qrPayload)
                } else {
                    continuation.resumeWithException(Exception("Failed to retrieve Hotspot credentials"))
                }
            }

            override fun onFailed(reason: Int) {
                super.onFailed(reason)
                Log.e("HotspotImpl", "Failed to start hotspot. Reason: $reason")
                continuation.resumeWithException(Exception("Hotspot failed with reason: $reason"))
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun stop() {
        Log.d("HotspotImpl", "Stopping Hotspot/Network...")
        hotspotReservation?.close()
        hotspotReservation = null
        
        peerNetworkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        peerNetworkCallback = null
    }

    override suspend fun joinLobby(payload: String): Boolean = suspendCancellableCoroutine { continuation ->
        // Expected payload: WIFI:T:WPA;S:NetworkName;P:Password;;
        if (!payload.startsWith("WIFI:")) {
            continuation.resumeWithException(Exception("Invalid QR Code payload for Hotspot"))
            return@suspendCancellableCoroutine
        }

        val parts = payload.split(";")
        var ssid = ""
        var password = ""

        for (part in parts) {
            if (part.startsWith("S:")) ssid = part.substring(2)
            if (part.startsWith("P:")) password = part.substring(2)
        }

        if (ssid.isEmpty()) {
            continuation.resumeWithException(Exception("SSID not found in QR code"))
            return@suspendCancellableCoroutine
        }

        Log.d("HotspotImpl", "Attempting to join Hotspot SSID: $ssid")

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        peerNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d("HotspotImpl", "Successfully connected to Hotspot!")
                // Bind the app's process to this specific network so WebRTC traffic goes over it
                connectivityManager.bindProcessToNetwork(network)
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("HotspotImpl", "Network unavailable")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

        connectivityManager.requestNetwork(request, peerNetworkCallback!!)
    }

    override fun getLocalIpAddress(): String? {
        // For Hotspot owner, it's typically a standard gateway IP, but we can query interfaces.
        // As a peer, the OS handles IP assignment.
        // We will implement a network interface scanner later if WebRTC needs explicit IP binding.
        // For now, WebRTC's ICE framework automatically discovers local interface IPs!
        return null
    }
}
