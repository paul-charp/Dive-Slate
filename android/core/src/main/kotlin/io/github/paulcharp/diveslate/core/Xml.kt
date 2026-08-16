package io.github.paulcharp.diveslate.core

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A small DOM wrapper, shaped like the parts of Python's ElementTree the
 * parsers use.
 *
 * **On hardening.** The Python original is a desktop CLI reading files the user
 * chose. This runs on a phone and is handed documents by a share intent from
 * another application, which is untrusted input by definition. So the reader
 * refuses doctype declarations and external entities outright: without that, a
 * crafted dive log could read arbitrary files off the device and exfiltrate
 * them. This is the one place the port deliberately diverges from Python
 * behaviour, and it should never be relaxed for compatibility with some
 * exporter that emits a DTD.
 */
internal object Xml {

    fun parse(text: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Refusing the doctype outright removes the entire XXE surface;
            // the remaining flags matter for parsers that ignore that one.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false // tags are matched on local name throughout
        }

        val document = try {
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw ParseException("malformed XML: ${e.message}")
        }
        return document.documentElement ?: throw ParseException("empty XML document")
    }
}

/** Raised when a file is the right format but its content is unusable. */
class ParseException(message: String) : IllegalArgumentException(message)

/** The tag name without any `namespace:` prefix. */
internal val Element.local: String
    get() = tagName.substringAfterLast(':')

/** Direct child elements, optionally filtered by local tag name. */
internal fun Element.children(name: String? = null): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as Element
            if (name == null || element.local == name) out.add(element)
        }
    }
    return out
}

/** The first direct child with this local name. */
internal fun Element.child(name: String): Element? = children(name).firstOrNull()

/** Namespace-agnostic descent through direct children. */
internal fun Element.descend(vararg path: String): Element? {
    var current: Element = this
    for (name in path) {
        current = current.child(name) ?: return null
    }
    return current
}

/** Every descendant with this local name, at any depth. */
internal fun Element.findAll(name: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(element: Element) {
        if (element.local == name) out.add(element)
        for (kid in element.children()) walk(kid)
    }
    walk(this)
    return out
}

/** An attribute's value, or null when absent or blank. */
internal fun Element.attr(name: String): String? =
    getAttribute(name).takeIf { it.isNotBlank() }

/** The trimmed text of a direct child, or null when absent or blank. */
internal fun Element.textOf(name: String): String? =
    child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** This element's own trimmed text, or null when blank. */
internal val Element.text: String?
    get() = textContent?.trim()?.takeIf { it.isNotEmpty() }
