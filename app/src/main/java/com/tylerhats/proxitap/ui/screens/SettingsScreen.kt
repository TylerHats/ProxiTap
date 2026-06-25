package com.tylerhats.proxitap.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    isBidirectional: Boolean = false,
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
    
    val sampleRateOptions = remember { listOf(8000, 16000, 32000, 44100) }
    var currentSampleRateIndex by remember {
        val currentRate = prefs.getInt("group_sample_rate", 16000)
        val idx = sampleRateOptions.indexOf(currentRate)
        mutableStateOf(if (idx != -1) idx.toFloat() else 1f)
    }
    
    var maxParticipants by remember { mutableStateOf(prefs.getInt("max_participants", 4)) }
    var opusDtx by remember { mutableStateOf(prefs.getBoolean("opus_dtx", true)) }
    var dtxThreshold by remember { mutableStateOf(prefs.getFloat("dtx_threshold", 50f)) }
    var displayName by remember { mutableStateOf(prefs.getString("display_name", android.os.Build.MODEL) ?: android.os.Build.MODEL) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "ns_enabled" -> nsEnabled = prefs.getBoolean(key, true)
                "aec_enabled" -> aecEnabled = prefs.getBoolean(key, true)
                "bluetooth_mic" -> bluetoothMic = prefs.getBoolean(key, true)
                "opus_dtx" -> opusDtx = prefs.getBoolean(key, true)
                "dtx_threshold" -> dtxThreshold = prefs.getFloat(key, 50f)
                "opus_bitrate" -> {
                    val bitrateVal = prefs.getInt(key, 64000)
                    val idx = bitrateOptions.indexOf(bitrateVal)
                    if (idx != -1) {
                        currentBitrateIndex = idx.toFloat()
                    }
                }
                "group_sample_rate" -> {
                    val rateVal = prefs.getInt(key, 16000)
                    val idx = sampleRateOptions.indexOf(rateVal)
                    if (idx != -1) {
                        currentSampleRateIndex = idx.toFloat()
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
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
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
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (!isMediaLobby || isGroupVoice) {
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
            
            if (!isGroupVoice) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (isGroupVoice) "Silence Gate (DTX)" else "Opus DTX (Bandwidth Saver)")
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
            
            if (isGroupVoice && opusDtx) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Silence Gate Threshold")
                        val label = when {
                            dtxThreshold < 30f -> "Very Sensitive (${dtxThreshold.roundToInt()})"
                            dtxThreshold < 80f -> "Normal (${dtxThreshold.roundToInt()})"
                            dtxThreshold < 150f -> "Firm (${dtxThreshold.roundToInt()})"
                            else -> "Strict (${dtxThreshold.roundToInt()})"
                        }
                        Text(label, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = dtxThreshold,
                        onValueChange = { 
                            dtxThreshold = it
                        },
                        onValueChangeFinished = {
                            prefs.edit().putFloat("dtx_threshold", dtxThreshold).apply()
                        },
                        valueRange = 10f..250f,
                        enabled = isHost
                    )
                    Text(
                        text = "Adjust to prevent your audio from cutting out or stuttering while speaking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (!isGroupVoice) {
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
                        },
                        onValueChangeFinished = {
                            val index = currentBitrateIndex.roundToInt()
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
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Group Audio Quality")
                        val currentRateVal = sampleRateOptions[currentSampleRateIndex.roundToInt()]
                        val rateLabel = when (currentRateVal) {
                            8000 -> "Low (8 kHz)"
                            16000 -> "Medium (16 kHz)"
                            32000 -> "High (32 kHz)"
                            44100 -> "Ultra (44.1 kHz)"
                            else -> "$currentRateVal Hz"
                        }
                        Text(rateLabel, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = currentSampleRateIndex,
                        onValueChange = { 
                            currentSampleRateIndex = it
                        },
                        onValueChangeFinished = {
                            val index = currentSampleRateIndex.roundToInt()
                            val selectedRate = sampleRateOptions[index]
                            prefs.edit().putInt("group_sample_rate", selectedRate).apply()
                        },
                        valueRange = 0f..3f,
                        steps = 2,
                        enabled = isHost
                    )
                    Text(
                        text = "Lower rates use significantly less bandwidth and improve range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }

        val isDirectUdpMedia = isMediaLobby && isBidirectional && !isGroupVoice
        if (isMediaLobby && !isDirectUdpMedia) {
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
                    },
                    onValueChangeFinished = {
                        prefs.edit().putInt("max_participants", maxParticipants).apply()
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
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Participants", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2 people (Fixed)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = if (isDirectUdpMedia) {
                        "Bidirectional Media is limited to 2 participants for high-quality peer-to-peer audio."
                    } else {
                        "Direct Voice is limited to 2 participants for high-quality peer-to-peer audio."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Save & Back")
        }
    }
}
