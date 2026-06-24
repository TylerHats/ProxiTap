package com.tylerhats.proxitap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.tylerhats.proxitap.ui.screens.*
import com.tylerhats.proxitap.ui.theme.ProxiTapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProxiTapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProxiTapApp()
                }
            }
        }
    }
}

@Composable
fun ProxiTapApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    var isHost by remember { mutableStateOf(false) }
    
    val nanImpl = remember { com.tylerhats.proxitap.network.NanImpl(context) }
    val hotspotImpl = remember { com.tylerhats.proxitap.network.HotspotImpl(context) }
    
    // Helper to get active manager based on mode
    fun getNetworkManager(mode: String?): com.tylerhats.proxitap.network.LocalNetworkManager {
        return if (mode == "hotspot") hotspotImpl else nanImpl
    }
    
    // Bind to CallService
    var callService by remember { mutableStateOf<com.tylerhats.proxitap.audio.CallService?>(null) }
    
    DisposableEffect(context) {
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                val binder = service as com.tylerhats.proxitap.audio.CallService.LocalBinder
                callService = binder.getService()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                callService = null
            }
        }
        val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
        context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(connection)
        }
    }
    
    val discoveredLobbies = remember { mutableStateListOf<DiscoveredLobby>() }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onHostClick = { name, pin, useHotspot, forceRnnoise -> 
                    isHost = true
                    navController.navigate("lobby?name=$name&pin=${pin ?: ""}&hotspot=$useHotspot") 
                },
                onJoinClick = { 
                    isHost = false
                    navController.navigate("scanner") 
                },
                onSearchAreaClick = {
                    isHost = false
                    discoveredLobbies.clear()
                    nanImpl.discoverLobbies { name, isProtected, payload ->
                        // Prevent duplicates
                        if (discoveredLobbies.none { it.name == name }) {
                            discoveredLobbies.add(DiscoveredLobby(name, isProtected, payload))
                        }
                    }
                    navController.navigate("serverList")
                }
            )
        }
        composable("serverList") {
            ServerListScreen(
                discoveredLobbies = discoveredLobbies,
                onLobbyClick = { lobby ->
                    // Pass the full payload to the CallScreen route
                    navController.navigate("call?mode=nan&service=${lobby.payload}&pin=")
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(
            route = "lobby?name={name}&pin={pin}&hotspot={hotspot}",
            arguments = listOf(
                androidx.navigation.navArgument("name") { defaultValue = "ProxiTap_Lobby" },
                androidx.navigation.navArgument("pin") { defaultValue = "" },
                androidx.navigation.navArgument("hotspot") { defaultValue = "false" }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: "ProxiTap_Lobby"
            val pin = backStackEntry.arguments?.getString("pin") ?: ""
            val useHotspot = backStackEntry.arguments?.getString("hotspot") == "true"
            val activeManager = if (useHotspot) hotspotImpl else nanImpl
            val modeStr = if (useHotspot) "hotspot" else "nan"
            
            var servicePayload by remember { mutableStateOf<String?>(null) }
            var hostNetworkReady by remember { mutableStateOf(false) }
            
            LaunchedEffect(name, pin) {
                try {
                    servicePayload = activeManager.startHosting(name, pin.isNotBlank())
                    hostNetworkReady = true
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to start hosting", e)
                }
            }

            LaunchedEffect(hostNetworkReady, callService) {
                if (hostNetworkReady && callService != null) {
                    val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    callService?.startHostSignaling()
                }
            }

            if (servicePayload != null) {
                val cleanPayload = servicePayload!!.removePrefix("PROXI:NAN:S:").removePrefix("PROXI:HOTSPOT:S:")
                LobbyScreen(
                    qrPayload = "https://pt.htsth.app/join?mode=$modeStr&service=$cleanPayload",
                    onStartCallClick = { navController.navigate("call") }
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        }
        composable("scanner") {
            ScannerScreen(
                onQrCodeScanned = { url -> 
                    navController.navigate("call") 
                },
                onCancelClick = { navController.popBackStack() }
            )
        }
        composable(
            route = "call?mode={mode}&service={service}&pin={pin}",
            deepLinks = listOf(navDeepLink { uriPattern = "https://pt.htsth.app/join?mode={mode}&service={service}&pin={pin}" })
        ) { backStackEntry ->
            val service = backStackEntry.arguments?.getString("service")
            val mode = backStackEntry.arguments?.getString("mode") ?: "nan"
            val activeManager = getNetworkManager(mode)
            
            val isDeepLinkJoin = service != null
            if (isDeepLinkJoin) isHost = false

            var isMuted by remember { mutableStateOf(false) }
            var isReconnecting by remember { mutableStateOf(isDeepLinkJoin) }

            LaunchedEffect(service) {
                if (isDeepLinkJoin && service != null) {
                    try {
                        val prefix = if (mode == "hotspot") "WIFI:T:WPA;S:" else "PROXI:NAN:S:"
                        val payload = "$prefix$service"
                        activeManager.joinLobby(payload)
                        
                        val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        
                        isReconnecting = false
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to join lobby", e)
                    }
                }
            }

            LaunchedEffect(isReconnecting, callService) {
                if (!isReconnecting && !isHost && callService != null) {
                    val hostIp = activeManager.getLocalIpAddress()
                    if (hostIp != null) {
                        // Pass IPv6 address in brackets if it is IPv6
                        val formattedIp = if (hostIp.contains(":")) "[$hostIp]" else hostIp
                        callService?.startPeerSignaling(formattedIp)
                    }
                }
            }

            val isSpeaking by callService?.isSpeaking?.collectAsState() ?: remember { mutableStateOf(false) }

            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
                isSpeaking = isSpeaking,
                isReconnecting = isReconnecting,
                onMuteToggle = { isMuted = !isMuted },
                onEndCallClick = { 
                    nanImpl.stop()
                    hotspotImpl.stop()
                    val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                    context.stopService(intent)
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
