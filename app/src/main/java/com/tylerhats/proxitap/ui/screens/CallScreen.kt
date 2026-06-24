package com.tylerhats.proxitap.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(
    isHost: Boolean,
    isMuted: Boolean,
    isSpeaking: Boolean = false,
    distanceMeters: Float? = null,
    isReconnecting: Boolean = false,
    onMuteToggle: () -> Unit,
    onEndCallClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeaking && !isReconnecting) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isSpeaking && !isReconnecting) 0f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
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
            FilledTonalButton(onClick = onSettingsClick, modifier = Modifier.height(48.dp)) {
                Text("Settings")
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            if (isSpeaking && !isReconnecting) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(ringScale)
                        .border(4.dp, MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha), CircleShape)
                )
            }

            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(if (isReconnecting) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isReconnecting) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        text = "PT", 
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = if (isReconnecting) "Reconnecting..." else "Voice Active", 
            style = MaterialTheme.typography.bodyLarge,
            color = if (isReconnecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
        )

        if (distanceMeters != null && !isReconnecting) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Distance: %.1fm".format(distanceMeters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onMuteToggle,
                modifier = Modifier.size(80.dp),
                containerColor = if (isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(if (isMuted) "Unmute" else "Mute")
            }
            
            FloatingActionButton(
                onClick = onEndCallClick,
                modifier = Modifier.size(80.dp),
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Text("End Call", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
