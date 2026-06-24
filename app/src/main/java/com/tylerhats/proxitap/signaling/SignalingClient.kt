package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

class SignalingClient(private val hostIp: String, private val port: Int = 8080) {

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000L
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    fun connect(myPeerId: String) {
        scope.launch {
            try {
                Log.d("SignalingClient", "Attempting to connect to ws://$hostIp:$port/signaling")
                client.webSocket(method = HttpMethod.Get, host = hostIp, port = port, path = "/signaling") {
                    session = this
                    Log.d("SignalingClient", "Successfully connected to Host Signaling Server")

                    // Send JOIN
                    val joinMsg = JSONObject().apply {
                        put("type", "JOIN")
                        put("peerId", myPeerId)
                    }
                    send(Frame.Text(joinMsg.toString()))

                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val message = frame.readText()
                            Log.d("SignalingClient", "Received from Host: $message")
                            _incomingMessages.emit(JSONObject(message))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SignalingClient", "WebSocket connection failed: ${e.message}")
            }
        }
    }

    fun sendMessage(json: JSONObject) {
        scope.launch {
            session?.send(Frame.Text(json.toString()))
        }
    }

    fun disconnect() {
        Log.d("SignalingClient", "Disconnecting client")
        scope.launch {
            session?.close()
            session = null
            client.close()
            coroutineContext.cancelChildren()
        }
    }
}
