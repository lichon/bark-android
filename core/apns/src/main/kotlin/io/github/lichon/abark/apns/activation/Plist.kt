package io.github.lichon.abark.apns.activation

import java.io.StringReader
import java.util.Base64
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * The subset of Apple's XML property-list format (`plist version="1.0"`) that activation needs.
 *
 * Encoded by hand and parsed with DOM rather than with `dd-plist`: StAX is absent on Android and DOM is available on
 * every platform this module targets, so `:core:apns` stays dependency-free apart from BouncyCastle.
 */
sealed interface PlistValue {

    data class String(val value: kotlin.String) : PlistValue

    data class Bool(val value: Boolean) : PlistValue

    data class Integer(val value: Long) : PlistValue

    data class Real(val value: Double) : PlistValue

    /** `<data>` — base64 (Apple's values are frequently DER blobs). */
    class Bytes(val value: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            other is Bytes && other.value.contentEquals(value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): kotlin.String = "Bytes(${value.size}B)"
    }

    /** `<date>` kept verbatim: we never do arithmetic on plist timestamps. */
    data class Date(val value: kotlin.String) : PlistValue

    data class Array(val items: List<PlistValue>) : PlistValue

    data class Dict(val entries: Map<kotlin.String, PlistValue>) : PlistValue

    companion object
}

/** Walks nested dictionaries: `plist.at("device-activation", "activation-record")`. */
fun PlistValue.at(vararg keys: kotlin.String): PlistValue? {
    var current: PlistValue? = this
    for (key in keys) {
        current = (current as? PlistValue.Dict)?.entries?.get(key) ?: return null
    }
    return current
}

fun PlistValue?.asString(): kotlin.String? = (this as? PlistValue.String)?.value

fun PlistValue?.asBytes(): ByteArray? = (this as? PlistValue.Bytes)?.value

class PlistException(message: kotlin.String, cause: Throwable? = null) : Exception(message, cause)

object PlistCodec {

    private const val HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
            "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"

    private const val INDENT = "\t"

    /**
     * Serialises [value] as a complete plist document. Dictionary keys are sorted, which is what Apple's own
     * `plistlib` does by default and what makes the bytes — and therefore the FairPlay signature over them —
     * reproducible across runs.
     */
    fun encode(value: PlistValue, sortKeys: Boolean = true): kotlin.String =
        StringBuilder().also { out ->
            out.append(HEADER)
            out.append("<plist version=\"1.0\">\n")
            writeValue(out, value, 0, sortKeys)
            out.append("\n</plist>\n")
        }.toString()

    fun parse(xml: kotlin.String): PlistValue {
        // A block lifted out of an HTML page arrives with leading whitespace, and an XML declaration may only be the
        // very first thing in the document.
        val document1 = xml.removePrefix("﻿").trimStart()
        if (document1.contains("<!ENTITY", ignoreCase = true)) {
            // Refuse internal entity declarations outright: activation responses have no legitimate use for them and
            // they are the whole point of an XXE payload.
            throw PlistException("plist must not declare entities")
        }
        val document = try {
            newDocumentBuilder().parse(InputSource(StringReader(document1)))
        } catch (e: PlistException) {
            throw e
        } catch (e: Exception) {
            throw PlistException("malformed plist: ${e.message}", e)
        }
        val root = document.documentElement
            ?: throw PlistException("empty document")
        val plist = if (root.tagName == "plist") firstChildElement(root) else root
        return readValue(plist ?: throw PlistException("empty plist"))
    }

    /** Extracts the plist that Alberts embeds in the HTML response as `<Protocol>…</Protocol>`. */
    fun extractProtocol(html: kotlin.String): kotlin.String? =
        boundedTag(html, "<Protocol>", "</Protocol>")

    /** Alberts reports a rejection as `<b>message</b>` somewhere in the HTML. */
    fun extractError(html: kotlin.String): kotlin.String? =
        boundedTag(html, "<b>", "</b>")?.trim()?.takeIf { it.isNotEmpty() }

    /** Loose (non-greedy-first) tag extraction that never scans the whole document for the closing tag. */
    private fun boundedTag(text: kotlin.String, open: kotlin.String, close: kotlin.String): kotlin.String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = text.indexOf(close, from)
        if (end < 0) return null
        return text.substring(from, end)
    }

    private fun writeValue(out: StringBuilder, value: PlistValue, depth: Int, sortKeys: Boolean) {
        out.append(INDENT.repeat(depth))
        when (value) {
            is PlistValue.String -> out.append("<string>").append(escape(value.value)).append("</string>")
            is PlistValue.Bool -> out.append(if (value.value) "<true/>" else "<false/>")
            is PlistValue.Integer -> out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.Real -> out.append("<real>").append(value.value).append("</real>")
            is PlistValue.Bytes -> {
                val base64 = Base64.getMimeEncoder(76, "\n".toByteArray())
                    .encodeToString(value.value)
                out.append("<data>").append(base64).append("</data>")
            }
            is PlistValue.Date -> out.append("<date>").append(escape(value.value)).append("</date>")
            is PlistValue.Array -> {
                if (value.items.isEmpty()) {
                    out.append("<array/>")
                } else {
                    out.append("<array>")
                    for (item in value.items) {
                        out.append('\n')
                        writeValue(out, item, depth + 1, sortKeys)
                    }
                    out.append('\n').append(INDENT.repeat(depth)).append("</array>")
                }
            }
            is PlistValue.Dict -> {
                if (value.entries.isEmpty()) {
                    out.append("<dict/>")
                } else {
                    val entries = if (sortKeys) {
                        value.entries.entries.sortedBy { it.key }
                    } else {
                        value.entries.entries.toList()
                    }
                    out.append("<dict>")
                    for ((key, entry) in entries) {
                        out.append('\n').append(INDENT.repeat(depth + 1))
                        out.append("<key>").append(escape(key)).append("</key>\n")
                        writeValue(out, entry, depth + 1, sortKeys)
                    }
                    out.append('\n').append(INDENT.repeat(depth)).append("</dict>")
                }
            }
        }
    }

    /**
     * Escapes text for XML 1.0. Control characters other than tab/newline cannot be represented at all, so they are
     * rejected instead of silently producing a document Apple will refuse.
     */
    fun escape(text: kotlin.String): kotlin.String {
        val out = StringBuilder(text.length + 16)
        for (char in text) {
            when {
                char == '&' -> out.append("&amp;")
                char == '<' -> out.append("&lt;")
                char == '>' -> out.append("&gt;")
                char == '\t' || char == '\n' || char == '\r' -> out.append(char)
                char < ' ' -> throw PlistException("control character U+${char.code.toString(16)} is not valid XML")
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun readValue(element: Element): PlistValue =
        when (element.tagName) {
            "string", "key", "date" -> {
                val text = element.textContent.orEmpty()
                if (element.tagName == "date") PlistValue.Date(text) else PlistValue.String(text)
            }
            "true" -> PlistValue.Bool(true)
            "false" -> PlistValue.Bool(false)
            "integer" -> PlistValue.Integer(
                element.textContent.trim().let { text ->
                    text.toLongOrNull() ?: text.toLongOrNull(16) ?: throw PlistException("bad integer: $text")
                },
            )
            "real" -> PlistValue.Real(
                element.textContent.trim().toDoubleOrNull() ?: throw PlistException("bad real"),
            )
            "data" -> PlistValue.Bytes(decodeBase64(element.textContent))
            "array" -> {
                val items = ArrayList<PlistValue>()
                var child = firstChildElement(element)
                while (child != null) {
                    items += readValue(child)
                    child = nextElement(child)
                }
                PlistValue.Array(items)
            }
            "dict" -> {
                val entries = LinkedHashMap<kotlin.String, PlistValue>()
                var key = firstChildElement(element)
                while (key != null) {
                    if (key.tagName != "key") throw PlistException("dict entry without <key>: ${key.tagName}")
                    val valueElement = nextElement(key) ?: throw PlistException("key without value")
                    entries[key.textContent.orEmpty()] = readValue(valueElement)
                    key = nextElement(valueElement)
                }
                PlistValue.Dict(entries)
            }
            "plist" -> firstChildElement(element)?.let(::readValue)
                ?: throw PlistException("empty plist")
            else -> throw PlistException("unsupported plist element: ${element.tagName}")
        }

    private fun decodeBase64(text: kotlin.String?): ByteArray {
        val cleaned = text.orEmpty().filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)
        return try {
            Base64.getDecoder().decode(cleaned)
        } catch (e: IllegalArgumentException) {
            throw PlistException("bad base64 in <data>", e)
        }
    }

    private fun firstChildElement(node: Node): Element? {
        var child = node.firstChild
        while (child != null) {
            if (child is Element) return child
            child = child.nextSibling
        }
        return null
    }

    private fun nextElement(node: Node): Element? {
        var sibling = node.nextSibling
        while (sibling != null) {
            if (sibling is Element) return sibling
            sibling = sibling.nextSibling
        }
        return null
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        // Every one of these is optional on some platform (Android's parser rejects several), hence runCatching.
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        val builder = factory.newDocumentBuilder()
        // The plist DOCTYPE references apple.com; never resolve it.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder
    }
}
