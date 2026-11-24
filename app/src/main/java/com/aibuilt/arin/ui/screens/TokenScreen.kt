package com.aibuilt.arin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.arin.viewmodel.ArinViewModel
import com.aibuilt.arin.viewmodel.ConnectionStatus

@Composable
fun TokenScreen(vm: ArinViewModel) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()

    var draft by rememberSaveable { mutableStateOf("") }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var feedbackOk by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("GitHub connection", style = MaterialTheme.typography.titleMedium)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (connection) {
                        ConnectionStatus.CONNECTED -> "✓ Connected. $detail"
                        ConnectionStatus.CHECKING -> "Checking token against api.github.com…"
                        ConnectionStatus.BROKEN -> "✗ Broken. $detail"
                        ConnectionStatus.UNKNOWN -> "No token saved yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (connection) {
                        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
                        ConnectionStatus.BROKEN -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    "GitHub replies \u201CNot Found\u201D (404) when a token can't see a repository — usually a revoked/expired token or a fine-grained token without Contents + Administration write. Arin now detects this and reports the real cause instead of a bare 404.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Create a token at github.com/settings/tokens",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching { uri.openUri("https://github.com/settings/tokens") }
                    }
                )
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Personal access token") },
            placeholder = { Text("ghp_… or github_pat_…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = draft.isNotBlank() && connection != ConnectionStatus.CHECKING,
                onClick = {
                    vm.saveTokenAndVerify(draft) { ok, msg ->
                        feedback = msg
                        feedbackOk = ok
                        if (ok) draft = ""
                    }
                }
            ) { Text("Connect & verify") }

            if (connection == ConnectionStatus.CHECKING) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (connection == ConnectionStatus.CONNECTED) {
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
            }
        }

        feedback?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (feedbackOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                "The token is stored only on this device and never leaves except for GitHub API calls. Revoke it anytime at github.com/settings/tokens.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
