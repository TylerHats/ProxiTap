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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(
    isHost: Boolean,
    isMuted: Boolean,
    isSpeaking: Boolean = false,
    isConnecting: Boolean = false,
    isReconnecting: Boolean = false,
    isMediaLobby: Boolean = false,
    isGroupVoice: Boolean = false,
    participants: List<String> = emptyList(),
    stats: Map<String, String> = emptyMap(),
    onMuteToggle: () -> Unit,
    onEndCallClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onWebPlayerClick: () -> Unit,
    onShowQrCodeClick: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition()
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeaking && !isReconnecting && !isConnecting) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isSpeaking && !isReconnecting && !isConnecting) 0f else 0.5f,
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
            Row {
                if (isHost && onShowQrCodeClick != null) {
                    IconButton(onClick = onShowQrCodeClick) {
                        Text("QR")
                    }
                }
                IconButton(onClick = onSettingsClick) {
                    Text("⚙️")
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            if (isSpeaking && !isReconnecting && !isConnecting) {
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
                    .background(if (isReconnecting || isConnecting) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isReconnecting || isConnecting) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isMediaLobby && !isGroupVoice) {
                    Text(
                        text = "MEDIA", 
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
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
            text = if (isConnecting) "Connecting..."
                   else if (isReconnecting) "Reconnecting..." 
                   else if (isGroupVoice) "Group Voice Active" 
                   else if (isMediaLobby) "Media Broadcasting Active" 
                   else "Voice Active", 
            style = MaterialTheme.typography.bodyLarge,
            color = if (isReconnecting || isConnecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
        )

        if (participants.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Participants", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .heightIn(max = 150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(participants.size) { index ->
                    val rawName = participants[index]
                    Text(
                        text = "• $rawName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
        
        var showStats by remember { mutableStateOf(false) }
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isHost && stats.isNotEmpty()) {
                OutlinedButton(onClick = { showStats = !showStats }) {
                    Text(if (showStats) "Hide Diagnostics" else "View Diagnostics")
                }
            }
            Button(
                onClick = onWebPlayerClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("🌐 Web Player")
            }
        }
        
        if (isHost && stats.isNotEmpty() && showStats) {
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .background(MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                items(stats.keys.toList().size) { index ->
                    val key = stats.keys.toList()[index]
                    val value = stats[key] ?: ""
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (index < stats.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val context = LocalContext.current
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            var isSpeakerOn by remember { mutableStateOf(audioManager.isSpeakerphoneOn) }

            FloatingActionButton(
                onClick = { 
                    isSpeakerOn = !isSpeakerOn
                    audioManager.isSpeakerphoneOn = isSpeakerOn
                },
                modifier = Modifier.size(80.dp),
                containerColor = if (isSpeakerOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(if (isSpeakerOn) "Speaker" else "Earpiece")
            }
            if (!isMediaLobby) {
                FloatingActionButton(
                    onClick = onMuteToggle,
                    modifier = Modifier.size(80.dp),
                    containerColor = if (isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(if (isMuted) "Unmute" else "Mute")
                }
            }
            
            var showExitConfirmation by remember { mutableStateOf(false) }

            if (showExitConfirmation) {
                AlertDialog(
                    onDismissRequest = { showExitConfirmation = false },
                    title = { Text(if (isHost) "End Call?" else "Leave Call?") },
                    text = { 
                        Text(
                            if (isHost) "Ending the call will disconnect all participants and close the app."
                            else "Are you sure you want to leave the call? This will close the app."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExitConfirmation = false
                                onEndCallClick()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(if (isHost) "End Call" else "Leave")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitConfirmation = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            FloatingActionButton(
                onClick = { showExitConfirmation = true },
                modifier = Modifier.size(80.dp),
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Text(if (isHost) "End Call" else "Leave", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
