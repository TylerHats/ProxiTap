package com.tylerhats.proxitap

import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch

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

    // Request required permissions on startup
    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results if needed
    }

    LaunchedEffect(Unit) {
        val ungrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungrantedPermissions.isNotEmpty()) {
            permissionLauncher.launch(ungrantedPermissions.toTypedArray())
        }
    }
    
    val mediaProjectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    var pendingLobbyAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
            intent.putExtra("media_projection_data", result.data)
            intent.putExtra("media_projection_result", result.resultCode)
            // The actual flags (isMediaLobby/isBidirectional) will be set when the lobby starts in LaunchedEffect
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            pendingLobbyAction?.invoke()
        } else {
            android.widget.Toast.makeText(context, "Permission required for Media Lobby", android.widget.Toast.LENGTH_LONG).show()
        }
        pendingLobbyAction = null
    }
    
    // Helper to get active manager based on mode
    fun getNetworkManager(mode: String?): com.tylerhats.proxitap.network.LocalNetworkManager {
        return if (mode == "hotspot") hotspotImpl else nanImpl
    }
    
    val distanceTracker = remember { com.tylerhats.proxitap.network.DistanceTracker(context) }
    
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
                onHostClick = { name, pin, useHotspot, enableRadar, isMediaLobby, isBidirectional -> 
                    isHost = true
                    val startAction = {
                        navController.navigate("lobby?name=$name&pin=${pin ?: ""}&hotspot=$useHotspot&radar=$enableRadar&media=$isMediaLobby&bidi=$isBidirectional")
                    }
                    if (isMediaLobby) {
                        pendingLobbyAction = startAction
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        startAction()
                    }
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
            route = "lobby?name={name}&pin={pin}&hotspot={hotspot}&radar={radar}&media={media}&bidi={bidi}",
            arguments = listOf(
                androidx.navigation.navArgument("name") { defaultValue = "ProxiTap_Lobby" },
                androidx.navigation.navArgument("pin") { defaultValue = "" },
                androidx.navigation.navArgument("hotspot") { defaultValue = "false" },
                androidx.navigation.navArgument("radar") { defaultValue = "false" },
                androidx.navigation.navArgument("media") { defaultValue = "false" },
                androidx.navigation.navArgument("bidi") { defaultValue = "false" }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: "ProxiTap_Lobby"
            val pin = backStackEntry.arguments?.getString("pin") ?: ""
            val useHotspot = backStackEntry.arguments?.getString("hotspot") == "true"
            val enableRadar = backStackEntry.arguments?.getString("radar") == "true"
            val isMediaLobby = backStackEntry.arguments?.getString("media") == "true"
            val isBidirectional = backStackEntry.arguments?.getString("bidi") == "true"
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
                    intent.putExtra("isMediaLobby", isMediaLobby)
                    intent.putExtra("isBidirectional", isBidirectional)
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
                    qrPayload = "https://pt.htsth.app/join?mode=$modeStr&radar=$enableRadar&service=$cleanPayload&media=$isMediaLobby&bidi=$isBidirectional",
                    onStartCallClick = { 
                        navController.navigate("call?mode=$modeStr&radar=$enableRadar&media=$isMediaLobby&bidi=$isBidirectional") 
                    }
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
            route = "call?mode={mode}&radar={radar}&service={service}&pin={pin}&media={media}&bidi={bidi}",
            deepLinks = listOf(navDeepLink { uriPattern = "https://pt.htsth.app/join?mode={mode}&radar={radar}&service={service}&pin={pin}&media={media}&bidi={bidi}" })
        ) { backStackEntry ->
            val service = backStackEntry.arguments?.getString("service")
            val mode = backStackEntry.arguments?.getString("mode") ?: "nan"
            val enableRadar = backStackEntry.arguments?.getString("radar") == "true"
            val isMediaLobby = backStackEntry.arguments?.getString("media") == "true"
            val isBidirectional = backStackEntry.arguments?.getString("bidi") == "true"
            val activeManager = getNetworkManager(mode)
            
            val isDeepLinkJoin = service != null
            if (isDeepLinkJoin) isHost = false

            var isReconnecting by remember { mutableStateOf(isDeepLinkJoin) }

            LaunchedEffect(service) {
                if (isDeepLinkJoin && service != null) {
                    val joinAction = suspend {
                        try {
                            val prefix = if (mode == "hotspot") "WIFI:T:WPA;S:" else "PROXI:NAN:S:"
                            val payload = "$prefix$service"
                            activeManager.joinLobby(payload)
                            
                            val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                            intent.putExtra("isMediaLobby", isMediaLobby)
                            intent.putExtra("isBidirectional", isBidirectional)
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
                    
                    if (isBidirectional && !isHost) {
                        pendingLobbyAction = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { joinAction() }
                        }
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        joinAction()
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

            // Radar Distance Tracking (Only for NAN mode when enabled)
            val connectedNanPeers by nanImpl.connectedPeers.collectAsState()
            LaunchedEffect(enableRadar, connectedNanPeers) {
                if (enableRadar && mode == "nan") {
                    distanceTracker.isTrackingEnabled = true
                    distanceTracker.startTracking(connectedNanPeers)
                } else {
                    distanceTracker.stopTracking()
                }
            }

            val peerDistances by distanceTracker.peerDistances.collectAsState()
            // We only have one peer in V1 (Host->Peer or Peer->Host), so we take the first distance if available
            val currentDistanceMeters = peerDistances.values.firstOrNull()

            DisposableEffect(context) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                        if (intent?.action == "com.tylerhats.proxitap.CALL_ENDED") {
                            nanImpl.stop()
                            hotspotImpl.stop()
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                        }
                    }
                }
                val filter = android.content.IntentFilter("com.tylerhats.proxitap.CALL_ENDED")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                onDispose {
                    context.unregisterReceiver(receiver)
                }
            }

            val isSpeaking by callService?.isSpeaking?.collectAsState() ?: remember { mutableStateOf(false) }
            val isMuted by callService?.isMuted?.collectAsState() ?: remember { mutableStateOf(false) }

            CallScreen(
                isHost = isHost,
                isMuted = isMuted,
                isSpeaking = isSpeaking,
                distanceMeters = currentDistanceMeters,
                isReconnecting = isReconnecting,
                onMuteToggle = { callService?.toggleMute() },
                onEndCallClick = { 
                    nanImpl.stop()
                    hotspotImpl.stop()
                    distanceTracker.stopTracking()
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
