package com.aibuilt.arin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.arin.viewmodel.ArinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(vm: ArinViewModel) {
    val repairs by vm.repairs.collectAsStateWithLifecycle()
    val builds by vm.builds.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val fmt = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("History", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }) { Text(
                "Repair rounds (${repairs.size})",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge
            ) }
            Tab(tab == 1, onClick = { tab = 1 }) { Text(
                "Builds (${builds.size})",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge
            ) }
        }

        if (tab == 0) {
            if (repairs.isEmpty()) EmptyHint("No repair rounds yet — run a build from the Build tab.")
            else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(repairs, key = { it.id }) { r ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when (r.level) {
                                "success" -> MaterialTheme.colorScheme.secondaryContainer
                                "error" -> MaterialTheme.colorScheme.errorContainer
                                "warn" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(fmt.format(Instant.ofEpochMilli(r.atEpochMs)), style = MaterialTheme.typography.labelSmall)
                            Text(r.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            if (builds.isEmpty()) EmptyHint("No builds recorded yet.")
            else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(builds, key = { it.id }) { b ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (b.success) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${b.owner}/${b.repo}", style = MaterialTheme.typography.titleSmall)
                            Text(fmt.format(Instant.ofEpochMilli(b.atEpochMs)), style = MaterialTheme.typography.labelSmall)
                            Text(b.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All repair rounds and build records will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
