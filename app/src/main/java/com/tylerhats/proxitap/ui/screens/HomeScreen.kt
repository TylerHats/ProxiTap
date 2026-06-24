package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onHostClick: (String, String?, Boolean, Boolean) -> Unit,
    onJoinClick: () -> Unit,
    onSearchAreaClick: () -> Unit
) {
    var lobbyName by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var useHotspot by remember { mutableStateOf(false) }
    var enableRadar by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ProxiTap",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Local Network Walkie-Talkie",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        OutlinedTextField(
            value = lobbyName,
            onValueChange = { lobbyName = it },
            label = { Text("Lobby Name (Required to Host)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Lobby PIN (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            text = "Leave blank for an Open Lobby",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start).padding(start = 16.dp, top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Use Local Hotspot Mode", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = useHotspot,
                onCheckedChange = { 
                    useHotspot = it
                    if (useHotspot) enableRadar = false // Radar requires NAN mode
                }
            )
        }

        if (!useHotspot) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Enable Distance Radar", style = MaterialTheme.typography.bodyLarge)
                    Text("Requires Wi-Fi RTT. Uses more battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = enableRadar,
                    onCheckedChange = { enableRadar = it }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { 
                val finalName = lobbyName.takeIf { it.isNotBlank() } ?: "ProxiTap_Lobby_${(100..999).random()}"
                onHostClick(finalName, pin.takeIf { it.isNotBlank() }, useHotspot, enableRadar) 
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Host a Lobby", style = MaterialTheme.typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onJoinClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Join via QR Code", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
            onClick = onSearchAreaClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Search Area (Discover Lobbies)", style = MaterialTheme.typography.titleMedium)
        }
    }
}
