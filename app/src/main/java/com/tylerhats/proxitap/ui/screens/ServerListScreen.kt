package com.tylerhats.proxitap.ui.screens

import androidx.compose.runtime.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class DiscoveredLobby(
    val name: String,
    val isProtected: Boolean,
    val isMedia: Boolean,
    val isBidi: Boolean,
    val payload: String
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    discoveredLobbies: List<DiscoveredLobby>,
    onLobbyClick: (DiscoveredLobby, String) -> Unit,
    onBackClick: () -> Unit
) {
    var selectedLobby by remember { mutableStateOf<DiscoveredLobby?>(null) }
    var pinInput by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Local Lobbies") },
            navigationIcon = {
                TextButton(onClick = onBackClick) {
                    Text("Back")
                }
            }
        )

        if (discoveredLobbies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Searching for nearby lobbies...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(discoveredLobbies) { lobby ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (lobby.isProtected) {
                                    selectedLobby = lobby
                                    pinInput = ""
                                } else {
                                    onLobbyClick(lobby, "")
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = lobby.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (lobby.isMedia) {
                                    val parts = lobby.payload.split("|")
                                    val isGroup = if (parts.size >= 6) parts[5] == "1" else false
                                    Text(
                                        text = if (isGroup) "Group Voice Call"
                                               else if (lobby.isBidi) "Media (Bidirectional)"
                                               else "Media (Broadcast)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Voice Call",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = if (lobby.isProtected) "🔒" else "🔓",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
        
        selectedLobby?.let { lobby ->
            AlertDialog(
                onDismissRequest = { selectedLobby = null },
                title = { Text("Enter PIN") },
                text = {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("PIN") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onLobbyClick(lobby, pinInput)
                        selectedLobby = null
                    }) {
                        Text("Join")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedLobby = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
