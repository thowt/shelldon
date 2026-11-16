/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.KnownHost
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.connectbot.sshlib.KeyFingerprint
import javax.inject.Inject

data class KnownHostRow(
    val knownHost: KnownHost,
    val fingerprint: String,
    val endpoint: String,
)

/** Every saved host key across all hosts, so a changed or stale key can be found and forgotten in one place. */
@HiltViewModel
class KnownHostsOverviewViewModel @Inject constructor(
    groups: ShelldonGroupRepository,
    private val hosts: HostRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    /** Null until the first list has loaded. */
    val rows: StateFlow<List<KnownHostRow>?> = groups.observeAllKnownHosts()
        .map { list ->
            list.map { known ->
                KnownHostRow(
                    knownHost = known,
                    fingerprint = KeyFingerprint.sha256(known.hostKey),
                    endpoint = if (known.port == 22) known.hostname else "${known.hostname}:${known.port}",
                )
            }
        }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun forget(knownHost: KnownHost) {
        viewModelScope.launch {
            withContext(dispatchers.io) { hosts.deleteKnownHost(knownHost) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsOverviewScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KnownHostsOverviewViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    var forgetting by remember { mutableStateOf<KnownHostRow?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shelldon_known_hosts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_navigate_up))
                    }
                },
            )
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            list.isEmpty() -> Text(
                text = stringResource(R.string.shelldon_known_hosts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(padding)
                    .padding(20.dp),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = list, key = { it.knownHost.id }) { row ->
                    KnownHostCard(row = row, onForget = { forgetting = row })
                }
            }
        }
    }

    forgetting?.let { row ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.shelldon_known_hosts_forget)) },
            text = { Text(stringResource(R.string.shelldon_known_hosts_forget_body, row.endpoint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(row.knownHost)
                        forgetting = null
                    },
                    modifier = Modifier.testTag("known_host_forget_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_known_hosts_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) {
                    Text(stringResource(R.string.shelldon_cancel))
                }
            },
        )
    }
}

@Composable
private fun KnownHostCard(
    row: KnownHostRow,
    onForget: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("known_host_${row.knownHost.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.endpoint,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.knownHost.hostKeyAlgo,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.shelldon_known_hosts_forget),
                )
            }
        }
    }
}
