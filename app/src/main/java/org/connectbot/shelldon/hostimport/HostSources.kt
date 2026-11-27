/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/**
 * Recognises a file from its content, not its name, and reads the hosts in it. Supported: CSV or
 * TSV, MobaXterm sessions, a PuTTY registry export and an mRemoteNG connection file.
 */
object HostSources {
    /** Files above this size are not read, for the same reason the `~/.ssh` import has a limit. */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun read(bytes: ByteArray): SourceResult {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return SourceResult.Failed(SourceProblem.UNRECOGNIZED)
        val text = TextDecoding.decode(bytes)
        val head = text.trimStart()
        return when {
            PuttyRegParser.looksLike(text) -> SourceResult.Found(FixedSource(SourceFormat.PUTTY, PuttyRegParser.parse(text)))
            MobaXtermParser.looksLike(text) -> SourceResult.Found(FixedSource(SourceFormat.MOBAXTERM, MobaXtermParser.parse(text)))
            head.startsWith("<") -> readXml(text)
            else -> CsvImport.open(text)?.let { SourceResult.Found(it) } ?: SourceResult.Failed(SourceProblem.UNRECOGNIZED)
        }
    }

    private fun readXml(text: String): SourceResult {
        if (!MRemoteNgParser.looksLike(text)) return SourceResult.Failed(SourceProblem.UNRECOGNIZED)
        return try {
            val hosts = MRemoteNgParser.parse(text) ?: return SourceResult.Failed(SourceProblem.ENCRYPTED)
            SourceResult.Found(FixedSource(SourceFormat.MREMOTENG, hosts))
        } catch (_: UnsafeXmlException) {
            SourceResult.Failed(SourceProblem.UNSAFE_XML)
        } catch (_: MalformedXmlException) {
            SourceResult.Failed(SourceProblem.MALFORMED)
        }
    }
}
