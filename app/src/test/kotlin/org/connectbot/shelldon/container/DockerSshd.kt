/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The Docker command line, nothing more: no test library, no daemon API client. */
internal object Docker {
    class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    fun run(args: List<String>, timeoutSeconds: Long = 120, stdin: ByteArray? = null): Result {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val reader = thread(isDaemon = true) { process.inputStream.copyTo(output) }
        process.outputStream.use { if (stdin != null) it.write(stdin) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            return Result(-1, output.toString(Charsets.UTF_8) + "\n(timed out after ${timeoutSeconds}s)")
        }
        reader.join(5_000)
        return Result(process.exitValue(), output.toString(Charsets.UTF_8))
    }

    /** Null when a daemon answers, otherwise why not. */
    fun unavailableReason(): String? = try {
        val result = run(listOf("version", "--format", "{{.Server.Version}}"), timeoutSeconds = 30)
        if (result.ok) null else result.output.trim()
    } catch (e: Exception) {
        "docker could not be started: ${e.message}"
    }
}

/**
 * A throw-away OpenSSH server in a container, reachable on loopback only. Old or weak algorithms are
 * enabled per test through sshd options on the command line, never in the image.
 *
 * The image is Debian's stock `openssh-server` plus one user. The user and password exist only inside
 * the container and are worthless anywhere else. Nothing from the host (keys, files, environment) is
 * passed in.
 */
internal class DockerSshd private constructor(
    private val name: String,
    val host: String,
    val port: Int,
    /** `<type> <base64>` of each host key the server presents. */
    val hostKeys: List<Pair<String, ByteArray>>,
) : AutoCloseable {
    fun logs(): String = Docker.run(listOf("logs", "--tail", "80", name), timeoutSeconds = 30).output

    override fun close() {
        Docker.run(listOf("rm", "-f", name), timeoutSeconds = 60)
    }

    companion object {
        const val USER = "testuser"
        const val PASSWORD = "testpass"

        // SHELLDON_TEST_BASE_IMAGE lets CI pull Debian from a mirror: anonymous Docker Hub pulls from
        // shared runners are rate limited.
        private val baseImage = System.getenv("SHELLDON_TEST_BASE_IMAGE")?.takeIf { it.isNotBlank() } ?: "debian:trixie-slim"

        // PerSourcePenalties is off because sshd 9.8+ would otherwise refuse the second connection from
        // 127.0.0.1 after a first one that failed key exchange, which several tests do on purpose.
        private val dockerfile = """
            ARG BASE_IMAGE=debian:trixie-slim
            FROM ${'$'}{BASE_IMAGE}
            RUN apt-get update \
             && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server \
             && rm -rf /var/lib/apt/lists/* \
             && mkdir -p /run/sshd \
             && ssh-keygen -A \
             && useradd --create-home --shell /bin/sh $USER \
             && echo '$USER:$PASSWORD' | chpasswd \
             && printf 'PasswordAuthentication yes\nKbdInteractiveAuthentication no\nPermitRootLogin no\nUsePAM no\nPerSourcePenalties no\n' > /etc/ssh/sshd_config.d/00-shelldon-test.conf
            EXPOSE 22
        """.trimIndent() + "\n"

        private val image: String by lazy { buildImage() }

        private fun buildImage(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((baseImage + "\n" + dockerfile).toByteArray())
            val tag = "shelldon-test-sshd:" + digest.take(6).joinToString("") { "%02x".format(it) }
            val result = Docker.run(
                listOf("build", "--quiet", "--build-arg", "BASE_IMAGE=$baseImage", "-t", tag, "-"),
                timeoutSeconds = 600,
                stdin = dockerfile.toByteArray(),
            )
            check(result.ok) { "Building the test image failed:\n${result.output}" }
            return tag
        }

        /** Starts sshd with the given `-o Key=Value` options and returns once it presents its identification line. */
        fun start(vararg sshdOptions: String): DockerSshd {
            val name = "shelldon-test-sshd-" + UUID.randomUUID().toString().take(8)
            val options = sshdOptions.flatMap { listOf("-o", it) }
            // 127.0.0.1:: = a random free port, bound to loopback only.
            val run = Docker.run(
                listOf("run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::22", image, "/usr/sbin/sshd", "-D", "-e") + options,
                timeoutSeconds = 120,
            )
            check(run.ok) { "Starting the container failed:\n${run.output}" }
            try {
                val mapping = Docker.run(listOf("port", name, "22/tcp"), timeoutSeconds = 30)
                check(mapping.ok) { "No port mapping:\n${mapping.output}" }
                val port = mapping.output.lineSequence().first { it.isNotBlank() }.trim().substringAfterLast(':').toInt()
                awaitBanner(name, port)
                return DockerSshd(name, "127.0.0.1", port, readHostKeys(name))
            } catch (e: Throwable) {
                val logs = Docker.run(listOf("logs", "--tail", "40", name), timeoutSeconds = 30).output
                Docker.run(listOf("rm", "-f", name), timeoutSeconds = 60)
                throw IllegalStateException("The SSH server did not come up: ${e.message}\n--- container log ---\n$logs", e)
            }
        }

        // Docker's port proxy accepts connections before sshd listens, so wait for a real banner.
        private fun awaitBanner(name: String, port: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            var last = "no answer"
            while (System.nanoTime() < deadline) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                        socket.soTimeout = 2_000
                        val line = StringBuilder()
                        while (line.length < 255) {
                            val b = socket.getInputStream().read()
                            if (b < 0 || b == '\n'.code) break
                            line.append(b.toChar())
                        }
                        if (line.startsWith("SSH-")) return
                        last = "got '$line'"
                    }
                } catch (e: Exception) {
                    last = e.toString()
                }
                Thread.sleep(500)
            }
            error("$name gave no SSH banner in 40 s ($last)")
        }

        private fun readHostKeys(name: String): List<Pair<String, ByteArray>> {
            val result = Docker.run(listOf("exec", name, "sh", "-c", "cat /etc/ssh/ssh_host_*_key.pub"), timeoutSeconds = 30)
            check(result.ok) { "Cannot read the host keys:\n${result.output}" }
            val keys = result.output.lineSequence().map { it.trim() }.filter { it.startsWith("ssh-") || it.startsWith("ecdsa-") }.map {
                val fields = it.split(' ')
                fields[0] to java.util.Base64.getDecoder().decode(fields[1])
            }.toList()
            check(keys.isNotEmpty()) { "The server has no host keys" }
            return keys
        }
    }
}
