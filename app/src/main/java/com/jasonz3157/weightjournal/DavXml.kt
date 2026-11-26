package com.jasonz3157.weightjournal

import okhttp3.HttpUrl
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

private const val DAV = "DAV:"
private const val MAX_XML_BYTES = 64L * 1024
private val successfulProperty = Regex("HTTP/[^ ]+ +200(?: .*)?")

internal fun Response.readDavLockToken(): String? {
    normalizeLockToken(header("Lock-Token"))?.let { return it }
    val locks = davRoot("prop")?.child("lockdiscovery")?.children("activelock") ?: return null
    val lock = locks.singleOrNull() ?: return null
    if (lock.child("lockscope")?.child("exclusive") == null || lock.child("locktype")?.child("write") == null) return null
    val root = lock.child("lockroot")
    if (root != null && !request.url.matchesHref(root.child("href")?.textContent)) return null
    return normalizeLockToken(lock.child("locktoken")?.child("href")?.textContent)
}

internal fun Response.readDavPropertyEtag(): String? {
    val resources = davRoot("multistatus")?.children("response") ?: return null
    val etags = resources
        .filter { request.url.matchesHref(it.child("href")?.textContent) }
        .flatMap { it.children("propstat") }
        .filter { it.child("status")?.textContent?.trim()?.matches(successfulProperty) == true }
        .mapNotNull { it.child("prop")?.child("getetag")?.textContent?.trim() }
        .distinct()
    return etags.singleOrNull()
}

private fun normalizeLockToken(value: String?): String? {
    val token = value?.trim()?.removeSurrounding("<", ">") ?: return null
    if (token.any { it !in '!'..'~' || it == '<' || it == '>' }) return null
    val uri = runCatching { URI(token) }.getOrNull() ?: return null
    return if (uri.isAbsolute) "<$token>" else null
}

private fun HttpUrl.matchesHref(href: String?): Boolean {
    val resolved = href?.trim()?.takeIf { it.isNotEmpty() }?.let(::resolve) ?: return false
    return resolved.scheme == scheme && resolved.host == host && resolved.port == port &&
        resolved.pathSegments == pathSegments && resolved.query == null && resolved.fragment == null
}

private fun Element.children(name: String): Sequence<Element> =
    generateSequence(firstChild) { it.nextSibling }.filterIsInstance<Element>()
        .filter { it.namespaceURI == DAV && it.localName == name }

private fun Element.child(name: String): Element? = children(name).singleOrNull()

private fun Response.davRoot(name: String): Element? {
    val source = body?.source() ?: return null
    if (source.request(MAX_XML_BYTES + 1)) return null
    val xml = source.readUtf8().removePrefix("\uFEFF")
    if (xml.isBlank() || "<!DOCTYPE" in xml) return null
    return try {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are disabled") }
        builder.setErrorHandler(DefaultHandler())
        builder.parse(InputSource(StringReader(xml))).documentElement
            ?.takeIf { it.namespaceURI == DAV && it.localName == name }
    } catch (_: SAXException) {
        null
    } catch (_: ParserConfigurationException) {
        null
    }
}
