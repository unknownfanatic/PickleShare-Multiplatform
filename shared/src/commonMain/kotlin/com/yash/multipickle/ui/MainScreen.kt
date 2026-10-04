package com.yash.multipickle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.name

@Composable
fun MainScreen(state: UiState, handleEvent: (AppEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "PickleShare",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth(), space = (-12).dp) {
                    SegmentedButton(
                        selected = state.isSenderMode,
                        onClick = {
                            handleEvent(AppEvent.OnSwitchMode(true))
                        },
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            text = "Sender Mode",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    SegmentedButton(
                        selected = !state.isSenderMode,
                        onClick = {
                            handleEvent(AppEvent.OnSwitchMode(false))
                        },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "Receiver Mode",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (state.statusMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            val totalFilesCount = if (state.isSenderMode) {
                state.selectedFiles?.size ?: 0
            } else {
                state.fileProgresses.size
            }

            if (totalFilesCount > 1 && state.currentProgress > 0.0f) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Overall Progress",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${(state.currentProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.currentProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (state.isSenderMode) {
                if (!state.selectedFiles.isNullOrEmpty()) {
                    item {
                        Text(
                            text = "Selected Files (${state.selectedFiles.size}):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.selectedFiles) { file ->
                        val fileProgress = state.fileProgresses[file.name] ?: 0f
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (fileProgress > 0f) {
                                        Text(
                                            text = if (fileProgress >= 1f) "100%" else "${(fileProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                                if (fileProgress > 0f) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { fileProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { handleEvent(AppEvent.OnSelectFiles) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (state.selectedFiles.isNullOrEmpty()) {
                                    "Select Files to Share"
                                } else {
                                    "Add More Files"
                                },
                                textAlign = TextAlign.Center
                            )
                        }

                        if (!state.selectedFiles.isNullOrEmpty()) {
                            Button(
                                onClick = { handleEvent(AppEvent.OnCancelClick) }
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gzip Compression",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (state.useCompression) "Enabled (chunks compressed on the fly)" else "Disabled (raw chunks)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.useCompression,
                                onCheckedChange = { handleEvent(AppEvent.OnToggleCompression(it)) }
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "Discovered Devices:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (state.peers.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.isSubnetScanning) "Scanning subnet..." else "Searching for nearby devices...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { handleEvent(AppEvent.OnScanSubnet) },
                                enabled = !state.isSubnetScanning
                            ) {
                                Text(if (state.isSubnetScanning) "Scanning..." else "Scan Subnet")
                            }
                        }
                    }
                } else {
                    items(state.peers) { peer ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = peer.deviceName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = peer.peerIp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = { handleEvent(AppEvent.OnSendToPeer(peer)) },
                                    enabled = !state.selectedFiles.isNullOrEmpty()
                                ) {
                                    Text("Send")
                                }
                            }
                        }
                    }
                }
            } else {
                // Receiver Mode UI
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Receiver Mode Active",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (state.currentProgress > 0f) {
                                Text(
                                    text = if (state.currentProgress >= 1f) {
                                        "Transfer complete (100%)"
                                    } else {
                                        "Receiving file(s)..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Text(
                                    text = "HTTP receiver server is running on port 53318.\nWaiting for incoming transfer requests...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                if (state.fileProgresses.isNotEmpty()) {
                    item {
                        Text(
                            text = "Receiving Files (${state.fileProgresses.size}):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.fileProgresses.entries.toList(), key = { it.key }) { (fileName, fileProgress) ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = if (fileProgress >= 1f) "100%" else "${(fileProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { fileProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Prompt Dialog on Receiver: "Person is sending you this file. Receive? Yes No"
    if (state.incomingPrompt != null) {
        val prompt = state.incomingPrompt
        val senderName = prompt.request.info.alias.ifBlank { prompt.request.info.deviceModel }
        val filesDesc = prompt.request.files.values.joinToString(", ") { it.fileName }

        AlertDialog(
            onDismissRequest = { handleEvent(AppEvent.OnDenyTransfer) },
            title = { Text("Incoming File Transfer") },
            text = {
                Text("$senderName is sending you $filesDesc. Receive?")
            },
            confirmButton = {
                Button(onClick = { handleEvent(AppEvent.OnAcceptTransfer) }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { handleEvent(AppEvent.OnDenyTransfer) }) {
                    Text("No")
                }
            }
        )
    }

    // Confirm Dialog when switching modes during an active transfer
    if (state.pendingModeSwitch != null) {
        val targetModeName = if (state.pendingModeSwitch) "Sender Mode" else "Receiver Mode"
        val currentModeName = if (state.isSenderMode) "Sender" else "Receiver"
        AlertDialog(
            onDismissRequest = { handleEvent(AppEvent.OnDismissModeSwitch) },
            title = { Text("Cancel Transfer?") },
            text = {
                Text("A file transfer is currently in progress. Do you want to cancel the transfer and switch to $targetModeName?")
            },
            confirmButton = {
                Button(
                    onClick = { handleEvent(AppEvent.OnConfirmModeSwitch) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Cancel & Switch")
                }
            },
            dismissButton = {
                TextButton(onClick = { handleEvent(AppEvent.OnDismissModeSwitch) }) {
                    Text("Stay in $currentModeName Mode")
                }
            }
        )
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(state = UiState(), handleEvent = {})
    }
}
