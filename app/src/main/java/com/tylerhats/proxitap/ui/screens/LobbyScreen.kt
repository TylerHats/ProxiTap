package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LobbyScreen(
    qrPayload: String,
    onStartCallClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Lobby Started", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        // QR Code Placeholder
        // In a real implementation we would generate the Bitmap with ZXing here
        Box(
            modifier = Modifier
                .size(250.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("QR Code\n$qrPayload", color = Color.Black)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Have your friends scan this QR code to join the lobby.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onStartCallClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Start Call")
        }
    }
}
