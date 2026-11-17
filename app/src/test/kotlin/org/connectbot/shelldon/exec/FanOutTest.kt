/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.exec

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FanOutTest {
    private fun finished(text: String) = ExecOutcome.Finished(
        ExecResult(text, "", 0, null, stdoutTruncated = false, stderrTruncated = false, durationMillis = 0),
    )

    @Test
    fun everyTargetGetsAResultInTheOrderGiven() = runTest {
        val results = FanOut.run(listOf("a", "b", "c")) { name ->
            // Later targets finish first.
            delay(if (name == "a") 30 else 10)
            finished(name)
        }
        assertThat(results.map { it.first }).containsExactly("a", "b", "c")
        assertThat(results.map { (it.second as ExecOutcome.Finished).result.stdout }).containsExactly("a", "b", "c")
    }

    @Test
    fun noMoreThanTheLimitRunAtOnce() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        FanOut.run((1..10).toList(), concurrency = 3) {
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(10)
            running.decrementAndGet()
            finished("x")
        }
        assertThat(peak.get()).isEqualTo(3)
    }

    @Test
    fun oneFailingTargetDoesNotStopTheOthers() = runTest {
        val results = FanOut.run(listOf("ok", "io", "odd", "gone")) { name ->
            when (name) {
                "io" -> throw IOException("connection reset")
                "odd" -> throw IllegalStateException("surprise")
                "gone" -> ExecOutcome.Unavailable
                else -> finished("fine")
            }
        }.toMap()
        assertThat(results["ok"]).isInstanceOf(ExecOutcome.Finished::class.java)
        assertThat(results["io"]).isEqualTo(ExecOutcome.Failed("connection reset"))
        assertThat(results["odd"]).isEqualTo(ExecOutcome.Failed("surprise"))
        assertThat(results["gone"]).isEqualTo(ExecOutcome.Unavailable)
    }

    @Test
    fun progressIsReportedForEveryTarget() = runTest {
        val seen = mutableListOf<String>()
        FanOut.run(listOf("a", "b"), concurrency = 1, onUpdate = { target, progress ->
            seen += "$target:" + if (progress is ExecProgress.Running) "running" else "done"
        }) { finished("x") }
        assertThat(seen).containsExactly("a:running", "a:done", "b:running", "b:done")
    }

    @Test
    fun noTargetsMeansNoResults() = runTest {
        assertThat(FanOut.run(emptyList<String>()) { finished("x") }).isEmpty()
    }
}
