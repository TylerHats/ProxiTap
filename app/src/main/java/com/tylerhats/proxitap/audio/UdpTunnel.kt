package com.tylerhats.proxitap.audio

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue

class UdpProxy(
    val localListenPort: Int,
    targetPort: Int?,
    private val onUdpDataReceived: (ByteArray) -> Unit
) {
    @Volatile
    var localTargetPort: Int? = targetPort
        private set

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isRunning = false
    private val localAddress = InetAddress.getByName("127.0.0.1")
    private val pendingPackets = ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        if (isRunning) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.receiveBufferSize = 65536
            s.sendBufferSize = 65536
            s.bind(InetSocketAddress(localAddress, localListenPort))
            socket = s
            isRunning = true
            Log.d("UdpProxy", "Bound UDP Proxy synchronously on 127.0.0.1:$localListenPort, targeting $localTargetPort")
        } catch (e: Exception) {
            Log.e("UdpProxy", "Failed to bind UDP Proxy on port $localListenPort", e)
            return
        }

        flushPendingPackets()

        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                val buffer = ByteArray(65535)
                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    onUdpDataReceived(data)
                }
            } catch (e: SocketException) {
                if (isRunning) {
                    Log.e("UdpProxy", "Socket error on port $localListenPort", e)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("UdpProxy", "Error receiving UDP on port $localListenPort", e)
                }
            }
        }
    }

    fun updateTargetPort(newTargetPort: Int?) {
        localTargetPort = newTargetPort
        if (newTargetPort != null) {
            flushPendingPackets()
        }
    }

    private fun flushPendingPackets() {
        val target = localTargetPort ?: return
        val s = socket ?: return
        scope.launch {
            while (pendingPackets.isNotEmpty()) {
                val data = pendingPackets.poll() ?: break
                try {
                    val packet = DatagramPacket(data, data.size, localAddress, target)
                    s.send(packet)
                } catch (e: Exception) {
                    Log.e("UdpProxy", "Error flushing buffered UDP packet to $target", e)
                }
            }
        }
    }

    fun sendUdpData(data: ByteArray) {
        val targetPort = localTargetPort
        if (targetPort == null) {
            while (pendingPackets.size >= 16) {
                pendingPackets.poll()
            }
            pendingPackets.offer(data)
            return
        }
        val s = socket ?: return
        try {
            val packet = DatagramPacket(data, data.size, localAddress, targetPort)
            s.send(packet)
        } catch (e: Exception) {
            scope.launch {
                try {
                    val packet = DatagramPacket(data, data.size, localAddress, targetPort)
                    s.send(packet)
                } catch (ex: Exception) {
                    Log.e("UdpProxy", "Error sending UDP data to $targetPort", ex)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        pendingPackets.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        scope.cancel()
    }
}

class DirectUdpAudioStreamer(
    private val localPort: Int,
    initialRemoteIp: String,
    private val remotePort: Int,
    private val onAudioFrameReceived: (ByteArray) -> Unit
) {
    private var socket: DatagramSocket? = null
    @Volatile
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sendSeqNum = 0
    private var lastReceivedSeqNum = -1
    val lossTracker = UdpPacketLossTracker()

    @Volatile
    private var remoteAddress: InetAddress? = null
    @Volatile
    private var currentRemoteIpStr: String = initialRemoteIp

    private val outgoingFrames = Channel<ByteArray>(
        capacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun updateRemoteIp(newRemoteIp: String) {
        currentRemoteIpStr = newRemoteIp
        scope.launch {
            resolveRemoteAddress(newRemoteIp)
        }
    }

    private fun resolveRemoteAddress(ipStr: String) {
        try {
            val clean = ipStr.trim().removePrefix("[").removeSuffix("]")
            if (clean.isNotEmpty()) {
                val resolved = InetAddress.getByName(clean)
                // Only overwrite if we don't already have a kernel-scoped address for the same raw IP bytes
                val existing = remoteAddress
                if (existing == null || !existing.address.contentEquals(resolved.address)) {
                    remoteAddress = resolved
                    Log.d("DirectUdpAudio", "Resolved remote UDP target: $resolved")
                }
            }
        } catch (e: Exception) {
            Log.e("DirectUdpAudio", "Failed to resolve remote IP '$ipStr': ${e.message}")
        }
    }

    fun start() {
        if (isRunning) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.receiveBufferSize = 65536
            s.sendBufferSize = 65536
            try {
                // DSCP EF (46 -> 0xB8) maps UDP audio to Wi-Fi WMM Voice (AC_VO) low-latency hardware queue
                s.trafficClass = 0xB8
            } catch (_: Exception) {}
            s.bind(InetSocketAddress(localPort))
            socket = s
            isRunning = true
        } catch (e: Exception) {
            Log.e("DirectUdpAudio", "Failed to bind UDP socket on port $localPort", e)
            return
        }

        // Receiver loop
        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            resolveRemoteAddress(currentRemoteIpStr)
            val buffer = ByteArray(4096)
            Log.d("DirectUdpAudio", "Listening on UDP port $localPort, initial target $currentRemoteIpStr:$remotePort")

            while (isActive && isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet) ?: break
                    // Learn the peer's exact InetAddress (including IPv6 link-local scope_id) from incoming packet
                    if (packet.address != null) {
                        remoteAddress = packet.address
                    }
                    if (packet.length <= 2) {
                        // 2-byte keepalive/handshake packet
                        continue
                    }
                    val data = packet.data
                    val seqNum = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
                    if (seqNum == 0xFFFF) continue

                    lossTracker.trackPacket(seqNum)

                    // Filter out stale out-of-order packets so they never glitch the audio stream
                    if (lastReceivedSeqNum != -1) {
                        var diff = seqNum - lastReceivedSeqNum
                        if (diff < -16384) diff += 32768
                        if (diff > 16384) diff -= 32768
                        if (diff <= 0 && diff > -500) {
                            continue
                        }
                    }
                    lastReceivedSeqNum = seqNum

                    val rawAudio = ByteArray(packet.length - 2)
                    System.arraycopy(data, 2, rawAudio, 0, rawAudio.size)
                    onAudioFrameReceived(rawAudio)
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e("DirectUdpAudio", "SocketException in UDP receiver: ${e.message}")
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e("DirectUdpAudio", "Error in UDP receiver: ${e.message}")
                    }
                }
            }
        }

        // High-priority ordered sender loop
        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            for (data in outgoingFrames) {
                if (!isRunning) break
                val target = remoteAddress ?: continue
                try {
                    val seq = sendSeqNum
                    sendSeqNum = (sendSeqNum + 1) and 0x7FFF

                    val packetData = ByteArray(data.size + 2)
                    packetData[0] = ((seq ushr 8) and 0xFF).toByte()
                    packetData[1] = (seq and 0xFF).toByte()
                    System.arraycopy(data, 0, packetData, 2, data.size)

                    val packet = DatagramPacket(packetData, packetData.size, target, remotePort)
                    socket?.send(packet)
                } catch (e: Exception) {
                    Log.e("DirectUdpAudio", "Error sending UDP audio frame: ${e.message}")
                }
            }
        }

        // Lightweight keepalive/punch loop so both sides immediately learn each other's scoped IPv6 address
        scope.launch {
            val keepAlivePayload = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
            while (isActive && isRunning) {
                val target = remoteAddress
                if (target != null) {
                    try {
                        val packet = DatagramPacket(keepAlivePayload, keepAlivePayload.size, target, remotePort)
                        socket?.send(packet)
                    } catch (_: Exception) {}
                } else {
                    resolveRemoteAddress(currentRemoteIpStr)
                }
                delay(300L)
            }
        }
    }

    fun sendAudio(data: ByteArray) {
        if (!isRunning) return
        outgoingFrames.trySend(data)
    }

    fun stop() {
        isRunning = false
        outgoingFrames.close()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        scope.cancel()
    }
}

class UdpPacketLossTracker(private val windowSize: Int = 100) {
    private var lastSeqNum = -1
    private var receivedCount = 0
    private var expectedCount = 0

    @Synchronized
    fun trackPacket(seqNum: Int) {
        if (lastSeqNum == -1) {
            lastSeqNum = seqNum
            receivedCount = 1
            expectedCount = 1
            return
        }

        val expectedNext = (lastSeqNum + 1) % 32768
        var diff = seqNum - expectedNext
        if (diff < -16384) diff += 32768
        if (diff > 16384) diff -= 32768

        if (diff >= 0) {
            expectedCount += 1 + diff
            receivedCount += 1
            lastSeqNum = seqNum
        } else {
            receivedCount += 1
        }

        if (expectedCount > windowSize) {
            expectedCount /= 2
            receivedCount /= 2
        }
    }

    @Synchronized
    fun getLossRate(): Float {
        if (expectedCount <= 0) return 0f
        val lost = expectedCount - receivedCount
        if (lost <= 0) return 0f
        return (lost.toFloat() / expectedCount) * 100f
    }
}
