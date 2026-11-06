package com.tylerhats.proxitap.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HotspotImpl(private val context: Context) : LocalNetworkManager {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var peerNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var hostIpAddress: String? = null

    private fun showToast(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {}
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
        var attempt = 0
        val maxAttempts = 3

        fun tryStartHotspot() {
            if (!continuation.isActive) return
            attempt++
            Log.d("HotspotImpl", "Starting LocalOnlyHotspot (attempt $attempt/$maxAttempts)...")
            try {
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
                            if (continuation.isActive) {
                                continuation.resume(qrPayload)
                            }
                        } else if (attempt < maxAttempts && continuation.isActive) {
                            showToast("Hotspot credentials incomplete, retrying ($attempt/$maxAttempts)...")
                            try { reservation?.close() } catch (e: Exception) {}
                            mainHandler.postDelayed({ tryStartHotspot() }, 700L)
                        } else if (continuation.isActive) {
                            continuation.resumeWithException(Exception("Failed to retrieve Hotspot credentials"))
                        }
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        Log.e("HotspotImpl", "Failed to start hotspot. Reason: $reason (attempt $attempt)")
                        if (attempt < maxAttempts && continuation.isActive) {
                            showToast("Hotspot busy, retrying setup ($attempt/$maxAttempts)...")
                            mainHandler.postDelayed({ tryStartHotspot() }, 800L)
                        } else if (continuation.isActive) {
                            showToast("Failed to start local Hotspot (code $reason)")
                            continuation.resumeWithException(Exception("Hotspot failed with reason: $reason"))
                        }
                    }
                }, mainHandler)
            } catch (e: SecurityException) {
                Log.e("HotspotImpl", "SecurityException starting hotspot. Missing permissions?", e)
                showToast("Missing permission to start local Hotspot")
                if (continuation.isActive) continuation.resumeWithException(e)
            } catch (e: Exception) {
                Log.e("HotspotImpl", "Exception starting hotspot", e)
                if (attempt < maxAttempts && continuation.isActive) {
                    showToast("Hotspot error, retrying ($attempt/$maxAttempts)...")
                    mainHandler.postDelayed({ tryStartHotspot() }, 800L)
                } else if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }

        tryStartHotspot()
    }

    override fun discoverLobbies(onLobbyFound: (String, Boolean, Boolean, Boolean, String) -> Unit) {
        // Hotspot discovery uses QR code
    }

    override fun stop() {
        Log.d("HotspotImpl", "Stopping Hotspot/Network...")
        try {
            hotspotReservation?.close()
        } catch (e: Exception) {}
        hotspotReservation = null

        peerNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        peerNetworkCallback = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (e: Exception) {}
        hostIpAddress = null
    }

    override suspend fun joinLobby(payload: String, pin: String): Boolean =
        suspendCancellableCoroutine { continuation ->
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

            var attempt = 0
            val maxAttempts = 3
            var joinSucceeded = false

            fun attemptJoin() {
                if (!continuation.isActive && !joinSucceeded) return
                attempt++
                Log.d("HotspotImpl", "Attempting to join Hotspot SSID: $ssid (attempt $attempt/$maxAttempts)")

                peerNetworkCallback?.let {
                    try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) {}
                }
                peerNetworkCallback = null

                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    var isAvailable = false
                    var hasIp = false
                    var handled = false

                    private fun checkAndResume() {
                        if (isAvailable && hasIp && !handled) {
                            handled = true
                            joinSucceeded = true
                            if (continuation.isActive) {
                                continuation.resume(true)
                            } else {
                                showToast("Reconnected to Host Hotspot")
                            }
                        }
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: android.net.LinkProperties
                    ) {
                        super.onLinkPropertiesChanged(network, linkProperties)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val dhcpAddress = linkProperties.dhcpServerAddress?.hostAddress
                            if (dhcpAddress != null) {
                                hostIpAddress = dhcpAddress
                                Log.d("HotspotImpl", "Host IP Discovered via DHCP Server: $hostIpAddress")
                                hasIp = true
                                checkAndResume()
                            }
                        }

                        if (!hasIp) {
                            for (route in linkProperties.routes) {
                                if (route.isDefaultRoute) {
                                    hostIpAddress = route.gateway?.hostAddress
                                    Log.d("HotspotImpl", "Discovered Host Gateway IP: $hostIpAddress")
                                    hasIp = true
                                    checkAndResume()
                                    break
                                }
                            }
                        }
                    }

                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d("HotspotImpl", "Peer Hotspot Data Path Established!")
                        connectivityManager.bindProcessToNetwork(network)
                        isAvailable = true
                        checkAndResume()
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.w("HotspotImpl", "Hotspot Network lost")
                        try {
                            connectivityManager.bindProcessToNetwork(null)
                        } catch (e: Exception) {}
                        try {
                            connectivityManager.unregisterNetworkCallback(this)
                        } catch (e: Exception) {}
                        if (peerNetworkCallback === this) peerNetworkCallback = null

                        if (!joinSucceeded) {
                            if (!handled) {
                                handled = true
                                if (attempt < maxAttempts && continuation.isActive) {
                                    showToast("Hotspot link dropped during setup, retrying ($attempt/$maxAttempts)...")
                                    mainHandler.postDelayed({ attemptJoin() }, 700L)
                                } else if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        } else {
                            showToast("Hotspot connection lost, reconnecting...")
                            attempt = 0
                            mainHandler.postDelayed({ attemptJoin() }, 800L)
                        }
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.e("HotspotImpl", "Hotspot Network unavailable (attempt $attempt)")
                        try {
                            connectivityManager.bindProcessToNetwork(null)
                        } catch (e: Exception) {}
                        try {
                            connectivityManager.unregisterNetworkCallback(this)
                        } catch (e: Exception) {}
                        if (peerNetworkCallback === this) peerNetworkCallback = null

                        if (!handled) {
                            handled = true
                            if (attempt < maxAttempts && continuation.isActive) {
                                showToast("Hotspot connection failed, retrying ($attempt/$maxAttempts)...")
                                mainHandler.postDelayed({ attemptJoin() }, 700L)
                            } else if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                }

                peerNetworkCallback = callback
                connectivityManager.requestNetwork(request, callback)
            }

            attemptJoin()
        }

    override fun getLocalIpAddress(): String? {
        return hostIpAddress
    }
}
