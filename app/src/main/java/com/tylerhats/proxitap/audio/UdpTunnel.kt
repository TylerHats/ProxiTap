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

