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
    val navController = rememberNavController()
    var isHost by remember { mutableStateOf(false) }
    
    val discoveredLobbies = remember {
        mutableStateListOf(
            DiscoveredLobby("Tyler's Lobby", false),
            DiscoveredLobby("Secret Commute Channel", true)
        )
    }

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
                    navController.navigate("serverList")
                }
            )
        }
        composable("serverList") {
            ServerListScreen(
                discoveredLobbies = discoveredLobbies,
                onLobbyClick = { lobby ->
                    navController.navigate("call?mode=nan&service=${lobby.name}&pin=")
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
            val pinParam = if (pin.isNotBlank()) "&pin=$pin" else ""
            LobbyScreen(
                qrPayload = "https://pt.htsth.app/join?mode=nan&service=$name$pinParam",
                onStartCallClick = { navController.navigate("call") }
            )
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
            val isDeepLinkJoin = backStackEntry.arguments?.getString("service") != null
            if (isDeepLinkJoin) isHost = false

            var isMuted by remember { mutableStateOf(false) }
            val isSpeaking by remember { mutableStateOf((0..1).random() == 1) }

            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
                isSpeaking = isSpeaking,
                onMuteToggle = { isMuted = !isMuted },
                onEndCallClick = { 
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
