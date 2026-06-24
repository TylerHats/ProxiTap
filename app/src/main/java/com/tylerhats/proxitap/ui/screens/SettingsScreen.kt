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

@Composable
fun SettingsScreen(
    isMediaLobby: Boolean = false,
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
    
    var opusDtx by remember { mutableStateOf(prefs.getBoolean("opus_dtx", true)) }
    var opusBitrate by remember { mutableStateOf(prefs.getInt("opus_bitrate", 64000).toFloat()) }
    var displayName by remember { mutableStateOf(prefs.getString("display_name", android.os.Build.MODEL) ?: android.os.Build.MODEL) }

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
                    text = "Media Lobby Active: Audio processing is completely bypassed to stream your device's raw uncompressed audio for maximum quality. Standard WebRTC audio settings are hidden.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text("Audio Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Noise Suppression (NS)")
            Switch(checked = nsEnabled, onCheckedChange = { 
                nsEnabled = it
                prefs.edit().putBoolean("ns_enabled", it).apply()
            })
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Acoustic Echo Cancellation (AEC)")
            Switch(checked = aecEnabled, onCheckedChange = { 
                aecEnabled = it
                prefs.edit().putBoolean("aec_enabled", it).apply()
            })
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
            Switch(checked = bluetoothMic, onCheckedChange = { 
                bluetoothMic = it
                prefs.edit().putBoolean("bluetooth_mic", it).apply()
            })
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
            Switch(checked = opusDtx, onCheckedChange = { 
                opusDtx = it
                prefs.edit().putBoolean("opus_dtx", it).apply()
            })
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Opus Bitrate (Quality)")
                Text("${(opusBitrate / 1000).toInt()} kbps", color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = opusBitrate,
                onValueChange = { 
                    opusBitrate = it
                    prefs.edit().putInt("opus_bitrate", it.toInt()).apply()
                },
                valueRange = 16000f..128000f,
                steps = 6 // (128 - 16) / 6 = approx intervals
            )
            Text(
                text = "Lower = more range/reliability. Higher = better audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Experimental Features", style = MaterialTheme.typography.titleMedium)
        
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
            Switch(checked = hardwarePtt, onCheckedChange = { 
                hardwarePtt = it
                prefs.edit().putBoolean("hardware_ptt", it).apply()
            })
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
            Switch(checked = volumePtt, onCheckedChange = { isChecked ->
                if (isChecked) {
                    showVolumePttDialog = true
                } else {
                    volumePtt = false
                    prefs.edit().putBoolean("volume_ptt", false).apply()
                }
            })
            
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
