/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/** The rows of a CSV file, header row included. Every row is padded to the same width. */
class CsvTable(val rows: List<List<String>>) {
    val width: Int = rows.maxOfOrNull { it.size } ?: 0
}

/** What the columns mean. */
enum class CsvField { NAME, HOST, PORT, USER, FOLDER, TAGS, PROTOCOL }

/**
 * @property hasHeader whether the first row names the columns instead of holding a host
 * @property columns the zero-based column that holds each field; a missing field is not read
 */
data class CsvSettings(
    val hasHeader: Boolean,
    val columns: Map<CsvField, Int>,
    /** A column that looks like it holds passwords. It is never read, only counted. */
    val passwordColumn: Int? = null,
)

/** Reads CSV and TSV text, quoted fields and line breaks inside quotes included (RFC 4180). */
internal object CsvReader {
    const val MAX_ROWS = 20_000
    const val MAX_FIELD_CHARS = 2_000

    fun read(text: String): CsvTable? {
        val delimiter = detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var i = 0

        fun endField() {
            row.add(if (field.length > MAX_FIELD_CHARS) field.substring(0, MAX_FIELD_CHARS) else field.toString())
            field.setLength(0)
            fieldWasQuoted = false
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }

                    c == '"' -> inQuotes = false

                    else -> field.append(c)
                }

                c == '"' && field.isEmpty() && !fieldWasQuoted -> {
                    inQuotes = true
                    fieldWasQuoted = true
                }

                c == delimiter -> endField()

                c == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }

                c == '\n' -> endRow()

                else -> field.append(c)
            }
            i++
            if (rows.size > MAX_ROWS) break
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldWasQuoted) endRow()
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }
        return CsvTable(rows.take(MAX_ROWS + 1).map { if (it.size < width) it + List(width - it.size) { "" } else it })
    }

    /** The comma, semicolon or tab that appears most often in the first line, outside quotes. */
    private fun detectDelimiter(text: String): Char {
        val counts = intArrayOf(0, 0, 0)
        var inQuotes = false
        for (c in text) {
            if (c == '"') inQuotes = !inQuotes
            if (inQuotes) continue
            if (c == '\n' || c == '\r') break
            when (c) {
                ',' -> counts[0]++
                ';' -> counts[1]++
                '\t' -> counts[2]++
            }
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return if (counts[best] == 0) ',' else charArrayOf(',', ';', '\t')[best]
    }
}

/** Guesses the columns from the header names and turns rows into hosts. */
object CsvImport {
    private val synonyms: List<Pair<CsvField, Set<String>>> = listOf(
        CsvField.HOST to setOf(
            "host", "hostname", "hostnameip", "hostip", "hostaddress", "address", "ipaddress", "ip", "remotehost",
            "server", "serveraddress", "computer", "fqdn", "target", "destination",
        ),
        CsvField.NAME to setOf(
            "name", "label", "nickname", "alias", "title", "session", "sessionname", "displayname", "connectionname",
            "servername",
        ),
        CsvField.PORT to setOf("port", "portnumber", "sshport", "tcpport"),
        CsvField.USER to setOf("user", "username", "login", "loginname", "userid", "account"),
        CsvField.FOLDER to setOf("folder", "foldername", "group", "groups", "groupname", "path", "folderpath", "parent"),
        CsvField.TAGS to setOf("tag", "tags", "labels", "keywords"),
        CsvField.PROTOCOL to setOf("protocol", "type", "sessiontype", "scheme", "connectiontype", "service"),
    )

    private val passwordNames = setOf("password", "pass", "passwd", "pwd", "secret", "passphrase")

    private fun normalise(header: String): String = header.lowercase().filter { it.isLetterOrDigit() }

    /** Reads [text] and picks columns and the header setting. Null when the text holds no rows. */
    fun open(text: String): CsvSource? {
        val table = CsvReader.read(text) ?: return null
        return CsvSource(table, autoSettings(table))
    }

    fun autoSettings(table: CsvTable): CsvSettings {
        val first = table.rows.firstOrNull().orEmpty()
        val recognised = first.map(::normalise)
        val known = synonyms.flatMap { it.second }.toSet() + passwordNames
        val hasHeader = recognised.any { it in known }
        if (!hasHeader) return CsvSettings(hasHeader = false, columns = emptyMap())
        val taken = HashSet<Int>()
        val columns = LinkedHashMap<CsvField, Int>()
        for ((field, names) in synonyms) {
            val index = recognised.indices.firstOrNull { it !in taken && recognised[it] in names } ?: continue
            columns[field] = index
            taken.add(index)
        }
        val password = recognised.indices.firstOrNull { it !in taken && recognised[it] in passwordNames }
        return CsvSettings(hasHeader = true, columns = columns, passwordColumn = password)
    }

    /** Names for the columns, to show in the mapping menus. */
    fun columnNames(table: CsvTable, hasHeader: Boolean): List<String> = List(table.width) { index ->
        val header = if (hasHeader) table.rows.firstOrNull()?.getOrNull(index)?.trim().orEmpty() else ""
        header.ifEmpty { "#${index + 1}" }
    }

    fun hosts(table: CsvTable, settings: CsvSettings): List<ImportedHost> {
        val hostColumn = settings.columns[CsvField.HOST] ?: return emptyList()
        val body = if (settings.hasHeader) table.rows.drop(1) else table.rows
        return body.map { row ->
            fun cell(field: CsvField): String = settings.columns[field]?.let { row.getOrNull(it) }?.trim().orEmpty()
            val address = row.getOrNull(hostColumn)?.trim().orEmpty()
            ImportedHost(
                name = cell(CsvField.NAME),
                hostname = address,
                port = cell(CsvField.PORT).toIntOrNull(),
                username = cell(CsvField.USER),
                protocol = protocolOf(cell(CsvField.PROTOCOL)),
                folder = splitFolder(cell(CsvField.FOLDER)),
                tags = splitTags(cell(CsvField.TAGS)),
                hadPassword = settings.passwordColumn?.let { row.getOrNull(it) }?.isNotBlank() == true,
            )
        }
    }

    internal fun protocolOf(value: String): ImportProtocol = when (value.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
        "", "ssh", "ssh2", "openssh", "sftp", "scp" -> ImportProtocol.SSH
        "telnet" -> ImportProtocol.TELNET
        else -> ImportProtocol.OTHER
    }

    /** `Prod/EU`, `Prod\EU` and `Prod > EU` all mean a folder EU inside a folder Prod. */
    internal fun splitFolder(value: String): List<String> = value.split('/', '\\', '>').map { it.trim() }.filter { it.isNotEmpty() }

    /** Tags are separated by commas, semicolons or bars. */
    internal fun splitTags(value: String): List<String> = value.split(',', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }
}
