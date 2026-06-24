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
import java.time.Duration
import io.ktor.server.cio.CIO
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class SignalingServer {
    private var server: ApplicationEngine? = null
    
    // peerId to WebSocketSession
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // For the Host to listen to incoming messages from Peers
    private val _incomingMessages = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    fun startServer(port: Int = 8080) {
        Log.d("SignalingServer", "Starting Ktor WebSocket server on port $port")
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
                                    sessions[currentPeerId!!] = this
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
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SignalingServer", "Error in WebSocket connection: ${e.message}")
                    } finally {
                        Log.d("SignalingServer", "Client $currentPeerId disconnected")
                        currentPeerId?.let { sessions.remove(it) }
                    }
                }
            }
        }.start(wait = false)
    }

    suspend fun sendMessageToPeer(targetPeerId: String, json: JSONObject) {
        sessions[targetPeerId]?.send(Frame.Text(json.toString()))
    }

    fun stopServer() {
        Log.d("SignalingServer", "Stopping Ktor WebSocket server")
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
    }
}
