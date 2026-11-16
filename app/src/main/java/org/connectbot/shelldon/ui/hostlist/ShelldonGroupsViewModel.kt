/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.Outcome
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import java.util.UUID
import javax.inject.Inject

/**
 * The folder and tag side of the Hosts screen. The host list itself still comes from the upstream
 * `HostListViewModel`; this one only adds the groups, tags and identities and the folder actions.
 */
@HiltViewModel
class ShelldonGroupsViewModel @Inject constructor(
    private val groups: ShelldonGroupRepository,
) : ViewModel() {
    val snapshot: StateFlow<GroupSnapshot> = groups.observeSnapshot()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SNAPSHOT)

    /** [onResult] gets null on success or the reason the change was refused. */
    fun createGroup(name: String, parentId: UUID?, identityId: UUID?, onResult: (Rejection?) -> Unit) {
        viewModelScope.launch {
            onResult((groups.createGroup(name, parentId, identityId = identityId) as? Outcome.Rejected)?.reason)
        }
    }

    fun editGroup(group: HostGroup, name: String, identityId: UUID?, onResult: (Rejection?) -> Unit) {
        viewModelScope.launch {
            onResult((groups.editGroup(group.id, name, group.color, identityId) as? Outcome.Rejected)?.reason)
        }
    }

    fun deleteGroup(id: UUID) {
        viewModelScope.launch { groups.deleteGroup(id) }
    }

    fun moveHost(host: Host, groupId: UUID?) {
        viewModelScope.launch { groups.setHostGroup(host.id, groupId) }
    }

    fun actions(): HostGroupActions = HostGroupActions(
        onCreateGroup = ::createGroup,
        onEditGroup = ::editGroup,
        onDeleteGroup = ::deleteGroup,
        onMoveHost = ::moveHost,
    )

    private companion object {
        val EMPTY_SNAPSHOT = GroupSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** What the Hosts list can ask for on folders. The defaults do nothing, so previews and tests need no wiring. */
class HostGroupActions(
    val onCreateGroup: (name: String, parentId: UUID?, identityId: UUID?, onResult: (Rejection?) -> Unit) -> Unit = { _, _, _, _ -> },
    val onEditGroup: (group: HostGroup, name: String, identityId: UUID?, onResult: (Rejection?) -> Unit) -> Unit = { _, _, _, _ -> },
    val onDeleteGroup: (id: UUID) -> Unit = {},
    val onMoveHost: (host: Host, groupId: UUID?) -> Unit = { _, _ -> },
)
