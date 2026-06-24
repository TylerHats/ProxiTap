package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onHostClick: (String?) -> Unit,
    onJoinClick: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
        
        Spacer(modifier = Modifier.height(64.dp))
        
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { onHostClick(pin.takeIf { it.isNotBlank() }) },
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
    }
}
