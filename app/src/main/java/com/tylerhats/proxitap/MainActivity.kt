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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
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
import com.tylerhats.proxitap.audio.PeerQuality
import com.tylerhats.proxitap.audio.RawParticipant

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
    var isHost by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var hostQrPayload by remember { mutableStateOf<String?>(null) }
    
    val nanImpl = remember { com.tylerhats.proxitap.network.NanImpl(context) }
    val hotspotImpl = remember { com.tylerhats.proxitap.network.HotspotImpl(context) }

    // Request required permissions on startup
    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
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
    var captureIntentData by remember { mutableStateOf<android.content.Intent?>(null) }
    var captureIntentResult by remember { mutableStateOf(0) }
    
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            captureIntentData = result.data
            captureIntentResult = result.resultCode
            pendingLobbyAction?.invoke()
        } else {
            android.widget.Toast.makeText(context, "Permission required for Media Lobby", android.widget.Toast.LENGTH_LONG).show()
        }
        pendingLobbyAction = null
    }
    
    // Bind to CallService
    var callService by remember { mutableStateOf<com.tylerhats.proxitap.audio.CallService?>(null) }
    
    // Helper to get active manager based on mode
    fun getNetworkManager(mode: String?): com.tylerhats.proxitap.network.LocalNetworkManager? {
        val s = callService ?: return null
        return if (mode == "hotspot") s.hotspotImpl else s.nanImpl
    }
    
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
                onHostClick = { name, pin, useHotspot, isMediaLobby, isBidirectional, requiresProjection -> 
                    isHost = true
                    val safePin = if (pin.isNullOrBlank()) "NONE" else pin
                    val startAction = {
                        navController.navigate("lobby?name=$name&pin=$safePin&hotspot=$useHotspot&media=$isMediaLobby&bidi=$isBidirectional&reqProj=$requiresProjection")
                    }
                    if (requiresProjection) {
                        pendingLobbyAction = startAction
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        // For Group Voice (isMediaLobby=true, requiresProjection=false) 
                        // and Direct Voice (isMediaLobby=false, requiresProjection=false)
                        val serviceIntent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java).apply {
                            putExtra("isMediaLobby", isMediaLobby)
                            putExtra("isBidirectional", isBidirectional)
                            putExtra("isGroupVoice", isMediaLobby && !requiresProjection)
                            putExtra("requiresProjection", false)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        startAction()
                    }
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
            LaunchedEffect(callService) {
                if (callService != null) {
                    while(true) {
                        discoveredLobbies.clear()
                        callService?.nanImpl?.discoverLobbies { name, isProtected, isMedia, isBidi, fullPayload ->
                            val existingIndex = discoveredLobbies.indexOfFirst { it.name == name }
                            if (existingIndex != -1) {
                                // Replace the stale cache with the new properties
                                discoveredLobbies[existingIndex] = DiscoveredLobby(name, isProtected, isMedia, isBidi, fullPayload)
                            } else {
                                discoveredLobbies.add(DiscoveredLobby(name, isProtected, isMedia, isBidi, fullPayload))
                            }
                        }
                        kotlinx.coroutines.delay(10000)
                    }
                }
            }
        }
        composable("serverList") {
            ServerListScreen(
                discoveredLobbies = discoveredLobbies,
                onLobbyClick = { lobby, pinInput ->
                    val encoded = java.net.URLEncoder.encode(lobby.payload, "UTF-8")
                    val safePin = if (pinInput.isBlank()) "NONE" else pinInput
                    val parts = lobby.payload.split("|")
                    val groupVoiceEnabled = if (parts.size >= 6) parts[5] == "1" else false
                    navController.navigate("call?mode=nan&service=$encoded&media=${lobby.isMedia}&bidi=${lobby.isBidi}&pin=$safePin&groupVoice=$groupVoiceEnabled")
                },
                onBackClick = {
                    nanImpl.stop()
                    navController.popBackStack()
                }
            )
        }
        composable(
            route = "lobby?name={name}&pin={pin}&hotspot={hotspot}&media={media}&bidi={bidi}&reqProj={reqProj}",
            arguments = listOf(
                androidx.navigation.navArgument("name") { defaultValue = "ProxiTap_Lobby" },
                androidx.navigation.navArgument("pin") { defaultValue = "" },
                androidx.navigation.navArgument("hotspot") { defaultValue = "false" },
                androidx.navigation.navArgument("media") { defaultValue = "false" },
                androidx.navigation.navArgument("bidi") { defaultValue = "false" },
                androidx.navigation.navArgument("reqProj") { defaultValue = "false" }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: "ProxiTap_Lobby"
            val pinArg = backStackEntry.arguments?.getString("pin") ?: ""
            val pin = if (pinArg == "NONE") "" else pinArg
            val useHotspot = backStackEntry.arguments?.getString("hotspot") == "true"
            val isMediaLobby = backStackEntry.arguments?.getString("media") == "true"
            val isBidirectional = backStackEntry.arguments?.getString("bidi") == "true"
            val requiresProjection = backStackEntry.arguments?.getString("reqProj") == "true"
            val isGroupVoice = isMediaLobby && !requiresProjection
            val modeStr = if (useHotspot) "hotspot" else "nan"
            val activeManager = getNetworkManager(modeStr)
            
            var servicePayload by remember { mutableStateOf<String?>(null) }
            var hostNetworkReady by remember { mutableStateOf(false) }
            
            LaunchedEffect(name, pin, activeManager) {
                if (activeManager != null) {
                    try {
                        servicePayload = activeManager.startHosting(name, pin, isMediaLobby, isBidirectional, isGroupVoice)
                        hostNetworkReady = true
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to start hosting", e)
                    }
                }
            }

            val hasJoined by (callService?.hasJoined ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()

            LaunchedEffect(hostNetworkReady, callService, hasJoined) {
                if (hostNetworkReady && callService != null && !hasJoined) {
                    val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                    intent.putExtra("isMediaLobby", isMediaLobby)
                    intent.putExtra("isBidirectional", isBidirectional)
                    intent.putExtra("isGroupVoice", isGroupVoice)
                    intent.putExtra("requiresProjection", requiresProjection)
                    if (isMediaLobby && requiresProjection && captureIntentData != null) {
                        intent.putExtra("media_projection_data", captureIntentData)
                        intent.putExtra("media_projection_result", captureIntentResult)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    callService?.setHasJoined(true)
                    callService?.startHostSignaling()
                }
            }

            val finalPayload = remember(servicePayload) {
                servicePayload?.let { payload ->
                    val cleanPayload = payload.removePrefix("PROXI:NAN:S:").removePrefix("WIFI:T:WPA;S:")
                    val encodedPayload = java.net.URLEncoder.encode(cleanPayload, "UTF-8")
                    "https://pt.htsth.app/join?mode=$modeStr&service=$encodedPayload&media=$isMediaLobby&bidi=$isBidirectional&groupVoice=$isGroupVoice"
                }
            }

            var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

            LaunchedEffect(finalPayload) {
                if (finalPayload != null) {
                    hostQrPayload = finalPayload
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        qrBitmap = com.tylerhats.proxitap.ui.screens.generateQrCode(finalPayload, 300)
                    }
                } else {
                    qrBitmap = null
                }
            }

            if (finalPayload != null && qrBitmap != null) {
                LobbyScreen(
                    qrPayload = finalPayload,
                    qrBitmap = qrBitmap,
                    onStartCallClick = { 
                        navController.navigate("call?mode=$modeStr&media=$isMediaLobby&bidi=$isBidirectional&groupVoice=$isGroupVoice") 
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
                    // Use Uri.parse to properly trigger the deep link resolution
                    try {
                        val uri = android.net.Uri.parse(url)
                        navController.navigate(uri)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to parse QR URL", e)
                    }
                },
                onCancelClick = { navController.popBackStack() }
            )
        }
        composable("qr?payload={payload}") { backStackEntry ->
            val payload = backStackEntry.arguments?.getString("payload")
            LobbyScreen(
                qrPayload = payload ?: "",
                buttonText = "Return to Call",
                onStartCallClick = { navController.popBackStack() }
            )
        }
        composable(
            route = "call?mode={mode}&service={service}&pin={pin}&media={media}&bidi={bidi}&groupVoice={groupVoice}",
            deepLinks = listOf(navDeepLink { uriPattern = "https://pt.htsth.app/join?mode={mode}&service={service}&pin={pin}&media={media}&bidi={bidi}&groupVoice={groupVoice}" })
        ) { backStackEntry ->
            val service = backStackEntry.arguments?.getString("service")
            val mode = backStackEntry.arguments?.getString("mode") ?: "nan"
            val isMediaLobby = backStackEntry.arguments?.getString("media") == "true"
            val isBidirectional = backStackEntry.arguments?.getString("bidi") == "true"
            val isGroupVoice = backStackEntry.arguments?.getString("groupVoice") == "true"
            val pinArg = backStackEntry.arguments?.getString("pin") ?: ""
            val pin = if (pinArg == "NONE") "" else pinArg
            val activeManager = getNetworkManager(mode)
            
            val isDeepLinkJoin = service != null
            if (isDeepLinkJoin) isHost = false

            var isJoiningLobby by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isDeepLinkJoin) }
            val hasJoined by (callService?.hasJoined ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            
            val isConnecting by (callService?.isConnecting ?: kotlinx.coroutines.flow.MutableStateFlow(isDeepLinkJoin)).collectAsState()
            val isReconnecting by (callService?.isReconnecting ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            
            var showPeerMediaProjectionPrompt by remember { 
                mutableStateOf(isDeepLinkJoin && isMediaLobby && isBidirectional && !isGroupVoice && captureIntentData == null) 
            }

            LaunchedEffect(service, activeManager, hasJoined, showPeerMediaProjectionPrompt) {
                if (isDeepLinkJoin && service != null && activeManager != null && !hasJoined && !showPeerMediaProjectionPrompt) {
                    val joinAction = suspend {
                        try {
                            val prefix = if (mode == "hotspot") "WIFI:T:WPA;S:" else "PROXI:NAN:S:"
                            val payload = "$prefix$service"
                            val joined = activeManager.joinLobby(payload, pin)
                            
                            if (joined) {
                                val intent = android.content.Intent(context, com.tylerhats.proxitap.audio.CallService::class.java)
                                intent.putExtra("isMediaLobby", isMediaLobby)
                                intent.putExtra("isBidirectional", isBidirectional)
                                intent.putExtra("isGroupVoice", isGroupVoice)
                                val requiresProj = isMediaLobby && !isGroupVoice
                                intent.putExtra("requiresProjection", requiresProj)
                                if (requiresProj && captureIntentData != null) {
                                    intent.putExtra("media_projection_data", captureIntentData)
                                    intent.putExtra("media_projection_result", captureIntentResult)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                isJoiningLobby = false
                            } else {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, "Failed to connect. Incorrect PIN or Host unavailable.", android.widget.Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to join lobby", e)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Error connecting to lobby.", android.widget.Toast.LENGTH_LONG).show()
                                navController.popBackStack()
                            }
                        }
                    }
                    
                    joinAction()
                }
            }

            LaunchedEffect(isJoiningLobby, callService, hasJoined) {
                if (!isJoiningLobby && !isHost && callService != null && !hasJoined) {
                    val hostIp = getNetworkManager(mode)?.getLocalIpAddress()
                    if (hostIp != null) {
                        callService?.setHasJoined(true)
                        // Pass IPv6 address in brackets if it is IPv6
                        val formattedIp = if (hostIp.contains(":")) "[$hostIp]" else hostIp
                        callService?.startPeerSignaling(formattedIp)
                    }
                }
            }

            // Distance tracking is removed

            DisposableEffect(context) {
                val activity = context as? android.app.Activity
                if (activity != null) {
                    val isVoiceStream = !isMediaLobby || isGroupVoice
                    activity.volumeControlStream = if (isVoiceStream) {
                        android.media.AudioManager.STREAM_VOICE_CALL
                    } else {
                        android.media.AudioManager.STREAM_MUSIC
                    }
                }
                
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                        if (intent?.action == "com.tylerhats.proxitap.CALL_ENDED") {
                            (context as? android.app.Activity)?.finishAffinity()
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
                    activity?.volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
                }
            }

            val isSpeaking by callService?.isSpeaking?.collectAsState() ?: remember { mutableStateOf(false) }
            val isMuted by callService?.isMuted?.collectAsState() ?: remember { mutableStateOf(false) }
            val participants by (callService?.participants ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
            val stats by (callService?.connectionStats ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
            val peerQualities by (callService?.peerQualities ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
            val rawParticipants by (callService?.rawParticipants ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
            
            val isActualHost = callService?.isHostSignaling ?: isHost
            
            if (showPeerMediaProjectionPrompt) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Bidirectional Media Sharing", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "This lobby is configured for Bidirectional Media Sharing, meaning you will share your device's system audio while listening to others.\n\nTo join, please grant screen/audio capture permission.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                pendingLobbyAction = {
                                    showPeerMediaProjectionPrompt = false
                                }
                                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Grant Permission & Join")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            } else {
                CallScreen(
                    isHost = isActualHost,
                    isMuted = isMuted,
                    isSpeaking = isSpeaking,
                    isConnecting = isConnecting,
                    isReconnecting = isReconnecting,
                    isMediaLobby = isMediaLobby,
                    isGroupVoice = callService?.isGroupVoice ?: isGroupVoice,
                    participants = participants,
                    rawParticipants = rawParticipants,
                    peerQualities = peerQualities,
                    stats = stats,
                    onMuteToggle = { callService?.toggleMute() },
                    onEndCallClick = { 
                        callService?.endCall()
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onSettingsClick = { navController.navigate("settings?media=$isMediaLobby&host=$isActualHost&group=${callService?.isGroupVoice ?: false}&bidi=$isBidirectional") },
                    onWebPlayerClick = { 
                        val intent = android.content.Intent(context, com.tylerhats.proxitap.ui.screens.WebBrowserActivity::class.java)
                        context.startActivity(intent)
                    },
                    onShowQrCodeClick = { 
                        hostQrPayload?.let { payload ->
                            navController.navigate("qr?payload=${java.net.URLEncoder.encode(payload, "UTF-8")}")
                        }
                    }
                )
            }
        }
        composable(
            route = "settings?media={media}&host={host}&group={group}&bidi={bidi}",
            arguments = listOf(
                androidx.navigation.navArgument("media") { defaultValue = "false" },
                androidx.navigation.navArgument("host") { defaultValue = "true" },
                androidx.navigation.navArgument("group") { defaultValue = "false" },
                androidx.navigation.navArgument("bidi") { defaultValue = "false" }
            )
        ) { backStackEntry ->
            val isMediaRoute = backStackEntry.arguments?.getString("media") == "true"
            val isHostRoute = backStackEntry.arguments?.getString("host") == "true"
            val isGroupRoute = backStackEntry.arguments?.getString("group") == "true"
            val isBidiRoute = backStackEntry.arguments?.getString("bidi") == "true"
            SettingsScreen(
                isHost = isHostRoute,
                isMediaLobby = isMediaRoute,
                isGroupVoice = isGroupRoute,
                isBidirectional = isBidiRoute,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
