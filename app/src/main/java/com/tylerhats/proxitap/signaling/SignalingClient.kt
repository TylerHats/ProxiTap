package com.tylerhats.proxitap.signaling

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class SignalingClient(
    private val hostIp: String,
    private val port: Int = 8080,
    private val onStatusMessage: ((String) -> Unit)? = null
) {

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 10_000L
        }
        engine {
            requestTimeout = 5_000L
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var connectionScope: CoroutineScope? = null
    @Volatile
    private var isIntentionallyDisconnected = false

    private val _incomingMessages = MutableSharedFlow<JSONObject>(
        replay = 16,
        extraBufferCapacity = 64
    )
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _incomingAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingAudio = _incomingAudio.asSharedFlow()

    private val outgoingAudioChannel = Channel<ByteArray>(
        capacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(myPeerId: String, deviceName: String) {
        isIntentionallyDisconnected = false
        startReconnectLoop(myPeerId, deviceName)
    }

    fun sendJoinMessage(myPeerId: String, deviceName: String) {
        val joinMsg = JSONObject().apply {
            put("type", "JOIN")
            put("peerId", myPeerId)
            put("deviceName", deviceName)
        }
        sendMessage(joinMsg)
    }

    private fun startReconnectLoop(myPeerId: String, deviceName: String) {
        connectionScope?.cancel()
        connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        connectionScope?.launch {
            var consecutiveFailures = 0
            var everConnected = false

            while (!isIntentionallyDisconnected && isActive) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    Log.d("SignalingClient", "Attempting to connect to ws://$hostIp:$port/signaling (attempt ${consecutiveFailures + 1})")
                    client.webSocket(
                        method = HttpMethod.Get,
                        host = hostIp,
                        port = port,
                        path = "/signaling"
                    ) {
                        session = this
                        val wasReconnect = everConnected || consecutiveFailures >= 2
                        everConnected = true
                        consecutiveFailures = 0
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.d("SignalingClient", "Successfully connected to Host Signaling Server")
                        if (wasReconnect) {
                            onStatusMessage?.invoke("Signaling link connected")
                        }

                        // Clear any buffered stale frames before starting
                        while (outgoingAudioChannel.tryReceive().isSuccess) { /* clear */ }

                        val sendJob = launch {
                            for (data in outgoingAudioChannel) {
                                try {
                                    send(Frame.Binary(true, data))
                                } catch (e: Exception) {
                                    Log.e("SignalingClient", "Error sending audio frame", e)
                                    break
                                }
                            }
                        }

                        // Send JOIN immediately
                        val joinMsg = JSONObject().apply {
                            put("type", "JOIN")
                            put("peerId", myPeerId)
                            put("deviceName", deviceName)
                        }
                        send(Frame.Text(joinMsg.toString()))

                        try {
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    Log.d("SignalingClient", "Received message: $text")
                                    val json = JSONObject(text)
                                    val type = json.optString("type")
                                    if (type == "SESSION_CLOSED") {
                                        Log.d("SignalingClient", "Host closed the session. Disconnecting intentionally.")
                                        isIntentionallyDisconnected = true
                                        _incomingMessages.tryEmit(json)
                                        session?.close(CloseReason(CloseReason.Codes.NORMAL, "Host closed session"))
                                        return@consumeEach
                                    }
                                    if (type == "PING") {
                                        val pong = JSONObject().apply {
                                            put("type", "PONG")
                                            put("timestamp", json.optLong("timestamp"))
                                        }
                                        send(Frame.Text(pong.toString()))
                                    } else {
                                        _incomingMessages.tryEmit(json)
                                    }
                                } else if (frame is Frame.Binary) {
                                    val bytes = frame.readBytes()
                                    _incomingAudio.tryEmit(bytes)
                                }
                            }
                        } finally {
                            sendJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.e("SignalingClient", "WebSocket connection failed (attempt $consecutiveFailures): ${e.message}")
                    if (!isIntentionallyDisconnected) {
                        if (everConnected && consecutiveFailures == 1) {
                            onStatusMessage?.invoke("Host signaling interrupted, reconnecting...")
                        } else if (!everConnected && consecutiveFailures == 2) {
                            onStatusMessage?.invoke("Waiting for host network interface, retrying...")
                        }
                    }
                }

                _connectionState.value = ConnectionState.DISCONNECTED
                session = null

                if (!isIntentionallyDisconnected) {
                    val backoffMs = when (consecutiveFailures) {
                        1 -> 450L
                        2 -> 800L
                        3 -> 1200L
                        else -> 2000L
                    }
                    Log.d("SignalingClient", "Reconnecting in ${backoffMs}ms...")
                    delay(backoffMs)
                }
            }
        }
    }

    fun sendMessage(json: JSONObject) {
        connectionScope?.launch {
            try {
                session?.send(Frame.Text(json.toString()))
            } catch (e: Exception) {
                Log.w("SignalingClient", "Failed to send message: ${e.message}")
            }
        }
    }

    suspend fun sendMessageSuspend(json: JSONObject) {
        try {
            session?.send(Frame.Text(json.toString()))
        } catch (e: Exception) {
            Log.w("SignalingClient", "Failed to send message suspend: ${e.message}")
        }
    }

    fun sendAudioData(data: ByteArray) {
        outgoingAudioChannel.trySend(data)
    }

    fun disconnect() {
        Log.d("SignalingClient", "Disconnecting client")
        isIntentionallyDisconnected = true
        while (outgoingAudioChannel.tryReceive().isSuccess) { /* drain */ }
        connectionScope?.launch {
            try { session?.close() } catch (e: Exception) {}
            session = null
            try { client.close() } catch (e: Exception) {}
            connectionScope?.cancel()
        }
    }
}
