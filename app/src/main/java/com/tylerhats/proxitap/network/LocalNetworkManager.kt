package com.tylerhats.proxitap.network

interface LocalNetworkManager {
    suspend fun startHosting(lobbyName: String, pin: String, isMediaLobby: Boolean = false, isBidirectional: Boolean = false): String
    fun stop()
    suspend fun joinLobby(payload: String, pin: String = ""): Boolean
    fun getLocalIpAddress(): String?
    fun discoverLobbies(onLobbyFound: (String, Boolean, Boolean, Boolean, String) -> Unit)
}
