/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/**
 * Reads an mRemoteNG connection file (`confCons.xml`). Containers become folders, connections
 * become hosts. Passwords in the file are encrypted and are not read. A file that is encrypted as a
 * whole (`FullFileEncryption="true"`) is refused, because there is nothing to read.
 */
internal object MRemoteNgParser {
    fun looksLike(text: String): Boolean {
        val head = text.take(2_000)
        return head.contains("mremoteng", ignoreCase = true) || (head.contains("<Connections") && head.contains("ConfVersion"))
    }

    /** Throws [UnsafeXmlException] or [MalformedXmlException]; returns null when the file is encrypted. */
    fun parse(text: String): List<ImportedHost>? {
        val hosts = ArrayList<ImportedHost>()
        // One entry per open element: the folder name it adds, or null.
        val stack = ArrayList<String?>()
        var encrypted = false
        MiniXml.scan(
            text,
            object : XmlEvents {
                override fun open(tag: XmlTag) {
                    if (tag.name == "Connections" && tag.attributes["FullFileEncryption"].equals("true", ignoreCase = true)) encrypted = true
                    if (tag.name != "Node") {
                        if (!tag.selfClosing) stack.add(null)
                        return
                    }
                    val type = tag.attributes["Type"].orEmpty()
                    val name = tag.attributes["Name"].orEmpty()
                    if (type.equals("Container", ignoreCase = true)) {
                        if (!tag.selfClosing) stack.add(name)
                        return
                    }
                    if (type.equals("Connection", ignoreCase = true)) {
                        hosts.add(
                            ImportedHost(
                                name = name,
                                hostname = tag.attributes["Hostname"].orEmpty().trim(),
                                port = tag.attributes["Port"]?.trim()?.toIntOrNull(),
                                username = tag.attributes["Username"].orEmpty().trim(),
                                protocol = protocolOf(tag.attributes["Protocol"].orEmpty()),
                                folder = stack.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() },
                                hadPassword = tag.attributes["Password"].orEmpty().isNotBlank(),
                            ),
                        )
                    }
                    if (!tag.selfClosing) stack.add(null)
                }

                override fun close(name: String) {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
            },
        )
        return if (encrypted) null else hosts
    }

    /** SSH1 is an obsolete protocol that this app does not speak, so it counts as "other". */
    private fun protocolOf(value: String): ImportProtocol = when (value.trim().lowercase()) {
        "ssh2" -> ImportProtocol.SSH
        "telnet" -> ImportProtocol.TELNET
        else -> ImportProtocol.OTHER
    }
}
