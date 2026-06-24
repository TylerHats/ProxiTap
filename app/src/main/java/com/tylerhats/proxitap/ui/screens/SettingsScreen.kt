package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ProxiTapSettings", android.content.Context.MODE_PRIVATE) }
    
    var nsEnabled by remember { mutableStateOf(prefs.getBoolean("ns_enabled", true)) }
    var aecEnabled by remember { mutableStateOf(prefs.getBoolean("aec_enabled", true)) }
    var bluetoothMic by remember { mutableStateOf(prefs.getBoolean("bluetooth_mic", true)) }
    var hardwarePtt by remember { mutableStateOf(prefs.getBoolean("hardware_ptt", false)) } // Default OFF

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)
    ) {
        Text("Audio Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
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

        Spacer(modifier = Modifier.height(32.dp))
        Text("Experimental Features", style = MaterialTheme.typography.titleMedium)
        
        var hardwarePtt by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Hardware Push-To-Talk")
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
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Save & Back")
        }
    }
}
