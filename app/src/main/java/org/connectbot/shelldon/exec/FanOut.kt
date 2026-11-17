/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.exec

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the same kind of work on many servers at once, at most [concurrency] at a time, and keeps one
 * result per server in the order given. One server failing never stops the others.
 */
object FanOut {
    const val DEFAULT_CONCURRENCY = 4

    /**
     * @param targets the servers (or anything that stands for one)
     * @param onUpdate called as each target starts ([ExecProgress.Running]) and when it ends
     * @param execute runs the command on one target; may throw, which becomes [ExecOutcome.Failed] for that target only
     */
    suspend fun <T> run(
        targets: List<T>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onUpdate: (T, ExecProgress) -> Unit = { _, _ -> },
        execute: suspend (T) -> ExecOutcome,
    ): List<Pair<T, ExecOutcome>> {
        require(concurrency > 0) { "Concurrency must be positive" }
        val gate = Semaphore(concurrency)
        return coroutineScope {
            targets.map { target ->
                async {
                    gate.withPermit {
                        onUpdate(target, ExecProgress.Running)
                        val outcome = try {
                            execute(target)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // One server's odd failure must not stop the others.
                            ExecOutcome.Failed(e.message ?: e.javaClass.simpleName)
                        }
                        onUpdate(target, ExecProgress.Done(outcome))
                        target to outcome
                    }
                }
            }.awaitAll()
        }
    }
}

/** Where one target is in a [FanOut] run. */
sealed interface ExecProgress {
    data object Running : ExecProgress

    data class Done(val outcome: ExecOutcome) : ExecProgress
}
