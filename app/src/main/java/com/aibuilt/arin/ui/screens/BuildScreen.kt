package com.aibuilt.arin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.arin.viewmodel.ArinViewModel
import com.aibuilt.arin.viewmodel.BuildPhase
import com.aibuilt.arin.viewmodel.ConnectionStatus

private val repoPattern = Regex("[A-Za-z0-9._-]+")

@Composable
fun BuildScreen(vm: ArinViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()

    var owner by rememberSaveable { mutableStateOf("") }
    var repo by rememberSaveable { mutableStateOf("") }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(phase) { if (phase is BuildPhase.Failed) showDialog = true }

    val repoValid = repo.isBlank() || repoPattern.matches(repo)
    val canBuild = owner.isNotBlank() && repoPattern.matches(repo) && phase !is BuildPhase.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Arin", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        Text(
            when (connection) {
                ConnectionStatus.CONNECTED -> detail
                ConnectionStatus.CHECKING -> "Checking GitHub connection…"
                ConnectionStatus.BROKEN -> "GitHub connection broken. ${detail.ifBlank { "Open Settings to reconnect." }}"
                ConnectionStatus.UNKNOWN -> "GitHub not connected — open Settings and add a token."
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (connection) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
                ConnectionStatus.BROKEN -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it.trim() },
            label = { Text("Owner / organisation (e.g. octocat)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Repository name (created if missing)") },
            singleLine = true,
            isError = !repoValid,
            supportingText = {
                if (!repoValid) Text("Use letters, numbers, dots, dashes or underscores.", color = MaterialTheme.colorScheme.error)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(enabled = canBuild, onClick = { vm.startBuild(owner, repo) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (phase is BuildPhase.Running) "Building…" else "Build")
        }

        when (val p = phase) {
            is BuildPhase.Running -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(p.step, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            is BuildPhase.Success -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("✓ ${p.summary}", style = MaterialTheme.typography.titleSmall)
                        LinkChip("Open APK page", p.apkLink)
                        LinkChip("Open source repo", p.sourceLink)
                        OutlinedButton(onClick = { vm.resetPhase() }) { Text("New build") }
                    }
                }
            }
            is BuildPhase.Failed -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.message, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { vm.startBuild(owner, repo) }) { Text("Retry") }
                            TextButton(onClick = { vm.resetPhase() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            BuildPhase.Idle -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        "Choose an owner and repository, then press Build. A missing repository is created automatically; any GitHub failure triggers self-repair rounds and a real explanation instead of \"GitHub: Not Found\".",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDialog && phase is BuildPhase.Failed) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("OK") } },
            title = { Text("Build failed") },
            text = { Text((phase as BuildPhase.Failed).message) }
        )
    }
}

@Composable
private fun LinkChip(label: String, url: String) {
    val uri = LocalUriHandler.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { runCatching { uri.openUri(url) } }
    )
}
