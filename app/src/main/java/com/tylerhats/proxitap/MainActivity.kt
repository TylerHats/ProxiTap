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
    val webRtcClient = remember { com.tylerhats.proxitap.audio.WebRtcClient(context) }
    
    val discoveredLobbies = remember { mutableStateListOf<DiscoveredLobby>() }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onHostClick = { name, pin -> 
                    isHost = true
                    navController.navigate("lobby?name=$name&pin=${pin ?: ""}") 
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
            route = "lobby?name={name}&pin={pin}",
            arguments = listOf(
                androidx.navigation.navArgument("name") { defaultValue = "ProxiTap_Lobby" },
                androidx.navigation.navArgument("pin") { defaultValue = "" }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: "ProxiTap_Lobby"
            val pin = backStackEntry.arguments?.getString("pin") ?: ""
            
            var servicePayload by remember { mutableStateOf<String?>(null) }
            
            LaunchedEffect(name, pin) {
                try {
                    servicePayload = nanImpl.startHosting(name, pin.isNotBlank())
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to start hosting", e)
                }
            }

            if (servicePayload != null) {
                // Remove the PROXI:NAN:S: prefix for the deep link, if it exists
                val cleanPayload = servicePayload!!.removePrefix("PROXI:NAN:S:")
                LobbyScreen(
                    qrPayload = "https://pt.htsth.app/join?mode=nan&service=$cleanPayload",
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
            val isDeepLinkJoin = service != null
            if (isDeepLinkJoin) isHost = false

            var isMuted by remember { mutableStateOf(false) }
            var isSpeaking by remember { mutableStateOf(false) }
            var isReconnecting by remember { mutableStateOf(isDeepLinkJoin) }

            LaunchedEffect(service) {
                if (isDeepLinkJoin && service != null) {
                    try {
                        // Reconstruct the payload the way NanImpl expects it
                        val payload = "PROXI:NAN:S:$service"
                        nanImpl.joinLobby(payload)
                        isReconnecting = false
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to join lobby", e)
                    }
                }
            }

            LaunchedEffect(Unit) {
                webRtcClient.startAudioLevelMonitoring { speaking ->
                    isSpeaking = speaking
                }
            }

            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
                isSpeaking = isSpeaking,
                isReconnecting = isReconnecting,
                onMuteToggle = { isMuted = !isMuted },
                onEndCallClick = { 
                    nanImpl.stop()
                    webRtcClient.dispose()
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
