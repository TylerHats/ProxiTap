package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onHostClick: (String, String?, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onJoinClick: () -> Unit,
    onSearchAreaClick: () -> Unit
) {
    var lobbyName by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var useHotspot by remember { mutableStateOf(false) }
    var enableRadar by remember { mutableStateOf(false) }
    var callType by remember { mutableStateOf(0) } // 0 = Direct Voice (1-on-1), 1 = Group Voice (3-8 people), 2 = Media Sharing
    var isBidirectional by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
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
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Call Type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
        Spacer(modifier = Modifier.height(8.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Direct Voice (1-on-1)" to "Uses WebRTC. Recommended for 2 people. Provides crystal clear audio with hardware echo cancellation.",
                "Group Voice (3-8 people)" to "Uses low-overhead WebSockets. Recommended for group calls. No screen capture permissions required.",
                "Media Sharing" to "Broadcasts your phone's system audio (music/video) to peers. Mics are disabled."
            ).forEachIndexed { index, (title, desc) ->
                Card(
                    onClick = { callType = index },
                    colors = CardDefaults.cardColors(
                        containerColor = if (callType == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = callType == index,
                                onClick = { callType = index }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(title, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (callType == index) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }
                }
            }
        }

        if (callType == 2) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Bidirectional Media", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isBidirectional,
                    onCheckedChange = { isBidirectional = it }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { 
                val finalName = lobbyName.takeIf { it.isNotBlank() } ?: "ProxiTap_Lobby_${(100..999).random()}"
                val isMedia = callType != 0
                val isBidi = callType == 1 || (callType == 2 && isBidirectional)
                val reqProj = callType == 2
                onHostClick(finalName, pin.takeIf { it.isNotBlank() }, useHotspot, enableRadar, isMedia, isBidi, reqProj) 
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
