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

class SignalingClient(private val hostIp: String, private val port: Int = 8080) {

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000L
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<String>()
    val incomingMessages = _incomingMessages.asSharedFlow()

    fun connect() {
        scope.launch {
            try {
                Log.d("SignalingClient", "Attempting to connect to ws://$hostIp:$port/signaling")
                client.webSocket(method = HttpMethod.Get, host = hostIp, port = port, path = "/signaling") {
                    session = this
                    Log.d("SignalingClient", "Successfully connected to Host Signaling Server")
                    
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val message = frame.readText()
                            Log.d("SignalingClient", "Received from Host: $message")
                            _incomingMessages.emit(message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SignalingClient", "WebSocket connection failed: ${e.message}")
            }
        }
    }

    fun sendMessage(message: String) {
        scope.launch {
            session?.send(Frame.Text(message))
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
