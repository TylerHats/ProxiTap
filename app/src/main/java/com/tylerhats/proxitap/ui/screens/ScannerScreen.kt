package com.tylerhats.proxitap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scan QR Code", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        // CameraX Viewfinder Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Camera Viewfinder Active", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Point your camera at the Host's screen",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        OutlinedButton(
            onClick = onCancelClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Cancel")
        }
    }
}
