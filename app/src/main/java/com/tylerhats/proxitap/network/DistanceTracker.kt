package com.tylerhats.proxitap.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.PeerHandle
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DistanceTracker(private val context: Context) {

    private val wifiRttManager: WifiRttManager? = context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null

    // Map of PeerHandle to Distance in meters
    private val _peerDistances = MutableStateFlow<Map<PeerHandle, Float>>(emptyMap())
    val peerDistances: StateFlow<Map<PeerHandle, Float>> = _peerDistances

    var isTrackingEnabled = false

    @SuppressLint("MissingPermission")
    fun startTracking(peers: List<PeerHandle>) {
        if (wifiRttManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            Log.e("DistanceTracker", "Wi-Fi RTT is not supported on this device.")
            return
        }

        if (!isTrackingEnabled) return

        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive) {
                if (peers.isNotEmpty()) {
                    val requestBuilder = RangingRequest.Builder()
                    peers.forEach { peer ->
                        requestBuilder.addWifiAwarePeer(peer)
                    }

                    val request = requestBuilder.build()
                    wifiRttManager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                        override fun onRangingFailure(code: Int) {
                            Log.e("DistanceTracker", "Ranging failed with code $code")
                        }

                        override fun onRangingResults(results: List<RangingResult>) {
                            val newDistances = mutableMapOf<PeerHandle, Float>()
                            for (result in results) {
                                if (result.status == RangingResult.STATUS_SUCCESS) {
                                    val distanceMeters = result.distanceMm / 1000f
                                    // Wi-Fi RTT results don't expose the original PeerHandle easily in public API,
                                    // but we can map them back if we only range one at a time, or assume order.
                                    // For ProxiTap, we typically only have 1 peer connected in V1.
                                    // Note: In a robust app, we'd iterate and map them properly.
                                    if (peers.size == 1) {
                                        newDistances[peers[0]] = distanceMeters
                                        Log.d("DistanceTracker", "Distance to peer: $distanceMeters meters")
                                    }
                                }
                            }
                            _peerDistances.value = newDistances
                        }
                    })
                }
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _peerDistances.value = emptyMap()
    }
}
