package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit
) {
    var nsEnabled by remember { mutableStateOf(true) }
    var aecEnabled by remember { mutableStateOf(true) }
    var bluetoothMic by remember { mutableStateOf(true) }
    var networkMode by remember { mutableStateOf("NAN") } // "NAN" or "HOTSPOT"

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("Audio Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Noise Suppression (NS)")
            Switch(checked = nsEnabled, onCheckedChange = { nsEnabled = it })
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Acoustic Echo Cancellation (AEC)")
            Switch(checked = aecEnabled, onCheckedChange = { aecEnabled = it })
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
            Switch(checked = bluetoothMic, onCheckedChange = { bluetoothMic = it })
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Network Mode", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            FilterChip(
                selected = networkMode == "NAN",
                onClick = { networkMode = "NAN" },
                label = { Text("Wi-Fi Aware (NAN)") }
            )
            FilterChip(
                selected = networkMode == "HOTSPOT",
                onClick = { networkMode = "HOTSPOT" },
                label = { Text("Local Hotspot") }
            )
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
