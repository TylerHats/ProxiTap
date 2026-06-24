package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import java.time.Duration
import io.ktor.server.cio.CIO
import java.util.Collections

class SignalingServer {
    private var server: ApplicationEngine? = null
    private val connectedSessions = Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())

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
                    connectedSessions.add(this)
                    
                    try {
                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                val message = frame.readText()
                                Log.d("SignalingServer", "Received message: $message")
                                
                                // Broadcast to all OTHER connected peers
                                // In a full implementation, this parses the JSON SDP/ICE and routes it to the specific peer
                                connectedSessions.filter { it != this }.forEach { session ->
                                    session.send(Frame.Text(message))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SignalingServer", "Error in WebSocket connection: ${e.message}")
                    } finally {
                        Log.d("SignalingServer", "Client disconnected")
                        connectedSessions.remove(this)
                    }
                }
            }
        }.start(wait = false)
    }

    suspend fun sendRemoteConfigCommand(targetPeerId: String, bitrate: Int?, ns: Boolean?, aec: Boolean?) {
        // Construct JSON command payload
        val jsonPayload = """
            {
                "type": "SET_CONFIG",
                "target": "$targetPeerId",
                "bitrate": $bitrate,
                "ns": $ns,
                "aec": $aec
            }
        """.trimIndent()
        
        Log.d("SignalingServer", "Broadcasting config command: $jsonPayload")
        // Broadcast to all clients (clients will ignore if target doesn't match)
        connectedSessions.forEach {
            it.send(Frame.Text(jsonPayload))
        }
    }

    fun stopServer() {
        Log.d("SignalingServer", "Stopping Ktor WebSocket server")
        server?.stop(1000, 2000)
        server = null
        connectedSessions.clear()
    }
}
