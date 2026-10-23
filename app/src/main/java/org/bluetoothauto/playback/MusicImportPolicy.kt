// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import java.net.URI
import java.util.Locale

/** Only provider-enumerated descendants of the explicitly chosen SAF tree can be linked. */
data class AudioDocument(val uri: String, val name: String, val relativePath: String)

object AudioFilePolicy {
    private val extensions = setOf("mp3","flac","ogg","oga","wav","m4a","aac","opus","wma","aif","aiff","ape","alac","amr","awb","ac3","dts","mka","mp2","mpa")
    fun accepts(name: String, mime: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in setOf("m3u","m3u8","xspf","pls")) return false
        return extension in extensions || mime?.startsWith("audio/", true) == true &&
            !mime.contains("mpegurl", true) && !mime.contains("scpls", true)
    }
}

/** Stable natural ordering without converting possibly huge digit strings to integers. */
object NaturalFileOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var a = 0; var b = 0
        while (a < left.length && b < right.length) {
            val ca = left[a]; val cb = right[b]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val aStart = a; val bStart = b
                while (a < left.length && left[a] in '0'..'9') a++
                while (b < right.length && right[b] in '0'..'9') b++
                val da = left.substring(aStart, a).trimStart('0').ifEmpty { "0" }
                val db = right.substring(bStart, b).trimStart('0').ifEmpty { "0" }
                if (da.length != db.length) return da.length.compareTo(db.length)
                val difference = da.compareTo(db)
                if (difference != 0) return difference
            } else {
                val difference = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (difference != 0) return difference
                a++; b++
            }
        }
        val remaining = (left.length - a).compareTo(right.length - b)
        return if (remaining != 0) remaining else left.compareTo(right)
    }
}

/** No network URI, global storage lookup, document-ID concatenation, or basename guessing. */
object PlaylistPathPolicy {
    fun resolve(location: String, documents: List<AudioDocument>, playlistDirectory: String,
                rootName: String, uriSyntax: Boolean): AudioDocument? {
        if (location.length !in 1..8192 || location.any { it == '\u0000' || it < ' ' }) return null
        if (location.startsWith("content://", true)) return documents.singleOrNull { it.uri == location }
        var path = location.trim()
        val drive = Regex("^[A-Za-z]:[/\\\\]")
        var absolute = path.startsWith('/') || drive.containsMatchIn(path)
        val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(path)?.value
        if (scheme != null && !drive.containsMatchIn(path) || uriSyntax) {
            val uri = try { URI(path) } catch (_: Exception) { return null }
            if (uri.scheme != null && !uri.scheme.equals("file", true)) return null
            if (!uri.authority.isNullOrEmpty() && !uri.authority.equals("localhost", true)) return null
            if (uri.query != null || uri.fragment != null) return null
            path = uri.path ?: return null // URI parsing performs exactly one percent decode; '+' stays literal.
            absolute = path.startsWith('/') || drive.containsMatchIn(path)
        }
        path = path.replace('\\', '/')
        if (path.startsWith("//")) return null // UNC shares are network locations, not granted local documents.
        if (drive.containsMatchIn(path)) path = path.substring(2)
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val candidate = if (absolute) {
            // Absolute desktop paths require an explicit matching root folder. Never guess by song name.
            val roots = parts.indices.filter { parts[it].equals(rootName, true) }
            // A repeated root name has more than one plausible desktop-to-tree
            // anchor. Require relinking instead of silently choosing a song.
            if (roots.size != 1) return null
            val root = roots.single()
            normalize(emptyList(), parts.drop(root + 1)) ?: return null
        } else normalize(playlistDirectory.split('/').filter { it.isNotEmpty() }, parts) ?: return null
        val relative = candidate.joinToString("/")
        val exact = documents.filter { it.relativePath == relative }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        return documents.filter { it.relativePath.equals(relative, true) }.singleOrNull()
    }

    private fun normalize(base: List<String>, parts: List<String>): List<String>? {
        val result = base.toMutableList()
        for (part in parts) when (part) {
            ".." -> if (result.isEmpty()) return null else result.removeAt(result.lastIndex)
            ".", "" -> Unit
            else -> result.add(part)
        }
        return result
    }
}
