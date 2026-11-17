/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Install this key on that server" waiting for a login. A server that is not connected has to be logged
 * in to first, in its console; this remembers which key was meant, so the console can ask to install it as
 * soon as the login is through. It holds ids only, never key material, and runs out after a while so a
 * forgotten request cannot surprise anyone hours later.
 */
@Singleton
class PendingKeyInstall @Inject constructor() {
    data class Request(val hostId: UUID, val keyId: UUID, val createdAtMillis: Long)

    private val current = MutableStateFlow<Request?>(null)

    val request: StateFlow<Request?> = current.asStateFlow()

    /** Remembers [keyId] for [hostId], replacing any earlier request. */
    fun set(hostId: UUID, keyId: UUID, nowMillis: Long = System.currentTimeMillis()) {
        current.value = Request(hostId, keyId, nowMillis)
    }

    fun clear() {
        current.value = null
    }

    companion object {
        const val MAX_AGE_MILLIS = 15 * 60 * 1000L
    }
}

/** The request, if it is for [hostId] and has not run out. */
fun PendingKeyInstall.Request?.forHost(hostId: UUID, nowMillis: Long = System.currentTimeMillis()): PendingKeyInstall.Request? = this?.takeIf {
    it.hostId == hostId && nowMillis - it.createdAtMillis in 0..PendingKeyInstall.MAX_AGE_MILLIS
}
