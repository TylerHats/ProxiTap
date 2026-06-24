package com.tylerhats.proxitap.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
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

class CallService : Service() {

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

    inner class LocalBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        webRtcClient = WebRtcClient(this)
        
        webRtcClient.startAudioLevelMonitoring { speaking ->
            _isSpeaking.value = speaking
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun startHostSignaling(port: Int = 8080) {
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

                    webRtcClient.createPeerConnection(senderId, signalingCallback)
                    // In a star topology, Host acts as SFU. It sends an offer to the new peer.
                    webRtcClient.createOffer(senderId, signalingCallback)
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
    }

    fun startPeerSignaling(hostIp: String, port: Int = 8080) {
        signalingClient = SignalingClient(hostIp, port)
        signalingClient?.connect(myPeerId)

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
                if (json.has("target") && json.getString("target") != myPeerId) return@collect

                val senderId = "HOST" // Peers only connect directly to host in SFU

                if (type == "OFFER") {
                    webRtcClient.createPeerConnection(senderId, signalingCallback)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webRtcClient.dispose()
        signalingServer?.stopServer()
        signalingClient?.disconnect()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "proxitap_call_channel",
                "ProxiTap Active Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the microphone active for Walkie-Talkie features"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "proxitap_call_channel")
            .setContentTitle("ProxiTap")
            .setContentText("Call is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Fallback icon
            .setOngoing(true)
            .build()
    }
}
