/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.ui.screens.hostlist.parseColor

/** Stable key for a [LastSession] row, also used in test tags. */
internal val LastSession.key: String
    get() = when (this) {
        is LastSession.Saved -> host.id.toString()
        is LastSession.Unsaved -> "${target.username}@${target.hostname}:${target.port}"
    }

/**
 * One row of the "Last sessions" section. Tapping connects again. A destination that came from
 * quick connect and is not in the host list yet also gets a Save button.
 */
@Composable
internal fun LastSessionRow(
    session: LastSession,
    onClick: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localLabel = stringResource(R.string.shelldon_local_shell)
    val title: String
    val address: String
    when (session) {
        is LastSession.Saved -> {
            title = session.host.nickname
            address = hostAddress(session.host, localLabel)
        }

        is LastSession.Unsaved -> {
            title = session.target.nickname
            address = quickTargetAddress(session.target)
        }
    }
    val ago = remember(session.lastConnectedMs) {
        DateUtils.getRelativeTimeSpanString(
            session.lastConnectedMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("shelldon_last_${session.key}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LastSessionTile(session)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ago,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session is LastSession.Unsaved) {
                val saveDescription = stringResource(R.string.shelldon_save_session_cd, title)
                FilledTonalButton(
                    onClick = onSave,
                    modifier = Modifier
                        .semantics { contentDescription = saveDescription }
                        .testTag("shelldon_last_save_${session.key}"),
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.shelldon_save),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LastSessionTile(session: LastSession) {
    val tileColor = when (session) {
        is LastSession.Saved -> parseColor(session.host.color)
        is LastSession.Unsaved -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val iconColor = if (tileColor.luminance() > 0.5f) Color(0xFF0E1411) else Color.White
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(tileColor, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (session is LastSession.Saved) Icons.Default.Dns else Icons.Default.History,
            contentDescription = null,
            tint = if (session is LastSession.Saved) iconColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `user@host`, with `:port` only when it is not the SSH default. */
internal fun quickTargetAddress(target: QuickConnectTarget): String = buildString {
    if (target.username.isNotEmpty()) append(target.username).append('@')
    append(target.hostname)
    if (target.port != 22) append(':').append(target.port)
}

/** The small Save button used on a session card. */
@Composable
internal fun SaveSessionButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.shelldon_save),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
