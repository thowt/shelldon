/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.ui.components.DisconnectAllDialog
import org.connectbot.ui.screens.hostlist.ConnectionState
import org.connectbot.ui.screens.hostlist.HostListUiState
import java.util.UUID

/** Which hosts the chip row shows. Only filters that match at least one host are offered. */
internal enum class HostFilter(@param:StringRes val label: Int) {
    ALL(R.string.shelldon_filter_all),
    SSH(R.string.shelldon_filter_ssh),
    MOSH(R.string.shelldon_filter_mosh),
    TELNET(R.string.shelldon_filter_telnet),
    LOCAL(R.string.shelldon_filter_local),
    LEGACY(R.string.shelldon_filter_legacy),
    ;

    fun matches(host: Host): Boolean = when (this) {
        ALL -> true
        SSH -> host.protocol == "ssh"
        MOSH -> host.protocol == "mosh"
        TELNET -> host.protocol == "telnet"
        LOCAL -> host.protocol == "local"
        LEGACY -> host.crypto.preset == "LEGACY"
    }
}

/** Search matches the nickname, host name or user name, ignoring case. */
internal fun Host.matchesQuery(query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    return nickname.contains(text, ignoreCase = true) ||
        hostname.contains(text, ignoreCase = true) ||
        username.contains(text, ignoreCase = true)
}

/**
 * The Hosts home screen: search and quick connect, filter chips, active sessions and the host cards.
 * Everything that used to sit in the top-right menu is either on screen or a bottom-bar tab; the
 * menu keeps only the rarely used actions.
 */
@Composable
fun ShelldonHostListContent(
    uiState: HostListUiState,
    onNavigateToConsole: (Host) -> Unit,
    onNavigateToEditHost: (Host?) -> Unit,
    onNavigateToPortForwards: (Host) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onToggleSortOrder: () -> Unit,
    onDeleteHost: (Host) -> Unit,
    onDuplicateHost: (Host) -> Unit,
    onForgetHostKeys: (Host) -> Unit,
    onDisconnectHost: (Host) -> Unit,
    onDisconnectAll: () -> Unit,
    modifier: Modifier = Modifier,
    makingShortcut: Boolean = false,
    onSelectShortcut: (Host) -> Unit = {},
    onExportHosts: () -> Unit = {},
    onImportHosts: () -> Unit = {},
    onQuickConnect: (QuickConnectTarget) -> Unit = {},
    unsavedSessions: List<Host> = emptyList(),
    lastSessions: List<LastSession> = emptyList(),
    onSaveActiveSession: (Host) -> Unit = {},
    onOpenLastSession: (LastSession) -> Unit = {},
    onSaveLastSession: (LastSession.Unsaved) -> Unit = {},
    groupSnapshot: GroupSnapshot = GroupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
    groupActions: HostGroupActions = HostGroupActions(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showDisconnectAllDialog by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Folders and tags. The open folder and the chosen tags survive rotation as plain text.
    var currentGroupText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTagsText by rememberSaveable { mutableStateOf("") }
    var creatingGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<HostGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<HostGroup?>(null) }
    var movingHost by remember { mutableStateOf<Host?>(null) }
    var groupError by remember { mutableStateOf<Rejection?>(null) }
    val currentGroupId = currentGroupText?.let { text -> runCatching { UUID.fromString(text) }.getOrNull() }
    // A tag that was deleted while it was selected simply stops counting, so the list never gets stuck on it.
    val selectedTagIds = selectedTagsText.split(',')
        .mapNotNull { text -> runCatching { UUID.fromString(text) }.getOrNull() }
        .filter { it in groupSnapshot.tagsById }
        .toSet()

    val hosts = uiState.hosts
    val availableFilters = HostFilter.entries.filter { filter -> filter == HostFilter.ALL || hosts.any(filter::matches) }
    val selectedFilter = HostFilter.entries.getOrElse(filterIndex) { HostFilter.ALL }
    val activeFilter = if (selectedFilter in availableFilters) selectedFilter else HostFilter.ALL
    val filteredHosts = hosts.filter { activeFilter.matches(it) && it.matchesQuery(query) }
    val browse = HostBrowser.browse(filteredHosts, groupSnapshot, currentGroupId, selectedTagIds, searching = query.isNotBlank())
    val visibleHosts = browse.hosts
    val hasGroups = groupSnapshot.groups.isNotEmpty()
    val sessionHosts = if (makingShortcut) {
        emptyList()
    } else {
        hosts.filter {
            val state = uiState.connectionStates[it.id]
            state == ConnectionState.CONNECTED || state == ConnectionState.UNREAD_OUTPUT
        }
    }
    val activeUnsaved = if (makingShortcut) emptyList() else unsavedSessions
    val shownLastSessions = if (makingShortcut) emptyList() else lastSessions.filter { it.matchesQuery(query) }
    val quickTarget = parseQuickConnect(query)
    val suggestQuickConnect = !makingShortcut && quickTarget != null && looksLikeAddress(query)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!makingShortcut && (hosts.isNotEmpty() || hasGroups)) {
                val addHost = stringResource(R.string.hostpref_add_host)
                ExtendedFloatingActionButton(
                    onClick = { onNavigateToEditHost(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.semantics { contentDescription = addHost },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shelldon_new), fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 112.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.shelldon_hosts_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (!makingShortcut) {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.button_more_options),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_list_title)) },
                                        onClick = {
                                            showMenu = false
                                            onNavigateToProfiles()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.list_menu_export_hosts)) },
                                        onClick = {
                                            showMenu = false
                                            onExportHosts()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.list_menu_import_hosts)) },
                                        onClick = {
                                            showMenu = false
                                            onImportHosts()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.title_help)) },
                                        onClick = {
                                            showMenu = false
                                            onNavigateToHelp()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "search") {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        focusRequester = focusRequester,
                        onQuickConnect = {
                            if (!makingShortcut) {
                                if (quickTarget != null) onQuickConnect(quickTarget) else focusRequester.requestFocus()
                            }
                        },
                    )
                }

                if (hosts.isNotEmpty() || hasGroups) {
                    item(key = "chips") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availableFilters.forEach { filter ->
                                ShelldonChip(
                                    selected = filter == activeFilter,
                                    label = "${stringResource(filter.label)} ${hosts.count(filter::matches)}",
                                    onClick = { filterIndex = filter.ordinal },
                                )
                            }
                            ShelldonChip(
                                selected = uiState.sortedByColor,
                                label = stringResource(R.string.shelldon_sort_color),
                                onClick = onToggleSortOrder,
                            )
                            if (!makingShortcut) {
                                ShelldonChip(
                                    selected = false,
                                    label = stringResource(R.string.shelldon_group_add_chip),
                                    onClick = {
                                        groupError = null
                                        creatingGroup = true
                                    },
                                )
                            }
                        }
                    }
                }

                if (groupSnapshot.tags.isNotEmpty()) {
                    item(key = "tag_chips") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groupSnapshot.tags.sortedBy { it.name.lowercase() }.forEach { tag ->
                                ShelldonChip(
                                    selected = tag.id in selectedTagIds,
                                    label = "#${tag.name}",
                                    onClick = {
                                        val next = if (tag.id in selectedTagIds) selectedTagIds - tag.id else selectedTagIds + tag.id
                                        selectedTagsText = next.joinToString(",")
                                    },
                                )
                            }
                        }
                    }
                }

                if (suggestQuickConnect && quickTarget != null) {
                    item(key = "quick_connect") {
                        QuickConnectRow(
                            target = quickTarget,
                            onClick = { onQuickConnect(quickTarget) },
                        )
                    }
                }

                if (sessionHosts.isNotEmpty() || activeUnsaved.isNotEmpty()) {
                    item(key = "sessions_header") {
                        SectionHeader(
                            title = stringResource(R.string.shelldon_sessions_title),
                            modifier = Modifier.padding(top = 12.dp),
                            trailing = {
                                TextButton(onClick = { showDisconnectAllDialog = true }) {
                                    Text(stringResource(R.string.shelldon_disconnect_all))
                                }
                            },
                        )
                    }
                    item(key = "sessions") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            sessionHosts.forEach { host ->
                                SessionCard(
                                    host = host,
                                    connectionState = uiState.connectionStates[host.id] ?: ConnectionState.CONNECTED,
                                    onClick = { onNavigateToConsole(host) },
                                )
                            }
                            activeUnsaved.forEach { host ->
                                SessionCard(
                                    host = host,
                                    connectionState = ConnectionState.CONNECTED,
                                    onClick = { onNavigateToConsole(host) },
                                    onSave = { onSaveActiveSession(host) },
                                )
                            }
                        }
                    }
                }

                if (shownLastSessions.isNotEmpty()) {
                    item(key = "last_sessions_header") {
                        SectionHeader(
                            title = stringResource(R.string.shelldon_last_sessions_title),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(items = shownLastSessions, key = { "last_${it.key}" }) { session ->
                        LastSessionRow(
                            session = session,
                            onClick = { onOpenLastSession(session) },
                            onSave = { (session as? LastSession.Unsaved)?.let(onSaveLastSession) },
                        )
                    }
                }

                if (!browse.isFlat && browse.breadcrumb.isNotEmpty()) {
                    item(key = "breadcrumb") {
                        GroupBreadcrumb(
                            path = browse.breadcrumb,
                            onOpen = { currentGroupText = it?.toString() },
                        )
                    }
                }

                if (browse.groups.isNotEmpty()) {
                    item(key = "groups_header") {
                        SectionHeader(
                            title = stringResource(R.string.shelldon_groups_section),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(items = browse.groups, key = { "group_${it.group.id}" }) { entry ->
                        GroupCard(
                            entry = entry,
                            identityName = entry.group.identityId?.let { groupSnapshot.identitiesById[it]?.name },
                            onOpen = { currentGroupText = entry.group.id.toString() },
                            onEdit = {
                                groupError = null
                                editingGroup = entry.group
                            },
                            onDelete = { deletingGroup = entry.group },
                        )
                    }
                }

                if (hosts.isEmpty() && !hasGroups) {
                    item(key = "empty") {
                        EmptyHosts(
                            onAdd = { onNavigateToEditHost(null) },
                            onImport = onImportHosts,
                        )
                    }
                } else {
                    item(key = "hosts_header") {
                        SectionHeader(
                            title = stringResource(R.string.shelldon_hosts_section),
                            modifier = Modifier.padding(top = 12.dp),
                            trailing = {
                                Text(
                                    text = visibleHosts.size.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    if (visibleHosts.isEmpty() && browse.groups.isEmpty()) {
                        item(key = "no_matches") {
                            Text(
                                text = when {
                                    !browse.isFlat -> stringResource(R.string.shelldon_group_empty)
                                    query.isBlank() -> stringResource(R.string.shelldon_no_tag_matches)
                                    else -> stringResource(R.string.shelldon_no_matches, query.trim())
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(items = visibleHosts, key = { it.id }) { host ->
                        HostCard(
                            host = host,
                            connectionState = uiState.connectionStates[host.id] ?: ConnectionState.UNKNOWN,
                            disconnectReason = uiState.disconnectReasons[host.id],
                            makingShortcut = makingShortcut,
                            onClick = {
                                if (makingShortcut) onSelectShortcut(host) else onNavigateToConsole(host)
                            },
                            onEdit = { onNavigateToEditHost(host) },
                            onPortForwards = { onNavigateToPortForwards(host) },
                            onDuplicate = { onDuplicateHost(host) },
                            onForgetHostKeys = { onForgetHostKeys(host) },
                            onDisconnect = { onDisconnectHost(host) },
                            onDelete = { onDeleteHost(host) },
                            caption = hostCaption(host, groupSnapshot, showPath = browse.isFlat),
                            onMoveToGroup = if (hasGroups) {
                                { movingHost = host }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    if (creatingGroup) {
        GroupEditorDialog(
            existing = null,
            identities = groupSnapshot.identities,
            error = groupError,
            onDismiss = { creatingGroup = false },
            onSave = { name, identityId ->
                groupActions.onCreateGroup(name, browse.breadcrumb.lastOrNull()?.id, identityId) { rejection ->
                    groupError = rejection
                    if (rejection == null) creatingGroup = false
                }
            },
        )
    }

    editingGroup?.let { group ->
        GroupEditorDialog(
            existing = group,
            identities = groupSnapshot.identities,
            error = groupError,
            onDismiss = { editingGroup = null },
            onSave = { name, identityId ->
                groupActions.onEditGroup(group, name, identityId) { rejection ->
                    groupError = rejection
                    if (rejection == null) editingGroup = null
                }
            },
        )
    }

    deletingGroup?.let { group ->
        GroupDeleteDialog(
            group = group,
            onDismiss = { deletingGroup = null },
            onConfirm = {
                groupActions.onDeleteGroup(group.id)
                if (currentGroupId == group.id) currentGroupText = group.parentId?.toString()
                deletingGroup = null
            },
        )
    }

    movingHost?.let { host ->
        MoveHostDialog(
            host = host,
            snapshot = groupSnapshot,
            onDismiss = { movingHost = null },
            onMove = { groupId ->
                groupActions.onMoveHost(host, groupId)
                movingHost = null
            },
        )
    }

    if (showDisconnectAllDialog) {
        DisconnectAllDialog(
            onDismiss = { showDisconnectAllDialog = false },
            onConfirm = {
                showDisconnectAllDialog = false
                onDisconnectAll()
            },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onQuickConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("shelldon_search"),
        placeholder = { Text(stringResource(R.string.shelldon_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("shelldon_search_clear"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.shelldon_clear_search),
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            errorContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onQuickConnect() }),
    )
}

@Composable
private fun ShelldonChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing()
    }
}

@Composable
private fun QuickConnectRow(
    target: QuickConnectTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("shelldon_quick_connect_row"),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column {
                Text(
                    text = stringResource(R.string.shelldon_connect_to, target.nickname),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.shelldon_connect_to_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyHosts(
    onAdd: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.shelldon_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.shelldon_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shelldon_empty_add))
            }
            FilledTonalButton(
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.shelldon_empty_import))
            }
        }
    }
}
