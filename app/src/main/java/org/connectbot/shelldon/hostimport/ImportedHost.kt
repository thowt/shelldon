/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/** What a saved connection from another app speaks. Only [SSH] and [TELNET] can be used here. */
enum class ImportProtocol { SSH, TELNET, OTHER }

/** The file formats that can be read. */
enum class SourceFormat { CSV, MOBAXTERM, PUTTY, MREMOTENG }

/**
 * One saved connection as another app stored it, before any checking. Passwords are never carried
 * over: [hadPassword] only lets the preview say how many were left behind.
 */
data class ImportedHost(
    val name: String,
    val hostname: String,
    /** Null when the file does not say; the planner then uses the protocol's default port. */
    val port: Int?,
    val username: String,
    val protocol: ImportProtocol,
    /** Folders from the outermost to the innermost. Empty means the top level. */
    val folder: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hadPassword: Boolean = false,
)

/** Why a file could not be turned into a host list at all. */
enum class SourceProblem {
    /** None of the supported formats. */
    UNRECOGNIZED,

    /** An mRemoteNG file that is encrypted as a whole. */
    ENCRYPTED,

    /** An XML file with a document type or entity definition, which is never read. */
    UNSAFE_XML,

    /** Not well-formed. */
    MALFORMED,
}

/** The hosts found in one file, and for CSV the table they were read from. */
sealed interface HostSource {
    val format: SourceFormat
    val hosts: List<ImportedHost>
}

/** A format whose layout is fixed: the hosts are read once. */
class FixedSource(override val format: SourceFormat, override val hosts: List<ImportedHost>) : HostSource

/** A CSV table plus the column choices that turn it into hosts; the person can change the choices. */
class CsvSource(val table: CsvTable, val settings: CsvSettings) : HostSource {
    override val format: SourceFormat get() = SourceFormat.CSV

    override val hosts: List<ImportedHost> by lazy { CsvImport.hosts(table, settings) }

    fun withSettings(settings: CsvSettings): CsvSource = CsvSource(table, settings)
}

sealed interface SourceResult {
    data class Found(val source: HostSource) : SourceResult

    data class Failed(val problem: SourceProblem) : SourceResult
}
