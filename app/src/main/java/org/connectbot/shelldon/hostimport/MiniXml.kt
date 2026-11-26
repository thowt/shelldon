/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/** A start tag. [name] has any namespace prefix removed. */
internal class XmlTag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean)

internal interface XmlEvents {
    fun open(tag: XmlTag)

    fun close(name: String)
}

internal class UnsafeXmlException : Exception("document type or entity declaration")

internal class MalformedXmlException(message: String) : Exception(message)

/**
 * Just enough XML for the exports this app reads: elements and attributes. It never expands
 * entities defined by the file, and refuses any `<!DOCTYPE` or `<!ENTITY`, which is what the
 * "billion laughs" and external-entity attacks need. Comments, processing instructions and CDATA
 * are skipped; text between tags is ignored.
 */
internal object MiniXml {
    private const val MAX_DEPTH = 200
    private const val MAX_ATTRIBUTES = 200

    fun scan(text: String, events: XmlEvents) {
        var i = 0
        var depth = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return
            i = lt
            when {
                text.startsWith("<!--", i) -> i = skipTo(text, "-->", i + 4)

                text.startsWith("<![CDATA[", i) -> i = skipTo(text, "]]>", i + 9)

                text.startsWith("<?", i) -> i = skipTo(text, "?>", i + 2)

                text.startsWith("<!", i) -> throw UnsafeXmlException()

                text.startsWith("</", i) -> {
                    val gt = text.indexOf('>', i)
                    if (gt < 0) throw MalformedXmlException("unterminated end tag")
                    events.close(localName(text.substring(i + 2, gt).trim()))
                    depth--
                    i = gt + 1
                }

                else -> {
                    val (tag, next) = readStartTag(text, i + 1)
                    events.open(tag)
                    if (!tag.selfClosing && ++depth > MAX_DEPTH) throw MalformedXmlException("nested too deeply")
                    i = next
                }
            }
        }
    }

    private fun skipTo(text: String, end: String, from: Int): Int {
        val at = text.indexOf(end, from)
        if (at < 0) throw MalformedXmlException("unterminated section")
        return at + end.length
    }

    private fun localName(name: String): String = name.substringAfter(':')

    private fun readStartTag(text: String, from: Int): Pair<XmlTag, Int> {
        var i = from
        val n = text.length
        val nameStart = i
        while (i < n && !text[i].isWhitespace() && text[i] != '/' && text[i] != '>') i++
        if (i == nameStart || i >= n) throw MalformedXmlException("bad start tag")
        val name = localName(text.substring(nameStart, i))
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) throw MalformedXmlException("unterminated start tag")
            when (text[i]) {
                '>' -> return XmlTag(name, attributes, false) to i + 1

                '/' -> {
                    if (i + 1 >= n || text[i + 1] != '>') throw MalformedXmlException("bad empty-element tag")
                    return XmlTag(name, attributes, true) to i + 2
                }
            }
            val keyStart = i
            while (i < n && !text[i].isWhitespace() && text[i] != '=' && text[i] != '>' && text[i] != '/') i++
            val key = text.substring(keyStart, i)
            while (i < n && text[i].isWhitespace()) i++
            if (key.isEmpty() || i >= n || text[i] != '=') throw MalformedXmlException("attribute without a value")
            i++
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n || (text[i] != '"' && text[i] != '\'')) throw MalformedXmlException("unquoted attribute")
            val quote = text[i]
            val end = text.indexOf(quote, i + 1)
            if (end < 0) throw MalformedXmlException("unterminated attribute")
            if (attributes.size >= MAX_ATTRIBUTES) throw MalformedXmlException("too many attributes")
            attributes[localName(key)] = decodeEntities(text.substring(i + 1, end))
            i = end + 1
        }
    }

    /** The five predefined entities and numeric references. Anything else stays as written. */
    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val semicolon = if (c == '&') value.indexOf(';', i) else -1
            val decoded = if (semicolon in (i + 2)..(i + 10)) entity(value.substring(i + 1, semicolon)) else null
            if (decoded != null) {
                out.append(decoded)
                i = semicolon + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun entity(body: String): String? = when {
        body == "amp" -> "&"
        body == "lt" -> "<"
        body == "gt" -> ">"
        body == "quot" -> "\""
        body == "apos" -> "'"
        body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
        body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
        else -> null
    }

    private fun codePoint(value: Int?): String? = value?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
}
