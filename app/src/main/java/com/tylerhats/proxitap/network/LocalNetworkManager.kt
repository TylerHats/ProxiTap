package com.tylerhats.proxitap.network

interface LocalNetworkManager {
    suspend fun startHosting(lobbyName: String, hasPin: Boolean): String
    fun stop()
    suspend fun joinLobby(payload: String): Boolean
    fun getLocalIpAddress(): String?
    fun discoverLobbies(onLobbyFound: (String, Boolean, String) -> Unit)
}
