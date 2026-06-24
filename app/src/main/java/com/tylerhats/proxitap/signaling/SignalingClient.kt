package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class SignalingClient(private val hostIp: String, private val port: Int = 8080) {

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000L
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var connectionScope: CoroutineScope? = null
    private var isIntentionallyDisconnected = false

    private val _incomingMessages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()
    
    private val _incomingAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudio = _incomingAudio.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(myPeerId: String, deviceName: String) {
        isIntentionallyDisconnected = false
        startReconnectLoop(myPeerId, deviceName)
    }

    private fun startReconnectLoop(myPeerId: String, deviceName: String) {
        connectionScope?.cancel()
        connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        connectionScope?.launch {
            while (!isIntentionallyDisconnected) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    Log.d("SignalingClient", "Attempting to connect to ws://$hostIp:$port/signaling")
                    client.webSocket(method = HttpMethod.Get, host = hostIp, port = port, path = "/signaling") {
                        session = this
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.d("SignalingClient", "Successfully connected to Host Signaling Server")

                        // Send JOIN
                        val joinMsg = JSONObject().apply {
                            put("type", "JOIN")
                            put("peerId", myPeerId)
                            put("deviceName", deviceName)
                        }
                        send(Frame.Text(joinMsg.toString()))

                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Log.d("SignalingClient", "Received message: $text")
                                val json = JSONObject(text)
                                if (json.optString("type") == "SESSION_CLOSED") {
                                    Log.d("SignalingClient", "Host closed the session. Disconnecting intentionally.")
                                    isIntentionallyDisconnected = true
                                    _incomingMessages.tryEmit(json)
                                    session?.close(CloseReason(CloseReason.Codes.NORMAL, "Host closed session"))
                                    return@consumeEach
                                }
                                _incomingMessages.tryEmit(json)
                            } else if (frame is Frame.Binary) {
                                val bytes = frame.readBytes()
                                _incomingAudio.tryEmit(bytes)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SignalingClient", "WebSocket connection failed: ${e.message}")
                }

                _connectionState.value = ConnectionState.DISCONNECTED
                session = null
                
                if (!isIntentionallyDisconnected) {
                    Log.d("SignalingClient", "Lost connection. Reconnecting in 3 seconds...")
                    delay(3000)
                }
            }
        }
    }

    fun sendMessage(json: JSONObject) {
        connectionScope?.launch {
            session?.send(Frame.Text(json.toString()))
        }
    }
    
    fun sendAudioData(data: ByteArray) {
        connectionScope?.launch {
            session?.send(Frame.Binary(true, data))
        }
    }

    fun disconnect() {
        Log.d("SignalingClient", "Disconnecting client")
        isIntentionallyDisconnected = true
        connectionScope?.launch {
            session?.close()
            session = null
            client.close()
            connectionScope?.cancel()
        }
    }
}
