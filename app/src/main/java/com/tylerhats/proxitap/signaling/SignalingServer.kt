package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.plugins.origin
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class SignalingServer {
    private var server: ApplicationEngine? = null

    // peerId to WebSocketSession
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    // peerId to deviceName
    private val participantNames = ConcurrentHashMap<String, String>()
    // peerId to IP address
    val peerIpAddresses = ConcurrentHashMap<String, String>()

    var hostName = "Host"
    var maxParticipants = 4

    // Replay = 16 ensures early JOIN messages are never lost before collectors attach
    private val _incomingMessages = MutableSharedFlow<Pair<String, JSONObject>>(
        replay = 16,
        extraBufferCapacity = 64
    )
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _incomingAudio = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 128)
    val incomingAudio = _incomingAudio.asSharedFlow()

    private suspend fun broadcastParticipants() {
        val participantsList = JSONArray()

        // Add Host at index 0
        val hostObj = JSONObject().apply {
            put("id", "HOST")
            put("name", hostName)
            put("isHost", true)
        }
        participantsList.put(hostObj)

        participantNames.forEach { (id, name) ->
            val p = JSONObject()
            p.put("id", id)
            p.put("name", name)
            p.put("isHost", false)
            participantsList.put(p)
        }
        val msg = JSONObject()
        msg.put("type", "PARTICIPANTS_UPDATE")
        msg.put("participants", participantsList)

        val msgString = msg.toString()
        sessions.values.forEach { session ->
            try {
                session.send(Frame.Text(msgString))
            } catch (e: Exception) {
                Log.w("SignalingServer", "Failed to send PARTICIPANTS_UPDATE to a peer: ${e.message}")
            }
        }
        _incomingMessages.tryEmit(Pair("HOST", msg))
    }

    fun startServer(port: Int = 8080) {
        Log.d("SignalingServer", "Starting Ktor WebSocket server on port $port")
        for (attempt in 1..3) {
            try {
                server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(WebSockets) {
                        pingPeriod = Duration.ofSeconds(10)
                        timeout = Duration.ofSeconds(15)
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    routing {
                        webSocket("/signaling") {
                            Log.d("SignalingServer", "New client connected to signaling server")
                            var currentPeerId: String? = null

                            try {
                                incoming.consumeEach { frame ->
                                    if (frame is Frame.Text) {
                                        val message = frame.readText()
                                        Log.d("SignalingServer", "Received message: $message")
                                        val json = JSONObject(message)
                                        val type = json.optString("type")

                                        if (type == "PING") {
                                            val pong = JSONObject().apply {
                                                put("type", "PONG")
                                                put("timestamp", json.optLong("timestamp"))
                                            }
                                            send(Frame.Text(pong.toString()))
                                            return@consumeEach
                                        }

                                        if (type == "JOIN") {
                                            val joiningPeerId = json.getString("peerId")
                                            currentPeerId = joiningPeerId
                                            val deviceName = if (json.has("deviceName")) {
                                                json.getString("deviceName")
                                            } else {
                                                "Unknown Device"
                                            }

                                            val isExistingPeer = sessions.containsKey(joiningPeerId)
                                            if (!isExistingPeer && sessions.size + 1 >= maxParticipants) {
                                                val rejectMsg = JSONObject().apply {
                                                    put("type", "REJECT")
                                                    put("reason", "Lobby is full (limit $maxParticipants)")
                                                }
                                                send(Frame.Text(rejectMsg.toString()))
                                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Lobby is full"))
                                                return@consumeEach
                                            }

                                            val remoteHost = this.call.request.origin.remoteHost
                                            val cleanRemoteHost = remoteHost
                                                .removePrefix("[")
                                                .removeSuffix("]")
                                                .split('%')
                                                .first()
                                            peerIpAddresses[joiningPeerId] = cleanRemoteHost
                                            Log.d("SignalingServer", "Peer $joiningPeerId connected from IP $cleanRemoteHost (raw: $remoteHost)")

                                            sessions[joiningPeerId] = this
                                            participantNames[joiningPeerId] = deviceName
                                            broadcastParticipants()
                                        }

                                        if (type == "UPDATE_NAME" && currentPeerId != null) {
                                            val newName = json.getString("name")
                                            participantNames[currentPeerId!!] = newName
                                            broadcastParticipants()
                                        }

                                        val senderId = currentPeerId ?: json.optString("peerId").takeIf { it.isNotEmpty() }
                                        if (senderId != null) {
                                            // Route to Host's WebRTC / Signaling engine
                                            _incomingMessages.tryEmit(Pair(senderId, json))

                                            // Also route to target peer if this is a mesh message between two peers
                                            if (json.has("target")) {
                                                val targetId = json.getString("target")
                                                if (targetId != "HOST") {
                                                    try {
                                                        sessions[targetId]?.send(Frame.Text(message))
                                                    } catch (e: Exception) {}
                                                }
                                            }
                                        }
                                    } else if (frame is Frame.Binary) {
                                        val senderId = currentPeerId
                                        if (senderId != null) {
                                            val bytes = frame.readBytes()
                                            _incomingAudio.tryEmit(Pair(senderId, bytes))

                                            // Broadcast binary audio to all OTHER peers
                                            sessions.forEach { (id, s) ->
                                                if (id != senderId) {
                                                    try {
                                                        s.send(Frame.Binary(true, bytes))
                                                    } catch (e: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("SignalingServer", "Error in WebSocket connection: ${e.message}")
                            } finally {
                                Log.d("SignalingServer", "Client $currentPeerId disconnected")
                                currentPeerId?.let { id ->
                                    // Only remove if the session map still points to THIS WebSocket session
                                    if (sessions.remove(id, this)) {
                                        participantNames.remove(id)
                                        peerIpAddresses.remove(id)
                                        broadcastParticipants()
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
                return
            } catch (e: Exception) {
                Log.e("SignalingServer", "Failed to start Ktor server on port $port (attempt $attempt): ${e.message}")
                if (attempt < 3) {
                    try { Thread.sleep(350) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    suspend fun sendMessageToPeer(targetPeerId: String, json: JSONObject) {
        try {
            sessions[targetPeerId]?.send(Frame.Text(json.toString()))
        } catch (e: Exception) {
            Log.w("SignalingServer", "Failed to send message to peer $targetPeerId: ${e.message}")
        }
    }

    suspend fun broadcastMessage(json: JSONObject) {
        val msgString = json.toString()
        sessions.values.forEach {
            try {
                it.send(Frame.Text(msgString))
            } catch (e: Exception) {}
        }
    }

    suspend fun updateHostName(newHostName: String) {
        hostName = newHostName
        broadcastParticipants()
    }

    suspend fun broadcastAudioData(data: ByteArray) {
        sessions.values.forEach {
            try {
                it.send(Frame.Binary(true, data))
            } catch (e: Exception) {}
        }
    }

    fun stopServer() {
        Log.d("SignalingServer", "Stopping Ktor WebSocket server")
        val msg = JSONObject().apply { put("type", "SESSION_CLOSED") }.toString()
        runBlocking {
            sessions.values.forEach {
                try {
                    it.send(Frame.Text(msg))
                } catch (e: Exception) {}
            }
        }
        try {
            server?.stop(500, 1000)
        } catch (e: Exception) {}
        server = null
        sessions.clear()
        participantNames.clear()
        peerIpAddresses.clear()
    }
}
