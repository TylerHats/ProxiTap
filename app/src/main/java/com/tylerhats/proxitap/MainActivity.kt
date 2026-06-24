package com.tylerhats.proxitap

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
                onHostClick = { 
                    isHost = true
                    navController.navigate("lobby") 
                },
                onJoinClick = { 
                    isHost = false
                    navController.navigate("scanner") 
                }
            )
        }
        composable("lobby") {
            LobbyScreen(
                qrPayload = "PROXI:NAN:S:ProxiTap_Lobby_123",
                onStartCallClick = { navController.navigate("call") }
            )
        }
        composable("scanner") {
            ScannerScreen(
                onQrCodeScanned = { navController.navigate("call") },
                onCancelClick = { navController.popBackStack() }
            )
        }
        composable("call") {
            var isMuted by remember { mutableStateOf(false) }
            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
                onMuteToggle = { isMuted = !isMuted },
                onEndCallClick = { 
                    // Clean up and go home
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
