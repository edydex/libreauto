// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.TreeMap
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

data class PlaylistEntry(val location: String, val title: String? = null, val artist: String? = null)
data class ParsedPlaylist(val entries: List<PlaylistEntry>, val skipped: Int = 0, val truncated: Boolean = false)

/** Parses local playlist references. The SAF resolver must enforce the selected tree's boundary. */
object PlaylistParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_LOCATION = 8192
    private const val MAX_METADATA = 180
    private const val XSPF_NS = "http://xspf.org/ns/0/"
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val windowsDrive = Regex("^[A-Za-z]:[/\\\\]")
    private val forbiddenXml = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
    private val plsKey = Regex("(?i)(File|Title)([1-9][0-9]{0,8})")

    fun parse(bytes: ByteArray, fileName: String): ParsedPlaylist {
        if (bytes.size > MAX_BYTES) return ParsedPlaylist(emptyList(), skipped = 1, truncated = true)
        if (bytes.isEmpty()) return ParsedPlaylist(emptyList())
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val text = decode(bytes, extension == "m3u")
            ?: return ParsedPlaylist(emptyList(), skipped = 1)
        return when {
            extension == "xspf" || text.trimStart().startsWith("<?xml") || text.trimStart().startsWith("<playlist") -> parseXspf(text)
            extension == "pls" || text.trimStart().startsWith("[playlist]", ignoreCase = true) -> parsePls(text)
            else -> parseM3u(text)
        }
    }

    private fun decode(bytes: ByteArray, legacyM3u: Boolean): String? {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            if (legacyM3u) String(bytes, Charsets.ISO_8859_1) else null
        }
    }

    private class Collected {
        val entries = ArrayList<PlaylistEntry>()
        var skipped = 0
        var truncated = false
        fun add(location: String?, title: String? = null, artist: String? = null) {
            val local = location?.let(::localReference)
            if (local == null) ++skipped
            else if (entries.size == MAX_ENTRIES) truncated = true
            else entries.add(PlaylistEntry(local, cleanMetadata(title), cleanMetadata(artist)))
        }
        fun result() = ParsedPlaylist(entries.toList(), skipped, truncated)
    }

    private fun parseM3u(text: String): ParsedPlaylist {
        val result = Collected()
        var title: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                title = if (line.length <= MAX_LOCATION) line.substringAfter(',', "").takeIf { it.isNotBlank() } else null
            } else if (line.startsWith("#EXT-X-", ignoreCase = true)) {
                // A streaming manifest is not a local music queue.
                return ParsedPlaylist(emptyList(), skipped = 1)
            } else if (!line.startsWith('#')) {
                result.add(line, title)
                title = null
            }
            // Other comments, including EXTVLCOPT, never become native VLC options.
        }
        return result.result()
    }

    private fun parsePls(text: String): ParsedPlaylist {
        data class Parts(var location: String? = null, var title: String? = null)
        val items = TreeMap<Int, Parts>()
        var truncated = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length > MAX_LOCATION + 32 || line.startsWith(';') || line.startsWith('#')) continue
            val split = line.indexOf('=')
            if (split <= 0) continue
            val key = line.substring(0, split).trim()
            val match = plsKey.matchEntire(key) ?: continue
            val index = match.groupValues[2].toIntOrNull() ?: continue
            val item = items.getOrPut(index) { Parts() }
            if (match.groupValues[1].equals("File", ignoreCase = true)) item.location = line.substring(split + 1).trim()
            else item.title = line.substring(split + 1).trim()
            if (items.size > MAX_ENTRIES) { items.pollLastEntry(); truncated = true }
        }
        val result = Collected()
        for (item in items.values) if (item.location != null) result.add(item.location, item.title)
        result.truncated = truncated
        return result.result()
    }

    private fun parseXspf(text: String): ParsedPlaylist {
        // Validate after decoding (also covers UTF-16); no DTD can be fetched or expanded.
        if (forbiddenXml.containsMatchIn(text)) return ParsedPlaylist(emptyList(), skipped = 1)
        val result = Collected()
        val handler = object : DefaultHandler() {
            var depth = 0
            var rootSeen = false
            var trackList = false
            var inTrack = false
            var location: String? = null
            var title: String? = null
            var artist: String? = null
            var field: String? = null
            val value = StringBuilder()
            val bases = ArrayList<String?>()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (++depth > 64) throw SAXException("Playlist nesting is too deep")
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
                val supportedNamespace = uri.isNullOrEmpty() || uri == XSPF_NS
                if (depth == 1) {
                    if (name != "playlist" || !supportedNamespace) throw SAXException("Not an XSPF playlist")
                    rootSeen = true
                }
                val previousBase = bases.lastOrNull()
                val ownBase = attributes.getValue(XMLConstants.XML_NS_URI, "base") ?: attributes.getValue("xml:base")
                bases.add(if (ownBase == null) previousBase else resolveReference(previousBase, ownBase)
                    ?: throw SAXException("Invalid playlist base URI"))
                if (depth == 2 && name == "trackList" && supportedNamespace) trackList = true
                if (depth == 3 && trackList && name == "track" && supportedNamespace) {
                    inTrack = true; location = null; title = null; artist = null
                }
                if (depth == 4 && inTrack && supportedNamespace && name in setOf("location", "title", "creator")) {
                    field = name; value.setLength(0)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (field != null && depth == 4 && value.length <= MAX_LOCATION)
                    value.append(ch, start, length.coerceAtMost(MAX_LOCATION + 1 - value.length))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (depth == 4 && field != null) {
                    val textValue = value.toString().trim()
                    when (field) {
                        "location" -> if (location == null && textValue.length <= MAX_LOCATION) {
                            val resolved = resolveReference(bases.lastOrNull(), textValue)
                            location = resolved?.let(::localReference)
                        }
                        "title" -> title = textValue
                        "creator" -> artist = textValue
                    }
                    field = null
                }
                if (depth == 3 && inTrack) { result.add(location, title, artist); inTrack = false }
                if (depth == 2) trackList = false
                bases.removeAt(bases.lastIndex)
                --depth
            }
        }
        return try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            val reader = factory.newSAXParser().xmlReader
            for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                                   "http://xml.org/sax/features/external-parameter-entities",
                                   "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                runCatching { reader.setFeature(feature, false) }
            }
            reader.entityResolver = EntityResolver { _, _ -> throw SAXException("External playlist resources are unsupported") }
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.parse(InputSource(StringReader(text)))
            if (handler.rootSeen) result.result() else ParsedPlaylist(emptyList(), skipped = 1)
        } catch (_: Exception) {
            // A malformed XML import is atomic; never replace a queue with its partial prefix.
            ParsedPlaylist(emptyList(), skipped = 1)
        }
    }

    private fun resolveReference(base: String?, value: String): String? = try {
        if (value.length > MAX_LOCATION) null
        else if (base.isNullOrEmpty()) URI(value).toString()
        else URI(base).resolve(URI(value)).toString().takeIf { it.length <= MAX_LOCATION }
    } catch (_: Exception) { null }

    private fun localReference(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LOCATION || value.any { it.code < 32 || it.code == 127 }) return null
        if (value.startsWith("//") || value.startsWith("\\\\")) return null
        if (windowsDrive.containsMatchIn(value)) return value
        val prefix = scheme.find(value)?.value?.dropLast(1)?.lowercase(Locale.ROOT)
        if (prefix != null && prefix !in setOf("file", "content")) return null
        if (prefix != null) {
            val uri = try { URI(value) } catch (_: Exception) { return null }
            if (prefix == "file" && !uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
            if (prefix == "content" && uri.authority.isNullOrEmpty()) return null
        }
        return value
    }

    private fun cleanMetadata(value: String?): String? = value?.filter { it.code >= 32 && it.code != 127 }
        ?.trim()?.take(MAX_METADATA)?.takeIf { it.isNotEmpty() }
}
