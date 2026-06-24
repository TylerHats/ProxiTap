package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(
    isHost: Boolean,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onEndCallClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isHost) "Host Lobby" else "Connected",
                style = MaterialTheme.typography.titleLarge
            )
            Button(onClick = onSettingsClick) {
                Text("Settings")
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Active caller visualization avatar
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "PT", 
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Voice Active", 
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onMuteToggle,
                containerColor = if (isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(if (isMuted) "Unmute" else "Mute")
            }
            
            FloatingActionButton(
                onClick = onEndCallClick,
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Text("End Call", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
