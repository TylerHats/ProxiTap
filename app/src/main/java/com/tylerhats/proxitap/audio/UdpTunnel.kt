package com.tylerhats.proxitap.audio

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class UdpProxy(
    val localListenPort: Int,
    var localTargetPort: Int?,
    private val onUdpDataReceived: (ByteArray) -> Unit
) {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private val localAddress = InetAddress.getByName("127.0.0.1")

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                socket = DatagramSocket(localListenPort, localAddress)
                val buffer = ByteArray(65535)
                Log.d("UdpProxy", "Started UDP Proxy listening on $localListenPort, targeting $localTargetPort")
                
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
                Log.e("UdpProxy", "Error receiving UDP on port $localListenPort", e)
            }
        }
    }

    fun sendUdpData(data: ByteArray) {
        val targetPort = localTargetPort ?: return
        scope.launch {
            try {
                val packet = DatagramPacket(data, data.size, localAddress, targetPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e("UdpProxy", "Error sending UDP data to $targetPort", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        scope.cancel()
    }
}

class DirectUdpAudioStreamer(
    private val localPort: Int,
    private val remoteIp: String,
    private val remotePort: Int,
    private val onAudioFrameReceived: (ByteArray) -> Unit
) {
    private var socket: java.net.DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var sendSeqNum = 0.toShort()
    val lossTracker = UdpPacketLossTracker()

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                val host = remoteIp.replace("[", "").replace("]", "")
                val remoteAddress = java.net.InetAddress.getByName(host)
                socket = java.net.DatagramSocket(localPort)
                val buffer = ByteArray(4096)
                Log.d("DirectUdpAudio", "Listening on UDP port $localPort, sending to $host:$remotePort")
                
                while (isActive && isRunning) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    if (packet.length < 2) continue
                    val data = packet.data
                    
                    // Parse 2-byte sequence number
                    val seqNum = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
                    lossTracker.trackPacket(seqNum)
                    
                    // Extract audio frame payload
                    val rawAudio = ByteArray(packet.length - 2)
                    System.arraycopy(data, 2, rawAudio, 0, rawAudio.size)
                    
                    onAudioFrameReceived(rawAudio)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("DirectUdpAudio", "Error in UDP receiver", e)
                }
            }
        }
    }

    fun sendAudio(data: ByteArray) {
        if (!isRunning) return
        try {
            val host = remoteIp.replace("[", "").replace("]", "")
            val remoteAddress = java.net.InetAddress.getByName(host)
            
            // Prepend 2-byte sequence number to outgoing frame
            val packetData = ByteArray(data.size + 2)
            packetData[0] = ((sendSeqNum.toInt() ushr 8) and 0xFF).toByte()
            packetData[1] = (sendSeqNum.toInt() and 0xFF).toByte()
            System.arraycopy(data, 0, packetData, 2, data.size)
            
            sendSeqNum = ((sendSeqNum + 1) % 32768).toShort()
            
            val packet = java.net.DatagramPacket(packetData, packetData.size, remoteAddress, remotePort)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e("DirectUdpAudio", "Error sending UDP audio", e)
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
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

