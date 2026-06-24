package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import java.time.Duration
import io.ktor.server.cio.CIO
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class SignalingServer {
    private var server: ApplicationEngine? = null
    
    // peerId to WebSocketSession
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    // peerId to deviceName
    private val participantNames = ConcurrentHashMap<String, String>()

    // For the Host to listen to incoming messages from Peers
    private val _incomingMessages = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()
    
    private val _incomingAudio = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val incomingAudio = _incomingAudio.asSharedFlow()

    private suspend fun broadcastParticipants() {
        val participantsList = org.json.JSONArray()
        participantNames.forEach { (id, name) ->
            val p = JSONObject()
            p.put("id", id)
            p.put("name", name)
            participantsList.put(p)
        }
        val msg = JSONObject()
        msg.put("type", "PARTICIPANTS_UPDATE")
        msg.put("participants", participantsList)
        
        val msgString = msg.toString()
        sessions.values.forEach { it.send(Frame.Text(msgString)) }
        _incomingMessages.tryEmit(Pair("HOST", msg))
    }

    fun startServer(port: Int = 8080) {
        Log.d("SignalingServer", "Starting Ktor WebSocket server on port $port")
        try {
            server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
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
                                    val type = json.getString("type")
                                    
                                    if (type == "JOIN") {
                                        currentPeerId = json.getString("peerId")
                                        val deviceName = if (json.has("deviceName")) json.getString("deviceName") else "Unknown Device"
                                        sessions[currentPeerId!!] = this
                                        participantNames[currentPeerId!!] = deviceName
                                        broadcastParticipants()
                                    }
                                    
                                    if (currentPeerId != null) {
                                        // Route to Host's WebRTC engine
                                        _incomingMessages.tryEmit(Pair(currentPeerId!!, json))
                                        
                                        // Also route to target peer if this is a mesh message between two peers
                                        if (json.has("target")) {
                                            val targetId = json.getString("target")
                                            sessions[targetId]?.send(Frame.Text(message))
                                        }
                                    }
                                } else if (frame is Frame.Binary) {
                                    if (currentPeerId != null) {
                                        val bytes = frame.readBytes()
                                        _incomingAudio.tryEmit(Pair(currentPeerId!!, bytes))
                                        
                                        // Broadcast binary audio to all OTHER peers
                                        sessions.forEach { (id, s) ->
                                            if (id != currentPeerId) {
                                                s.send(Frame.Binary(true, bytes))
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SignalingServer", "Error in WebSocket connection: ${e.message}")
                        } finally {
                            Log.d("SignalingServer", "Client $currentPeerId disconnected")
                            currentPeerId?.let { 
                                sessions.remove(it)
                                participantNames.remove(it)
                                broadcastParticipants()
                            }
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            Log.e("SignalingServer", "Failed to start Ktor server on port $port: ${e.message}")
        }
    }

    suspend fun sendMessageToPeer(targetPeerId: String, json: JSONObject) {
        sessions[targetPeerId]?.send(Frame.Text(json.toString()))
    }
    
    suspend fun broadcastAudioData(data: ByteArray) {
        sessions.values.forEach { it.send(Frame.Binary(true, data)) }
    }

    fun stopServer() {
        Log.d("SignalingServer", "Stopping Ktor WebSocket server")
        // Broadcast SESSION_CLOSED to all peers before stopping
        val msg = JSONObject().apply { put("type", "SESSION_CLOSED") }.toString()
        runBlocking {
            sessions.values.forEach { 
                try { it.send(Frame.Text(msg)) } catch (e: Exception) {} 
            }
        }
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
        participantNames.clear()
    }
}
