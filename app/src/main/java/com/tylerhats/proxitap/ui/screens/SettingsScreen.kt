package com.tylerhats.proxitap.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    isHost: Boolean = true,
    isMediaLobby: Boolean = false,
    isGroupVoice: Boolean = false,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ProxiTapSettings", android.content.Context.MODE_PRIVATE) }
    
    var nsEnabled by remember { mutableStateOf(prefs.getBoolean("ns_enabled", true)) }
    var aecEnabled by remember { mutableStateOf(prefs.getBoolean("aec_enabled", true)) }
    var bluetoothMic by remember { mutableStateOf(prefs.getBoolean("bluetooth_mic", true)) }
    var hardwarePtt by remember { mutableStateOf(prefs.getBoolean("hardware_ptt", false)) }
    var volumePtt by remember { mutableStateOf(prefs.getBoolean("volume_ptt", false)) }
    var showVolumePttDialog by remember { mutableStateOf(false) }
    
    val bitrateOptions = remember { listOf(8000, 16000, 32000, 64000, 128000, 256000) }
    var currentBitrateIndex by remember {
        val currentBitrate = prefs.getInt("opus_bitrate", 64000)
        val idx = bitrateOptions.indexOf(currentBitrate)
        mutableStateOf(if (idx != -1) idx.toFloat() else 3f)
    }
    
    var maxParticipants by remember { mutableStateOf(prefs.getInt("max_participants", 4)) }
    var opusDtx by remember { mutableStateOf(prefs.getBoolean("opus_dtx", true)) }
    var displayName by remember { mutableStateOf(prefs.getString("display_name", android.os.Build.MODEL) ?: android.os.Build.MODEL) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "ns_enabled" -> nsEnabled = prefs.getBoolean(key, true)
                "aec_enabled" -> aecEnabled = prefs.getBoolean(key, true)
                "bluetooth_mic" -> bluetoothMic = prefs.getBoolean(key, true)
                "opus_dtx" -> opusDtx = prefs.getBoolean(key, true)
                "opus_bitrate" -> {
                    val bitrateVal = prefs.getInt(key, 64000)
                    val idx = bitrateOptions.indexOf(bitrateVal)
                    if (idx != -1) {
                        currentBitrateIndex = idx.toFloat()
                    }
                }
                "max_participants" -> maxParticipants = prefs.getInt(key, 4)
                "hardware_ptt" -> hardwarePtt = prefs.getBoolean(key, false)
                "volume_ptt" -> volumePtt = prefs.getBoolean(key, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)
    ) {
        Text("Profile & Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = displayName,
            onValueChange = { 
                displayName = it
                prefs.edit().putString("display_name", it).apply()
            },
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (isMediaLobby) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = if (isGroupVoice) {
                        "Group Voice Active: Running high-efficiency WebSocket group audio. WebRTC constraints are bypassed."
                    } else {
                        "Media Lobby Active: Audio processing is completely bypassed to stream system audio. WebRTC settings are hidden."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (!isMediaLobby) {
            Text("Audio Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
        
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Noise Suppression (NS)")
                Switch(
                    checked = nsEnabled,
                    onCheckedChange = { 
                        nsEnabled = it
                        prefs.edit().putBoolean("ns_enabled", it).apply()
                    },
                    enabled = isHost
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Acoustic Echo Cancellation (AEC)")
                Switch(
                    checked = aecEnabled,
                    onCheckedChange = { 
                        aecEnabled = it
                        prefs.edit().putBoolean("aec_enabled", it).apply()
                    },
                    enabled = isHost
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bluetooth Mic vs Music Quality")
                    Text(
                        text = if (bluetoothMic) "Using Earbud Mic (Drops music quality)" else "Using Phone Mic (High music quality)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = bluetoothMic,
                    onCheckedChange = { 
                        bluetoothMic = it
                        prefs.edit().putBoolean("bluetooth_mic", it).apply()
                    },
                    enabled = isHost
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Opus DTX (Bandwidth Saver)")
                    Text(
                        text = "Halts transmission during silence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = opusDtx,
                    onCheckedChange = { 
                        opusDtx = it
                        prefs.edit().putBoolean("opus_dtx", it).apply()
                    },
                    enabled = isHost
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Opus Bitrate (Quality)")
                    val currentBitrateVal = bitrateOptions[currentBitrateIndex.roundToInt()]
                    Text("${currentBitrateVal / 1000} kbps", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = currentBitrateIndex,
                    onValueChange = { 
                        currentBitrateIndex = it
                        val index = it.roundToInt()
                        val selectedBitrate = bitrateOptions[index]
                        prefs.edit().putInt("opus_bitrate", selectedBitrate).apply()
                    },
                    valueRange = 0f..5f,
                    steps = 4,
                    enabled = isHost
                )
                Text(
                    text = "Snaps to: 8, 16, 32, 64, 128, or 256 kbps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Show Max Participants for multi-peer modes (isMediaLobby is true),
        // or show as read-only/disabled locked to 2 for Direct Voice (!isMediaLobby)
        Spacer(modifier = Modifier.height(16.dp))
        if (isMediaLobby) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Participants")
                    Text("$maxParticipants people", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = maxParticipants.toFloat(),
                    onValueChange = { 
                        val maxVal = it.roundToInt()
                        maxParticipants = maxVal
                        prefs.edit().putInt("max_participants", maxVal).apply()
                    },
                    valueRange = 2f..8f,
                    steps = 5,
                    enabled = isHost
                )
                Text(
                    text = "Includes the host. Higher limits may reduce Wi-Fi Aware reliability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Direct Voice (strictly 2 participants)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Participants", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2 people (Fixed)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "Direct Voice is limited to 2 participants for high-quality peer-to-peer audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Show Experimental PTT Features only for Voice Modes (Direct Voice and Group Voice)
        // Bypassed for Media Sharing (isMediaLobby = true && !isGroupVoice)
        if (!isMediaLobby || isGroupVoice) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Experimental Features", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bluetooth Button Push-To-Talk")
                    Text(
                        text = "Use earbud play/pause button to toggle mute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = hardwarePtt,
                    onCheckedChange = { 
                        hardwarePtt = it
                        prefs.edit().putBoolean("hardware_ptt", it).apply()
                    },
                    enabled = isHost
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Physical Volume Button PTT (Zello-style)")
                    Text(
                        text = "Requires Accessibility Permissions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = volumePtt,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            showVolumePttDialog = true
                        } else {
                            volumePtt = false
                            prefs.edit().putBoolean("volume_ptt", false).apply()
                        }
                    },
                    enabled = isHost
                )
            }
            
            if (showVolumePttDialog) {
                AlertDialog(
                    onDismissRequest = { showVolumePttDialog = false },
                    title = { Text("Accessibility Permission Required") },
                    text = { 
                        Text("To intercept the physical volume buttons while the screen is locked, ProxiTap requires an Accessibility Service.\n\nYou will be redirected to Android Settings. Please find 'ProxiTap' in the Accessibility menu and enable it.") 
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showVolumePttDialog = false
                            volumePtt = true
                            prefs.edit().putBoolean("volume_ptt", true).apply()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showVolumePttDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Save & Back")
        }
    }
}
