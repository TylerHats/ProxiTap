package com.tylerhats.proxitap.ui.screens

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
    val payload: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    discoveredLobbies: List<DiscoveredLobby>,
    onLobbyClick: (DiscoveredLobby) -> Unit,
    onBackClick: () -> Unit
) {
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
                            .clickable { onLobbyClick(lobby) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lobby.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (lobby.isProtected) "🔒" else "🔓",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
