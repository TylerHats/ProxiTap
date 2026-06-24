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
import androidx.compose.ui.platform.LocalContext
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
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onHostClick = { pin -> 
                    isHost = true
                    // In a real app we'd pass the PIN to the ViewModel that manages the NanImpl broadcast.
                    // For UI flow, we'll embed it in the Lobby URL.
                    navController.navigate("lobby?pin=${pin ?: ""}") 
                },
                onJoinClick = { 
                    isHost = false
                    navController.navigate("scanner") 
                }
            )
        }
        composable(
            route = "lobby?pin={pin}",
            arguments = listOf(androidx.navigation.navArgument("pin") { defaultValue = "" })
        ) { backStackEntry ->
            val pin = backStackEntry.arguments?.getString("pin") ?: ""
            val pinParam = if (pin.isNotBlank()) "&pin=$pin" else ""
            LobbyScreen(
                qrPayload = "https://pt.htsth.app/join?mode=nan&service=ProxiTap_Lobby_123$pinParam",
                onStartCallClick = { navController.navigate("call") }
            )
        }
        composable("scanner") {
            ScannerScreen(
                onQrCodeScanned = { navController.navigate("call") },
                onCancelClick = { navController.popBackStack() }
            )
        }
        composable(
            route = "call?mode={mode}&service={service}&pin={pin}",
            deepLinks = listOf(navDeepLink { uriPattern = "https://pt.htsth.app/join?mode={mode}&service={service}&pin={pin}" })
        ) { backStackEntry ->
            // If the user arrived via deep link, they are definitely joining (not hosting)
            val isDeepLinkJoin = backStackEntry.arguments?.getString("service") != null
            if (isDeepLinkJoin) isHost = false

            var isMuted by remember { mutableStateOf(false) }
            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
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
