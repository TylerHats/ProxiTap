package com.tylerhats.proxitap.network

/**
 * Abstract interface for local P2P networking protocols (Hotspot vs NAN).
 */
interface LocalNetworkManager {
    
    /**
     * Starts hosting a lobby.
     * @return A string representing the connection payload (e.g., SSID/Password for Hotspot, or MAC/Service Name for NAN) to be encoded into the QR Code.
     */
    suspend fun startHosting(): String

    /**
     * Stops hosting or disconnects from a lobby.
     */
    fun stop()

    /**
     * For peers: Connects to a lobby using the parsed QR code payload.
     * @param payload The data decoded from the QR code.
     * @return True if connection was successful.
     */
    suspend fun joinLobby(payload: String): Boolean

    /**
     * Returns the local IP address assigned to this device on the P2P network.
     * Needed for Ktor signaling.
     */
    fun getLocalIpAddress(): String?
}
